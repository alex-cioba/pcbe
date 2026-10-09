import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BURSA - varianta CORECTA (simulare cu fire de executie).
 *
 * Reguli de sincronizare:
 *  1. Fiecare actiune (Stock) are propriul ReentrantLock. Plasarea / modificarea / anularea unei oferte
 *     SI matching-ul se fac sub acest lock => o tranzactie este atomica fata de modificari
 *     ("cererile si ofertele pot fi modificate oricind, daca nu exista o tranzactie in desfasurare").
 *  2. Fiecare Trader are propriul lock pentru portofel (cash + actiuni detinute).
 *  3. Ordinea de luare a lock-urilor este mereu: lock(Stock) -> lock(Trader cu id mai mic) -> lock(Trader cu id mai mare).
 *     Ordinea globala fixa elimina deadlock-ul.
 *  4. Starea comuna citita fara lock-ul actiunii (lastPrice, active) este volatile.
 *  5. Istoricul tranzactiilor este o ConcurrentLinkedQueue (scriere sub lock, citire de oricine).
 *  6. Invariante verificate la final: cash total si numarul total de actiuni sunt conservate, nimic negativ.
 *
 * Preturile sunt in cenți (long) ca sa evitam erorile de rotunjire ale double.
 * Compilare: javac BursaCorecta.java   |   Rulare: java BursaCorecta   (JDK 17+)
 */
public class BursaCorecta {

    enum Side { BUY, SELL }
    enum Result { OK, BUYER_CANNOT_PAY, SELLER_CANNOT_DELIVER }

    static final String[] SYMBOLS = {"ACME", "BETA", "CORP", "DELT"};
    static final int TRADERS = 10;
    static final int ITERATIONS = 5_000;
    static final long INITIAL_CASH = 1_000_000;   // cenți
    static final int INITIAL_SHARES = 1_000;      // per actiune, per trader
    static final long INITIAL_PRICE = 10_000;     // cenți
    static final long ANALYSIS_NS = 50_000;       // timpul "de gandire" al unui trader (in afara oricarui lock)

    static final AtomicLong ORDER_SEQ = new AtomicLong();
    static final AtomicLong TX_SEQ = new AtomicLong();

    record Transaction(long id, String symbol, int qty, long price, int buyerId, int sellerId) {}

    // ------------------------------------------------------------------ Order
    static final class Order {
        final long seq = ORDER_SEQ.incrementAndGet();
        final Stock stock;
        final Trader trader;
        final Side side;
        long price;                 // protejat de stock.lock
        int qty;                    // protejat de stock.lock
        volatile boolean active = true;

        Order(Stock stock, Trader trader, Side side, long price, int qty) {
            this.stock = stock; this.trader = trader; this.side = side; this.price = price; this.qty = qty;
        }
    }

    // ----------------------------------------------------------------- Trader
    static final class Trader {
        final int id;
        private final ReentrantLock lock = new ReentrantLock();
        private long cash = INITIAL_CASH;                          // protejat de lock
        private final int[] shares = new int[SYMBOLS.length];      // protejat de lock

        Trader(int id) {
            this.id = id;
            Arrays.fill(shares, INITIAL_SHARES);
        }

        long cash() { lock.lock(); try { return cash; } finally { lock.unlock(); } }
        int shares(int stockIdx) { lock.lock(); try { return shares[stockIdx]; } finally { lock.unlock(); } }
    }

    /** Muta banii si actiunile intre doi traderi. Lock-uri luate in ordinea id-urilor => fara deadlock. */
    static Result settle(Trader buyer, Trader seller, int stockIdx, int qty, long price) {
        Trader first = buyer.id < seller.id ? buyer : seller;
        Trader second = (first == buyer) ? seller : buyer;
        first.lock.lock();
        try {
            second.lock.lock();
            try {
                long total = price * qty;
                if (buyer.cash < total) return Result.BUYER_CANNOT_PAY;
                if (seller.shares[stockIdx] < qty) return Result.SELLER_CANNOT_DELIVER;
                buyer.cash -= total;
                buyer.shares[stockIdx] += qty;
                seller.cash += total;
                seller.shares[stockIdx] -= qty;
                return Result.OK;
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
    }

    // ------------------------------------------------------------------ Stock
    static final Comparator<Order> BUY_FIRST =
            Comparator.<Order>comparingLong(o -> -o.price).thenComparingLong(o -> o.seq);
    static final Comparator<Order> SELL_FIRST =
            Comparator.<Order>comparingLong(o -> o.price).thenComparingLong(o -> o.seq);

    static final class Stock {
        final int idx;
        final String symbol;
        private final ReentrantLock lock = new ReentrantLock();
        private final List<Order> buys = new ArrayList<>();    // protejat de lock
        private final List<Order> sells = new ArrayList<>();   // protejat de lock
        final Queue<Transaction> history = new ConcurrentLinkedQueue<>();
        volatile long lastPrice = INITIAL_PRICE;

        Stock(int idx, String symbol) { this.idx = idx; this.symbol = symbol; }

        Order place(Trader t, Side side, long price, int qty) {
            Order o = new Order(this, t, side, price, qty);
            lock.lock();
            try {
                (side == Side.BUY ? buys : sells).add(o);
                match();
            } finally {
                lock.unlock();
            }
            return o;
        }

        /** false daca oferta a fost deja executata complet / anulata. */
        boolean modify(Order o, long newPrice, int newQty) {
            lock.lock();
            try {
                if (!o.active) return false;
                o.price = newPrice;
                o.qty = newQty;
                match();
                return true;
            } finally {
                lock.unlock();
            }
        }

        boolean cancel(Order o) {
            lock.lock();
            try {
                if (!o.active) return false;
                remove(o);
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** Informatie publica: {cel mai bun pret de cumparare, cel mai bun pret de vanzare}; 0 = nu exista. */
        long[] bestBidAsk() {
            lock.lock();
            try {
                long bid = 0, ask = 0;
                for (Order o : buys) bid = Math.max(bid, o.price);
                for (Order o : sells) ask = (ask == 0) ? o.price : Math.min(ask, o.price);
                return new long[]{bid, ask};
            } finally {
                lock.unlock();
            }
        }

        private void remove(Order o) {
            o.active = false;
            (o.side == Side.BUY ? buys : sells).remove(o);
        }

        /** Apelat DOAR cu lock-ul actiunii luat. */
        private void match() {
            while (true) {
                buys.sort(BUY_FIRST);
                sells.sort(SELL_FIRST);
                Order b = null, s = null;
                search:
                for (Order bo : buys) {
                    for (Order so : sells) {
                        if (so.price > bo.price) break;                 // vanzatorii sunt sortati crescator
                        if (so.trader != bo.trader) { b = bo; s = so; break search; }
                    }
                }
                if (b == null) return;

                int n = Math.min(b.qty, s.qty);
                long px = (b.seq < s.seq) ? b.price : s.price;          // pretul ofertei mai vechi
                switch (settle(b.trader, s.trader, idx, n, px)) {
                    case OK -> {
                        b.qty -= n;
                        s.qty -= n;
                        lastPrice = px;
                        history.add(new Transaction(TX_SEQ.incrementAndGet(), symbol, n, px, b.trader.id, s.trader.id));
                        if (b.qty == 0) remove(b);
                        if (s.qty == 0) remove(s);
                    }
                    case BUYER_CANNOT_PAY -> remove(b);
                    case SELLER_CANNOT_DELIVER -> remove(s);
                }
            }
        }
    }

    // ------------------------------------------------------------ Trader task
    static final class TraderTask implements Runnable {
        private final Trader trader;
        private final Stock[] stocks;
        private final Random rnd;
        private final List<Order> open = new ArrayList<>();   // folosit doar de firul acestui trader

        TraderTask(Trader trader, Stock[] stocks) {
            this.trader = trader; this.stocks = stocks; this.rnd = new Random(trader.id * 7919L);
        }

        @Override public void run() {
            for (int i = 0; i < ITERATIONS; i++) step();
        }

        private void step() {
            LockSupport.parkNanos(ANALYSIS_NS);               // analiza pietei, fara lock-uri
            Stock s = stocks[rnd.nextInt(stocks.length)];
            int action = rnd.nextInt(10);
            if (action < 3) placeOrder(s, Side.BUY);
            else if (action < 6) placeOrder(s, Side.SELL);
            else if (action < 8) modifyRandom();
            else if (action < 9) cancelRandom();
            if (open.size() > 50) open.removeIf(o -> !o.active);
        }

        private void placeOrder(Stock s, Side side) {
            int qty = 1 + rnd.nextInt(20);
            long[] bidAsk = s.bestBidAsk();
            long price = s.lastPrice + rnd.nextInt(101) - 50;
            if (side == Side.BUY) {
                if (bidAsk[1] > 0 && rnd.nextInt(3) == 0) price = bidAsk[1];   // cumpara la cel mai bun pret de vanzare
                if (trader.cash() < price * qty) return;                       // verificare orientativa; settle() reverifica
            } else {
                if (bidAsk[0] > 0 && rnd.nextInt(3) == 0) price = bidAsk[0];
                if (trader.shares(s.idx) < qty) return;
            }
            open.add(s.place(trader, side, price, qty));
        }

        private void modifyRandom() {
            if (open.isEmpty()) return;
            int k = rnd.nextInt(open.size());
            Order o = open.get(k);
            long newPrice = o.stock.lastPrice + rnd.nextInt(101) - 50;
            if (!o.stock.modify(o, newPrice, 1 + rnd.nextInt(20))) open.remove(k);
        }

        private void cancelRandom() {
            if (open.isEmpty()) return;
            Order o = open.remove(rnd.nextInt(open.size()));
            o.stock.cancel(o);
        }
    }

    // ------------------------------------------------------------------- main
    public static void main(String[] args) throws Exception {
        Stock[] stocks = new Stock[SYMBOLS.length];
        for (int i = 0; i < stocks.length; i++) stocks[i] = new Stock(i, SYMBOLS[i]);
        Trader[] traders = new Trader[TRADERS];
        for (int i = 0; i < TRADERS; i++) traders[i] = new Trader(i);

        ExecutorService pool = Executors.newFixedThreadPool(TRADERS);
        long t0 = System.nanoTime();
        for (Trader t : traders) pool.execute(new TraderTask(t, stocks));
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("=== BursaCorecta ===");
        System.out.printf("Timp: %d ms, tranzactii: %d%n", ms, TX_SEQ.get());
        for (Stock s : stocks)
            System.out.printf("  %s: ultimul pret=%d, tranzactii=%d%n", s.symbol, s.lastPrice, s.history.size());

        // verificarea invariantelor
        boolean ok = true;
        long cashSum = 0;
        for (Trader t : traders) {
            cashSum += t.cash();
            if (t.cash() < 0) { ok = false; System.out.println("VIOLARE: cash negativ la trader " + t.id); }
        }
        if (cashSum != TRADERS * INITIAL_CASH) { ok = false; System.out.println("VIOLARE: cash total = " + cashSum); }
        for (Stock s : stocks) {
            long sum = 0;
            for (Trader t : traders) {
                int h = t.shares(s.idx);
                if (h < 0) { ok = false; System.out.println("VIOLARE: actiuni negative " + s.symbol + " la trader " + t.id); }
                sum += h;
            }
            if (sum != (long) TRADERS * INITIAL_SHARES) { ok = false; System.out.println("VIOLARE: total " + s.symbol + " = " + sum); }
        }
        System.out.println(ok ? "Invariante OK (cash si actiuni conservate)." : "INVARIANTE incalcate!");
    }
}

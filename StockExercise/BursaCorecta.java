import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BURSA - varianta CORECTA (simulare cu fire de executie).
 *
 * Reguli de sincronizare:
 *  1. Registrul de oferte al unei actiuni (Stock) este format din doua ConcurrentSkipListSet sortate
 *     (cumparari / vanzari). Nu exista lock pe intreaga actiune.
 *  2. Fiecare oferta (Order) are propriul ReentrantLock. O tranzactie blocheaza DOAR cele doua oferte implicate,
 *     deci restul ofertelor aceleiasi actiuni pot fi plasate / modificate / anulate / executate in paralel
 *     ("cererile si ofertele pot fi modificate oricind, daca nu exista o tranzactie in desfasurare" - pe oferta).
 *  3. Pretul unei oferte este final (este cheia de sortare). Modificarea = sub lock-ul ofertei, oferta veche este
 *     dezactivata si inlocuita cu una noua (care pierde prioritatea in timp, ca la o bursa reala).
 *  4. Fiecare Trader are propriul lock pentru portofel (cash + actiuni detinute).
 *  5. Ordinea de luare a lock-urilor este mereu:
 *     lock(Order cu seq mai mic) -> lock(Order cu seq mai mare) -> lock(Trader cu id mai mic) -> lock(Trader cu id mai mare).
 *     Niciun fir nu tine mai mult de doua lock-uri de oferte; ordinea globala fixa elimina deadlock-ul.
 *  6. O oferta este inserata in registru INAINTE de a cauta contrapartida => din doua oferte compatibile plasate
 *     simultan, cea inserata a doua o vede sigur pe prima; nicio pereche compatibila nu ramane neexecutata.
 *     Prioritatea pret-timp este garantata doar fata de ofertele deja prezente in registru la momentul cautarii.
 *  7. Ultima tranzactie (deci ultimul pret) este un AtomicReference actualizat cu "cea cu id-ul cel mai mare",
 *     ca o tranzactie mai veche terminata mai tarziu sa nu suprascrie una mai noua.
 *  8. Istoricul tranzactiilor este o ConcurrentLinkedQueue.
 *  9. Invariante verificate la final: cash total si numarul total de actiuni sunt conservate, nimic negativ,
 *     si nu exista oferte compatibile (de la traderi diferiti) ramase in registru.
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
        final long price;           // cheia de sortare in registru => nu se schimba niciodata
        int qty;                    // protejat de lock
        volatile boolean active = true;   // scris doar sub lock
        final ReentrantLock lock = new ReentrantLock();

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
        private final ConcurrentSkipListSet<Order> buys = new ConcurrentSkipListSet<>(BUY_FIRST);
        private final ConcurrentSkipListSet<Order> sells = new ConcurrentSkipListSet<>(SELL_FIRST);
        final Queue<Transaction> history = new ConcurrentLinkedQueue<>();
        private final AtomicReference<Transaction> lastTx;

        Stock(int idx, String symbol) {
            this.idx = idx; this.symbol = symbol;
            this.lastTx = new AtomicReference<>(new Transaction(0, symbol, 0, INITIAL_PRICE, -1, -1));
        }

        long lastPrice() { return lastTx.get().price(); }

        Order place(Trader t, Side side, long price, int qty) {
            Order o = new Order(this, t, side, price, qty);
            book(side).add(o);        // intai in registru, apoi cautam contrapartida (regula 6)
            match(o);
            return o;
        }

        /** Inlocuieste oferta; intoarce noua oferta sau null daca cea veche a fost deja executata complet / anulata. */
        Order modify(Order o, long newPrice, int newQty) {
            o.lock.lock();
            try {
                if (!o.active) return null;
                deactivate(o);
            } finally {
                o.lock.unlock();
            }
            return place(o.trader, o.side, newPrice, newQty);
        }

        boolean cancel(Order o) {
            o.lock.lock();
            try {
                if (!o.active) return false;
                deactivate(o);
                return true;
            } finally {
                o.lock.unlock();
            }
        }

        /** Informatie publica: {cel mai bun pret de cumparare, cel mai bun pret de vanzare}; 0 = nu exista. */
        long[] bestBidAsk() {
            Order b = first(buys), s = first(sells);
            return new long[]{b == null ? 0 : b.price, s == null ? 0 : s.price};
        }

        /** true daca exista o cerere si o oferta compatibile, de la traderi diferiti (folosit la verificarea finala). */
        boolean hasCrossingOrders() {
            for (Order b : buys)
                for (Order s : sells) {
                    if (s.price > b.price) break;
                    if (s.trader != b.trader) return true;
                }
            return false;
        }

        private static Order first(ConcurrentSkipListSet<Order> set) {
            try { return set.first(); } catch (NoSuchElementException e) { return null; }
        }

        private ConcurrentSkipListSet<Order> book(Side side) { return side == Side.BUY ? buys : sells; }

        /** Apelat DOAR cu lock-ul ofertei luat. */
        private void deactivate(Order o) {
            o.active = false;
            book(o.side).remove(o);
        }

        private static boolean crosses(Order a, Order b) {
            Order buy = a.side == Side.BUY ? a : b;
            Order sell = (buy == a) ? b : a;
            return sell.price <= buy.price;
        }

        /** Lock-urile ofertelor se iau mereu in ordinea seq (regula 5). */
        private static void lockPair(Order a, Order b) {
            Order first = a.seq < b.seq ? a : b;
            Order second = (first == a) ? b : a;
            first.lock.lock();
            second.lock.lock();
        }

        private static void unlockPair(Order a, Order b) {
            a.lock.unlock();
            b.lock.unlock();
        }

        /** Executa oferta o contra ofertelor compatibile din partea opusa, cea mai buna prima. */
        private void match(Order o) {
            for (Order c : book(o.side == Side.BUY ? Side.SELL : Side.BUY)) {
                if (!o.active) return;
                if (!crosses(o, c)) return;            // registrul e sortat => nici urmatoarele nu sunt compatibile
                if (c.trader == o.trader) continue;
                lockPair(o, c);
                try {
                    if (!o.active || !c.active) continue;  // reverificare sub lock-uri
                    Order b = o.side == Side.BUY ? o : c;
                    Order s = (b == o) ? c : o;
                    int n = Math.min(b.qty, s.qty);
                    long px = (b.seq < s.seq) ? b.price : s.price;          // pretul ofertei mai vechi
                    switch (settle(b.trader, s.trader, idx, n, px)) {
                        case OK -> {
                            b.qty -= n;
                            s.qty -= n;
                            Transaction tx = new Transaction(TX_SEQ.incrementAndGet(), symbol, n, px, b.trader.id, s.trader.id);
                            history.add(tx);
                            lastTx.accumulateAndGet(tx, (x, y) -> x.id() > y.id() ? x : y);
                            if (b.qty == 0) deactivate(b);
                            if (s.qty == 0) deactivate(s);
                        }
                        case BUYER_CANNOT_PAY -> deactivate(b);
                        case SELLER_CANNOT_DELIVER -> deactivate(s);
                    }
                } finally {
                    unlockPair(o, c);
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
            long price = s.lastPrice() + rnd.nextInt(101) - 50;
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
            long newPrice = o.stock.lastPrice() + rnd.nextInt(101) - 50;
            Order replacement = o.stock.modify(o, newPrice, 1 + rnd.nextInt(20));
            if (replacement == null) open.remove(k);
            else open.set(k, replacement);
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
            System.out.printf("  %s: ultimul pret=%d, tranzactii=%d%n", s.symbol, s.lastPrice(), s.history.size());

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
            if (s.hasCrossingOrders()) { ok = false; System.out.println("VIOLARE: oferte compatibile neexecutate la " + s.symbol); }
        }
        System.out.println(ok ? "Invariante OK (cash si actiuni conservate, nicio oferta compatibila ramasa)." : "INVARIANTE incalcate!");
    }
}

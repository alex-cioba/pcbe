import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BURSA - varianta GRESITA #2: DEADLOCK / LIVELOCK / PERFORMANTA.
 * Datele sunt protejate corect (nu apar data races), dar strategia de blocare e proasta.
 * Alegerea defectului se face din linia de comanda:
 *
 *   java BursaDeadlock deadlock   - lock(buyer) apoi lock(seller), in ordinea rolurilor din tranzactie.
 *                                   Firul 1: actiunea X, trader A cumpara de la B  -> ia A, asteapta B
 *                                   Firul 2: actiunea Y, trader B cumpara de la A  -> ia B, asteapta A  => DEADLOCK (asteptare circulara).
 *                                   Un watchdog (ThreadMXBean.findDeadlockedThreads) il detecteaza si il afiseaza.
 *
 *   java BursaDeadlock livelock   - evitam blocarea cu tryLock(): daca nu obtinem ambele lock-uri, le eliberam
 *                                   si reincercam IMEDIAT, fara backoff aleator. Firele raman active (consuma CPU,
 *                                   cresc contorul RETRIES) dar progresul (tranzactiile) poate stagna / incetini mult.
 *                                   Fenomen probabilistic: depinde de planificare; urmareste liniile [watchdog].
 *
 *   java BursaDeadlock slow       - corect, dar cu UN SINGUR lock global (synchronized(GLOBAL)) care acopera tot pasul unui
 *                                   trader, inclusiv "analiza" (50 µs). Toti traderii sunt serializati => fara paralelism.
 *                                   Compara timpul cu BursaCorecta (aceeasi analiza, dar in afara lock-urilor).
 *
 * Compilare: javac BursaDeadlock.java
 */
public class BursaDeadlock {

    enum Mode { DEADLOCK, LIVELOCK, SLOW }
    enum Side { BUY, SELL }
    enum Result { OK, BUYER_CANNOT_PAY, SELLER_CANNOT_DELIVER }

    static volatile Mode MODE = Mode.DEADLOCK;
    static final Object GLOBAL = new Object();

    static final String[] SYMBOLS = {"ACME", "BETA", "CORP", "DELT"};
    static final int TRADERS = 10;
    static final int ITERATIONS = 5_000;
    static final long INITIAL_CASH = 1_000_000;
    static final int INITIAL_SHARES = 1_000;
    static final long INITIAL_PRICE = 10_000;
    static final long ANALYSIS_NS = 50_000;
    static final long TIMEOUT_S = 30;

    static final AtomicLong ORDER_SEQ = new AtomicLong();
    static final AtomicLong TX_SEQ = new AtomicLong();
    static final AtomicLong RETRIES = new AtomicLong();

    record Transaction(long id, String symbol, int qty, long price, int buyerId, int sellerId) {}

    static final class Order {
        final long seq = ORDER_SEQ.incrementAndGet();
        final Stock stock; final Trader trader; final Side side;
        long price; int qty;
        volatile boolean active = true;
        Order(Stock stock, Trader trader, Side side, long price, int qty) {
            this.stock = stock; this.trader = trader; this.side = side; this.price = price; this.qty = qty;
        }
    }

    static final class Trader {
        final int id;
        final ReentrantLock lock = new ReentrantLock();
        long cash = INITIAL_CASH;
        final int[] shares = new int[SYMBOLS.length];
        Trader(int id) { this.id = id; Arrays.fill(shares, INITIAL_SHARES); }

        long cash() { lock.lock(); try { return cash; } finally { lock.unlock(); } }
        int shares(int s) { lock.lock(); try { return shares[s]; } finally { lock.unlock(); } }
    }

    /** Transferul propriu-zis; se apeleaza cu ambele lock-uri de trader luate. */
    private static Result transfer(Trader buyer, Trader seller, int stockIdx, int qty, long price) {
        long total = price * qty;
        if (buyer.cash < total) return Result.BUYER_CANNOT_PAY;
        if (seller.shares[stockIdx] < qty) return Result.SELLER_CANNOT_DELIVER;
        buyer.cash -= total; buyer.shares[stockIdx] += qty;
        seller.cash += total; seller.shares[stockIdx] -= qty;
        return Result.OK;
    }

    static Result settle(Trader buyer, Trader seller, int stockIdx, int qty, long price) {
        switch (MODE) {
            case DEADLOCK: {
                buyer.lock.lock();                                  // BUG: ordine dependenta de rolul din tranzactie, nu de o ordine globala
                try {
                    LockSupport.parkNanos(50_000);                  // lărgește fereastra (in viata reala: cod de validare, logging...)
                    seller.lock.lock();
                    try { return transfer(buyer, seller, stockIdx, qty, price); }
                    finally { seller.lock.unlock(); }
                } finally {
                    buyer.lock.unlock();
                }
            }
            case LIVELOCK: {
                while (true) {
                    if (buyer.lock.tryLock()) {
                        try {
                            LockSupport.parkNanos(20_000);
                            if (seller.lock.tryLock()) {
                                try { return transfer(buyer, seller, stockIdx, qty, price); }
                                finally { seller.lock.unlock(); }
                            }
                        } finally {
                            buyer.lock.unlock();                    // renunta la tot ...
                        }
                    }
                    RETRIES.incrementAndGet();                      // BUG: ... si reincearca imediat, simetric, fara backoff aleator
                    Thread.yield();
                }
            }
            default: {                                              // SLOW: blocare corecta (ordonata); problema e altundeva
                Trader first = buyer.id < seller.id ? buyer : seller;
                Trader second = (first == buyer) ? seller : buyer;
                first.lock.lock();
                try {
                    second.lock.lock();
                    try { return transfer(buyer, seller, stockIdx, qty, price); }
                    finally { second.lock.unlock(); }
                } finally {
                    first.lock.unlock();
                }
            }
        }
    }

    static final Comparator<Order> BUY_FIRST =
            Comparator.<Order>comparingLong(o -> -o.price).thenComparingLong(o -> o.seq);
    static final Comparator<Order> SELL_FIRST =
            Comparator.<Order>comparingLong(o -> o.price).thenComparingLong(o -> o.seq);

    static final class Stock {
        final int idx; final String symbol;
        final ReentrantLock lock = new ReentrantLock();
        private final List<Order> buys = new ArrayList<>();
        private final List<Order> sells = new ArrayList<>();
        final Queue<Transaction> history = new ConcurrentLinkedQueue<>();
        volatile long lastPrice = INITIAL_PRICE;

        Stock(int idx, String symbol) { this.idx = idx; this.symbol = symbol; }

        Order place(Trader t, Side side, long price, int qty) {
            Order o = new Order(this, t, side, price, qty);
            lock.lock();
            try { (side == Side.BUY ? buys : sells).add(o); match(); }
            finally { lock.unlock(); }
            return o;
        }

        boolean modify(Order o, long newPrice, int newQty) {
            lock.lock();
            try {
                if (!o.active) return false;
                o.price = newPrice; o.qty = newQty;
                match();
                return true;
            } finally { lock.unlock(); }
        }

        boolean cancel(Order o) {
            lock.lock();
            try { if (!o.active) return false; remove(o); return true; }
            finally { lock.unlock(); }
        }

        long[] bestBidAsk() {
            lock.lock();
            try {
                long bid = 0, ask = 0;
                for (Order o : buys) bid = Math.max(bid, o.price);
                for (Order o : sells) ask = (ask == 0) ? o.price : Math.min(ask, o.price);
                return new long[]{bid, ask};
            } finally { lock.unlock(); }
        }

        private void remove(Order o) { o.active = false; (o.side == Side.BUY ? buys : sells).remove(o); }

        private void match() {
            while (true) {
                buys.sort(BUY_FIRST); sells.sort(SELL_FIRST);
                Order b = null, s = null;
                search:
                for (Order bo : buys)
                    for (Order so : sells) {
                        if (so.price > bo.price) break;
                        if (so.trader != bo.trader) { b = bo; s = so; break search; }
                    }
                if (b == null) return;
                int n = Math.min(b.qty, s.qty);
                long px = (b.seq < s.seq) ? b.price : s.price;
                switch (settle(b.trader, s.trader, idx, n, px)) {
                    case OK -> {
                        b.qty -= n; s.qty -= n; lastPrice = px;
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

    static final class TraderTask implements Runnable {
        private final Trader trader; private final Stock[] stocks; private final Random rnd;
        private final List<Order> open = new ArrayList<>();

        TraderTask(Trader trader, Stock[] stocks) {
            this.trader = trader; this.stocks = stocks; this.rnd = new Random(trader.id * 7919L);
        }

        @Override public void run() {
            for (int i = 0; i < ITERATIONS; i++) {
                if (MODE == Mode.SLOW) {
                    synchronized (GLOBAL) { step(); }       // BUG de performanta: lock global peste tot pasul, inclusiv analiza
                } else {
                    step();
                }
            }
        }

        private void step() {
            LockSupport.parkNanos(ANALYSIS_NS);              // in modul SLOW se petrece in interiorul lock-ului global
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
                if (bidAsk[1] > 0 && rnd.nextInt(3) == 0) price = bidAsk[1];
                if (trader.cash() < price * qty) return;
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
            if (!o.stock.modify(o, o.stock.lastPrice + rnd.nextInt(101) - 50, 1 + rnd.nextInt(20))) open.remove(k);
        }

        private void cancelRandom() {
            if (open.isEmpty()) return;
            Order o = open.remove(rnd.nextInt(open.size()));
            o.stock.cancel(o);
        }
    }

    public static void main(String[] args) throws Exception {
        MODE = args.length > 0 ? Mode.valueOf(args[0].toUpperCase()) : Mode.DEADLOCK;
        System.out.println("=== BursaDeadlock, mod = " + MODE + " ===");

        Stock[] stocks = new Stock[SYMBOLS.length];
        for (int i = 0; i < stocks.length; i++) stocks[i] = new Stock(i, SYMBOLS[i]);
        Trader[] traders = new Trader[TRADERS];
        for (int i = 0; i < TRADERS; i++) traders[i] = new Trader(i);

        ExecutorService pool = Executors.newFixedThreadPool(TRADERS);
        long t0 = System.nanoTime();
        for (Trader t : traders) pool.execute(new TraderTask(t, stocks));
        pool.shutdown();

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long lastTx = 0;
        while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
            long tx = TX_SEQ.get();
            System.out.printf("[watchdog] t=%ds tranzactii=%d (+%d/s) reincercari tryLock=%d%n",
                    (System.nanoTime() - t0) / 1_000_000_000L, tx, tx - lastTx, RETRIES.get());
            lastTx = tx;
            long[] dead = mx.findDeadlockedThreads();
            if (dead != null) {
                System.out.println("!!! DEADLOCK detectat intre firele:");
                for (ThreadInfo ti : mx.getThreadInfo(dead, false, false))
                    System.out.printf("    %s asteapta %s detinut de %s%n", ti.getThreadName(), ti.getLockName(), ti.getLockOwnerName());
                Runtime.getRuntime().halt(1);
            }
            if ((System.nanoTime() - t0) / 1_000_000_000L >= TIMEOUT_S) {
                System.out.println("!!! TIMEOUT dupa " + TIMEOUT_S + "s: progres insuficient (livelock / starvation probabil).");
                Runtime.getRuntime().halt(1);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("Terminat in %d ms, tranzactii=%d, reincercari tryLock=%d%n", ms, TX_SEQ.get(), RETRIES.get());
    }
}

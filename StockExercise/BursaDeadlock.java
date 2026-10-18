import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BURSA - varianta GRESITA #2: DEADLOCK / LIVELOCK / PERFORMANTA.
 *
 * Structura e identica cu BursaCorecta (registru ConcurrentSkipListSet, cate un lock pe fiecare oferta,
 * oferta inserata inainte de matching), datele sunt protejate corect (nu apar data races),
 * dar strategia de luare a lock-urilor pe oferte e proasta. Alegerea defectului se face din linia de comanda:
 *
 *   java BursaDeadlock deadlock   - lockPair(o, c) ia lock-urile in ordinea ROLURILOR: intai oferta proprie (agresorul o),
 *                                   apoi contrapartida c - nu in ordinea globala dupa seq.
 *                                   Firul 1: cumparare X (agresor) gaseste vanzarea Y -> ia X, asteapta Y
 *                                   Firul 2: vanzare  Y (agresor) gaseste cumpararea X -> ia Y, asteapta X  => DEADLOCK.
 *                                   Ambele oferte sunt deja in registru (regula "insereaza, apoi cauta"), deci fiecare
 *                                   o vede pe cealalta. Un watchdog (ThreadMXBean.findDeadlockedThreads) il detecteaza.
 *
 *   java BursaDeadlock livelock   - evitam blocarea cu tryLock(): daca nu obtinem ambele lock-uri de oferte, le eliberam
 *                                   si reincercam IMEDIAT, simetric, fara backoff aleator. Firele raman active (consuma CPU,
 *                                   cresc contorul RETRIES) dar progresul (tranzactiile) poate stagna / incetini mult.
 *                                   Fenomen probabilistic: depinde de planificare; urmareste liniile [watchdog].
 *
 *   java BursaDeadlock slow       - lock-uri pe oferte corecte (ordinea seq), dar UN SINGUR lock global (synchronized(GLOBAL))
 *                                   acopera tot pasul unui trader, inclusiv "analiza" (50 µs). Toti traderii sunt serializati,
 *                                   deci lock-urile fine pe oferte nu mai aduc niciun paralelism.
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
        final long price;                       // cheia de sortare in registru => nu se schimba niciodata
        int qty;                                // protejat de lock
        volatile boolean active = true;         // scris doar sub lock
        final ReentrantLock lock = new ReentrantLock();
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

    /** Corect in toate modurile (ordinea id-urilor); defectele sunt la nivelul lock-urilor pe oferte. */
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
                buyer.cash -= total; buyer.shares[stockIdx] += qty;
                seller.cash += total; seller.shares[stockIdx] -= qty;
                return Result.OK;
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
    }

    /** Ia lock-urile agresorului o si ale contrapartidei c; strategia depinde de MODE. */
    static void lockPair(Order o, Order c) {
        switch (MODE) {
            case DEADLOCK: {
                o.lock.lock();                                      // BUG: intai oferta proprie, apoi contrapartida -
                LockSupport.parkNanos(50_000);                      //      ordine data de rol, nu de seq (largim fereastra)
                c.lock.lock();
                return;
            }
            case LIVELOCK: {
                while (true) {
                    if (o.lock.tryLock()) {
                        LockSupport.parkNanos(20_000);
                        if (c.lock.tryLock()) return;
                        o.lock.unlock();                            // renunta la tot ...
                    }
                    RETRIES.incrementAndGet();                      // BUG: ... si reincearca imediat, simetric, fara backoff aleator
                    Thread.yield();
                }
            }
            default: {                                              // SLOW: ordinea globala dupa seq (corect); problema e altundeva
                Order first = o.seq < c.seq ? o : c;
                Order second = (first == o) ? c : o;
                first.lock.lock();
                second.lock.lock();
            }
        }
    }

    static void unlockPair(Order a, Order b) {
        a.lock.unlock();
        b.lock.unlock();
    }

    static final Comparator<Order> BUY_FIRST =
            Comparator.<Order>comparingLong(o -> -o.price).thenComparingLong(o -> o.seq);
    static final Comparator<Order> SELL_FIRST =
            Comparator.<Order>comparingLong(o -> o.price).thenComparingLong(o -> o.seq);

    static final class Stock {
        final int idx; final String symbol;
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
            book(side).add(o);        // intai in registru, apoi cautam contrapartida
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

        long[] bestBidAsk() {
            Order b = first(buys), s = first(sells);
            return new long[]{b == null ? 0 : b.price, s == null ? 0 : s.price};
        }

        private static Order first(ConcurrentSkipListSet<Order> set) {
            try { return set.first(); } catch (NoSuchElementException e) { return null; }
        }

        private ConcurrentSkipListSet<Order> book(Side side) { return side == Side.BUY ? buys : sells; }

        private void deactivate(Order o) { o.active = false; book(o.side).remove(o); }

        private static boolean crosses(Order a, Order b) {
            Order buy = a.side == Side.BUY ? a : b;
            Order sell = (buy == a) ? b : a;
            return sell.price <= buy.price;
        }

        private void match(Order o) {
            for (Order c : book(o.side == Side.BUY ? Side.SELL : Side.BUY)) {
                if (!o.active) return;
                if (!crosses(o, c)) return;
                if (c.trader == o.trader) continue;
                lockPair(o, c);
                try {
                    if (!o.active || !c.active) continue;
                    Order b = o.side == Side.BUY ? o : c;
                    Order s = (b == o) ? c : o;
                    int n = Math.min(b.qty, s.qty);
                    long px = (b.seq < s.seq) ? b.price : s.price;
                    switch (settle(b.trader, s.trader, idx, n, px)) {
                        case OK -> {
                            b.qty -= n; s.qty -= n;
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
            long price = s.lastPrice() + rnd.nextInt(101) - 50;
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
            Order replacement = o.stock.modify(o, o.stock.lastPrice() + rnd.nextInt(101) - 50, 1 + rnd.nextInt(20));
            if (replacement == null) open.remove(k);
            else open.set(k, replacement);
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

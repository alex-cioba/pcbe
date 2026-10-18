import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BURSA - varianta GRESITA #1: DATA RACES si inconsistenta datelor.
 *
 * Structura e identica cu BursaCorecta, dar TOATA sincronizarea a fost scoasa.
 * Erorile (marcate "BUG" in cod):
 *  BUG 1. Colectii nethread-safe (ArrayList) modificate si parcurse concurent
 *         -> ConcurrentModificationException, oferte pierdute, "Comparison method violates its general contract".
 *  BUG 2. match() ruleaza concurent pe aceeasi actiune: doua fire aleg aceeasi pereche de oferte
 *         si o executa de doua ori (double execution) -> se creeaza/distrug actiuni si bani.
 *  BUG 3. Read-modify-write neatomic: buyer.cash -= total, shares[i] += qty (lost update).
 *  BUG 4. Check-then-act in settle(): verifici fondurile, apoi (dupa un yield) le scazi -> cash/actiuni negative.
 *  BUG 5. Contoare partajate neatomice (orderSeq++, txSeq++) si campuri citite fara volatile/lock (lastPrice, active).
 *  BUG 6. modify(): if (o.active) ... apoi scrie => oferta poate fi executata intre test si scriere.
 *
 * La final se verifica invariantele (cash total, actiuni totale, valori negative): vor fi incalcate.
 * Compilare: javac BursaRace.java   |   Rulare: java BursaRace
 */
public class BursaRace {

    enum Side { BUY, SELL }
    enum Result { OK, BUYER_CANNOT_PAY, SELLER_CANNOT_DELIVER }

    static final String[] SYMBOLS = {"ACME", "BETA", "CORP", "DELT"};
    static final int TRADERS = 10;
    static final int ITERATIONS = 5_000;
    static final long INITIAL_CASH = 1_000_000;
    static final int INITIAL_SHARES = 1_000;
    static final long INITIAL_PRICE = 10_000;

    static long orderSeq = 0;                                   // BUG 5
    static long txSeq = 0;                                      // BUG 5
    static final AtomicInteger EXCEPTIONS = new AtomicInteger();

    record Transaction(long id, String symbol, int qty, long price, int buyerId, int sellerId) {}

    static final class Order {
        final long seq = ++orderSeq;                            // BUG 5 (increment neatomic)
        final Stock stock; final Trader trader; final Side side;
        long price; int qty;
        boolean active = true;                                  // BUG 5 (fara volatile)

        Order(Stock stock, Trader trader, Side side, long price, int qty) {
            this.stock = stock; this.trader = trader; this.side = side; this.price = price; this.qty = qty;
        }
    }

    static final class Trader {
        final int id;
        long cash = INITIAL_CASH;                               // fara lock
        final int[] shares = new int[SYMBOLS.length];           // fara lock
        Trader(int id) { this.id = id; Arrays.fill(shares, INITIAL_SHARES); }
    }

    static Result settle(Trader buyer, Trader seller, int stockIdx, int qty, long price) {
        long total = price * qty;
        if (buyer.cash < total) return Result.BUYER_CANNOT_PAY;             // BUG 4: check ...
        if (seller.shares[stockIdx] < qty) return Result.SELLER_CANNOT_DELIVER;
        Thread.yield();                                                     // largeste fereastra de race
        buyer.cash -= total;                                                // BUG 3: ... then act, neatomic
        buyer.shares[stockIdx] += qty;
        seller.cash += total;
        seller.shares[stockIdx] -= qty;
        return Result.OK;
    }

    static final Comparator<Order> BUY_FIRST =
            Comparator.<Order>comparingLong(o -> -o.price).thenComparingLong(o -> o.seq);
    static final Comparator<Order> SELL_FIRST =
            Comparator.<Order>comparingLong(o -> o.price).thenComparingLong(o -> o.seq);

    static final class Stock {
        final int idx; final String symbol;
        final List<Order> buys = new ArrayList<>();                         // BUG 1
        final List<Order> sells = new ArrayList<>();                        // BUG 1
        final List<Transaction> history = new ArrayList<>();                // BUG 1
        long lastPrice = INITIAL_PRICE;                                     // BUG 5

        Stock(int idx, String symbol) { this.idx = idx; this.symbol = symbol; }

        Order place(Trader t, Side side, long price, int qty) {
            Order o = new Order(this, t, side, price, qty);
            (side == Side.BUY ? buys : sells).add(o);                       // BUG 1
            match();                                                        // BUG 2: fara lock
            return o;
        }

        boolean modify(Order o, long newPrice, int newQty) {
            if (!o.active) return false;                                    // BUG 6: check ...
            Thread.yield();
            o.price = newPrice;                                             // ... then act
            o.qty = newQty;
            match();
            return true;
        }

        boolean cancel(Order o) {
            if (!o.active) return false;
            remove(o);
            return true;
        }

        long[] bestBidAsk() {
            long bid = 0, ask = 0;
            for (Order o : buys) bid = Math.max(bid, o.price);              // BUG 1: iterare in timpul modificarilor
            for (Order o : sells) ask = (ask == 0) ? o.price : Math.min(ask, o.price);
            return new long[]{bid, ask};
        }

        void remove(Order o) {
            o.active = false;
            (o.side == Side.BUY ? buys : sells).remove(o);
        }

        void match() {
            while (true) {
                buys.sort(BUY_FIRST);
                sells.sort(SELL_FIRST);
                Order b = null, s = null;
                search:
                for (Order bo : buys) {
                    for (Order so : sells) {
                        if (so.price > bo.price) break;
                        if (so.trader != bo.trader) { b = bo; s = so; break search; }
                    }
                }
                if (b == null) return;

                int n = Math.min(b.qty, s.qty);
                if (n <= 0) {                                               // stare corupta de race: evitam bucla infinita
                    if (b.qty <= 0) remove(b);
                    if (s.qty <= 0) remove(s);
                    continue;
                }
                long px = (b.seq < s.seq) ? b.price : s.price;
                switch (settle(b.trader, s.trader, idx, n, px)) {
                    case OK -> {
                        b.qty -= n;                                         // BUG 2/3: alt fir poate fi deja aici
                        s.qty -= n;
                        lastPrice = px;
                        history.add(new Transaction(++txSeq, symbol, n, px, b.trader.id, s.trader.id));
                        if (b.qty <= 0) remove(b);
                        if (s.qty <= 0) remove(s);
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
                try {
                    step();
                } catch (RuntimeException e) {          // CME, IAE etc. provocate de race-uri
                    EXCEPTIONS.incrementAndGet();
                }
            }
        }

        private void step() {
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
                if (trader.cash < price * qty) return;
            } else {
                if (bidAsk[0] > 0 && rnd.nextInt(3) == 0) price = bidAsk[0];
                if (trader.shares[s.idx] < qty) return;
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
        Stock[] stocks = new Stock[SYMBOLS.length];
        for (int i = 0; i < stocks.length; i++) stocks[i] = new Stock(i, SYMBOLS[i]);
        Trader[] traders = new Trader[TRADERS];
        for (int i = 0; i < TRADERS; i++) traders[i] = new Trader(i);

        ExecutorService pool = Executors.newFixedThreadPool(TRADERS);
        long t0 = System.nanoTime();
        for (Trader t : traders) pool.execute(new TraderTask(t, stocks));
        pool.shutdown();
        if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
            System.out.println("Timeout (bucla infinita provocata de stare corupta?)");
            Runtime.getRuntime().halt(2);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("=== BursaRace (GRESITA: data races) ===");
        System.out.printf("Timp: %d ms, txSeq=%d, tranzactii in istoric=%d, exceptii=%d%n",
                ms, txSeq, stocks[0].history.size() + stocks[1].history.size() + stocks[2].history.size() + stocks[3].history.size(),
                EXCEPTIONS.get());

        int violations = 0;
        long cashSum = 0;
        for (Trader t : traders) {
            cashSum += t.cash;
            if (t.cash < 0) { violations++; System.out.println("VIOLARE: cash negativ la trader " + t.id + " = " + t.cash); }
        }
        if (cashSum != TRADERS * INITIAL_CASH) {
            violations++;
            System.out.printf("VIOLARE: cash total = %d (asteptat %d, diferenta %d)%n", cashSum, TRADERS * INITIAL_CASH, cashSum - TRADERS * INITIAL_CASH);
        }
        for (Stock s : stocks) {
            long sum = 0;
            for (Trader t : traders) {
                if (t.shares[s.idx] < 0) { violations++; System.out.println("VIOLARE: actiuni negative " + s.symbol + " la trader " + t.id + " = " + t.shares[s.idx]); }
                sum += t.shares[s.idx];
            }
            if (sum != (long) TRADERS * INITIAL_SHARES) {
                violations++;
                System.out.printf("VIOLARE: total %s = %d (asteptat %d)%n", s.symbol, sum, (long) TRADERS * INITIAL_SHARES);
            }
        }
        System.out.println(violations == 0 ? "Nicio violare observata in aceasta rulare (reruleaza; race-urile sunt nedeterministe)."
                                           : "Invarianta incalcata de " + violations + " ori => data race-uri confirmate.");
    }
}

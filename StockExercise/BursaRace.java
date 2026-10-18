import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BURSA - varianta GRESITA #1: DATA RACES si inconsistenta datelor.
 *
 * Structura e identica cu BursaCorecta (registru ConcurrentSkipListSet, matching pe oferta "agresor",
 * oferta inserata inainte de matching), dar lock-urile pe oferte si pe traderi au fost scoase.
 * Erorile (marcate "BUG" in cod):
 *  BUG 1. match() nu ia lock-urile celor doua oferte: doi agresori pot executa simultan aceeasi oferta
 *         din registru (double execution) -> oferta e executata peste cantitatea ei (qty negativ).
 *  BUG 2. Check-then-act pe "active": verifici ca oferta e activa, apoi (dupa un yield) o executi,
 *         desi intre timp altcineva a executat-o / anulat-o.
 *  BUG 3. settle() fara lock-urile traderilor: verifici fondurile, apoi le scazi (check-then-act),
 *         iar buyer.cash -= total, shares[i] += qty sunt read-modify-write neatomice (lost update)
 *         -> se creeaza/distrug bani si actiuni, apar valori negative.
 *  BUG 4. b.qty -= n, s.qty -= n neatomice, fara lock.
 *  BUG 5. Contoare partajate neatomice (orderSeq++, txSeq++). Doua oferte cu acelasi seq sunt "egale" pentru
 *         comparator => ConcurrentSkipListSet.add() o refuza pe a doua: oferta se pierde fara urma.
 *         lastPrice e un long simplu (fara volatile, fara "pastreaza tranzactia cea mai noua").
 *  BUG 6. modify() schimba pretul IN LOC, cat timp oferta e inca in registru. Pretul e cheia de sortare
 *         => registrul devine nesortat, remove() nu mai gaseste oferta (oferte "fantoma"), iar matching-ul
 *         se opreste prea devreme si lasa oferte compatibile neexecutate.
 *  BUG 7. Istoricul este un ArrayList nethread-safe -> tranzactii pierdute / exceptii.
 *
 * La final se verifica invariantele (cash total, actiuni totale, valori negative, oferte compatibile ramase,
 * oferte executate peste cantitate, oferte pierdute): vor fi incalcate.
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
    static final AtomicInteger OVERFILLS = new AtomicInteger();     // oferte executate peste cantitate (BUG 1/2/4)
    static final AtomicInteger LOST_ORDERS = new AtomicInteger();   // oferte refuzate de registru (BUG 5)
    static final AtomicInteger GHOSTS = new AtomicInteger();        // oferte pe care remove() nu le-a gasit (BUG 6)

    record Transaction(long id, String symbol, int qty, long price, int buyerId, int sellerId) {}

    static final class Order {
        final long seq = ++orderSeq;                            // BUG 5 (increment neatomic)
        final Stock stock; final Trader trader; final Side side;
        long price;                                             // BUG 6 (cheie de sortare modificabila)
        int qty;                                                // BUG 4 (fara lock)
        boolean active = true;                                  // BUG 2/5 (fara volatile, fara lock)

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
        if (buyer.cash < total) return Result.BUYER_CANNOT_PAY;             // BUG 3: check ...
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
        final ConcurrentSkipListSet<Order> buys = new ConcurrentSkipListSet<>(BUY_FIRST);
        final ConcurrentSkipListSet<Order> sells = new ConcurrentSkipListSet<>(SELL_FIRST);
        final List<Transaction> history = new ArrayList<>();                // BUG 7
        long lastPrice = INITIAL_PRICE;                                     // BUG 5

        Stock(int idx, String symbol) { this.idx = idx; this.symbol = symbol; }

        Order place(Trader t, Side side, long price, int qty) {
            Order o = new Order(this, t, side, price, qty);
            if (!book(side).add(o)) LOST_ORDERS.incrementAndGet();          // BUG 5: seq duplicat => oferta "egala" refuzata
            match(o);
            return o;
        }

        boolean modify(Order o, long newPrice, int newQty) {
            if (!o.active) return false;                                    // BUG 2: check ...
            Thread.yield();
            o.price = newPrice;                                             // BUG 6: ... then act, cheia se schimba in registru
            o.qty = newQty;
            match(o);
            return true;
        }

        boolean cancel(Order o) {
            if (!o.active) return false;
            deactivate(o);
            return true;
        }

        long[] bestBidAsk() {
            Order b = first(buys), s = first(sells);
            return new long[]{b == null ? 0 : b.price, s == null ? 0 : s.price};
        }

        /** Oferte active, compatibile, de la traderi diferiti, ramase in registru (verificare finala). */
        int crossingOrders() {
            int count = 0;
            for (Order b : buys) {
                if (!b.active) continue;
                for (Order s : sells)
                    if (s.active && s.price <= b.price && s.trader != b.trader) { count++; break; }
            }
            return count;
        }

        private static Order first(ConcurrentSkipListSet<Order> set) {
            try { return set.first(); } catch (NoSuchElementException e) { return null; }
        }

        private ConcurrentSkipListSet<Order> book(Side side) { return side == Side.BUY ? buys : sells; }

        private void deactivate(Order o) {
            o.active = false;
            if (!book(o.side).remove(o)) GHOSTS.incrementAndGet();          // BUG 6: pretul schimbat => nu mai e gasita
        }

        private static boolean crosses(Order a, Order b) {
            Order buy = a.side == Side.BUY ? a : b;
            Order sell = (buy == a) ? b : a;
            return sell.price <= buy.price;
        }

        private void match(Order o) {
            for (Order c : book(o.side == Side.BUY ? Side.SELL : Side.BUY)) {
                if (!o.active) return;
                if (!crosses(o, c)) return;                                 // BUG 6: registrul poate fi nesortat
                if (c.trader == o.trader) continue;
                // BUG 1: lipseste lockPair(o, c)
                if (!o.active || !c.active) continue;                       // BUG 2: check ...
                Thread.yield();                                             //        (largeste fereastra)
                Order b = o.side == Side.BUY ? o : c;
                Order s = (b == o) ? c : o;
                int n = Math.min(b.qty, s.qty);
                if (n <= 0) continue;                                       // stare corupta de race: oferta deja consumata
                long px = (b.seq < s.seq) ? b.price : s.price;
                switch (settle(b.trader, s.trader, idx, n, px)) {           // ... then act
                    case OK -> {
                        b.qty -= n;                                         // BUG 4: alt fir poate fi deja aici
                        s.qty -= n;
                        if (b.qty < 0 || s.qty < 0) OVERFILLS.incrementAndGet();
                        lastPrice = px;                                     // BUG 5
                        history.add(new Transaction(++txSeq, symbol, n, px, b.trader.id, s.trader.id));   // BUG 5, 7
                        if (b.qty <= 0) deactivate(b);
                        if (s.qty <= 0) deactivate(s);
                    }
                    case BUYER_CANNOT_PAY -> deactivate(b);
                    case SELLER_CANNOT_DELIVER -> deactivate(s);
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
                } catch (RuntimeException e) {          // exceptii provocate de race-uri (ex. ArrayList.add concurent)
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

        int historySize = 0;
        for (Stock s : stocks) historySize += s.history.size();
        System.out.println("=== BursaRace (GRESITA: data races) ===");
        System.out.printf("Timp: %d ms, txSeq=%d, tranzactii in istoric=%d, exceptii=%d%n", ms, txSeq, historySize, EXCEPTIONS.get());
        System.out.printf("Oferte executate peste cantitate=%d, oferte pierdute (seq duplicat)=%d, oferte fantoma=%d%n",
                OVERFILLS.get(), LOST_ORDERS.get(), GHOSTS.get());

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
            int crossing = s.crossingOrders();
            if (crossing > 0) { violations++; System.out.printf("VIOLARE: %d cereri compatibile neexecutate la %s%n", crossing, s.symbol); }
        }
        if (OVERFILLS.get() > 0) violations++;
        if (LOST_ORDERS.get() > 0) violations++;
        if (historySize != txSeq) {
            violations++;
            System.out.printf("VIOLARE: istoric are %d tranzactii, txSeq=%d%n", historySize, txSeq);
        }
        System.out.println(violations == 0 ? "Nicio violare observata in aceasta rulare (reruleaza; race-urile sunt nedeterministe)."
                                           : "Invarianta incalcata de " + violations + " ori => data race-uri confirmate.");
    }
}

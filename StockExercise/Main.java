import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Porneste simularea: creeaza bursa si traderii, ii lasa sa tranzactioneze
 * un timp, apoi afiseaza un raport si verifica invariantii.
 */
public class Main {

    private static final int SIMULATION_SECONDS = 15;
    private static final int START_CASH = 50_000;
    private static final int START_SHARES = 100;

    public static void main(String[] args) throws InterruptedException {
        Map<String, Integer> startPrices = new HashMap<>();
        startPrices.put("AAPL", 150);
        startPrices.put("TSLA", 250);
        startPrices.put("NVDA", 120);
        Exchange exchange = new Exchange(startPrices);
        List<String> symbols = exchange.getSymbols();

        List<Trader> traders = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            traders.add(new RandomTrader("Random" + i, exchange, START_CASH, START_SHARES));
        }
        for (int i = 1; i <= 3; i++) {
            traders.add(new TrendTrader("Trend" + i, exchange, START_CASH, START_SHARES));
        }
        for (int i = 1; i <= 3; i++) {
            traders.add(new ImpatientTrader("Impatient" + i, exchange, START_CASH, START_SHARES));
        }

        int initialCash = totalCash(traders);
        Map<String, Integer> initialShares = totalShares(traders, symbols);

        // Pornim cate un fir de executie pentru fiecare trader.
        List<Thread> threads = new ArrayList<>();
        for (Trader trader : traders) {
            Thread thread = new Thread(trader, trader.getName());
            threads.add(thread);
            thread.start();
        }

        // Lasam simularea sa ruleze si afisam periodic registrele.
        for (int second = 1; second <= SIMULATION_SECONDS; second++) {
            Thread.sleep(1000);
            if (second % 5 == 0) {
                System.out.println("\n---------- Order book-uri dupa " + second + " secunde ----------");
                for (String symbol : symbols) {
                    System.out.print(exchange.describeBook(symbol));
                }
                System.out.println();
            }
        }

        // Oprim traderii si asteptam sa se termine firele.
        for (Trader trader : traders) {
            trader.stop();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        // Anulam comenzile ramase, ca banii/actiunile rezervate sa revina la traderi.
        for (Trader trader : traders) {
            trader.cancelAllOrders();
        }

        printReport(exchange, traders, symbols);
        checkInvariants(traders, symbols, initialCash, initialShares);
    }

    private static void printReport(Exchange exchange, List<Trader> traders, List<String> symbols) {
        System.out.println("\n================ RAPORT FINAL ================");
        System.out.println("Tranzactii efectuate: " + exchange.getHistory().size());
        System.out.println("Modificari/anulari refuzate (tranzactie in desfasurare): "
                + exchange.getRefusedModifications());

        System.out.println("\nUltimul pret:");
        for (String symbol : symbols) {
            System.out.println("  " + symbol + ": " + exchange.getLastPrice(symbol));
        }

        System.out.println("\nPortofolii:");
        for (Trader trader : traders) {
            StringBuilder line = new StringBuilder();
            line.append(String.format("  %-11s bani=%6d", trader.getName(), trader.getCash()));
            for (String symbol : symbols) {
                line.append("  ").append(symbol).append("=").append(trader.getShares(symbol));
            }
            System.out.println(line);
        }
    }

    /** Banii si actiunile doar se muta intre traderi, deci totalul trebuie sa ramana acelasi. */
    private static void checkInvariants(List<Trader> traders, List<String> symbols,
                                        int initialCash, Map<String, Integer> initialShares) {
        System.out.println("\nVerificare invarianti:");

        int finalCash = totalCash(traders);
        System.out.println("  Total bani: " + initialCash + " -> " + finalCash
                + (initialCash == finalCash ? "  OK" : "  EROARE"));

        Map<String, Integer> finalShares = totalShares(traders, symbols);
        for (String symbol : symbols) {
            int before = initialShares.get(symbol);
            int after = finalShares.get(symbol);
            System.out.println("  Total " + symbol + ": " + before + " -> " + after
                    + (before == after ? "  OK" : "  EROARE"));
        }
    }

    private static int totalCash(List<Trader> traders) {
        int total = 0;
        for (Trader trader : traders) {
            total += trader.getCash();
        }
        return total;
    }

    private static Map<String, Integer> totalShares(List<Trader> traders, List<String> symbols) {
        Map<String, Integer> totals = new HashMap<>();
        for (String symbol : symbols) {
            int total = 0;
            for (Trader trader : traders) {
                total += trader.getShares(symbol);
            }
            totals.put(symbol, total);
        }
        return totals;
    }
}

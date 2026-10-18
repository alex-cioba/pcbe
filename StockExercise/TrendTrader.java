import java.util.List;

/**
 * Urmareste tendinta: daca pretul a crescut in ultimele tranzactii cumpara,
 * daca a scazut vinde.
 */
public class TrendTrader extends Trader {

    private static final int HISTORY_SIZE = 5;

    public TrendTrader(String name, Exchange exchange, int startCash, int startShares) {
        super(name, exchange, startCash, startShares);
    }

    @Override
    protected void act() {
        String symbol = randomSymbol();
        List<Integer> prices = exchange.getRecentPrices(symbol, HISTORY_SIZE);
        if (prices.size() < HISTORY_SIZE) {
            return; // inca nu sunt destule tranzactii ca sa vedem o tendinta
        }

        int first = prices.get(0);
        int last = prices.get(prices.size() - 1);
        int quantity = 1 + random.nextInt(5);

        if (last > first) {
            buy(symbol, last + 1, quantity);   // pretul creste -> cumpara
        } else if (last < first) {
            sell(symbol, last - 1, quantity);  // pretul scade -> vinde
        }
    }
}

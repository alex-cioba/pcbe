/**
 * Strategie simpla: cumpara sau vinde aleator, la un pret apropiat de ultimul pret.
 */
public class RandomTrader extends Trader {

    public RandomTrader(String name, Exchange exchange, int startCash, int startShares) {
        super(name, exchange, startCash, startShares);
    }

    @Override
    protected void act() {
        String symbol = randomSymbol();
        int lastPrice = exchange.getLastPrice(symbol);
        int price = lastPrice + random.nextInt(7) - 3; // intre -3 si +3 fata de ultimul pret
        int quantity = 1 + random.nextInt(10);

        if (random.nextBoolean()) {
            buy(symbol, price, quantity);
        } else {
            sell(symbol, price, quantity);
        }
    }
}

/**
 * Pune comenzi la un pret avantajos pentru el. Daca o comanda nu se executa
 * in PATIENCE_MS, ii modifica pretul treptat spre piata.
 * Acest trader foloseste regula de modificare din enunt.
 */
public class ImpatientTrader extends Trader {

    private static final long PATIENCE_MS = 1500;

    public ImpatientTrader(String name, Exchange exchange, int startCash, int startShares) {
        super(name, exchange, startCash, startShares);
    }

    @Override
    protected void act() {
        Order waiting = findOldWaitingOrder();
        if (waiting != null) {
            becomeLessPicky(waiting);
            return;
        }

        String symbol = randomSymbol();
        int lastPrice = exchange.getLastPrice(symbol);
        int quantity = 1 + random.nextInt(8);

        if (random.nextBoolean()) {
            buy(symbol, lastPrice - 2, quantity);   // vrea sa cumpere mai ieftin
        } else {
            sell(symbol, lastPrice + 2, quantity);  // vrea sa vanda mai scump
        }
    }

    private Order findOldWaitingOrder() {
        long now = System.currentTimeMillis();
        for (Order order : myOrders) {
            if (order.isActive() && now - order.getCreatedAt() > PATIENCE_MS) {
                return order;
            }
        }
        return null;
    }

    private void becomeLessPicky(Order order) {
        int newPrice;
        if (order.isBuy()) {
            newPrice = order.getPrice() + 1;
        } else {
            newPrice = order.getPrice() - 1;
        }
        if (newPrice < 1) {
            return;
        }

        boolean modified = exchange.modifyPrice(order, newPrice);
        if (modified) {
            System.out.println("[MODIFICARE] " + name + " schimba pretul comenzii #"
                    + order.getId() + " la " + newPrice);
        }
    }
}

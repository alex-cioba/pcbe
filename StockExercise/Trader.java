import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Un participant la bursa, care ruleaza pe propriul fir de executie.
 * Fiecare subclasa are alt algoritm de decizie (metoda act()).
 *
 * Banii si actiunile sunt "rezervate" cand se plaseaza o comanda,
 * ca un trader sa nu poata vinde ce nu are sau cumpara fara bani.
 */
public abstract class Trader implements Runnable {

    private static final int MAX_OPEN_ORDERS = 4;

    protected final String name;
    protected final Exchange exchange;
    protected final Random random = new Random();
    protected final List<Order> myOrders = new ArrayList<>(); // folosita doar de firul traderului

    private int cash;
    private final Map<String, Integer> shares = new HashMap<>();
    private volatile boolean running = true;

    public Trader(String name, Exchange exchange, int startCash, int startSharesPerSymbol) {
        this.name = name;
        this.exchange = exchange;
        this.cash = startCash;
        for (String symbol : exchange.getSymbols()) {
            shares.put(symbol, startSharesPerSymbol);
        }
    }

    @Override
    public void run() {
        while (running) {
            myOrders.removeIf(order -> !order.isActive());

            if (myOrders.size() >= MAX_OPEN_ORDERS) {
                cancelOldestOrder();
            } else {
                act();
            }

            pause(100 + random.nextInt(300));
        }
    }

    /** Algoritmul de decizie al traderului: se uita la piata si plaseaza/modifica comenzi. */
    protected abstract void act();

    public void stop() {
        running = false;
    }

    public String getName() {
        return name;
    }

    // ---------- actiuni disponibile subclaselor ----------

    protected void buy(String symbol, int price, int quantity) {
        if (price < 1 || quantity < 1) {
            return;
        }
        if (!reserveCash(price * quantity)) {
            return; // nu are destui bani
        }
        Order order = exchange.placeOrder(this, symbol, Order.Type.BUY, price, quantity);
        myOrders.add(order);
    }

    protected void sell(String symbol, int price, int quantity) {
        if (price < 1 || quantity < 1) {
            return;
        }
        if (!reserveShares(symbol, quantity)) {
            return; // nu are destule actiuni
        }
        Order order = exchange.placeOrder(this, symbol, Order.Type.SELL, price, quantity);
        myOrders.add(order);
    }

    protected void cancelOldestOrder() {
        Order oldest = myOrders.get(0);
        exchange.cancelOrder(oldest); // daca e refuzata, reincearca la pasul urmator
    }

    public void cancelAllOrders() {
        for (Order order : myOrders) {
            exchange.cancelOrder(order);
        }
        myOrders.clear();
    }

    protected String randomSymbol() {
        List<String> symbols = exchange.getSymbols();
        return symbols.get(random.nextInt(symbols.size()));
    }

    // ---------- bani si actiuni (apelate si din OrderBook, deci synchronized) ----------

    private synchronized boolean reserveCash(int amount) {
        if (cash < amount) {
            return false;
        }
        cash -= amount;
        return true;
    }

    private synchronized boolean reserveShares(String symbol, int quantity) {
        int owned = shares.get(symbol);
        if (owned < quantity) {
            return false;
        }
        shares.put(symbol, owned - quantity);
        return true;
    }

    /** Cumparatorul primeste actiunile si diferenta de bani rezervata in plus. */
    synchronized void settleBuy(String symbol, int quantity, int orderPrice, int tradePrice) {
        shares.put(symbol, shares.get(symbol) + quantity);
        cash += (orderPrice - tradePrice) * quantity;
    }

    /** Vanzatorul primeste banii (actiunile i-au fost deja rezervate). */
    synchronized void settleSell(int quantity, int tradePrice) {
        cash += quantity * tradePrice;
    }

    /** La anulare, traderul primeste inapoi ce a rezervat pentru partea neexecutata. */
    synchronized void releaseReservation(Order order) {
        if (order.isBuy()) {
            cash += order.getPrice() * order.getQuantity();
        } else {
            String symbol = order.getSymbol();
            shares.put(symbol, shares.get(symbol) + order.getQuantity());
        }
    }

    /** La modificarea pretului unei cereri, ajustam suma rezervata. */
    synchronized boolean adjustReservation(Order order, int newPrice) {
        if (!order.isBuy()) {
            return true; // la vanzare sunt rezervate actiuni, nu bani
        }
        int difference = (newPrice - order.getPrice()) * order.getQuantity();
        if (difference > cash) {
            return false;
        }
        cash -= difference;
        return true;
    }

    public synchronized int getCash() {
        return cash;
    }

    public synchronized int getShares(String symbol) {
        return shares.get(symbol);
    }

    private void pause(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}

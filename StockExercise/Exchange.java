import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bursa: cate un order book pentru fiecare actiune + istoria comuna a tranzactiilor.
 */
public class Exchange {

    private final Map<String, OrderBook> books = new HashMap<>();
    private final Map<String, Integer> startPrices;
    private final List<Transaction> history = new ArrayList<>();
    private final AtomicInteger refusedModifications = new AtomicInteger();

    public Exchange(Map<String, Integer> startPrices) {
        this.startPrices = startPrices;
        for (String symbol : startPrices.keySet()) {
            books.put(symbol, new OrderBook(symbol, this));
        }
    }

    public List<String> getSymbols() {
        return new ArrayList<>(books.keySet());
    }

    // ---------- operatii pentru traderi ----------

    public Order placeOrder(Trader trader, String symbol, Order.Type type, int price, int quantity) {
        Order order = new Order(trader, symbol, type, price, quantity);
        books.get(symbol).addOrder(order);
        return order;
    }

    public boolean cancelOrder(Order order) {
        return books.get(order.getSymbol()).cancelOrder(order);
    }

    public boolean modifyPrice(Order order, int newPrice) {
        return books.get(order.getSymbol()).modifyPrice(order, newPrice);
    }

    // ---------- informatii comune (oricine le poate citi) ----------

    public synchronized List<Transaction> getHistory() {
        return new ArrayList<>(history);
    }

    /** Pretul ultimei tranzactii pe actiune, sau pretul de pornire daca nu a fost niciuna. */
    public synchronized int getLastPrice(String symbol) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Transaction t = history.get(i);
            if (t.getSymbol().equals(symbol)) {
                return t.getPrice();
            }
        }
        return startPrices.get(symbol);
    }

    /** Ultimele `count` preturi ale unei actiuni, de la cel mai vechi la cel mai nou. */
    public synchronized List<Integer> getRecentPrices(String symbol, int count) {
        List<Integer> prices = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && prices.size() < count; i--) {
            Transaction t = history.get(i);
            if (t.getSymbol().equals(symbol)) {
                prices.add(t.getPrice());
            }
        }
        Collections.reverse(prices);
        return prices;
    }

    public String describeBook(String symbol) {
        return books.get(symbol).describe();
    }

    public int getRefusedModifications() {
        return refusedModifications.get();
    }

    // ---------- apelate din OrderBook ----------

    synchronized void recordTransaction(Transaction transaction) {
        history.add(transaction);
        System.out.println(transaction);
    }

    void countRefusedModification() {
        refusedModifications.incrementAndGet();
    }
}

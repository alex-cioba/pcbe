import java.util.concurrent.atomic.AtomicInteger;

/**
 * O cerere (BUY) sau o oferta (SELL) pentru o anumita actiune.
 * Pretul si cantitatea se modifica doar sub lock-ul order book-ului,
 * dar sunt citite si de alte fire, de aceea sunt volatile.
 */
public class Order {

    public enum Type { BUY, SELL }

    private static final AtomicInteger nextId = new AtomicInteger(1);

    private final int id;
    private final Trader trader;
    private final String symbol;
    private final Type type;
    private final long createdAt;
    private volatile int price;
    private volatile int quantity;

    public Order(Trader trader, String symbol, Type type, int price, int quantity) {
        this.id = nextId.getAndIncrement();
        this.trader = trader;
        this.symbol = symbol;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.createdAt = System.currentTimeMillis();
    }

    public int getId() { return id; }
    public Trader getTrader() { return trader; }
    public String getSymbol() { return symbol; }
    public Type getType() { return type; }
    public long getCreatedAt() { return createdAt; }
    public int getPrice() { return price; }
    public int getQuantity() { return quantity; }

    public boolean isBuy() { return type == Type.BUY; }

    /** O comanda e activa cat timp mai are actiuni de cumparat/vandut. */
    public boolean isActive() { return quantity > 0; }

    public void setPrice(int price) { this.price = price; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    @Override
    public String toString() {
        return "#" + id + " " + trader.getName() + " " + type + " " + quantity + " x " + symbol + " @ " + price;
    }
}

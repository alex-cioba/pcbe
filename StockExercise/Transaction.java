/**
 * O tranzactie efectuata: cine a cumparat, de la cine, cat si la ce pret.
 */
public class Transaction {

    private final String symbol;
    private final String buyer;
    private final String seller;
    private final int quantity;
    private final int price;
    private final long time;

    public Transaction(String symbol, String buyer, String seller, int quantity, int price) {
        this.symbol = symbol;
        this.buyer = buyer;
        this.seller = seller;
        this.quantity = quantity;
        this.price = price;
        this.time = System.currentTimeMillis();
    }

    public String getSymbol() { return symbol; }
    public String getBuyer() { return buyer; }
    public String getSeller() { return seller; }
    public int getQuantity() { return quantity; }
    public int getPrice() { return price; }
    public long getTime() { return time; }

    @Override
    public String toString() {
        return "[TRANZACTIE] " + symbol + ": " + buyer + " cumpara " + quantity
                + " de la " + seller + " @ " + price;
    }
}

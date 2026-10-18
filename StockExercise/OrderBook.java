import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registrul de cereri si oferte pentru O SINGURA actiune.
 * Toate operatiile pe el trec prin acelasi lock, deci:
 *  - doua tranzactii pe aceeasi actiune nu pot rula simultan;
 *  - o modificare/anulare nu poate avea loc in timpul unei tranzactii.
 */
public class OrderBook {

    /** Durata simulata a unei tranzactii, ca sa se vada si refuzurile de modificare. */
    private static final int TRADE_DURATION_MS = 10;

    private final String symbol;
    private final Exchange exchange;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Order> buys = new ArrayList<>();
    private final List<Order> sells = new ArrayList<>();

    public OrderBook(String symbol, Exchange exchange) {
        this.symbol = symbol;
        this.exchange = exchange;
    }

    /** Adauga o comanda noua si incearca imediat sa o potriveasca. */
    public void addOrder(Order order) {
        lock.lock();
        try {
            if (order.isBuy()) {
                buys.add(order);
            } else {
                sells.add(order);
            }
            match();
        } finally {
            lock.unlock();
        }
    }

    /** Anuleaza o comanda. Refuza daca e o tranzactie in desfasurare. */
    public boolean cancelOrder(Order order) {
        if (!lock.tryLock()) {
            exchange.countRefusedModification();
            return false;
        }
        try {
            List<Order> side = order.isBuy() ? buys : sells;
            if (!side.remove(order)) {
                return false; // a fost deja executata complet
            }
            order.getTrader().releaseReservation(order);
            order.setQuantity(0);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Modifica pretul unei comenzi. Refuza daca e o tranzactie in desfasurare. */
    public boolean modifyPrice(Order order, int newPrice) {
        if (!lock.tryLock()) {
            exchange.countRefusedModification();
            return false;
        }
        try {
            List<Order> side = order.isBuy() ? buys : sells;
            if (!side.contains(order)) {
                return false; // a fost deja executata complet
            }
            if (!order.getTrader().adjustReservation(order, newPrice)) {
                return false; // traderul nu are destui bani pentru noul pret
            }
            order.setPrice(newPrice);
            match(); // noul pret poate crea o potrivire
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cat timp cea mai buna cerere >= cea mai buna oferta, se tranzactioneaza
     * n = min(cantitate cerere, cantitate oferta).
     * Se apeleaza doar cand lock-ul e deja luat.
     */
    private void match() {
        buys.sort(OrderBook::compareBuys);
        sells.sort(OrderBook::compareSells);

        while (!buys.isEmpty() && !sells.isEmpty()) {
            Order buy = buys.get(0);
            Order sell = sells.get(0);

            if (buy.getPrice() < sell.getPrice()) {
                break; // nu mai exista potrivire de pret
            }

            int n = Math.min(buy.getQuantity(), sell.getQuantity());

            // Pretul tranzactiei e pretul comenzii care astepta deja in registru.
            int tradePrice;
            if (buy.getId() < sell.getId()) {
                tradePrice = buy.getPrice();
            } else {
                tradePrice = sell.getPrice();
            }

            simulateTradeDuration();

            buy.getTrader().settleBuy(symbol, n, buy.getPrice(), tradePrice);
            sell.getTrader().settleSell(n, tradePrice);

            buy.setQuantity(buy.getQuantity() - n);
            sell.setQuantity(sell.getQuantity() - n);

            if (buy.getQuantity() == 0) {
                buys.remove(0);
            }
            if (sell.getQuantity() == 0) {
                sells.remove(0);
            }

            Transaction transaction = new Transaction(symbol,
                    buy.getTrader().getName(), sell.getTrader().getName(), n, tradePrice);
            exchange.recordTransaction(transaction);
        }
    }

    /** Cererile: pretul cel mai mare primul; la egalitate, cea mai veche. */
    private static int compareBuys(Order a, Order b) {
        if (a.getPrice() != b.getPrice()) {
            return Integer.compare(b.getPrice(), a.getPrice());
        }
        return Integer.compare(a.getId(), b.getId());
    }

    /** Ofertele: pretul cel mai mic primul; la egalitate, cea mai veche. */
    private static int compareSells(Order a, Order b) {
        if (a.getPrice() != b.getPrice()) {
            return Integer.compare(a.getPrice(), b.getPrice());
        }
        return Integer.compare(a.getId(), b.getId());
    }

    private void simulateTradeDuration() {
        try {
            Thread.sleep(TRADE_DURATION_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Copie text a registrului (primele 5 de fiecare parte), sigura pentru afisare. */
    public String describe() {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("=== ").append(symbol).append(" ===\n");
            sb.append("  CERERI (BUY):\n");
            for (int i = 0; i < Math.min(5, buys.size()); i++) {
                sb.append("    ").append(buys.get(i)).append("\n");
            }
            sb.append("  OFERTE (SELL):\n");
            for (int i = 0; i < Math.min(5, sells.size()); i++) {
                sb.append("    ").append(sells.get(i)).append("\n");
            }
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }
}

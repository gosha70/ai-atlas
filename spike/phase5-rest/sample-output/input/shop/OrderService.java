package shop;
import com.egoge.ai.atlas.annotations.*;
import com.egoge.ai.atlas.annotations.AgenticExposed.HttpMethod;
import com.egoge.ai.atlas.annotations.AgenticExposed.Rest;
import java.util.*;
@AgenticExposed(rest = @Rest(resource = "orders"))
public class OrderService {
    public static Order lastPlaced;
    private final Map<Long, Order> orders = new TreeMap<>(Map.of(7L, new Order(7L, "NEW")));
    @AgenticExposed(description = "Order by id", returnType = Order.class,
            rest = @Rest(method = HttpMethod.GET, path = "/{id}"))
    public Order get(Long id) { return orders.get(id); }
    @AgenticExposed(description = "Orders by status", returnType = Order.class,
            rest = @Rest(method = HttpMethod.GET, path = ""))
    public List<Order> byStatus(String status) {
        return orders.values().stream().filter(o -> o.getStatus().equals(status)).toList();
    }
    @AgenticExposed(description = "Place an order", returnType = Order.class,
            rest = @Rest(method = HttpMethod.POST, path = "", status = 201))
    public Order place(Order order) { lastPlaced = order; orders.put(order.getId(), order); return order; }
    @AgenticExposed(description = "Change an order's status", returnType = Order.class,
            rest = @Rest(method = HttpMethod.PATCH, path = "/{id}/status"))
    public Order changeStatus(Long id, String status) {
        Order o = orders.get(id); o.setStatus(status); return o;
    }
    @AgenticExposed(description = "Cancel an order", rest = @Rest(method = HttpMethod.DELETE, path = "/{id}"))
    public void cancel(Long id) { orders.remove(id); }
    @AgenticExposed(description = "Number of orders")
    public long count() { return orders.size(); }
}

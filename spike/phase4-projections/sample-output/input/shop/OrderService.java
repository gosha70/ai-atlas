package shop;
import com.egoge.ai.atlas.annotations.*;
import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
import java.util.List;
@AgenticExposed(description = "Order operations", returnType = Order.class)
public class OrderService {
    @AgenticExposed(description = "Find an order on both channels")
    public Order find(Long id) { return Order.sample(); }
    @AgenticExposed(description = "List orders on both channels")
    public List<Order> list() { return List.of(Order.sample()); }
    @AgenticExposed(description = "Agent-only lookup", channels = Channel.AI)
    public Order forAgent(Long id) { return Order.sample(); }
    @AgenticExposed(description = "REST-only lookup", channels = Channel.API)
    public Order forApi(Long id) { return Order.sample(); }
    @AgenticExposed(description = "A customer", returnType = Customer.class)
    public Customer customer(Long id) { return new Customer(3L, "Alice"); }
}

package shop;
import com.egoge.ai.atlas.annotations.*;
import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
@AgenticEntity(description = "An action on an order")
public class OrderAction {
    @AgenticField(description = "Action id") private Long id;
    @AgenticField(description = "Action type") private String type;
    @AgenticField(description = "Employee who performed it", channels = Channel.API) private String performedBy;
    @AgenticField(description = "Parent order") private Order order;
    public OrderAction(Long id, String type, String performedBy, Order order) {
        this.id = id; this.type = type; this.performedBy = performedBy; this.order = order;
    }
    public Long getId() { return id; }
    public String getType() { return type; }
    public String getPerformedBy() { return performedBy; }
    public Order getOrder() { return order; }
}

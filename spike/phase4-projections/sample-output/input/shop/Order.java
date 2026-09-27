package shop;
import com.egoge.ai.atlas.annotations.*;
import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
import java.util.List;
@AgenticEntity(description = "A customer order")
public class Order {
    @AgenticField(description = "Order id") private Long id;
    @AgenticField(description = "Status") private String status;
    @AgenticField(description = "Internal margin, for REST back-office clients", channels = Channel.API) private Integer marginCents;
    @AgenticField(description = "Plain-language summary for agents", channels = Channel.AI) private String agentSummary;
    @AgenticField(description = "Actions on the order") private List<OrderAction> actions;
    @AgenticField(description = "The customer") private Customer customer;
    private String customerSsn; // never annotated: on no channel
    public Long getId() { return id; }
    public String getStatus() { return status; }
    public Integer getMarginCents() { return marginCents; }
    public String getAgentSummary() { return agentSummary; }
    public List<OrderAction> getActions() { return actions; }
    public Customer getCustomer() { return customer; }
    public String getCustomerSsn() { return customerSsn; }
    public static Order sample() {
        Order o = new Order();
        o.id = 7L; o.status = "SHIPPED"; o.marginCents = 1234; o.agentSummary = "Shipped yesterday";
        o.customerSsn = "123-45-6789"; o.customer = new Customer(3L, "Alice");
        o.actions = List.of(new OrderAction(100L, "CREATED", "clerk-17", o));
        return o;
    }
}

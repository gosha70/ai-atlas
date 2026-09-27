package shop;
import com.egoge.ai.atlas.annotations.*;
import java.util.List;
@AgenticEntity(description = "A shipment")
public class Shipment {
    @AgenticField(description = "Shipment id") private Long id;
    @AgenticField(description = "Actions") private List<OrderAction> actions;
    public Long getId() { return id; }
    public List<OrderAction> getActions() { return actions; }
}

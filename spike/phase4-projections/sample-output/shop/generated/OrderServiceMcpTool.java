package shop.generated;

import java.lang.Long;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import shop.Order;
import shop.OrderService;

@Generated("com.egoge.ai.atlas.processor")
@Service
public class OrderServiceMcpTool {
    private final OrderService service;

    public OrderServiceMcpTool(OrderService service) {
        this.service = service;
    }

    @Tool(
            name = "find",
            description = "Find an order on both channels"
    )
    public OrderAiDto find(@ToolParam(description = "id") Long id) {
        return OrderAiDto.fromEntity(service.find(id));
    }

    @Tool(
            name = "list",
            description = "List orders on both channels"
    )
    public List<OrderAiDto> list() {
        return service.list().stream().map(e -> OrderAiDto.fromEntity((Order) e)).toList();
    }

    @Tool(
            name = "forAgent",
            description = "Agent-only lookup"
    )
    public OrderAiDto forAgent(@ToolParam(description = "id") Long id) {
        return OrderAiDto.fromEntity(service.forAgent(id));
    }

    @Tool(
            name = "customer",
            description = "A customer"
    )
    public CustomerDto customer(@ToolParam(description = "id") Long id) {
        return CustomerDto.fromEntity(service.customer(id));
    }
}

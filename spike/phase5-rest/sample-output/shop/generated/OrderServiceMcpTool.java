package shop.generated;

import java.lang.Long;
import java.lang.String;
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
            name = "get",
            description = "Order by id"
    )
    public OrderDto get(@ToolParam(description = "id") Long id) {
        return OrderDto.fromEntity(service.get(id));
    }

    @Tool(
            name = "byStatus",
            description = "Orders by status"
    )
    public List<OrderDto> byStatus(@ToolParam(description = "status") String status) {
        return service.byStatus(status).stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @Tool(
            name = "place",
            description = "Place an order"
    )
    public OrderDto place(@ToolParam(description = "order") Order order) {
        return OrderDto.fromEntity(service.place(order));
    }

    @Tool(
            name = "changeStatus",
            description = "Change an order's status"
    )
    public OrderDto changeStatus(@ToolParam(description = "id") Long id,
            @ToolParam(description = "status") String status) {
        return OrderDto.fromEntity(service.changeStatus(id, status));
    }

    @Tool(
            name = "cancel",
            description = "Cancel an order"
    )
    public void cancel(@ToolParam(description = "id") Long id) {
        service.cancel(id);
    }

    @Tool(
            name = "count",
            description = "Number of orders"
    )
    public long count() {
        return service.count();
    }
}

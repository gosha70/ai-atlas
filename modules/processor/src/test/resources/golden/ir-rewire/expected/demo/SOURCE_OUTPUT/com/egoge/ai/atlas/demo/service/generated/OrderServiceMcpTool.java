package com.egoge.ai.atlas.demo.service.generated;

import com.egoge.ai.atlas.demo.entity.Order;
import com.egoge.ai.atlas.demo.entity.generated.OrderDto;
import com.egoge.ai.atlas.demo.service.OrderService;
import java.lang.Long;
import java.lang.String;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Generated("com.egoge.ai.atlas.processor")
@Service
public class OrderServiceMcpTool {
    private final OrderService service;

    public OrderServiceMcpTool(OrderService service) {
        this.service = service;
    }

    @Tool(
            name = "findById",
            description = "[DEPRECATED since v1, use findByIdV2] Find order by ID (legacy)"
    )
    public OrderDto findById(@ToolParam(description = "id") Long id) {
        return OrderDto.fromEntity(service.findById(id));
    }

    @Tool(
            name = "findByIdV2",
            description = "[Since v2] Find order by ID"
    )
    public OrderDto findByIdV2(@ToolParam(description = "id") Long id) {
        return OrderDto.fromEntity(service.findByIdV2(id));
    }

    @Tool(
            name = "findByStatus",
            description = "Find orders by status (e.g. PENDING, CONFIRMED)"
    )
    public List<OrderDto> findByStatus(@ToolParam(description = "status") String status) {
        return service.findByStatus(status).stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }
}

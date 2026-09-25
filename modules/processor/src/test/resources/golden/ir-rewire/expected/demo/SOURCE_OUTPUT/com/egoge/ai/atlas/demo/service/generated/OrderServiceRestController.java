package com.egoge.ai.atlas.demo.service.generated;

import com.egoge.ai.atlas.demo.entity.Order;
import com.egoge.ai.atlas.demo.entity.generated.OrderDto;
import com.egoge.ai.atlas.demo.service.OrderService;
import java.lang.Deprecated;
import java.lang.Long;
import java.lang.String;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v2/order-service")
public class OrderServiceRestController {
    private final OrderService service;

    public OrderServiceRestController(OrderService service) {
        this.service = service;
    }

    @PostMapping("/find-by-id")
    @Deprecated
    public OrderDto findById(@RequestParam Long id) {
        return OrderDto.fromEntity(service.findById(id));
    }

    @PostMapping("/find-by-id-v2")
    public OrderDto findByIdV2(@RequestParam Long id) {
        return OrderDto.fromEntity(service.findByIdV2(id));
    }

    @PostMapping("/find-by-status")
    public List<OrderDto> findByStatus(@RequestParam String status) {
        return service.findByStatus(status).stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }
}

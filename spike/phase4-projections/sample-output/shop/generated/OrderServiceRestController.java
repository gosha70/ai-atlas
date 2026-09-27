package shop.generated;

import java.lang.Long;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shop.Order;
import shop.OrderService;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/order-service")
public class OrderServiceRestController {
    private final OrderService service;

    public OrderServiceRestController(OrderService service) {
        this.service = service;
    }

    @PostMapping("/find")
    public OrderDto find(@RequestParam Long id) {
        return OrderDto.fromEntity(service.find(id));
    }

    @GetMapping("/list")
    public List<OrderDto> list() {
        return service.list().stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @PostMapping("/for-api")
    public OrderDto forApi(@RequestParam Long id) {
        return OrderDto.fromEntity(service.forApi(id));
    }

    @PostMapping("/customer")
    public CustomerDto customer(@RequestParam Long id) {
        return CustomerDto.fromEntity(service.customer(id));
    }
}

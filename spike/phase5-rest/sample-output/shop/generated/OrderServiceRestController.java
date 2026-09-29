package shop.generated;

import java.lang.Long;
import java.lang.String;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import shop.Order;
import shop.OrderService;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/orders")
public class OrderServiceRestController {
    private final OrderService service;

    public OrderServiceRestController(OrderService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public OrderDto get(@PathVariable("id") Long id) {
        return OrderDto.fromEntity(service.get(id));
    }

    @GetMapping
    public List<OrderDto> byStatus(@RequestParam String status) {
        return service.byStatus(status).stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDto place(@RequestBody Order order) {
        return OrderDto.fromEntity(service.place(order));
    }

    @PatchMapping("/{id}/status")
    public OrderDto changeStatus(@PathVariable("id") Long id, @RequestParam String status) {
        return OrderDto.fromEntity(service.changeStatus(id, status));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable("id") Long id) {
        service.cancel(id);
    }

    @GetMapping("/count")
    public long count() {
        return service.count();
    }
}

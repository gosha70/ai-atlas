package a.generated;

import a.OrderService;
import java.lang.Long;
import java.lang.String;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/order-service")
public class OrderServiceRestController {
    private final OrderService service;

    public OrderServiceRestController(OrderService service) {
        this.service = service;
    }

    @PostMapping("/find")
    public String find(@RequestParam Long id) {
        return service.find(id);
    }
}

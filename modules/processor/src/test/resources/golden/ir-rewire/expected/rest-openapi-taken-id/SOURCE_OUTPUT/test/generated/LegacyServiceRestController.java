package test.generated;

import java.lang.String;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import test.LegacyService;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/legacy-service")
public class LegacyServiceRestController {
    private final LegacyService service;

    public LegacyServiceRestController(LegacyService service) {
        this.service = service;
    }

    @GetMapping("/order-service_find_get")
    public String OrderService_find_get() {
        return service.OrderService_find_get();
    }
}

package b.generated;

import b.PricingService;
import java.lang.String;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/pricing-service")
public class PricingServiceRestController {
    private final PricingService service;

    public PricingServiceRestController(PricingService service) {
        this.service = service;
    }

    @GetMapping("/find")
    public String find() {
        return service.find();
    }
}

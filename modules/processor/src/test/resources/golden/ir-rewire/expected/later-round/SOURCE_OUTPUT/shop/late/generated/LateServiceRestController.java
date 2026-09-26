package shop.late.generated;

import java.lang.String;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shop.late.LateService;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/late-service")
public class LateServiceRestController {
    private final LateService service;

    public LateServiceRestController(LateService service) {
        this.service = service;
    }

    @GetMapping("/late")
    public String late() {
        return service.late();
    }
}

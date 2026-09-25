package b.generated;

import b.PricingService;
import java.lang.String;
import javax.annotation.processing.Generated;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Generated("com.egoge.ai.atlas.processor")
@Service
public class PricingServiceMcpTool {
    private final PricingService service;

    public PricingServiceMcpTool(PricingService service) {
        this.service = service;
    }

    @Tool(
            name = "findPrices",
            description = "Find prices"
    )
    public String findPrices() {
        return service.find();
    }
}

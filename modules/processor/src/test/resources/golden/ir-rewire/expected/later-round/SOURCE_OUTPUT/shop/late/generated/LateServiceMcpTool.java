package shop.late.generated;

import java.lang.String;
import javax.annotation.processing.Generated;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import shop.late.LateService;

@Generated("com.egoge.ai.atlas.processor")
@Service
public class LateServiceMcpTool {
    private final LateService service;

    public LateServiceMcpTool(LateService service) {
        this.service = service;
    }

    @Tool(
            name = "late",
            description = "Late operation"
    )
    public String late() {
        return service.late();
    }
}

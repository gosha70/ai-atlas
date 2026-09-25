package a.generated;

import a.InventoryService;
import java.lang.String;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/inventory-service")
public class InventoryServiceRestController {
    private final InventoryService service;

    public InventoryServiceRestController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/find")
    public String find() {
        return service.find();
    }
}

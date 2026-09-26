package shop.generated;

import java.lang.Deprecated;
import java.lang.String;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shop.CatalogService;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/catalog-service")
public class CatalogServiceRestController {
    private final CatalogService service;

    public CatalogServiceRestController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public String list() {
        return service.list();
    }

    @GetMapping("/lookup")
    @Deprecated
    public String lookup() {
        return service.lookup();
    }
}

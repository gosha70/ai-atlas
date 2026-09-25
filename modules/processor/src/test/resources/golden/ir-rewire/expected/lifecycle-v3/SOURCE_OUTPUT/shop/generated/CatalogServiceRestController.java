package shop.generated;

import java.lang.Deprecated;
import java.lang.Long;
import java.lang.String;
import java.util.Arrays;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shop.CatalogService;
import shop.Product;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/shop-api/v3/catalog-service")
public class CatalogServiceRestController {
    private final CatalogService service;

    public CatalogServiceRestController(CatalogService service) {
        this.service = service;
    }

    @PostMapping("/find")
    public ProductView find(@RequestParam Long id) {
        return ProductView.fromEntity(service.find(id));
    }

    @GetMapping("/find")
    public ProductView find() {
        return ProductView.fromEntity(service.find());
    }

    @GetMapping("/find-all")
    public List<ProductView> findAll() {
        return service.findAll().stream().map(e -> ProductView.fromEntity((Product) e)).toList();
    }

    @PostMapping("/top")
    public List<ProductView> top(@RequestParam int n) {
        return Arrays.stream(service.top(n)).map(e -> ProductView.fromEntity((Product) e)).toList();
    }

    @PostMapping("/lookup-v3")
    public ProductView lookupV3(@RequestParam String code) {
        return ProductView.fromEntity(service.lookupV3(code));
    }

    @GetMapping("/list-all")
    @Deprecated
    public List<ProductView> listAll() {
        return service.listAll().stream().map(e -> ProductView.fromEntity((Product) e)).toList();
    }
}

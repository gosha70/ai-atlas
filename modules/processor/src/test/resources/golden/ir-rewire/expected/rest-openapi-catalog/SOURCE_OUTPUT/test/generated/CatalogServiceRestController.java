package test.generated;

import java.lang.Boolean;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.String;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import test.CatalogService;
import test.Item;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/catalog-service")
public class CatalogServiceRestController {
    private final CatalogService service;

    public CatalogServiceRestController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/current")
    public ItemDto current() {
        return ItemDto.fromEntity(service.current());
    }

    @PostMapping("/find-by-id")
    public ItemDto findById(@RequestParam Long id) {
        return ItemDto.fromEntity(service.findById(id));
    }

    @PostMapping("/search")
    public List<ItemDto> search(@RequestParam String name, @RequestParam Integer limit,
            @RequestParam Boolean active) {
        return service.search(name, limit, active).stream().map(e -> ItemDto.fromEntity((Item) e)).toList();
    }

    @PostMapping("/label")
    public String label(@RequestParam Long id) {
        return service.label(id);
    }

    @GetMapping("/count")
    public long count() {
        return service.count();
    }

    @GetMapping("/ids")
    public List<Long> ids() {
        return service.ids();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    @PostMapping("/touch")
    public void touch(@RequestParam Long id) {
        service.touch(id);
    }

    @GetMapping("/reset")
    public void reset() {
        service.reset();
    }
}

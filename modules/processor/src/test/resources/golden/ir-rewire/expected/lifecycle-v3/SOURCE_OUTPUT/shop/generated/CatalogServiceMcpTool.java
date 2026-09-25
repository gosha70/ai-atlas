package shop.generated;

import java.lang.Long;
import java.lang.String;
import java.util.Arrays;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import shop.CatalogService;
import shop.Product;
import shop.Status;

@Generated("com.egoge.ai.atlas.processor")
@Service
public class CatalogServiceMcpTool {
    private final CatalogService service;

    public CatalogServiceMcpTool(CatalogService service) {
        this.service = service;
    }

    @Tool(
            name = "find",
            description = "Browse the catalog"
    )
    public ProductView find(@ToolParam(description = "id") Long id) {
        return ProductView.fromEntity(service.find(id));
    }

    @Tool(
            name = "findFeatured",
            description = "The featured product"
    )
    public ProductView findFeatured() {
        return ProductView.fromEntity(service.find());
    }

    @Tool(
            name = "findAll",
            description = "Browse the catalog"
    )
    public List<ProductView> findAll() {
        return service.findAll().stream().map(e -> ProductView.fromEntity((Product) e)).toList();
    }

    @Tool(
            name = "top",
            description = "Browse the catalog"
    )
    public List<ProductView> top(@ToolParam(description = "n") int n) {
        return Arrays.stream(service.top(n)).map(e -> ProductView.fromEntity((Product) e)).toList();
    }

    @Tool(
            name = "lookupV3",
            description = "[Since v3] Look a product up by its v3 code"
    )
    public ProductView lookupV3(@ToolParam(description = "code") String code) {
        return ProductView.fromEntity(service.lookupV3(code));
    }

    @Tool(
            name = "listAll",
            description = "[DEPRECATED since v2, use findAll] Browse the catalog"
    )
    public List<ProductView> listAll() {
        return service.listAll().stream().map(e -> ProductView.fromEntity((Product) e)).toList();
    }

    @Tool(
            name = "aiOnly",
            description = "Browse the catalog"
    )
    public ProductView aiOnly(@ToolParam(description = "status") Status status) {
        return ProductView.fromEntity(service.aiOnly(status));
    }
}

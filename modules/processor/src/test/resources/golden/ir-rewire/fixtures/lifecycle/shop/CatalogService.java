package shop;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import java.util.List;

@AgenticExposed(description = "Browse the catalog", returnType = Product.class)
public class CatalogService {
    public Product find(Long id) { return null; }

    @AgenticExposed(toolName = "findFeatured", description = "The featured product")
    public Product find() { return null; }

    public List<Product> findAll() { return List.of(); }

    public Product[] top(int n) { return new Product[0]; }

    @AgenticExposed(apiUntil = 1)
    public Product legacyLookup(String code) { return null; }

    @AgenticExposed(apiSince = 3, description = "Look a product up by its v3 code")
    public Product lookupV3(String code) { return null; }

    @AgenticExposed(apiDeprecatedSince = 2, apiReplacement = "findAll")
    public List<Product> listAll() { return List.of(); }

    @AgenticExposed(channels = { AgenticExposed.Channel.AI })
    public Product aiOnly(Status status) { return null; }
}

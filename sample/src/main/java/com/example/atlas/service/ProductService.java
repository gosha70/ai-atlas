package com.example.atlas.service;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.example.atlas.entity.Product;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Demo service exposing product operations.
 * Demonstrates method versioning: deprecated method with replacement,
 * new method in v2, and future method not yet generated.
 */
@Service
@AgenticExposed(
        toolName = "productService",
        description = "Product catalog operations",
        returnType = Product.class
)
public class ProductService {

    @AgenticExposed(description = "Find product by ID (legacy)",
            returnType = Product.class,
            apiDeprecatedSince = 1, apiReplacement = "findByIdV2")
    public Product findById(Long id) {
        return buildStubProduct(id);
    }

    @AgenticExposed(description = "Find product by ID",
            returnType = Product.class, apiSince = 2)
    public Product findByIdV2(Long id) {
        return buildStubProduct(id);
    }

    public List<Product> listAll() {
        return List.of(buildStubProduct(1L), buildStubProduct(2L));
    }

    @AgenticExposed(description = "Find products by tag (planned for v3)",
            returnType = Product.class, apiSince = 3)
    public List<Product> findByTag(String tag) {
        return List.of();
    }

    private Product buildStubProduct(Long id) {
        Product product = new Product();
        product.setId(id);
        product.setName("Widget " + id);
        product.setCategory("electronics");
        product.setPriceCents(1999L);
        product.setPriceMajor(19);
        product.setPriceMinor(99);
        product.setLegacySku("SKU-" + id);
        product.setSupplierContact("supplier@example.com");
        product.setInternalCostBreakdown("cost breakdown data");
        return product;
    }
}

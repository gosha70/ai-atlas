package com.example.atlas.entity;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticField;

/**
 * Demo entity representing a catalog product.
 * Demonstrates field versioning: deprecated fields, new fields,
 * removed fields, and PII exclusion via the whitelist model.
 */
@AgenticEntity(name = "product", description = "A catalog product")
public class Product {

    @AgenticField(description = "Unique product identifier")
    private Long id;

    @AgenticField(description = "Product display name")
    private String name;

    @AgenticField(description = "Product category")
    private String category;

    @AgenticField(description = "Price in cents",
            deprecatedSinceVersion = 2,
            deprecatedMessage = "Use priceMajor and priceMinor instead")
    private long priceCents;

    @AgenticField(description = "Price — whole currency units", sinceVersion = 2)
    private int priceMajor;

    @AgenticField(description = "Price — fractional currency units", sinceVersion = 2)
    private int priceMinor;

    @AgenticField(description = "Legacy SKU code (removed in v2)",
            sinceVersion = 1, removedInVersion = 2)
    private String legacySku;

    // PII — intentionally NOT annotated
    private String supplierContact;
    private String internalCostBreakdown;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public long getPriceCents() { return priceCents; }
    public void setPriceCents(long priceCents) { this.priceCents = priceCents; }

    public int getPriceMajor() { return priceMajor; }
    public void setPriceMajor(int priceMajor) { this.priceMajor = priceMajor; }

    public int getPriceMinor() { return priceMinor; }
    public void setPriceMinor(int priceMinor) { this.priceMinor = priceMinor; }

    public String getLegacySku() { return legacySku; }
    public void setLegacySku(String legacySku) { this.legacySku = legacySku; }

    public String getSupplierContact() { return supplierContact; }
    public void setSupplierContact(String supplierContact) { this.supplierContact = supplierContact; }

    public String getInternalCostBreakdown() { return internalCostBreakdown; }
    public void setInternalCostBreakdown(String s) { this.internalCostBreakdown = s; }
}

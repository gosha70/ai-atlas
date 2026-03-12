/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.demo.entity;

import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.annotations.AgenticEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo entity representing a customer order.
 * Only fields annotated with {@code @AgenticField} will appear
 * in the generated {@code OrderDto} record.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Enum field auto-detection for validValues</li>
 *   <li>Custom field name via {@code @AgenticField(name = ...)}</li>
 *   <li>Class-level metadata for enriched JSON output</li>
 *   <li>Bidirectional relationship with {@link OrderAction} (circular reference handling)</li>
 * </ul>
 */
@AgenticEntity(
        name = "order",
        description = "A customer order with status tracking and item summary"
)
public class Order {

    /**
     * Order status enum — values are auto-detected by the annotation processor
     * and included in generated DTO metadata and OpenAPI schemas.
     */
    public enum OrderStatus {
        PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
    }

    @AgenticField(description = "Unique order identifier")
    private Long id;

    @AgenticField(description = "Current order status")
    private OrderStatus status;

    @AgenticField(name = "totalCents", description = "Total order amount in cents",
            deprecatedSinceVersion = 2,
            deprecatedMessage = "Use totalMajorUnits and totalMinorUnits instead")
    private long totalAmountCents;

    @AgenticField(description = "Total amount — whole currency units (e.g. dollars)",
            sinceVersion = 2)
    private int totalMajorUnits;

    @AgenticField(description = "Total amount — fractional currency units (e.g. cents)",
            sinceVersion = 2)
    private int totalMinorUnits;

    @AgenticField(description = "Number of items in the order")
    private int itemCount;

    @AgenticField(description = "Actions performed on this order")
    private List<OrderAction> actions = new ArrayList<>();

    @AgenticField(description = "Legacy free-text notes (removed in v2)",
            sinceVersion = 1, removedInVersion = 2)
    private String legacyNotes;

    // PII fields — intentionally NOT annotated
    private String customerName;
    private String customerEmail;
    private String shippingAddress;
    private String creditCardNumber;
    private String customerSsn;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public long getTotalAmountCents() {
        return totalAmountCents;
    }

    public void setTotalAmountCents(long totalAmountCents) {
        this.totalAmountCents = totalAmountCents;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }

    public List<OrderAction> getActions() {
        return actions;
    }

    public void setActions(List<OrderAction> actions) {
        this.actions = actions;
    }

    public int getTotalMajorUnits() {
        return totalMajorUnits;
    }

    public void setTotalMajorUnits(int totalMajorUnits) {
        this.totalMajorUnits = totalMajorUnits;
    }

    public int getTotalMinorUnits() {
        return totalMinorUnits;
    }

    public void setTotalMinorUnits(int totalMinorUnits) {
        this.totalMinorUnits = totalMinorUnits;
    }

    public String getLegacyNotes() {
        return legacyNotes;
    }

    public void setLegacyNotes(String legacyNotes) {
        this.legacyNotes = legacyNotes;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public void setShippingAddress(String shippingAddress) {
        this.shippingAddress = shippingAddress;
    }

    public String getCreditCardNumber() {
        return creditCardNumber;
    }

    public void setCreditCardNumber(String creditCardNumber) {
        this.creditCardNumber = creditCardNumber;
    }

    public String getCustomerSsn() {
        return customerSsn;
    }

    public void setCustomerSsn(String customerSsn) {
        this.customerSsn = customerSsn;
    }
}

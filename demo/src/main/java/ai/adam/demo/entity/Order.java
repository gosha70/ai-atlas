/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.demo.entity;

import ai.adam.annotations.AgentVisible;
import ai.adam.annotations.AgentVisibleClass;

/**
 * Demo entity representing a customer order.
 * Only fields annotated with {@code @AgentVisible} will appear
 * in the generated {@code OrderDto} record.
 */
@AgentVisibleClass
public class Order {

    @AgentVisible(description = "Unique order identifier")
    private Long id;

    @AgentVisible(description = "Current order status")
    private String status;

    @AgentVisible(description = "Total order amount in cents")
    private long totalAmountCents;

    @AgentVisible(description = "Number of items in the order")
    private int itemCount;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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

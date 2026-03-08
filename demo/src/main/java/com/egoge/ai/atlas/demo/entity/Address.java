/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.demo.entity;

import com.egoge.ai.atlas.annotations.AgentVisible;
import com.egoge.ai.atlas.annotations.AgentVisibleClass;

/**
 * Demo entity representing a customer address.
 * All fields are safe for AI agent consumption.
 */
@AgentVisibleClass(
        name = "address",
        description = "A customer mailing address"
)
public class Address {

    @AgentVisible(description = "Unique address identifier")
    private Long id;

    @AgentVisible(description = "Street line")
    private String street;

    @AgentVisible(description = "City name")
    private String city;

    @AgentVisible(description = "State or province code")
    private String state;

    @AgentVisible(description = "Postal / ZIP code")
    private String zipCode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStreet() { return street; }
    public void setStreet(String street) { this.street = street; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getZipCode() { return zipCode; }
    public void setZipCode(String zipCode) { this.zipCode = zipCode; }
}

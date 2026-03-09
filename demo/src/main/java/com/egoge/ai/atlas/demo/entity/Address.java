/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.demo.entity;

import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.annotations.AgenticEntity;

/**
 * Demo entity representing a customer address.
 * All fields are safe for AI agent consumption.
 */
@AgenticEntity(
        name = "address",
        description = "A customer mailing address"
)
public class Address {

    @AgenticField(description = "Unique address identifier")
    private Long id;

    @AgenticField(description = "Street line")
    private String street;

    @AgenticField(description = "City name")
    private String city;

    @AgenticField(description = "State or province code")
    private String state;

    @AgenticField(description = "Postal / ZIP code")
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

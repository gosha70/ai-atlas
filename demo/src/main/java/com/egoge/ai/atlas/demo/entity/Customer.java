/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.demo.entity;

import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.annotations.AgenticEntity;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Demo entity representing a customer.
 * Only fields annotated with {@code @AgenticField} will appear
 * in the generated {@code CustomerDto} record.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Entity cross-reference: Customer → Collection&lt;Address&gt;</li>
 *   <li>{@code Collection} (not {@code List}) return type to exercise
 *       hierarchy-aware collection detection via {@code isAssignable}</li>
 *   <li>PII exclusion: email, creditCardNumber, ssn are not exposed</li>
 * </ul>
 */
@AgenticEntity(
        name = "customer",
        description = "A customer with addresses and contact info"
)
public class Customer {

    @AgenticField(description = "Unique customer identifier")
    private Long id;

    @AgenticField(description = "Customer display name")
    private String name;

    @AgenticField(description = "Customer mailing addresses", type = Address.class)
    @SuppressWarnings("rawtypes")
    private Collection addresses = new ArrayList<>();

    // PII fields — intentionally NOT annotated
    private String email;
    private String creditCardNumber;
    private String ssn;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @SuppressWarnings("rawtypes")
    public Collection getAddresses() { return addresses; }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setAddresses(Collection addresses) { this.addresses = addresses; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getCreditCardNumber() { return creditCardNumber; }
    public void setCreditCardNumber(String creditCardNumber) { this.creditCardNumber = creditCardNumber; }

    public String getSsn() { return ssn; }
    public void setSsn(String ssn) { this.ssn = ssn; }
}

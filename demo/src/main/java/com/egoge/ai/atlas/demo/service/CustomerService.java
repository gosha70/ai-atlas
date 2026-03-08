/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.demo.service;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.demo.entity.Address;
import com.egoge.ai.atlas.demo.entity.Customer;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Demo service demonstrating method-level {@code @AgenticExposed}.
 * Only individually annotated methods are exposed to AI agents;
 * unannotated methods remain internal.
 */
@Service
public class CustomerService {

    /**
     * Returns all customers — exposed to AI agents.
     */
    @AgenticExposed(
            description = "Retrieve all customers with their addresses",
            returnType = Customer.class,
            channels = { AgenticExposed.Channel.API }
    )
    public List<?> getCustomers() {
        return List.of(stubCustomer(1L, "Alice"), stubCustomer(2L, "Bob"));
    }

    /**
     * Returns customers with credit info — NOT exposed to AI agents.
     * This method is intentionally unannotated to keep sensitive
     * credit data out of MCP tools and REST endpoints.
     */
    @SuppressWarnings("unchecked")
    public List<Customer> getCustomersWithCreditInfo() {
        List<Customer> customers = (List<Customer>) getCustomers();
        for (Customer c : customers) {
            c.setCreditCardNumber("4111-1111-1111-1111");
            c.setSsn("123-45-6789");
        }
        return customers;
    }

    private static Customer stubCustomer(Long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        customer.setEmail(name.toLowerCase() + "@example.com");

        Address home = new Address();
        home.setId(id * 10);
        home.setStreet("123 Main St");
        home.setCity("Springfield");
        home.setState("IL");
        home.setZipCode("62701");

        Address work = new Address();
        work.setId(id * 10 + 1);
        work.setStreet("456 Corporate Blvd");
        work.setCity("Chicago");
        work.setState("IL");
        work.setZipCode("60601");

        customer.setAddresses(List.of(home, work));
        return customer;
    }
}

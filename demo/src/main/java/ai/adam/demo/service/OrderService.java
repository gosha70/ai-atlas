/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.demo.service;

import ai.adam.annotations.AgenticExposed;
import ai.adam.demo.entity.Order;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Demo service exposing order operations to AI agents.
 */
@Service
@AgenticExposed(
        toolName = "orderService",
        description = "Order management operations",
        returnType = Order.class
)
public class OrderService {

    public Order findById(Long id) {
        // Stub — will be backed by real data in Cycle 2
        Order order = new Order();
        order.setId(id);
        order.setStatus("PENDING");
        order.setTotalAmountCents(9999L);
        order.setItemCount(3);
        return order;
    }

    public List<Order> findByStatus(String status) {
        // Stub
        return List.of();
    }
}

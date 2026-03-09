/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.demo.entity;

import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.annotations.AgenticEntity;

/**
 * Demo entity representing an action performed on an order.
 * Has a back-reference to {@link Order}, forming a bidirectional
 * relationship that would cause {@code StackOverflowError} with
 * naive JSON serialization. AI-ATLAS handles this via
 * {@code SerializationContext} identity-based cycle detection.
 */
@AgenticEntity(
        name = "orderAction",
        description = "An action or status change performed on an order"
)
public class OrderAction {

    public enum ActionType {
        CREATED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED, NOTE
    }

    @AgenticField(description = "Unique action identifier")
    private Long id;

    @AgenticField(description = "Type of action performed")
    private ActionType actionType;

    @AgenticField(description = "Human-readable action description")
    private String description;

    @AgenticField(description = "When the action occurred (ISO-8601)")
    private String timestamp;

    @AgenticField(description = "Parent order this action belongs to")
    private Order order;

    // PII — intentionally NOT annotated
    private String performedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }

    public String getPerformedBy() { return performedBy; }
    public void setPerformedBy(String performedBy) { this.performedBy = performedBy; }
}

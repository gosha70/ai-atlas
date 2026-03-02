/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.demo.entity;

import ai.atlas.annotations.AgentVisible;
import ai.atlas.annotations.AgentVisibleClass;

/**
 * Demo entity representing an action performed on an order.
 * Has a back-reference to {@link Order}, forming a bidirectional
 * relationship that would cause {@code StackOverflowError} with
 * naive JSON serialization. AI-ATLAS handles this via
 * {@code SerializationContext} identity-based cycle detection.
 */
@AgentVisibleClass(
        name = "orderAction",
        description = "An action or status change performed on an order"
)
public class OrderAction {

    public enum ActionType {
        CREATED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED, NOTE
    }

    @AgentVisible(description = "Unique action identifier")
    private Long id;

    @AgentVisible(description = "Type of action performed")
    private ActionType actionType;

    @AgentVisible(description = "Human-readable action description")
    private String description;

    @AgentVisible(description = "When the action occurred (ISO-8601)")
    private String timestamp;

    @AgentVisible(description = "Parent order this action belongs to")
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

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.runtime.json;

import ai.adam.annotations.AgentVisible;
import ai.adam.annotations.AgentVisibleClass;

/**
 * Test entity for serializer tests.
 */
@AgentVisibleClass(
        name = "widget",
        description = "A test widget"
)
public class TestEntity {

    public enum Status {
        ACTIVE, INACTIVE
    }

    @AgentVisible(description = "Widget ID")
    private Long id;

    @AgentVisible(description = "Widget name")
    private String name;

    @AgentVisible(description = "Widget status")
    private Status status;

    @AgentVisible(name = "alias", description = "Display name", checkCircularReference = false)
    private String displayName;

    @AgentVisible(description = "Sensitive field", sensitive = true)
    private String secret;

    // NOT annotated — should be excluded
    private String hidden;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public String getHidden() { return hidden; }
    public void setHidden(String hidden) { this.hidden = hidden; }
}

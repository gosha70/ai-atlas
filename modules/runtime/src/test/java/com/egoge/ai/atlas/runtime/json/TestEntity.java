/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.json;

import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.annotations.AgenticEntity;

/**
 * Test entity for serializer tests.
 */
@AgenticEntity(
        name = "widget",
        description = "A test widget"
)
public class TestEntity {

    public enum Status {
        ACTIVE, INACTIVE
    }

    @AgenticField(description = "Widget ID")
    private Long id;

    @AgenticField(description = "Widget name")
    private String name;

    @AgenticField(description = "Widget status")
    private Status status;

    @AgenticField(name = "alias", description = "Display name", checkCircularReference = false)
    private String displayName;

    @AgenticField(description = "Sensitive field", sensitive = true)
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

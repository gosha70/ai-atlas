/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.runtime.json;

import ai.adam.annotations.AgentVisible;
import ai.adam.annotations.AgentVisibleClass;

/**
 * Test entity with back-reference for circular reference tests.
 */
@AgentVisibleClass(name = "child", description = "A child entity")
public class TestChild {

    @AgentVisible(description = "Child ID")
    private Long id;

    @AgentVisible(description = "Back-reference to parent")
    private TestParent parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TestParent getParent() { return parent; }
    public void setParent(TestParent parent) { this.parent = parent; }
}

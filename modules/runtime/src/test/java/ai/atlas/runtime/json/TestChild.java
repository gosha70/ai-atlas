/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.runtime.json;

import ai.atlas.annotations.AgentVisible;
import ai.atlas.annotations.AgentVisibleClass;

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

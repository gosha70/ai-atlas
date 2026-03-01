/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.runtime.json;

import ai.atlas.annotations.AgentVisible;
import ai.atlas.annotations.AgentVisibleClass;

/**
 * Test entity with circular reference for serializer tests.
 */
@AgentVisibleClass(name = "parent", description = "A parent entity")
public class TestParent {

    @AgentVisible(description = "Parent ID")
    private Long id;

    @AgentVisible(description = "Child reference")
    private TestChild child;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TestChild getChild() { return child; }
    public void setChild(TestChild child) { this.child = child; }
}

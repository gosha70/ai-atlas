/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.json;

import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.annotations.AgenticEntity;

import java.util.List;

/**
 * Test entity with circular reference for serializer tests.
 */
@AgenticEntity(name = "parent", description = "A parent entity")
public class TestParent {

    @AgenticField(description = "Parent ID")
    private Long id;

    @AgenticField(description = "Child reference")
    private TestChild child;

    @AgenticField(description = "Children list")
    private List<TestChild> children;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TestChild getChild() { return child; }
    public void setChild(TestChild child) { this.child = child; }

    public List<TestChild> getChildren() { return children; }
    public void setChildren(List<TestChild> children) { this.children = children; }
}

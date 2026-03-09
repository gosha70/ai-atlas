/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.json;

import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.annotations.AgenticEntity;

/**
 * Test entity with back-reference for circular reference tests.
 */
@AgenticEntity(name = "child", description = "A child entity")
public class TestChild {

    @AgenticField(description = "Child ID")
    private Long id;

    @AgenticField(description = "Back-reference to parent")
    private TestParent parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TestParent getParent() { return parent; }
    public void setParent(TestParent parent) { this.parent = parent; }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.json;

import com.egoge.ai.atlas.annotations.AgenticField;

/**
 * An unannotated subtype of {@link TestEntity}: only the entity's {@code @AgenticField} getters may
 * be serialized, never the subtype's own (issue #50).
 */
public class TestVip extends TestEntity {

    // NOT an entity, so its own fields are excluded even when annotated
    @AgenticField(description = "Loyalty tier")
    private String tier;

    private String ssn;

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public String getSsn() { return ssn; }
    public void setSsn(String ssn) { this.ssn = ssn; }

    /** Overrides an entity getter: the entity's field is still serialized, through this override. */
    @Override
    public String getName() { return "VIP " + super.getName(); }
}

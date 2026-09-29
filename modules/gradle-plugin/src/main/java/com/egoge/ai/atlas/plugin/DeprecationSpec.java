/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.provider.Property;

/**
 * {@code agentic { release { deprecation { … } } }}: what a release may remove from the contract
 * the previous release published. A removal is a field or operation no longer published, a
 * channel it is reachable on that it loses, or a module's whole contract disappearing.
 */
public abstract class DeprecationSpec {

    /**
     * The releases a field or operation must have been published in while deprecated before a
     * release may remove it. Defaults to 1.
     */
    public abstract Property<Integer> getMinReleases();

    /**
     * The majors between a field or operation's deprecation major and the {@code apiMajor} of the
     * release that removes it. Defaults to 1, so an element cannot be removed in the major it was
     * deprecated in.
     */
    public abstract Property<Integer> getMinMajors();

    /**
     * Whether any other breaking difference fails a release with the same {@code apiMajor} as the
     * previous release. Defaults to true. Across a major, breaking differences are only listed in
     * the changelog.
     */
    public abstract Property<Boolean> getFailOnBreaking();
}

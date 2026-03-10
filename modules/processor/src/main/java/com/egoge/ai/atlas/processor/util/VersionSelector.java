/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;

/**
 * Shared version filtering logic used by all generators.
 * Determines whether a method is active or deprecated for a given major version.
 */
public final class VersionSelector {

    private VersionSelector() {
    }

    /**
     * Returns true if the method is active (should be generated) for the given major version.
     * A method is active when {@code apiSince <= configuredMajor <= apiUntil}.
     */
    public static boolean isActive(MethodModel method, int configuredMajor) {
        return method.apiSince() <= configuredMajor && configuredMajor <= method.apiUntil();
    }

    /**
     * Returns true if the method is deprecated for the given major version.
     * A method is deprecated when {@code apiDeprecatedSince > 0 && apiDeprecatedSince <= configuredMajor}.
     * Only meaningful when {@link #isActive} also returns true.
     */
    public static boolean isDeprecated(MethodModel method, int configuredMajor) {
        return method.apiDeprecatedSince() > 0 && method.apiDeprecatedSince() <= configuredMajor;
    }
}

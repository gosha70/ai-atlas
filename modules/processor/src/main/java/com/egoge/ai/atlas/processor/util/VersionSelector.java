/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;

/**
 * Shared version filtering logic used by all generators.
 * Determines whether a method or field is active or deprecated for a given major version.
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

    /**
     * Returns true if the field is active (should be included in DTO) for the given major version.
     * A field is active when {@code sinceVersion <= configuredMajor && configuredMajor < removedInVersion}.
     * Note: removedInVersion uses exclusive (half-open) semantics — the field is NOT present
     * in the removedInVersion.
     */
    public static boolean isFieldActive(FieldModel field, int configuredMajor) {
        return field.sinceVersion() <= configuredMajor && configuredMajor < field.removedInVersion();
    }

    /**
     * Returns true if the field is deprecated for the given major version.
     * A field is deprecated when {@code deprecatedSinceVersion > 0 && deprecatedSinceVersion <= configuredMajor}.
     */
    public static boolean isFieldDeprecated(FieldModel field, int configuredMajor) {
        return field.deprecatedSinceVersion() > 0 && field.deprecatedSinceVersion() <= configuredMajor;
    }
}

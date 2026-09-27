/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.tools.Diagnostic;

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

    /**
     * Validates a method's resolved lifecycle attributes, reporting the first violation as an
     * ERROR on the method.
     *
     * @return whether the attributes are valid
     */
    public static boolean validateLifecycle(String methodName, int apiSince, int apiUntil, int apiDeprecatedSince,
                                            ExecutableElement method, Messager messager) {
        if (apiSince < 1) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] apiSince must be >= 1 on method '" + methodName + "'. Got: " + apiSince, method);
            return false;
        }
        if (apiDeprecatedSince < 0) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] apiDeprecatedSince must be >= 0 on '" + methodName + "'. Got: " + apiDeprecatedSince, method);
            return false;
        }
        if (apiSince > apiUntil) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] apiSince (" + apiSince + ") must be <= apiUntil (" + apiUntil
                            + ") on method '" + methodName + "'", method);
            return false;
        }
        if (apiDeprecatedSince > 0 && apiDeprecatedSince < apiSince) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] apiDeprecatedSince (" + apiDeprecatedSince + ") must be >= apiSince ("
                            + apiSince + ") on '" + methodName + "'", method);
            return false;
        }
        if (apiDeprecatedSince > apiUntil) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] apiDeprecatedSince (" + apiDeprecatedSince + ") must be <= apiUntil ("
                            + apiUntil + ") on method '" + methodName + "'", method);
            return false;
        }
        return true;
    }
}

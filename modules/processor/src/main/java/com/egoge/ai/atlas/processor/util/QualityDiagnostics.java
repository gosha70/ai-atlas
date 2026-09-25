/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * ai-atlas quality diagnostics: warnings by default, errors under the strict-mode option
 * (FR-012..FR-014).
 */
public final class QualityDiagnostics {

    private QualityDiagnostics() {
    }

    /**
     * Returns the kind quality diagnostics are reported as — ERROR when {@code value} is
     * {@code true}, WARNING when it is absent or {@code false} (case-insensitive) — or null after
     * reporting an ERROR naming {@code option} and {@code value} when it is anything else.
     */
    public static Diagnostic.Kind resolveKind(String option, String value, Messager messager) {
        if (value == null || "false".equalsIgnoreCase(value)) {
            return Diagnostic.Kind.WARNING;
        }
        if ("true".equalsIgnoreCase(value)) {
            return Diagnostic.Kind.ERROR;
        }
        messager.printMessage(Diagnostic.Kind.ERROR,
                "[ai-atlas] " + option + " must be 'true' or 'false'. Got: " + value);
        return null;
    }

    /** Reports an active AI-channel method whose own {@code @AgenticExposed} has no description. */
    public static void reportMissingDescription(Diagnostic.Kind kind, Messager messager,
                                                TypeElement serviceType, ExecutableElement method,
                                                MethodModel model, AgenticExposed typeAnnotation,
                                                int apiMajor) {
        if (!model.channels().contains("AI") || !VersionSelector.isActive(model, apiMajor)) {
            return;
        }
        String fallback = AttributeResolver.describeDescriptionFallback(
                method.getAnnotation(AgenticExposed.class), typeAnnotation, model.methodName());
        if (fallback == null) {
            return;
        }
        messager.printMessage(kind,
                "[ai-atlas] " + serviceType.getQualifiedName() + "#" + model.methodName()
                        + " (MCP tool '" + model.toolName() + "') has no description of its own;"
                        + " the tool is described by " + fallback
                        + ". Set description on the method's @AgenticExposed so a model can choose the tool",
                method);
    }
}

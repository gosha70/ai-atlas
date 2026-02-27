/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor.model;

import com.palantir.javapoet.ClassName;

import java.util.List;

/**
 * Internal model representing a service class or method annotated with
 * {@code @AgenticExposed}. Skeleton for Cycle 2 generators.
 */
public record ServiceModel(
        ClassName serviceClassName,
        List<MethodModel> methods
) {

    public record MethodModel(
            String methodName,
            String toolName,
            String description,
            ClassName returnEntityType,
            List<ParameterModel> parameters
    ) {
    }

    public record ParameterModel(
            String name,
            String typeName
    ) {
    }
}

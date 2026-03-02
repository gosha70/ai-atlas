/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.processor.model;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import java.util.List;

/**
 * Internal model representing a service class annotated with
 * {@code @AgenticExposed} (type-level) or containing {@code @AgenticExposed}
 * methods. Used by McpToolGenerator and RestControllerGenerator.
 */
public record ServiceModel(
    ClassName serviceClassName,
    List<MethodModel> methods
) {

  /**
   * Represents a single exposed method on the service.
   */
  public record MethodModel(
      String methodName,
      String toolName,
      String description,
      TypeName returnType,
      ClassName returnEntityType,
      ClassName returnDtoType,
      boolean collectionReturn,
      List<ParameterModel> parameters
  ) {
  }

  /**
   * Represents a parameter of an exposed method.
   */
  public record ParameterModel(
      String name,
      TypeName typeName,
      String description
  ) {
  }
}

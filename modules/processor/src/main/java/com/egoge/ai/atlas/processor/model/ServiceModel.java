/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.model;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import java.util.List;
import java.util.Set;

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
   * Classifies the return type shape for correct stream/mapping code generation.
   */
  public enum ReturnKind {
      /** Not a collection or array — direct mapping */
      NONE,
      /** Assignable to java.util.Collection — has .stream() */
      COLLECTION,
      /** Assignable to java.lang.Iterable but NOT Collection — needs StreamSupport */
      ITERABLE,
      /** Java array type — needs Arrays.stream() */
      ARRAY
  }

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
      ReturnKind returnKind,
      List<ParameterModel> parameters,
      Set<String> channels
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

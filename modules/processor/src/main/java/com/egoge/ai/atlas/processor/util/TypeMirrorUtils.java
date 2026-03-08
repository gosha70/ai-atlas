/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Utilities for working with TypeMirror instances during annotation processing.
 */
public final class TypeMirrorUtils {

  private TypeMirrorUtils() {
  }

  /**
   * Converts a TypeMirror to a JavaPoet TypeName.
   */
  public static TypeName toTypeName(TypeMirror typeMirror) {
    return TypeName.get(typeMirror);
  }

  /**
   * Extracts the ClassName from a DeclaredType's TypeElement.
   */
  public static ClassName toClassName(TypeElement typeElement) {
    return ClassName.get(typeElement);
  }

  /**
   * Extracts the TypeElement from a DeclaredType.
   *
   * @return the TypeElement, or null if the TypeMirror is not a DeclaredType
   */
  public static TypeElement asTypeElement(TypeMirror typeMirror) {
    if (typeMirror instanceof DeclaredType declaredType) {
      var element = declaredType.asElement();
      if (element instanceof TypeElement typeElement) {
        return typeElement;
      }
    }
    return null;
  }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.FieldModel.CollectionKind;
import com.palantir.javapoet.TypeName;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans a TypeElement and its superclass chain for fields annotated
 * with {@code @AgenticField} and converts them to {@link FieldModel} instances.
 * Detects enum field types, collection/iterable/array types, and extracts
 * their element types for cross-entity DTO mapping.
 */
public final class FieldScanner {

  private FieldScanner() {
  }

  /**
   * Scans the given type element and its entire superclass chain
   * for {@code @AgenticField} fields. Fields from supertypes appear
   * before subtype fields. Duplicate field names are skipped (subtype wins).
   * Fields inactive for the given {@code apiMajor} are excluded.
   *
   * @param typeElement   the entity class to scan
   * @param processingEnv the annotation processing environment (for type hierarchy checks)
   * @param apiMajor      the configured API major version used for field filtering
   * @return list of FieldModel for each annotated field active at apiMajor
   */
  public static List<FieldModel> scan(TypeElement typeElement, ProcessingEnvironment processingEnv,
                                      int apiMajor) {
    if (typeElement == null) {
      return Collections.emptyList();
    }

    Types typeUtils = processingEnv.getTypeUtils();
    Elements elementUtils = processingEnv.getElementUtils();

    Set<String> seenFieldNames = new LinkedHashSet<>();
    List<FieldModel> allFields = new ArrayList<>();

    // Walk superclass chain (top-down: collect supertypes first, then reverse)
    List<TypeElement> hierarchy = new ArrayList<>();
    TypeElement current = typeElement;
    do {
      hierarchy.add(current);
      current = getSuperclassElement(current);
    } while (current != null);

    // Process from top of hierarchy down (so superclass fields come first)
    for (int i = hierarchy.size() - 1; i >= 0; i--) {
      for (var enclosed : hierarchy.get(i).getEnclosedElements()) {
        if (enclosed.getKind() != ElementKind.FIELD) {
          continue;
        }
        AgenticField annotation = enclosed.getAnnotation(AgenticField.class);
        if (annotation == null) {
          continue;
        }

        String fieldName = enclosed.getSimpleName().toString();
        if (seenFieldNames.add(fieldName)) {
          var field = (VariableElement) enclosed;

          // Resolve display name: use annotation.name() if non-empty, else field name
          String displayName = annotation.name().isEmpty()
              ? fieldName
              : annotation.name();

          // Resolve allowed values: prefer explicit annotation values, fall back to enum constants
          boolean isEnum = false;
          List<String> allowedValues;
          String[] explicitValues = annotation.allowedValues();
          TypeMirror fieldType = field.asType();
          if (explicitValues.length > 0) {
            allowedValues = List.of(explicitValues);
          } else if (fieldType instanceof DeclaredType declaredType
              && declaredType.asElement().getKind() == ElementKind.ENUM) {
            isEnum = true;
            allowedValues = extractEnumConstants((TypeElement) declaredType.asElement());
          } else {
            allowedValues = Collections.emptyList();
          }

          // Detect collection/iterable/array kind and extract element type
          CollectionKind collectionKind = CollectionKind.NONE;
          TypeName elementTypeName = null;

          if (fieldType.getKind() == TypeKind.ARRAY) {
            ArrayType arrayType = (ArrayType) fieldType;
            collectionKind = CollectionKind.ARRAY;
            elementTypeName = TypeName.get(arrayType.getComponentType());
          } else if (fieldType instanceof DeclaredType) {
            TypeElement collectionEl = elementUtils.getTypeElement("java.util.Collection");
            TypeElement iterableEl = elementUtils.getTypeElement("java.lang.Iterable");
            TypeMirror erasedField = typeUtils.erasure(fieldType);

            if (collectionEl != null
                && typeUtils.isAssignable(erasedField, typeUtils.erasure(collectionEl.asType()))) {
              collectionKind = CollectionKind.COLLECTION;
            } else if (iterableEl != null
                && typeUtils.isAssignable(erasedField, typeUtils.erasure(iterableEl.asType()))) {
              collectionKind = CollectionKind.ITERABLE;
            }

            if (collectionKind != CollectionKind.NONE) {
              TypeMirror elementMirror = resolveElementType(fieldType, typeUtils, elementUtils);
              if (elementMirror != null) {
                elementTypeName = TypeName.get(elementMirror);
              }
            }
          }

          // Read @AgenticField(type = ...) hint via MirroredTypeException
          TypeMirror hintMirror = resolveTypeHintMirror(annotation);
          TypeName hintTypeName = hintMirror != null ? TypeName.get(hintMirror) : null;

          Messager messager = processingEnv.getMessager();

          // Warn if type hint is set on a non-collection/non-iterable/non-array field
          if (hintTypeName != null && collectionKind == CollectionKind.NONE) {
            messager.printMessage(
                Diagnostic.Kind.WARNING,
                "@AgenticField(type = ...) on non-collection field '"
                    + fieldName + "' has no effect — hint is only used for collection element types",
                field
            );
          }

          // Validate assignability: element type must be assignable TO the hint
          // (upcast is safe; downcast would produce ClassCastException in generated code).
          // e.g. List<InternalCustomer> + type=BaseCustomer → OK (InternalCustomer → BaseCustomer)
          //      List<BaseCustomer> + type=InternalCustomer → ERROR (would cast Base → Internal)
          if (hintMirror != null && elementTypeName != null && collectionKind != CollectionKind.NONE) {
            TypeMirror elementMirror = resolveElementTypeForValidation(
                fieldType, collectionKind, typeUtils, elementUtils);
            if (elementMirror != null) {
              TypeMirror erasedHint = typeUtils.erasure(hintMirror);
              TypeMirror erasedElement = typeUtils.erasure(elementMirror);
              if (!typeUtils.isAssignable(erasedElement, erasedHint)) {
                messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@AgenticField(type = " + hintMirror + ") is not assignable from "
                        + "collection element type " + elementMirror + " on field '"
                        + fieldName + "' — generated code would cast "
                        + elementMirror + " to " + hintMirror
                        + ", causing ClassCastException at runtime",
                    field
                );
                hintTypeName = null;
              }
            }
          }

          // Read version attributes
          int sinceVer = annotation.sinceVersion();
          int removedInVer = annotation.removedInVersion();
          int deprecatedSinceVer = annotation.deprecatedSinceVersion();
          String deprecatedMsg = annotation.deprecatedMessage();

          // Validate version ranges (rules 1–7 from spec)
          if (sinceVer < 1) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "[ai-atlas] @AgenticField.sinceVersion must be >= 1 on field '"
                    + fieldName + "'. Got: " + sinceVer, field);
            continue;
          }
          if (removedInVer < 1) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "[ai-atlas] @AgenticField.removedInVersion must be >= 1 on field '"
                    + fieldName + "'. Got: " + removedInVer, field);
            continue;
          }
          if (sinceVer >= removedInVer) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "[ai-atlas] @AgenticField.sinceVersion (" + sinceVer
                    + ") must be < removedInVersion (" + removedInVer
                    + ") on field '" + fieldName + "'", field);
            continue;
          }
          if (deprecatedSinceVer < 0) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "[ai-atlas] @AgenticField.deprecatedSinceVersion must be >= 0 on field '"
                    + fieldName + "'. Got: " + deprecatedSinceVer, field);
            continue;
          }
          if (deprecatedSinceVer > 0 && deprecatedSinceVer < sinceVer) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "[ai-atlas] @AgenticField.deprecatedSinceVersion (" + deprecatedSinceVer
                    + ") must be >= sinceVersion (" + sinceVer
                    + ") on field '" + fieldName
                    + "' — a field cannot be deprecated before it is introduced", field);
            continue;
          }
          if (deprecatedSinceVer > 0 && deprecatedSinceVer >= removedInVer) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "[ai-atlas] @AgenticField.deprecatedSinceVersion (" + deprecatedSinceVer
                    + ") must be < removedInVersion (" + removedInVer
                    + ") on field '" + fieldName + "'", field);
            continue;
          }
          if (deprecatedSinceVer == 0 && !deprecatedMsg.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                "[ai-atlas] @AgenticField.deprecatedMessage on field '"
                    + fieldName + "' has no effect without deprecatedSinceVersion > 0", field);
          }

          FieldModel fieldModel = new FieldModel(
              fieldName,
              displayName,
              TypeName.get(fieldType),
              annotation.description(),
              annotation.sensitive(),
              annotation.checkCircularReference(),
              isEnum,
              allowedValues,
              collectionKind,
              elementTypeName,
              hintTypeName,
              sinceVer,
              removedInVer,
              deprecatedSinceVer,
              deprecatedMsg
          );

          // Filter by version — only include fields active for the configured apiMajor
          if (!VersionSelector.isFieldActive(fieldModel, apiMajor)) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "[ai-atlas] Field '" + fieldName + "' excluded from "
                    + typeElement.getSimpleName() + " DTO — not active for apiMajor="
                    + apiMajor + " (sinceVersion=" + sinceVer
                    + ", removedInVersion=" + removedInVer + ")", field);
            continue;
          }

          allFields.add(fieldModel);
        }
      }
    }

    return allFields;
  }

  /**
   * Reads the {@code type()} attribute from {@code @AgenticField}, handling
   * {@code MirroredTypeException} as required by JSR 269.
   *
   * @return the hint TypeMirror, or null if {@code void.class} (the default)
   */
  private static TypeMirror resolveTypeHintMirror(AgenticField annotation) {
    try {
      Class<?> hintClass = annotation.type();
      if (hintClass == void.class) {
        return null;
      }
      // Reflective path (shouldn't happen during annotation processing, but handle it)
      return null;
    } catch (MirroredTypeException e) {
      TypeMirror typeMirror = e.getTypeMirror();
      if (typeMirror.getKind() == TypeKind.VOID) {
        return null;
      }
      return typeMirror;
    }
  }

  /**
   * Resolves the element type for a collection/iterable field type.
   * Handles three cases:
   * <ol>
   *   <li>Direct type args: {@code List<Item>} → {@code Item}</li>
   *   <li>Wildcard type args: {@code List<? extends Item>} → {@code Item}</li>
   *   <li>Non-generic concrete subtypes: {@code class ItemBag extends ArrayList<Item>}
   *       → walks supertypes to find the parameterized {@code Iterable<Item>} ancestor</li>
   * </ol>
   * <p>Conservative: takes the first declared type argument when present.
   * For multi-type-parameter collections (e.g., {@code Bucket<K, V> implements Iterable<V>}),
   * arg[0] is {@code K}, which will not match a registered entity — the field passes
   * through unchanged. This avoids generating incorrect mapping code.
   *
   * @return the resolved element TypeMirror, or null if unresolvable
   */
  private static TypeMirror resolveElementType(TypeMirror fieldType,
                                                Types typeUtils, Elements elementUtils) {
    // Try direct type arguments first (covers List<Item>, List<? extends Item>, etc.)
    if (fieldType instanceof DeclaredType declaredType && !declaredType.getTypeArguments().isEmpty()) {
      return unwrapWildcard(declaredType.getTypeArguments().getFirst());
    }

    // Non-generic concrete subtype (e.g., class ItemBag extends ArrayList<Item>):
    // walk supertypes to find the first parameterized Iterable/Collection ancestor
    TypeElement iterableEl = elementUtils.getTypeElement("java.lang.Iterable");
    if (iterableEl == null) {
      return null;
    }
    TypeMirror erasedIterable = typeUtils.erasure(iterableEl.asType());

    Set<String> visited = new HashSet<>();
    Deque<TypeMirror> queue = new ArrayDeque<>(typeUtils.directSupertypes(fieldType));
    while (!queue.isEmpty()) {
      TypeMirror supertype = queue.poll();
      if (!(supertype instanceof DeclaredType dt)) {
        continue;
      }
      String qname = ((TypeElement) dt.asElement()).getQualifiedName().toString();
      if (!visited.add(qname)) {
        continue;
      }
      if (!dt.getTypeArguments().isEmpty()
          && typeUtils.isAssignable(typeUtils.erasure(supertype), erasedIterable)) {
        return unwrapWildcard(dt.getTypeArguments().getFirst());
      }
      queue.addAll(typeUtils.directSupertypes(supertype));
    }
    return null;
  }

  /**
   * Resolves element/component type for assignability validation.
   * Uses array component type for arrays and generic element resolution for
   * collections/iterables.
   */
  private static TypeMirror resolveElementTypeForValidation(TypeMirror fieldType,
                                                             CollectionKind collectionKind,
                                                             Types typeUtils,
                                                             Elements elementUtils) {
    if (collectionKind == CollectionKind.ARRAY && fieldType.getKind() == TypeKind.ARRAY) {
      return ((ArrayType) fieldType).getComponentType();
    }
    return resolveElementType(fieldType, typeUtils, elementUtils);
  }

  /**
   * Unwraps a wildcard type to its upper bound.
   * {@code ? extends Item} → {@code Item}.
   * {@code ?} or {@code ? super Item} → {@code null} (not useful for entity mapping).
   */
  private static TypeMirror unwrapWildcard(TypeMirror type) {
    if (type instanceof WildcardType wildcardType) {
      return wildcardType.getExtendsBound();
    }
    return type;
  }

  /**
   * Extracts the names of all enum constants from the given enum type element.
   *
   * @param enumElement the TypeElement representing an enum type
   * @return list of enum constant names in declaration order
   */
  private static List<String> extractEnumConstants(TypeElement enumElement) {
    List<String> constants = new ArrayList<>();
    for (var enclosed : enumElement.getEnclosedElements()) {
      if (enclosed.getKind() == ElementKind.ENUM_CONSTANT) {
        constants.add(enclosed.getSimpleName().toString());
      }
    }
    return constants;
  }

  /**
   * Resolves the superclass TypeElement, or null if the superclass is
   * {@code java.lang.Object} or absent.
   */
  private static TypeElement getSuperclassElement(TypeElement typeElement) {
    TypeMirror superclass = typeElement.getSuperclass();
    if (superclass.getKind() == TypeKind.NONE) {
      return null;
    }
    if (superclass instanceof DeclaredType declaredType) {
      var element = declaredType.asElement();
      if (element instanceof TypeElement superElement) {
        String qualifiedName = superElement.getQualifiedName().toString();
        if ("java.lang.Object".equals(qualifiedName)) {
          return null;
        }
        return superElement;
      }
    }
    return null;
  }
}

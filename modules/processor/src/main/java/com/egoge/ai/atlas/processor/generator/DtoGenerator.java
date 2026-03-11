/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.FieldModel.CollectionKind;
import com.egoge.ai.atlas.processor.util.EntityRefResolver;
import com.egoge.ai.atlas.processor.util.EntityRefResolver.EntityRef;
import com.egoge.ai.atlas.processor.util.VersionSelector;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * Generates Java record DTOs from {@link EntityModel} instances.
 * Each generated record contains only the whitelisted {@code @AgenticField}
 * fields active for the configured {@code apiMajor}, a static {@code fromEntity()}
 * factory method for null-safe mapping, and static metadata constants for enriched
 * JSON serialization.
 *
 * <p>When a field's type (or collection/array element type) is another registered
 * {@code @AgenticEntity} entity, the generated DTO uses the corresponding
 * DTO type and maps through {@code XxxDto.fromEntity()} in the factory method.
 * Bidirectional entity relationships are handled via ThreadLocal-based cycle
 * detection to prevent infinite recursion.
 */
public final class DtoGenerator {

  private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");
  private static final ClassName LIST = ClassName.get(List.class);
  private static final ClassName MAP = ClassName.get(Map.class);
  private static final ClassName STRING = ClassName.get(String.class);

  private DtoGenerator() {
  }

  /**
   * Generates a DTO record and writes it to the filer.
   *
   * @param model          the entity model to generate from
   * @param entityRegistry all registered entity models (for resolving cross-references)
   * @param apiMajor       the configured API major version (for computed deprecation state)
   * @param filer          the annotation processing filer
   * @param messager       the compiler messager for diagnostics
   */
  public static void generate(EntityModel model, Map<String, EntityModel> entityRegistry,
                              int apiMajor, Filer filer, Messager messager) {
    TypeSpec recordSpec = buildRecordSpec(model, entityRegistry, apiMajor);
    JavaFile javaFile = JavaFile.builder(model.dtoPackageName(), recordSpec)
        .indent("    ")
        .build();

    try {
      javaFile.writeTo(filer);
      messager.printMessage(Diagnostic.Kind.NOTE,
          "[ai-atlas] Generated DTO: " + model.dtoClassName().canonicalName());
    } catch (IOException e) {
      messager.printMessage(Diagnostic.Kind.ERROR,
          "[ai-atlas] Failed to write DTO: " + model.dtoClassName().canonicalName()
              + " — " + e.getMessage());
    }
  }

  /**
   * Builds the TypeSpec for the DTO record (visible for testing).
   */
  public static TypeSpec buildRecordSpec(EntityModel model, Map<String, EntityModel> entityRegistry,
                                         int apiMajor) {
    boolean hasEntityRefs = model.fields().stream()
        .anyMatch(f -> EntityRefResolver.resolve(f, entityRegistry) != null);

    // Build record constructor — its parameters define the record components
    MethodSpec.Builder ctorBuilder = MethodSpec.constructorBuilder();
    for (FieldModel field : model.fields()) {
      TypeName componentType = resolvedFieldType(field, entityRegistry);
      ctorBuilder.addParameter(ParameterSpec.builder(componentType, field.name()).build());
    }

    ClassName dtoClassName = ClassName.get(model.dtoPackageName(), model.dtoName());

    TypeSpec.Builder recordBuilder = TypeSpec.recordBuilder(model.dtoName())
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(AnnotationSpec.builder(GENERATED)
            .addMember("value", "$S", "com.egoge.ai.atlas.processor")
            .build())
        .recordConstructor(ctorBuilder.build());

    // Add nested FieldMeta record
    recordBuilder.addType(buildFieldMetaRecord());

    // Add CLASS_NAME constant
    recordBuilder.addField(FieldSpec.builder(STRING, "CLASS_NAME",
            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
        .initializer("$S", model.displayName())
        .build());

    // Add CLASS_DESCRIPTION constant
    recordBuilder.addField(FieldSpec.builder(STRING, "CLASS_DESCRIPTION",
            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
        .initializer("$S", model.classDescription())
        .build());

    // Add INCLUDE_TYPE_INFO constant
    recordBuilder.addField(FieldSpec.builder(boolean.class, "INCLUDE_TYPE_INFO",
            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
        .initializer("$L", model.includeTypeInfo())
        .build());

    // Add FIELD_METADATA map
    ClassName fieldMetaType = dtoClassName.nestedClass("FieldMeta");
    ParameterizedTypeName metadataMapType = ParameterizedTypeName.get(MAP, STRING, fieldMetaType);
    recordBuilder.addField(FieldSpec.builder(metadataMapType, "FIELD_METADATA",
            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
        .initializer(buildFieldMetadataInitializer(model, fieldMetaType, apiMajor))
        .build());

    // Add ThreadLocal for cycle detection (only when entity refs exist)
    if (hasEntityRefs) {
      ParameterizedTypeName setOfObject = ParameterizedTypeName.get(
          ClassName.get(Set.class), ClassName.get(Object.class));
      ParameterizedTypeName threadLocalType = ParameterizedTypeName.get(
          ClassName.get(ThreadLocal.class), setOfObject);
      recordBuilder.addField(FieldSpec.builder(threadLocalType, "_visiting",
              Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
          .initializer("new $T<>()", ThreadLocal.class)
          .build());
    }

    // Add static fromEntity() factory method
    recordBuilder.addMethod(buildFromEntityMethod(model, entityRegistry, hasEntityRefs));

    return recordBuilder.build();
  }

  /**
   * Builds the nested FieldMeta record type.
   * Components: description, validValues, sensitive, checkCircularReference,
   * deprecated (computed current-state), deprecatedMessage.
   */
  private static TypeSpec buildFieldMetaRecord() {
    ParameterizedTypeName listOfString = ParameterizedTypeName.get(LIST, STRING);

    MethodSpec ctor = MethodSpec.constructorBuilder()
        .addParameter(ParameterSpec.builder(STRING, "description").build())
        .addParameter(ParameterSpec.builder(listOfString, "validValues").build())
        .addParameter(ParameterSpec.builder(boolean.class, "sensitive").build())
        .addParameter(ParameterSpec.builder(boolean.class, "checkCircularReference").build())
        .addParameter(ParameterSpec.builder(boolean.class, "deprecated").build())
        .addParameter(ParameterSpec.builder(STRING, "deprecatedMessage").build())
        .build();

    return TypeSpec.recordBuilder("FieldMeta")
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .recordConstructor(ctor)
        .build();
  }

  /**
   * Builds the initializer block for the FIELD_METADATA map.
   * Deprecation state is computed at generation time using {@link VersionSelector#isFieldDeprecated}.
   */
  private static CodeBlock buildFieldMetadataInitializer(EntityModel model, ClassName fieldMetaType,
                                                          int apiMajor) {
    CodeBlock.Builder builder = CodeBlock.builder();
    builder.add("$T.ofEntries(\n", MAP);

    for (int i = 0; i < model.fields().size(); i++) {
      FieldModel field = model.fields().get(i);
      String displayName = field.displayName();

      // Build validValues list expression
      CodeBlock validValuesExpr;
      if (!field.enumValues().isEmpty()) {
        CodeBlock.Builder valuesBuilder = CodeBlock.builder();
        valuesBuilder.add("$T.of(", LIST);
        for (int j = 0; j < field.enumValues().size(); j++) {
          if (j > 0) {
            valuesBuilder.add(", ");
          }
          valuesBuilder.add("$S", field.enumValues().get(j));
        }
        valuesBuilder.add(")");
        validValuesExpr = valuesBuilder.build();
      } else {
        validValuesExpr = CodeBlock.of("$T.of()", LIST);
      }

      // Compute deprecation state for the configured apiMajor (not raw annotation values)
      boolean isDeprecatedNow = VersionSelector.isFieldDeprecated(field, apiMajor);
      String effectiveMessage = isDeprecatedNow ? field.deprecatedMessage() : "";

      if (i > 0) {
        builder.add(",\n");
      }
      builder.add("$T.entry($S, new $T($S, $L, $L, $L, $L, $S))",
          MAP, displayName, fieldMetaType,
          field.description(),
          validValuesExpr,
          field.sensitive(),
          field.checkCircularReference(),
          isDeprecatedNow,
          effectiveMessage);
    }

    builder.add("\n)");
    return builder.build();
  }

  private static MethodSpec buildFromEntityMethod(EntityModel model,
                                                   Map<String, EntityModel> entityRegistry,
                                                   boolean hasEntityRefs) {
    ClassName entityType = model.sourceClassName();
    ClassName dtoType = model.dtoClassName();

    MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("fromEntity")
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .returns(dtoType)
        .addParameter(ParameterSpec.builder(entityType, "entity").build());

    // Null guard
    methodBuilder.addStatement("if (entity == null) return null");

    // Cycle detection preamble (only when entity refs exist)
    if (hasEntityRefs) {
      methodBuilder.addStatement("$T<$T> v = _visiting.get()", Set.class, Object.class);
      methodBuilder.addStatement("boolean root = v == null");
      methodBuilder.beginControlFlow("if (root)");
      methodBuilder.addStatement("v = $T.newSetFromMap(new $T<>())",
          Collections.class, IdentityHashMap.class);
      methodBuilder.addStatement("_visiting.set(v)");
      methodBuilder.endControlFlow();
      methodBuilder.addStatement("if (!v.add(entity)) return null");
      methodBuilder.beginControlFlow("try");
    }

    // Build constructor call with getter expressions
    CodeBlock.Builder constructorArgs = CodeBlock.builder();
    for (int i = 0; i < model.fields().size(); i++) {
      FieldModel field = model.fields().get(i);
      String getter = resolveGetter(field);
      if (i > 0) {
        constructorArgs.add(",\n");
      }

      EntityRef ref = EntityRefResolver.resolve(field, entityRegistry);
      if (ref != null) {
        constructorArgs.add(buildMappingExpression(ref, getter));
      } else {
        constructorArgs.add("entity.$L()", getter);
      }
    }

    if (hasEntityRefs) {
      methodBuilder.addStatement("return new $T(\n$L\n)", dtoType, constructorArgs.build());
      methodBuilder.nextControlFlow("finally");
      methodBuilder.addStatement("v.remove(entity)");
      methodBuilder.beginControlFlow("if (root)");
      methodBuilder.addStatement("_visiting.remove()");
      methodBuilder.endControlFlow();
      methodBuilder.endControlFlow(); // end try-finally
    } else {
      methodBuilder.addStatement("return new $T(\n$L\n)", dtoType, constructorArgs.build());
    }

    return methodBuilder.build();
  }

  /**
   * Builds the mapping expression for an entity reference field in fromEntity().
   */
  private static CodeBlock buildMappingExpression(EntityRef ref, String getter) {
    return switch (ref.collectionKind()) {
      case COLLECTION -> CodeBlock.of(
          "entity.$L() == null ? null : entity.$L().stream().map(e -> $T.fromEntity(($T) e)).toList()",
          getter, getter, ref.dtoClass(), ref.entityClass());
      case ITERABLE -> CodeBlock.of(
          "entity.$L() == null ? null : $T.stream(entity.$L().spliterator(), false)"
              + ".map(e -> $T.fromEntity(($T) e)).toList()",
          getter, StreamSupport.class, getter, ref.dtoClass(), ref.entityClass());
      case ARRAY -> CodeBlock.of(
          "entity.$L() == null ? null : $T.stream(entity.$L()).map(e -> $T.fromEntity(($T) e)).toList()",
          getter, Arrays.class, getter, ref.dtoClass(), ref.entityClass());
      case NONE -> CodeBlock.of("$T.fromEntity(entity.$L())", ref.dtoClass(), getter);
    };
  }

  /**
   * Resolves the getter method name for a field following JavaBean conventions.
   * Boolean fields use {@code isX()}, all others use {@code getX()}.
   */
  private static String resolveGetter(FieldModel field) {
    String name = field.name();
    String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    if (field.typeName().equals(com.palantir.javapoet.TypeName.BOOLEAN)) {
      return "is" + capitalized;
    }
    return "get" + capitalized;
  }

  /**
   * Returns the type to use in the generated DTO record component.
   * Maps entity references to their DTO types; leaves other types unchanged.
   * Collections and arrays of entities are always mapped to {@code List<DtoType>}.
   */
  private static TypeName resolvedFieldType(FieldModel field, Map<String, EntityModel> entityRegistry) {
    EntityRef ref = EntityRefResolver.resolve(field, entityRegistry);
    if (ref == null) {
      return field.typeName();
    }
    if (ref.collectionKind() != CollectionKind.NONE) {
      return ParameterizedTypeName.get(LIST, ref.dtoClass());
    }
    return ref.dtoClass();
  }
}

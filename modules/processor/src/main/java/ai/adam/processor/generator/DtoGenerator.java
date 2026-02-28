/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor.generator;

import ai.adam.processor.model.EntityModel;
import ai.adam.processor.model.FieldModel;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Generates Java record DTOs from {@link EntityModel} instances.
 * Each generated record contains only the whitelisted {@code @AgentVisible}
 * fields, a static {@code fromEntity()} factory method for null-safe mapping,
 * and static metadata constants for enriched JSON serialization.
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
     * @param model    the entity model to generate from
     * @param filer    the annotation processing filer
     * @param messager the compiler messager for diagnostics
     */
    public static void generate(EntityModel model, Filer filer, Messager messager) {
        TypeSpec recordSpec = buildRecordSpec(model);
        JavaFile javaFile = JavaFile.builder(model.dtoPackageName(), recordSpec)
                .indent("    ")
                .build();

        try {
            javaFile.writeTo(filer);
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[ai-adam] Generated DTO: " + model.dtoClassName().canonicalName());
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-adam] Failed to write DTO: " + model.dtoClassName().canonicalName()
                            + " — " + e.getMessage());
        }
    }

    /**
     * Builds the TypeSpec for the DTO record (visible for testing).
     */
    public static TypeSpec buildRecordSpec(EntityModel model) {
        // Build record constructor — its parameters define the record components
        MethodSpec.Builder ctorBuilder = MethodSpec.constructorBuilder();
        for (FieldModel field : model.fields()) {
            ctorBuilder.addParameter(ParameterSpec.builder(field.typeName(), field.name()).build());
        }

        ClassName dtoClassName = ClassName.get(model.dtoPackageName(), model.dtoName());

        TypeSpec.Builder recordBuilder = TypeSpec.recordBuilder(model.dtoName())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", "ai.adam.processor")
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
                .initializer(buildFieldMetadataInitializer(model, fieldMetaType))
                .build());

        // Add static fromEntity() factory method
        recordBuilder.addMethod(buildFromEntityMethod(model));

        return recordBuilder.build();
    }

    /**
     * Builds the nested FieldMeta record type.
     */
    private static TypeSpec buildFieldMetaRecord() {
        ParameterizedTypeName listOfString = ParameterizedTypeName.get(LIST, STRING);

        MethodSpec ctor = MethodSpec.constructorBuilder()
                .addParameter(ParameterSpec.builder(STRING, "description").build())
                .addParameter(ParameterSpec.builder(listOfString, "validValues").build())
                .addParameter(ParameterSpec.builder(boolean.class, "sensitive").build())
                .addParameter(ParameterSpec.builder(boolean.class, "checkCircularReference").build())
                .build();

        return TypeSpec.recordBuilder("FieldMeta")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .recordConstructor(ctor)
                .build();
    }

    /**
     * Builds the initializer block for the FIELD_METADATA map.
     */
    private static CodeBlock buildFieldMetadataInitializer(EntityModel model, ClassName fieldMetaType) {
        CodeBlock.Builder builder = CodeBlock.builder();
        builder.add("$T.ofEntries(\n", MAP);

        for (int i = 0; i < model.fields().size(); i++) {
            FieldModel field = model.fields().get(i);
            String displayName = field.displayName();

            // Build validValues list expression
            CodeBlock validValuesExpr;
            if (field.enumType() && !field.enumValues().isEmpty()) {
                CodeBlock.Builder valuesBuilder = CodeBlock.builder();
                valuesBuilder.add("$T.of(", LIST);
                for (int j = 0; j < field.enumValues().size(); j++) {
                    if (j > 0) valuesBuilder.add(", ");
                    valuesBuilder.add("$S", field.enumValues().get(j));
                }
                valuesBuilder.add(")");
                validValuesExpr = valuesBuilder.build();
            } else {
                validValuesExpr = CodeBlock.of("$T.of()", LIST);
            }

            if (i > 0) builder.add(",\n");
            builder.add("$T.entry($S, new $T($S, $L, $L, $L))",
                    MAP, displayName, fieldMetaType,
                    field.description(),
                    validValuesExpr,
                    field.sensitive(),
                    field.checkCircularReference());
        }

        builder.add("\n)");
        return builder.build();
    }

    private static MethodSpec buildFromEntityMethod(EntityModel model) {
        ClassName entityType = model.sourceClassName();
        ClassName dtoType = model.dtoClassName();

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("fromEntity")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(dtoType)
                .addParameter(ParameterSpec.builder(entityType, "entity").build());

        // Null guard
        methodBuilder.addStatement("if (entity == null) return null");

        // Build constructor call with getter expressions
        CodeBlock.Builder constructorArgs = CodeBlock.builder();
        for (int i = 0; i < model.fields().size(); i++) {
            FieldModel field = model.fields().get(i);
            String getter = resolveGetter(field);
            if (i > 0) {
                constructorArgs.add(",\n");
            }
            constructorArgs.add("entity.$L()", getter);
        }

        methodBuilder.addStatement("return new $T(\n$L\n)", dtoType, constructorArgs.build());

        return methodBuilder.build();
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
}

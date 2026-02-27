/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor.generator;

import ai.adam.processor.model.EntityModel;
import ai.adam.processor.model.FieldModel;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import java.io.IOException;

/**
 * Generates Java record DTOs from {@link EntityModel} instances.
 * Each generated record contains only the whitelisted {@code @AgentVisible}
 * fields and a static {@code fromEntity()} factory method for null-safe mapping.
 */
public final class DtoGenerator {

    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");

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

        TypeSpec.Builder recordBuilder = TypeSpec.recordBuilder(model.dtoName())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", "ai.adam.processor")
                        .build())
                .recordConstructor(ctorBuilder.build());

        // Add static fromEntity() factory method
        recordBuilder.addMethod(buildFromEntityMethod(model));

        return recordBuilder.build();
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

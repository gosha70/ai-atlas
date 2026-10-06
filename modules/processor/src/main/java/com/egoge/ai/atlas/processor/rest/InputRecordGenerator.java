/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.rest;

import com.egoge.ai.atlas.processor.rest.InputRecord.InputField;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import java.io.IOException;

/**
 * Generates an {@link InputRecord}: a Java record of the whitelisted fields a request body may set,
 * and {@code toEntity()}, which creates the entity from them.
 */
final class InputRecordGenerator {

    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");

    private InputRecordGenerator() {
    }

    /**
     * Writes the record to the filer.
     *
     * @param enforceRequired whether the record rejects a missing required component, as with
     *                        {@code ai.atlas.constraints=true}
     */
    static void generate(InputRecord input, boolean enforceRequired, Filer filer, Messager messager) {
        JavaFile javaFile = JavaFile.builder(input.name().packageName(), buildRecordSpec(input, enforceRequired))
                .indent("    ")
                .build();
        try {
            javaFile.writeTo(filer);
            messager.printMessage(Diagnostic.Kind.NOTE, "[ai-atlas] Generated input record: " + input.name());
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] Failed to write input record: " + input.name() + " — " + e.getMessage());
        }
    }

    /**
     * The record. When {@code enforceRequired}, a required component is boxed, so a missing one
     * reads as {@code null} rather than a primitive's default, and the compact constructor rejects
     * it with an {@link IllegalArgumentException}, which Jackson reports and Spring answers 400 Bad
     * Request. Otherwise the record is exactly the entity's whitelisted fields.
     */
    static TypeSpec buildRecordSpec(InputRecord input, boolean enforceRequired) {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder();
        MethodSpec.Builder checks = MethodSpec.compactConstructorBuilder().addModifiers(Modifier.PUBLIC);
        boolean checked = false;
        for (InputField component : input.fields()) {
            TypeName type = component.field().typeName();
            if (enforceRequired && component.required()) {
                type = type.box();
                checks.beginControlFlow("if ($N == null)", component.field().name())
                        .addStatement("throw new $T($S)", IllegalArgumentException.class,
                                "'" + component.field().name() + "' is required")
                        .endControlFlow();
                checked = true;
            }
            constructor.addParameter(ParameterSpec.builder(type, component.field().name()).build());
        }
        ClassName entity = input.entity().sourceClassName();
        MethodSpec.Builder toEntity = MethodSpec.methodBuilder("toEntity")
                .addJavadoc("Creates the entity from the whitelisted fields; no other property is set.\n")
                .addModifiers(Modifier.PUBLIC)
                .returns(entity);
        if (input.viaConstructor()) {
            CodeBlock.Builder arguments = CodeBlock.builder();
            for (int i = 0; i < input.fields().size(); i++) {
                arguments.add(i > 0 ? ", $N" : "$N", input.fields().get(i).field().name());
            }
            toEntity.addStatement("return new $T($L)", entity, arguments.build());
        } else {
            // A local name no component shadows
            String local = "entity";
            while (hasComponent(input, local)) {
                local = "_" + local;
            }
            toEntity.addStatement("$T $N = new $T()", entity, local, entity);
            for (InputField component : input.fields()) {
                toEntity.addStatement("$N.$N($N)", local, component.setter(), component.field().name());
            }
            toEntity.addStatement("return $N", local);
        }
        TypeSpec.Builder record = TypeSpec.recordBuilder(input.name().simpleName())
                .addJavadoc("The fields of {@link $T} a REST request body may set.\n", entity)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", "com.egoge.ai.atlas.processor")
                        .build())
                .recordConstructor(constructor.build());
        if (checked) {
            record.addMethod(checks.addJavadoc("Rejects a missing required field.\n").build());
        }
        return record.addMethod(toEntity.build()).build();
    }

    private static boolean hasComponent(InputRecord input, String name) {
        return input.fields().stream().anyMatch(component -> component.field().name().equals(name));
    }
}

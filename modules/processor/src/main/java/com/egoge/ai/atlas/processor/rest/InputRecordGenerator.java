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

    /** Writes the record to the filer. */
    static void generate(InputRecord input, Filer filer, Messager messager) {
        JavaFile javaFile = JavaFile.builder(input.name().packageName(), buildRecordSpec(input))
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

    static TypeSpec buildRecordSpec(InputRecord input) {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder();
        for (InputField component : input.fields()) {
            constructor.addParameter(ParameterSpec.builder(component.field().typeName(), component.field().name())
                    .build());
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
        return TypeSpec.recordBuilder(input.name().simpleName())
                .addJavadoc("The fields of {@link $T} a REST request body may set.\n", entity)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", "com.egoge.ai.atlas.processor")
                        .build())
                .recordConstructor(constructor.build())
                .addMethod(toEntity.build())
                .build();
    }

    private static boolean hasComponent(InputRecord input, String name) {
        return input.fields().stream().anyMatch(component -> component.field().name().equals(name));
    }
}

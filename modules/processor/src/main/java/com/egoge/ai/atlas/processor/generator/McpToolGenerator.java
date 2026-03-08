/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ParameterModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ReturnKind;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
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

/**
 * Generates Spring {@code @Service} classes with {@code @Tool}-annotated methods
 * that delegate to the original service and map entity results to DTOs.
 *
 * <p>Generated tools are auto-discovered by Spring AI's MCP server autoconfiguration
 * via {@code MethodToolCallbackProvider}.
 */
public final class McpToolGenerator {

    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");
    private static final ClassName SERVICE = ClassName.get("org.springframework.stereotype", "Service");
    private static final ClassName TOOL = ClassName.get("org.springframework.ai.tool.annotation", "Tool");
    private static final ClassName TOOL_PARAM = ClassName.get("org.springframework.ai.tool.annotation", "ToolParam");

    private McpToolGenerator() {
    }

    /**
     * Generates an MCP tool wrapper class and writes it to the filer.
     */
    public static void generate(ServiceModel model, String packageName, Filer filer, Messager messager) {
        String toolClassName = model.serviceClassName().simpleName() + "McpTool";
        TypeSpec toolSpec = buildToolSpec(model, toolClassName);
        if (toolSpec == null) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[ai-atlas] Skipped MCP tool for " + model.serviceClassName().simpleName()
                            + " — no methods with AI channel");
            return;
        }
        JavaFile javaFile = JavaFile.builder(packageName, toolSpec)
                .indent("    ")
                .build();

        try {
            javaFile.writeTo(filer);
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[ai-atlas] Generated MCP tool: " + packageName + "." + toolClassName);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] Failed to write MCP tool: " + packageName + "." + toolClassName
                            + " — " + e.getMessage());
        }
    }

    static TypeSpec buildToolSpec(ServiceModel model, String toolClassName) {
        // Filter to AI-channel methods only
        var aiMethods = model.methods().stream()
                .filter(m -> m.channels().contains("AI"))
                .toList();
        if (aiMethods.isEmpty()) {
            return null;
        }

        ClassName serviceType = model.serviceClassName();

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(toolClassName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", "com.egoge.ai.atlas.processor")
                        .build())
                .addAnnotation(SERVICE);

        // Private final service field
        classBuilder.addField(FieldSpec.builder(serviceType, "service", Modifier.PRIVATE, Modifier.FINAL)
                .build());

        // Constructor injection
        classBuilder.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(serviceType, "service").build())
                .addStatement("this.service = service")
                .build());

        // @Tool methods
        for (MethodModel method : aiMethods) {
            classBuilder.addMethod(buildToolMethod(method));
        }

        return classBuilder.build();
    }

    private static MethodSpec buildToolMethod(MethodModel method) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(method.toolName())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(TOOL)
                        .addMember("name", "$S", method.toolName())
                        .addMember("description", "$S", method.description())
                        .build());

        // Return type
        boolean isCollection = method.returnKind() != ReturnKind.NONE;
        if (method.returnDtoType() != null) {
            if (isCollection) {
                methodBuilder.returns(ParameterizedTypeName.get(
                        ClassName.get("java.util", "List"), method.returnDtoType()));
            } else {
                methodBuilder.returns(method.returnDtoType());
            }
        } else {
            methodBuilder.returns(method.returnType());
        }

        // Parameters with @ToolParam
        for (ParameterModel param : method.parameters()) {
            ParameterSpec.Builder paramBuilder = ParameterSpec.builder(param.typeName(), param.name());
            String desc = param.description().isEmpty() ? param.name() : param.description();
            paramBuilder.addAnnotation(AnnotationSpec.builder(TOOL_PARAM)
                    .addMember("description", "$S", desc)
                    .build());
            methodBuilder.addParameter(paramBuilder.build());
        }

        // Method body: delegate to service, map to DTO if applicable
        String callArgs = buildCallArgs(method);

        if (method.returnDtoType() != null && method.returnEntityType() != null) {
            addMappingStatement(methodBuilder, method, callArgs);
        } else {
            methodBuilder.addStatement("return service.$L($L)", method.methodName(), callArgs);
        }

        return methodBuilder.build();
    }

    private static void addMappingStatement(MethodSpec.Builder methodBuilder,
                                               MethodModel method, String callArgs) {
        switch (method.returnKind()) {
            case COLLECTION -> methodBuilder.addStatement(
                    "return service.$L($L).stream().map(e -> $T.fromEntity(($T) e)).toList()",
                    method.methodName(), callArgs, method.returnDtoType(), method.returnEntityType());
            case ITERABLE -> methodBuilder.addStatement(
                    "return $T.stream(service.$L($L).spliterator(), false)"
                            + ".map(e -> $T.fromEntity(($T) e)).toList()",
                    ClassName.get("java.util.stream", "StreamSupport"),
                    method.methodName(), callArgs, method.returnDtoType(), method.returnEntityType());
            case ARRAY -> methodBuilder.addStatement(
                    "return $T.stream(service.$L($L)).map(e -> $T.fromEntity(($T) e)).toList()",
                    ClassName.get("java.util", "Arrays"),
                    method.methodName(), callArgs, method.returnDtoType(), method.returnEntityType());
            case NONE -> methodBuilder.addStatement("return $T.fromEntity(service.$L($L))",
                    method.returnDtoType(), method.methodName(), callArgs);
        }
    }

    private static String buildCallArgs(MethodModel method) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < method.parameters().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(method.parameters().get(i).name());
        }
        return sb.toString();
    }
}

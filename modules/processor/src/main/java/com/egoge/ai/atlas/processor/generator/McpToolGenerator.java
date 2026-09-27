/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints;
import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints.PatternConstraint;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractProjection;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ParameterModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ReturnKind;
import com.egoge.ai.atlas.processor.util.VersionSelector;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
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
    private static final ClassName VALIDATED = ClassName.get("org.springframework.validation.annotation", "Validated");
    private static final String BV_PACKAGE = "jakarta.validation.constraints";
    private static final ClassName NOT_NULL = ClassName.get(BV_PACKAGE, "NotNull");
    private static final ClassName NOT_BLANK = ClassName.get(BV_PACKAGE, "NotBlank");
    private static final ClassName DECIMAL_MIN = ClassName.get(BV_PACKAGE, "DecimalMin");
    private static final ClassName DECIMAL_MAX = ClassName.get(BV_PACKAGE, "DecimalMax");
    private static final ClassName SIZE = ClassName.get(BV_PACKAGE, "Size");
    private static final ClassName PATTERN = ClassName.get(BV_PACKAGE, "Pattern");
    private static final ClassName PATTERN_FLAG = PATTERN.nestedClass("Flag");
    /** The Bean Validation annotation whose resolution enables enforcement (FR-016). */
    public static final String VALIDATION_API_PROBE = BV_PACKAGE + ".NotNull";

    private McpToolGenerator() {
    }

    /**
     * Generates an MCP tool wrapper class and writes it to the filer.
     *
     * @param constraints the constraint surfaces, or {@code null} when {@code ai.atlas.constraints} is off
     */
    public static void generate(ServiceModel model, String packageName, int apiMajor,
                                ConstraintSurfaces constraints, Filer filer, Messager messager) {
        String toolClassName = model.serviceClassName().simpleName() + "McpTool";
        TypeSpec toolSpec = buildToolSpec(model, toolClassName, apiMajor, constraints);
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

    static TypeSpec buildToolSpec(ServiceModel model, String toolClassName, int apiMajor,
                                  ConstraintSurfaces constraints) {
        // Filter to AI-channel methods that are active for the configured major
        var aiMethods = model.methods().stream()
                .filter(m -> m.channels().contains("AI") && VersionSelector.isActive(m, apiMajor))
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
        boolean enforce = constraints != null && constraints.beanValidation();
        if (enforce) {
            // Spring's method validation rejects a violating call before it reaches the service (ADR-6)
            classBuilder.addAnnotation(VALIDATED);
        }

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
            ContractIr.Operation irOperation = constraints != null
                    ? constraints.operation(ContractProjection.operationKey(serviceType, method)) : null;
            classBuilder.addMethod(buildToolMethod(method, apiMajor, irOperation, enforce));
        }

        return classBuilder.build();
    }

    /**
     * @param irOperation the IR operation whose parameters' contracts the parameters carry, or
     *                    {@code null} when {@code ai.atlas.constraints} is off
     * @param enforce     whether to emit the contracts as Bean Validation annotations
     */
    private static MethodSpec buildToolMethod(MethodModel method, int apiMajor,
                                              ContractIr.Operation irOperation, boolean enforce) {
        // Enrich description with version/deprecation metadata
        String desc = method.description();
        if (VersionSelector.isDeprecated(method, apiMajor)) {
            String replacement = method.apiReplacement().isEmpty()
                    ? "" : ", use " + method.apiReplacement();
            desc = "[DEPRECATED since v" + method.apiDeprecatedSince() + replacement + "] " + desc;
        } else if (method.apiSince() > 1) {
            desc = "[Since v" + method.apiSince() + "] " + desc;
        }

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(method.toolName())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(TOOL)
                        .addMember("name", "$S", method.toolName())
                        .addMember("description", "$S", desc)
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
        for (int i = 0; i < method.parameters().size(); i++) {
            ParameterModel param = method.parameters().get(i);
            ParameterSpec.Builder paramBuilder = ParameterSpec.builder(param.typeName(), param.name());
            String paramDesc = param.description().isEmpty() ? param.name() : param.description();
            AnnotationSpec.Builder toolParam = AnnotationSpec.builder(TOOL_PARAM)
                    .addMember("description", "$S", paramDesc);
            if (irOperation != null) {
                ContractIr.Parameter irParam = ConstraintSurfaces.parameter(irOperation, i, param);
                toolParam.addMember("required", "$L", irParam.required());
                paramBuilder.addAnnotation(toolParam.build());
                if (enforce) {
                    addBeanValidation(paramBuilder, param.typeName(), irParam);
                }
            } else {
                paramBuilder.addAnnotation(toolParam.build());
            }
            methodBuilder.addParameter(paramBuilder.build());
        }

        // Method body: delegate to service, map to DTO if applicable
        String callArgs = buildCallArgs(method);

        if (method.returnType().equals(TypeName.VOID)) {
            methodBuilder.addStatement("service.$L($L)", method.methodName(), callArgs);
        } else if (method.returnDtoType() != null && method.returnEntityType() != null) {
            addMappingStatement(methodBuilder, method, callArgs);
        } else {
            methodBuilder.addStatement("return service.$L($L)", method.methodName(), callArgs);
        }

        return methodBuilder.build();
    }

    /**
     * Carries a parameter's effective contract (FR-003) as Bean Validation annotations: never the
     * source annotations, so the MCP boundary enforces exactly the published contract (FR-016).
     */
    private static void addBeanValidation(ParameterSpec.Builder paramBuilder, TypeName type,
                                          ContractIr.Parameter irParam) {
        EffectiveConstraints c = irParam.constraints();
        if (irParam.required() && !type.isPrimitive()) {
            paramBuilder.addAnnotation(NOT_NULL);
        }
        if (c.minimum() != null) {
            paramBuilder.addAnnotation(AnnotationSpec.builder(DECIMAL_MIN)
                    .addMember("value", "$S", c.minimum())
                    .addMember("inclusive", "$L", !c.exclusiveMinimum())
                    .build());
        }
        if (c.maximum() != null) {
            paramBuilder.addAnnotation(AnnotationSpec.builder(DECIMAL_MAX)
                    .addMember("value", "$S", c.maximum())
                    .addMember("inclusive", "$L", !c.exclusiveMaximum())
                    .build());
        }
        // ConstraintChecks rejects lengths off a CharSequence and item counts off a collection or
        // array, so at most one pair is set and never on a primitive: one @Size carries either.
        Integer min = c.minLength() != null ? c.minLength() : c.minItems();
        Integer max = c.maxLength() != null ? c.maxLength() : c.maxItems();
        if (min != null || max != null) {
            AnnotationSpec.Builder size = AnnotationSpec.builder(SIZE);
            if (min != null) {
                size.addMember("min", "$L", min);
            }
            if (max != null) {
                size.addMember("max", "$L", max);
            }
            paramBuilder.addAnnotation(size.build());
        }
        for (PatternConstraint pattern : c.patterns()) {
            AnnotationSpec.Builder annotation = AnnotationSpec.builder(PATTERN).addMember("regexp", "$S", pattern.regex());
            for (String flag : pattern.flags()) {
                annotation.addMember("flags", "$T.$L", PATTERN_FLAG, flag);
            }
            paramBuilder.addAnnotation(annotation.build());
        }
        if (c.notBlank()) {
            paramBuilder.addAnnotation(NOT_BLANK);
        }
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

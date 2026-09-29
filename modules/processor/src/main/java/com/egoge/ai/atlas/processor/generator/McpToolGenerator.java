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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
     * @param paging      the paging contracts by operation identity, or {@code null} when
     *                    {@code ai.atlas.collections} is off
     */
    public static void generate(ServiceModel model, String packageName, int apiMajor,
                                ConstraintSurfaces constraints, Map<String, PagingContract> paging,
                                Filer filer, Messager messager) {
        String toolClassName = model.serviceClassName().simpleName() + "McpTool";
        TypeSpec toolSpec = buildToolSpec(model, ClassName.get(packageName, toolClassName), apiMajor, constraints,
                paging);
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
        return buildToolSpec(model, ClassName.get("", toolClassName), apiMajor, constraints, null);
    }

    private static TypeSpec buildToolSpec(ServiceModel model, ClassName toolClass, int apiMajor,
                                          ConstraintSurfaces constraints, Map<String, PagingContract> paging) {
        String toolClassName = toolClass.simpleName();
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
        List<PagingContract> contracts = new ArrayList<>();
        for (MethodModel method : aiMethods) {
            String key = ContractProjection.operationKey(serviceType, method);
            ContractIr.Operation irOperation = constraints != null ? constraints.operation(key) : null;
            PagingContract contract = paging != null ? paging.get(key) : null;
            contracts.add(contract);
            classBuilder.addMethod(buildToolMethod(method, apiMajor, irOperation, enforce, contract, toolClass));
        }
        PagingContract.envelopeRecords(contracts).forEach(classBuilder::addType);

        return classBuilder.build();
    }

    /**
     * @param irOperation the IR operation whose parameters' contracts the parameters carry, or
     *                    {@code null} when {@code ai.atlas.constraints} is off
     * @param enforce     whether to emit the contracts as Bean Validation annotations
     * @param paging      the method's paging contract, or {@code null} when it has none or
     *                    {@code ai.atlas.collections} is off
     * @param toolClass   the generated class, which nests the envelope records
     */
    private static MethodSpec buildToolMethod(MethodModel method, int apiMajor, ContractIr.Operation irOperation,
                                              boolean enforce, PagingContract paging, ClassName toolClass) {
        // Enrich description with version/deprecation metadata
        String desc = method.description();
        if (VersionSelector.isDeprecated(method, apiMajor)) {
            String replacement = method.apiReplacement().isEmpty()
                    ? "" : ", use " + method.apiReplacement();
            desc = "[DEPRECATED since v" + method.apiDeprecatedSince() + replacement + "] " + desc;
        } else if (method.apiSince() > 1) {
            desc = "[Since v" + method.apiSince() + "] " + desc;
        }
        String guidance = paging != null
                ? paging.toolGuidance(method.parameters().stream().map(ParameterModel::name).toList()) : "";
        if (!guidance.isEmpty()) {
            desc = desc.isEmpty() ? guidance : desc + (desc.endsWith(".") ? " " : ". ") + guidance;
        }

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(method.toolName())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(TOOL)
                        .addMember("name", "$S", method.toolName())
                        .addMember("description", "$S", desc)
                        .build());
        AnnotationSpec bound = paging != null ? paging.boundAnnotation() : null;
        if (bound != null) {
            methodBuilder.addAnnotation(bound);
        }

        // Return type
        boolean isCollection = method.returnKind() != ReturnKind.NONE;
        if (paging != null && paging.enveloped()) {
            methodBuilder.returns(paging.envelopeType(toolClass, method));
        } else if (method.returnDtoType() != null) {
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
            if (paging != null && paging.replaces(i)) {
                // The service takes a Pageable: the tool takes the page and size it is built from, never a sort
                // Longs, so the tool itself rejects a size or page beyond an int, naming it and its range
                methodBuilder.addParameter(ParameterSpec.builder(Long.class, PagingContract.PAGE_PARAM)
                        .addAnnotation(AnnotationSpec.builder(TOOL_PARAM)
                                .addMember("description", "$S", PagingContract.PAGE_DESCRIPTION)
                                .addMember("required", "$L", false).build())
                        .build());
                methodBuilder.addParameter(ParameterSpec.builder(Long.class, PagingContract.SIZE_PARAM)
                        .addAnnotation(AnnotationSpec.builder(TOOL_PARAM)
                                .addMember("description", "$S", PagingContract.SIZE_DESCRIPTION).build())
                        .build());
                continue;
            }
            ParameterSpec.Builder paramBuilder = ParameterSpec.builder(param.typeName(), param.name());
            String paramDesc = param.description().isEmpty() ? param.name() : param.description();
            AnnotationSpec.Builder toolParam = AnnotationSpec.builder(TOOL_PARAM)
                    .addMember("description", "$S", paramDesc);
            if (irOperation == null && paging != null && paging.optionalCursor(i)) {
                paramBuilder.addAnnotation(toolParam.addMember("required", "$L", false).build());
            } else if (irOperation != null) {
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
        CodeBlock callArgs = buildCallArgs(method, paging);
        if (paging != null && paging.pageable() >= 0) {
            paging.addMcpPagingChecks(methodBuilder, method.toolName());
        }

        if (method.returnType().equals(TypeName.VOID)) {
            methodBuilder.addStatement("service.$L($L)", method.methodName(), callArgs);
        } else if (paging != null && paging.enveloped()) {
            paging.addEnvelopeStatements(methodBuilder, toolClass, method, callArgs);
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
        // Checked again here so a broken invariant fails loudly instead of emitting a bad @Size.
        boolean hasLength = c.minLength() != null || c.maxLength() != null;
        boolean hasItems = c.minItems() != null || c.maxItems() != null;
        if (hasLength && hasItems) {
            throw new IllegalStateException("Parameter '" + irParam.name()
                    + "' carries both length and item constraints");
        }
        Integer min = hasLength ? c.minLength() : c.minItems();
        Integer max = hasLength ? c.maxLength() : c.maxItems();
        if ((min != null || max != null) && type.isPrimitive()) {
            throw new IllegalStateException("Parameter '" + irParam.name()
                    + "' is primitive " + type + " but carries a length or item constraint");
        }
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
                                               MethodModel method, CodeBlock callArgs) {
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

    private static CodeBlock buildCallArgs(MethodModel method, PagingContract paging) {
        CodeBlock.Builder args = CodeBlock.builder();
        for (int i = 0; i < method.parameters().size(); i++) {
            if (i > 0) {
                args.add(", ");
            }
            args.add(paging != null && paging.replaces(i)
                    ? PagingContract.pageRequestArgument() : CodeBlock.of("$L", method.parameters().get(i).name()));
        }
        return args.build();
    }
}

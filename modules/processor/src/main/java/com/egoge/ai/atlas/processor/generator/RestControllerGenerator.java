/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractProjection;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ParameterModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ReturnKind;
import com.egoge.ai.atlas.processor.rest.RestOperation;
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
import java.util.Locale;
import java.util.function.Function;

/**
 * Generates Spring {@code @RestController} classes with request mappings
 * that delegate to the original service and return PII-safe DTOs.
 *
 * <p>Each operation is mapped as its resolved {@link RestOperation} says, the model the OpenAPI
 * document reads too: the class maps the service's resource, each method its HTTP method and path
 * below it, with {@code @ResponseStatus} for a status other than 200. A parameter is bound with
 * {@code @PathVariable}, {@code @RequestParam} or {@code @RequestBody}; an entity body binds its
 * whitelisted input record and passes {@code toEntity()} to the service. On the RPC mapping,
 * methods with no parameters produce {@code @GetMapping} and methods with parameters
 * {@code @PostMapping}, every parameter a {@code @RequestParam}.
 */
public final class RestControllerGenerator {

    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");
    private static final ClassName REST_CONTROLLER = ClassName.get("org.springframework.web.bind.annotation", "RestController");
    private static final ClassName REQUEST_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "RequestMapping");
    private static final ClassName GET_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "GetMapping");
    private static final ClassName POST_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "PostMapping");
    private static final ClassName REQUEST_BODY = ClassName.get("org.springframework.web.bind.annotation", "RequestBody");
    private static final ClassName REQUEST_PARAM = ClassName.get("org.springframework.web.bind.annotation", "RequestParam");
    private static final ClassName PATH_VARIABLE = ClassName.get("org.springframework.web.bind.annotation", "PathVariable");
    private static final ClassName RESPONSE_STATUS = ClassName.get("org.springframework.web.bind.annotation", "ResponseStatus");
    private static final ClassName HTTP_STATUS = ClassName.get("org.springframework.http", "HttpStatus");
    private static final String MAPPING_PACKAGE = "org.springframework.web.bind.annotation";

    private RestControllerGenerator() {
    }

    /**
     * Generates a REST controller class and writes it to the filer.
     *
     * @param constraints the constraint surfaces, or {@code null} when {@code ai.atlas.constraints} is off
     * @param routes      each API operation's resolved mapping by {@link ContractProjection#operationKey}
     */
    public static void generate(ServiceModel model, String packageName,
                                String apiBasePath, int apiMajor, ConstraintSurfaces constraints,
                                Function<String, RestOperation> routes, Filer filer, Messager messager) {
        String controllerName = model.serviceClassName().simpleName() + "RestController";
        TypeSpec controllerSpec = buildControllerSpec(model, controllerName, apiBasePath, apiMajor, constraints,
                routes);
        if (controllerSpec == null) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[ai-atlas] Skipped REST controller for " + model.serviceClassName().simpleName()
                            + " — no methods with API channel");
            return;
        }
        JavaFile javaFile = JavaFile.builder(packageName, controllerSpec)
                .indent("    ")
                .build();

        try {
            javaFile.writeTo(filer);
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[ai-atlas] Generated REST controller: " + packageName + "." + controllerName);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] Failed to write REST controller: " + packageName + "." + controllerName
                            + " — " + e.getMessage());
        }
    }

    static TypeSpec buildControllerSpec(ServiceModel model, String controllerName,
                                        String apiBasePath, int apiMajor, ConstraintSurfaces constraints,
                                        Function<String, RestOperation> routes) {
        // Filter to API-channel methods only
        var apiMethods = model.methods().stream()
                .filter(m -> m.channels().contains("API") && VersionSelector.isActive(m, apiMajor))
                .toList();
        if (apiMethods.isEmpty()) {
            return null;
        }

        ClassName serviceType = model.serviceClassName();

        // Every operation of a service shares its resource
        String basePath = apiBasePath + "/v" + apiMajor
                + "/" + routes.apply(ContractProjection.operationKey(serviceType, apiMethods.get(0))).resource();

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(controllerName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", "com.egoge.ai.atlas.processor")
                        .build())
                .addAnnotation(REST_CONTROLLER)
                .addAnnotation(AnnotationSpec.builder(REQUEST_MAPPING)
                        .addMember("value", "$S", basePath)
                        .build());

        // Private final service field
        classBuilder.addField(FieldSpec.builder(serviceType, "service", Modifier.PRIVATE, Modifier.FINAL)
                .build());

        // Constructor injection
        classBuilder.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(serviceType, "service").build())
                .addStatement("this.service = service")
                .build());

        // Endpoint methods
        for (MethodModel method : apiMethods) {
            ContractIr.Operation irOperation = constraints != null
                    ? constraints.operation(ContractProjection.operationKey(serviceType, method)) : null;
            classBuilder.addMethod(buildEndpointMethod(method, apiMajor, irOperation,
                    routes.apply(ContractProjection.operationKey(serviceType, method))));
        }

        return classBuilder.build();
    }

    /**
     * @param irOperation the IR operation whose parameters' requiredness the bindings follow, or
     *                    {@code null} when {@code ai.atlas.constraints} is off
     */
    private static MethodSpec buildEndpointMethod(MethodModel method, int apiMajor,
                                                  ContractIr.Operation irOperation, RestOperation rest) {
        AnnotationSpec.Builder mapping = AnnotationSpec.builder(mappingAnnotation(rest.httpMethod()));
        if (!rest.path().isEmpty()) {
            mapping.addMember("value", "$S", rest.path());
        }

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(method.methodName())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(mapping.build());
        if (rest.status() != RestOperation.DEFAULT_STATUS) {
            methodBuilder.addAnnotation(AnnotationSpec.builder(RESPONSE_STATUS)
                    .addMember("value", "$T.$L", HTTP_STATUS, rest.statusName())
                    .build());
        }

        if (VersionSelector.isDeprecated(method, apiMajor)) {
            methodBuilder.addAnnotation(Deprecated.class);
        }

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

        // Parameters bound where the mapping locates them; an entity body binds its input record
        for (int i = 0; i < method.parameters().size(); i++) {
            ParameterModel param = method.parameters().get(i);
            String in = rest.in(i);
            boolean optional = irOperation != null && !ConstraintSurfaces.parameter(irOperation, i, param).required();
            if (RestOperation.PATH.equals(in)) {
                methodBuilder.addParameter(ParameterSpec.builder(param.typeName(), param.name())
                        .addAnnotation(AnnotationSpec.builder(PATH_VARIABLE).addMember("value", "$S", param.name())
                                .build())
                        .build());
                continue;
            }
            if (RestOperation.BODY.equals(in)) {
                AnnotationSpec.Builder body = AnnotationSpec.builder(REQUEST_BODY);
                if (optional) {
                    body.addMember("required", "$L", false);
                }
                TypeName bodyType = rest.inputRecord() != null ? rest.inputRecord() : param.typeName();
                methodBuilder.addParameter(ParameterSpec.builder(bodyType, param.name())
                        .addAnnotation(body.build()).build());
                continue;
            }
            ParameterSpec.Builder paramBuilder = ParameterSpec.builder(param.typeName(), param.name());
            if (optional) {
                // An OPTIONAL parameter (FR-015); required ones keep the plain binding
                paramBuilder.addAnnotation(AnnotationSpec.builder(REQUEST_PARAM)
                        .addMember("required", "$L", false)
                        .build());
            } else {
                paramBuilder.addAnnotation(REQUEST_PARAM);
            }
            methodBuilder.addParameter(paramBuilder.build());
        }

        // Method body: delegate to service, map to DTO
        String callArgs = buildCallArgs(method, rest, irOperation);

        if (method.returnType().equals(TypeName.VOID)) {
            methodBuilder.addStatement("service.$L($L)", method.methodName(), callArgs);
        } else if (method.returnDtoType() != null && method.returnEntityType() != null) {
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

    /** The service call's arguments; an input record body passes the entity it creates. */
    private static String buildCallArgs(MethodModel method, RestOperation rest, ContractIr.Operation irOperation) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < method.parameters().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ParameterModel param = method.parameters().get(i);
            String name = param.name();
            if (i == rest.bodyIndex() && rest.inputRecord() != null) {
                boolean optional = irOperation != null
                        && !ConstraintSurfaces.parameter(irOperation, i, param).required();
                sb.append(optional ? name + " != null ? " + name + ".toEntity() : null" : name + ".toEntity()");
            } else {
                sb.append(name);
            }
        }
        return sb.toString();
    }

    private static ClassName mappingAnnotation(String httpMethod) {
        return switch (httpMethod) {
            case RestOperation.GET -> GET_MAPPING;
            case RestOperation.POST -> POST_MAPPING;
            default -> ClassName.get(MAPPING_PACKAGE,
                    httpMethod.charAt(0) + httpMethod.substring(1).toLowerCase(Locale.ROOT) + "Mapping");
        };
    }

    public static String toKebabCase(String camelCase) {
        return camelCase
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .toLowerCase();
    }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor.generator;

import ai.adam.processor.model.ServiceModel;
import ai.adam.processor.model.ServiceModel.MethodModel;
import ai.adam.processor.model.ServiceModel.ParameterModel;
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

/**
 * Generates Spring {@code @RestController} classes with request mappings
 * that delegate to the original service and return PII-safe DTOs.
 *
 * <p>Methods with no parameters produce {@code @GetMapping},
 * methods with parameters produce {@code @PostMapping}.
 */
public final class RestControllerGenerator {

    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");
    private static final ClassName REST_CONTROLLER = ClassName.get("org.springframework.web.bind.annotation", "RestController");
    private static final ClassName REQUEST_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "RequestMapping");
    private static final ClassName GET_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "GetMapping");
    private static final ClassName POST_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "PostMapping");
    private static final ClassName REQUEST_BODY = ClassName.get("org.springframework.web.bind.annotation", "RequestBody");
    private static final ClassName REQUEST_PARAM = ClassName.get("org.springframework.web.bind.annotation", "RequestParam");

    private RestControllerGenerator() {
    }

    /**
     * Generates a REST controller class and writes it to the filer.
     */
    public static void generate(ServiceModel model, String packageName, Filer filer, Messager messager) {
        String controllerName = model.serviceClassName().simpleName() + "RestController";
        TypeSpec controllerSpec = buildControllerSpec(model, controllerName);
        JavaFile javaFile = JavaFile.builder(packageName, controllerSpec)
                .indent("    ")
                .build();

        try {
            javaFile.writeTo(filer);
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[ai-adam] Generated REST controller: " + packageName + "." + controllerName);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-adam] Failed to write REST controller: " + packageName + "." + controllerName
                            + " — " + e.getMessage());
        }
    }

    static TypeSpec buildControllerSpec(ServiceModel model, String controllerName) {
        ClassName serviceType = model.serviceClassName();

        // Derive base path from service name: OrderService → /api/v1/order-service
        String basePath = "/api/v1/" + toKebabCase(model.serviceClassName().simpleName());

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(controllerName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", "ai.adam.processor")
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
        for (MethodModel method : model.methods()) {
            classBuilder.addMethod(buildEndpointMethod(method));
        }

        return classBuilder.build();
    }

    private static MethodSpec buildEndpointMethod(MethodModel method) {
        String path = "/" + toKebabCase(method.methodName());
        boolean hasParams = !method.parameters().isEmpty();

        // GET for no params, POST for params
        AnnotationSpec mappingAnnotation;
        if (hasParams) {
            mappingAnnotation = AnnotationSpec.builder(POST_MAPPING)
                    .addMember("value", "$S", path)
                    .build();
        } else {
            mappingAnnotation = AnnotationSpec.builder(GET_MAPPING)
                    .addMember("value", "$S", path)
                    .build();
        }

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(method.methodName())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(mappingAnnotation);

        // Return type
        if (method.returnDtoType() != null) {
            if (method.collectionReturn()) {
                methodBuilder.returns(ParameterizedTypeName.get(
                        ClassName.get("java.util", "List"), method.returnDtoType()));
            } else {
                methodBuilder.returns(method.returnDtoType());
            }
        } else {
            methodBuilder.returns(method.returnType());
        }

        // Parameters with @RequestParam
        for (ParameterModel param : method.parameters()) {
            ParameterSpec.Builder paramBuilder = ParameterSpec.builder(param.typeName(), param.name());
            paramBuilder.addAnnotation(REQUEST_PARAM);
            methodBuilder.addParameter(paramBuilder.build());
        }

        // Method body: delegate to service, map to DTO
        String callArgs = buildCallArgs(method);

        if (method.returnDtoType() != null && method.returnEntityType() != null) {
            if (method.collectionReturn()) {
                methodBuilder.addStatement("return service.$L($L).stream().map($T::fromEntity).toList()",
                        method.methodName(), callArgs, method.returnDtoType());
            } else {
                methodBuilder.addStatement("return $T.fromEntity(service.$L($L))",
                        method.returnDtoType(), method.methodName(), callArgs);
            }
        } else {
            methodBuilder.addStatement("return service.$L($L)", method.methodName(), callArgs);
        }

        return methodBuilder.build();
    }

    /**
     * Converts camelCase to kebab-case for URL paths.
     * e.g. "OrderService" → "order-service", "findByStatus" → "find-by-status"
     */
    private static String buildCallArgs(MethodModel method) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < method.parameters().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(method.parameters().get(i).name());
        }
        return sb.toString();
    }

    static String toKebabCase(String camelCase) {
        return camelCase
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .toLowerCase();
    }
}

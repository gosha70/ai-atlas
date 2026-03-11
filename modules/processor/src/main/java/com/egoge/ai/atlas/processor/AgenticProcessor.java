/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.processor.generator.ApiVersionPropertiesGenerator;
import com.egoge.ai.atlas.processor.generator.DtoGenerator;
import com.egoge.ai.atlas.processor.generator.McpToolGenerator;
import com.egoge.ai.atlas.processor.generator.OpenApiGenerator;
import com.egoge.ai.atlas.processor.generator.RestControllerGenerator;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ParameterModel;
import com.egoge.ai.atlas.processor.util.AttributeResolver;
import com.egoge.ai.atlas.processor.util.EntityRefResolver;
import com.egoge.ai.atlas.processor.util.FieldScanner;
import com.egoge.ai.atlas.processor.util.PiiDetector;
import com.egoge.ai.atlas.processor.util.ReturnTypeValidator;
import com.google.auto.service.AutoService;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JSR 269 annotation processor for the AI-ATLAS framework. */
@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "com.egoge.ai.atlas.annotations.AgenticEntity",
        "com.egoge.ai.atlas.annotations.AgenticExposed"
})
@SupportedOptions({
        "ai.atlas.pii.patterns", "ai.atlas.pii.patterns.file",
        "ai.atlas.api.basePath", "ai.atlas.api.major", "ai.atlas.openapi.infoVersion"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class AgenticProcessor extends AbstractProcessor {

    private static final String OPT_API_BASE_PATH = "ai.atlas.api.basePath";
    private static final String OPT_API_MAJOR = "ai.atlas.api.major";
    private static final String OPT_OPENAPI_INFO_VERSION = "ai.atlas.openapi.infoVersion";

    private final Map<String, EntityModel> entityRegistry = new HashMap<>();
    private final Set<String> dtoSkippedKeys = new HashSet<>();
    private final List<ServiceModel> serviceRegistry = new ArrayList<>();
    private boolean openApiGenerated = false;
    private boolean apiVersionPropertiesGenerated = false;
    private String apiBasePath;
    private int apiMajor;
    private String openApiInfoVersion;
    private boolean versionConfigValid;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        resolveVersionConfig();
    }

    private void resolveVersionConfig() {
        Messager msg = processingEnv.getMessager();
        Map<String, String> opts = processingEnv.getOptions();
        versionConfigValid = true;

        apiBasePath = opts.getOrDefault(OPT_API_BASE_PATH, "/api");
        if (!apiBasePath.startsWith("/")) {
            msg.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] ai.atlas.api.basePath must start with '/'. Got: " + apiBasePath);
            versionConfigValid = false;
            return;
        }
        while (apiBasePath.endsWith("/") && apiBasePath.length() > 1) {
            apiBasePath = apiBasePath.substring(0, apiBasePath.length() - 1);
        }
        if ("/".equals(apiBasePath)) {
            msg.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] ai.atlas.api.basePath must not be '/'. Use a path like '/api'.");
            versionConfigValid = false;
            return;
        }

        String majorStr = opts.getOrDefault(OPT_API_MAJOR, "1");
        try {
            apiMajor = Integer.parseInt(majorStr);
            if (apiMajor < 1) {
                msg.printMessage(Diagnostic.Kind.ERROR,
                        "[ai-atlas] ai.atlas.api.major must be a positive integer. Got: " + majorStr);
                versionConfigValid = false;
                return;
            }
        } catch (NumberFormatException e) {
            msg.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] ai.atlas.api.major must be an integer. Got: " + majorStr);
            versionConfigValid = false;
            return;
        }

        String infoVersionRaw = opts.get(OPT_OPENAPI_INFO_VERSION);
        openApiInfoVersion = infoVersionRaw != null ? infoVersionRaw : (apiMajor + ".0.0");
        if (openApiInfoVersion.isBlank()) {
            msg.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] ai.atlas.openapi.infoVersion must not be empty");
            versionConfigValid = false;
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!versionConfigValid) {
            return false;
        }
        if (roundEnv.processingOver()) {
            return false;
        }

        // Phase 1: Process @AgenticEntity entities → generate DTOs
        processEntities(roundEnv);

        // Phase 2: Process @AgenticExposed services → generate MCP tools + REST controllers
        processServices(roundEnv);

        // Phase 3: Generate aggregate artifacts once per compilation
        if (!openApiGenerated && (!entityRegistry.isEmpty() || !serviceRegistry.isEmpty())) {
            OpenApiGenerator.generate(
                    new ArrayList<>(entityRegistry.values()),
                    serviceRegistry,
                    apiBasePath, apiMajor, openApiInfoVersion,
                    processingEnv.getFiler(),
                    processingEnv.getMessager()
            );
            openApiGenerated = true;
        }
        if (!apiVersionPropertiesGenerated) {
            ApiVersionPropertiesGenerator.generate(
                    apiBasePath, apiMajor,
                    processingEnv.getFiler(), processingEnv.getMessager());
            apiVersionPropertiesGenerated = true;
        }

        return true;
    }

    private void processEntities(RoundEnvironment roundEnv) {
        // Pass 1: Validate and register all entity models
        List<String> roundEntityKeys = new ArrayList<>();
        for (var element : roundEnv.getElementsAnnotatedWith(AgenticEntity.class)) {
            if (element.getKind() == ElementKind.INTERFACE) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "@AgenticEntity on interface " + element.getSimpleName()
                                + " is not supported — interfaces have no fields. Skipping.", element);
                continue;
            }
            if (element.getKind() == ElementKind.ENUM) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "@AgenticEntity on enum " + element.getSimpleName()
                                + " is not supported — use on concrete classes. Skipping.", element);
                continue;
            }
            if (element.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@AgenticEntity can only be applied to classes", element);
                continue;
            }

            TypeElement typeElement = (TypeElement) element;
            if (typeElement.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "@AgenticEntity on abstract class " + typeElement.getSimpleName()
                                + " — DTO will be generated but fromEntity() may not be callable directly",
                        typeElement);
            }

            String key = typeElement.getQualifiedName().toString();
            processEntity(typeElement);
            // Only add to roundEntityKeys if entity has active fields (non-empty)
            EntityModel registered = entityRegistry.get(key);
            if (registered != null && !registered.fields().isEmpty()) {
                roundEntityKeys.add(key);
            }
        }

        // Pass 2: Validate nested refs and generate DTOs
        for (String key : roundEntityKeys) {
            EntityModel model = entityRegistry.get(key);
            boolean hasEmptyRef = false;
            for (FieldModel field : model.fields()) {
                EntityRefResolver.EntityRef ref = EntityRefResolver.resolve(field, entityRegistry);
                if (ref != null) {
                    EntityModel refEntity = entityRegistry.get(ref.entityClass().canonicalName());
                    if (refEntity != null && refEntity.fields().isEmpty()) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                "[ai-atlas] Field '" + field.name() + "' in "
                                        + model.sourceClassName().simpleName()
                                        + " references entity " + ref.entityClass().simpleName()
                                        + " which has no active fields for apiMajor=" + apiMajor);
                        hasEmptyRef = true;
                    }
                }
            }
            if (hasEmptyRef) {
                dtoSkippedKeys.add(key);
            } else {
                DtoGenerator.generate(model, entityRegistry, apiMajor,
                        processingEnv.getFiler(), processingEnv.getMessager());
            }
        }
    }

    private void processEntity(TypeElement typeElement) {
        var annotation = typeElement.getAnnotation(AgenticEntity.class);
        var fields = FieldScanner.scan(typeElement, processingEnv, apiMajor);

        emitPiiWarnings(typeElement);

        String simpleName = typeElement.getSimpleName().toString();
        String dtoName = annotation.dtoName().isEmpty() ? simpleName + "Dto" : annotation.dtoName();
        String sourcePackage = processingEnv.getElementUtils()
                .getPackageOf(typeElement).getQualifiedName().toString();
        String dtoPackage = annotation.packageName().isEmpty()
                ? sourcePackage + ".generated" : annotation.packageName();
        String displayName = annotation.name().isEmpty() ? simpleName : annotation.name();
        ClassName sourceClassName = ClassName.get(typeElement);
        EntityModel model = new EntityModel(sourceClassName, dtoName, dtoPackage,
                displayName, annotation.description(), annotation.includeTypeInfo(), fields);

        if (fields.isEmpty()) {
            // Register with empty fields so references can be detected in pass 2,
            // but do NOT add to roundEntityKeys — no DTO will be generated.
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "@AgenticEntity " + simpleName
                            + " has no active @AgenticField fields for apiMajor=" + apiMajor,
                    typeElement);
            entityRegistry.put(typeElement.getQualifiedName().toString(), model);
            return;
        }

        // Validate: displayName (metadata key) must be unique across all active fields.
        Map<String, String> displayNameToField = new HashMap<>();
        boolean hasDuplicateAlias = false;
        for (var field : fields) {
            String previous = displayNameToField.put(field.displayName(), field.name());
            if (previous != null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@AgenticField name \"" + field.displayName()
                                + "\" on field '" + field.name()
                                + "' conflicts with field '" + previous
                                + "' — metadata keys must be unique within " + simpleName,
                        typeElement);
                hasDuplicateAlias = true;
            }
        }
        if (hasDuplicateAlias) {
            return;
        }

        entityRegistry.put(typeElement.getQualifiedName().toString(), model);
    }

    private void processServices(RoundEnvironment roundEnv) {
        Set<String> typeLevelTypes = new LinkedHashSet<>();
        Map<String, TypeElement> typesByQName = new LinkedHashMap<>();
        Map<String, List<ExecutableElement>> methodsByType = new LinkedHashMap<>();

        for (var element : roundEnv.getElementsAnnotatedWith(AgenticExposed.class)) {
            if (element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.INTERFACE) {
                TypeElement typeElement = (TypeElement) element;
                String qName = typeElement.getQualifiedName().toString();
                typeLevelTypes.add(qName);
                typesByQName.put(qName, typeElement);
            } else if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) element;
                TypeElement enclosingType = (TypeElement) method.getEnclosingElement();
                String qName = enclosingType.getQualifiedName().toString();
                typesByQName.put(qName, enclosingType);
                methodsByType.computeIfAbsent(qName, k -> new ArrayList<>()).add(method);
            }
        }

        for (var entry : typesByQName.entrySet()) {
            String qName = entry.getKey();
            TypeElement typeElement = entry.getValue();
            if (typeLevelTypes.contains(qName)) {
                processServiceType(typeElement);
            } else {
                processServiceWithMethods(typeElement, methodsByType.get(qName));
            }
        }
    }

    private void processServiceType(TypeElement typeElement) {
        List<ExecutableElement> methods = typeElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.METHOD)
                .filter(e -> e.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC))
                .map(e -> (ExecutableElement) e)
                .toList();

        if (methods.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "@AgenticExposed on " + typeElement.getSimpleName()
                            + " has no public methods to expose", typeElement);
            return;
        }
        processServiceWithMethods(typeElement, methods);
    }

    private void processServiceWithMethods(TypeElement serviceType, List<ExecutableElement> methods) {
        ClassName serviceClassName = ClassName.get(serviceType);
        String servicePackage = processingEnv.getElementUtils()
                .getPackageOf(serviceType).getQualifiedName().toString();
        String generatedPackage = servicePackage + ".generated";
        AgenticExposed typeAnnotation = serviceType.getAnnotation(AgenticExposed.class);

        List<MethodModel> methodModels = new ArrayList<>();
        for (ExecutableElement method : methods) {
            MethodModel methodModel = buildMethodModel(method, typeAnnotation);
            if (methodModel != null) {
                methodModels.add(methodModel);
            }
        }
        if (methodModels.isEmpty()) {
            return;
        }

        ServiceModel model = new ServiceModel(serviceClassName, methodModels);
        serviceRegistry.add(model);
        McpToolGenerator.generate(model, generatedPackage, apiMajor,
                processingEnv.getFiler(), processingEnv.getMessager());
        RestControllerGenerator.generate(model, generatedPackage, apiBasePath, apiMajor,
                processingEnv.getFiler(), processingEnv.getMessager());
    }

    private MethodModel buildMethodModel(ExecutableElement method, AgenticExposed typeAnnotation) {
        AgenticExposed methodAnnotation = method.getAnnotation(AgenticExposed.class);
        if (methodAnnotation == null && typeAnnotation == null) {
            return null;
        }

        String methodName = method.getSimpleName().toString();
        String toolName = (methodAnnotation != null && !methodAnnotation.toolName().isEmpty())
                ? methodAnnotation.toolName() : methodName;
        String description = AttributeResolver.resolveDescription(methodAnnotation, typeAnnotation, methodName);

        ClassName returnEntityType = AttributeResolver.resolveReturnEntityType(
                methodAnnotation, typeAnnotation, processingEnv.getTypeUtils());
        ClassName returnDtoType = null;
        TypeName returnType = TypeName.get(method.getReturnType());
        ServiceModel.ReturnKind returnKind = resolveReturnKind(method);
        if (returnEntityType != null) {
            TypeMirror returnEntityMirror = AttributeResolver.resolveReturnEntityTypeMirror(
                    methodAnnotation, typeAnnotation);
            if (returnEntityMirror != null) {
                var compat = ReturnTypeValidator.validateReturnTypeCompat(
                        method, returnEntityMirror, returnKind != ServiceModel.ReturnKind.NONE,
                        processingEnv.getTypeUtils());
                if (compat == ReturnTypeValidator.Result.INCOMPATIBLE) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@AgenticExposed(returnType = " + returnEntityType.simpleName()
                                    + ") is not compatible with method return type "
                                    + method.getReturnType(), method);
                    return null;
                }
                if (compat == ReturnTypeValidator.Result.INCONCLUSIVE) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                            "[ai-atlas] @AgenticExposed(returnType = " + returnEntityType.simpleName()
                                    + ") on '" + methodName + "' — cast safety cannot be verified", method);
                }
            }
            EntityModel entityModel = entityRegistry.get(returnEntityType.canonicalName());
            if (entityModel != null && entityModel.fields().isEmpty()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "[ai-atlas] Entity " + returnEntityType.simpleName()
                                + " has no active fields for apiMajor=" + apiMajor
                                + " — cannot generate DTO for service method '" + methodName + "'",
                        method);
                return null;
            }
            if (entityModel != null && dtoSkippedKeys.contains(returnEntityType.canonicalName())) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "[ai-atlas] Entity " + returnEntityType.simpleName()
                                + " has nested references to empty entities for apiMajor=" + apiMajor
                                + " — cannot generate DTO for service method '" + methodName + "'",
                        method);
                return null;
            }
            if (entityModel != null) {
                returnDtoType = entityModel.dtoClassName();
            }
        }

        List<ParameterModel> params = method.getParameters().stream()
                .map(p -> new ParameterModel(p.getSimpleName().toString(), TypeName.get(p.asType()), ""))
                .toList();
        Set<String> channels = AttributeResolver.resolveChannels(
                methodAnnotation, typeAnnotation, method, processingEnv.getMessager());
        if (channels == null) {
            return null;
        }

        int apiSince = AttributeResolver.resolveIntAttr(
                methodAnnotation, typeAnnotation, AgenticExposed::apiSince, 1);
        int apiUntil = AttributeResolver.resolveIntAttr(
                methodAnnotation, typeAnnotation, AgenticExposed::apiUntil, Integer.MAX_VALUE);
        int apiDeprecatedSince = AttributeResolver.resolveIntAttr(
                methodAnnotation, typeAnnotation, AgenticExposed::apiDeprecatedSince, 0);
        String apiReplacement = AttributeResolver.resolveStringAttr(
                methodAnnotation, typeAnnotation, AgenticExposed::apiReplacement, "");

        if (apiSince < 1) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] apiSince must be >= 1 on method '" + methodName + "'. Got: " + apiSince, method);
            return null;
        }
        if (apiDeprecatedSince < 0) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] apiDeprecatedSince must be >= 0 on '" + methodName + "'. Got: " + apiDeprecatedSince, method);
            return null;
        }
        if (apiSince > apiUntil) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] apiSince (" + apiSince + ") must be <= apiUntil (" + apiUntil
                            + ") on method '" + methodName + "'", method);
            return null;
        }
        if (apiDeprecatedSince > 0 && apiDeprecatedSince < apiSince) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] apiDeprecatedSince (" + apiDeprecatedSince + ") must be >= apiSince ("
                            + apiSince + ") on '" + methodName + "'", method);
            return null;
        }
        if (apiDeprecatedSince > apiUntil) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] apiDeprecatedSince (" + apiDeprecatedSince + ") must be <= apiUntil ("
                            + apiUntil + ") on method '" + methodName + "'", method);
            return null;
        }

        return new MethodModel(methodName, toolName, description, returnType, returnEntityType,
                returnDtoType, returnKind, params, channels, apiSince, apiUntil, apiDeprecatedSince, apiReplacement);
    }

    private ServiceModel.ReturnKind resolveReturnKind(ExecutableElement method) {
        TypeMirror returnType = method.getReturnType();
        var typeUtils = processingEnv.getTypeUtils();
        var elementUtils = processingEnv.getElementUtils();
        if (returnType.getKind() == javax.lang.model.type.TypeKind.ARRAY) {
            return ServiceModel.ReturnKind.ARRAY;
        }
        TypeMirror erasedReturn = typeUtils.erasure(returnType);
        TypeElement collectionEl = elementUtils.getTypeElement("java.util.Collection");
        if (collectionEl != null
                && typeUtils.isAssignable(erasedReturn, typeUtils.erasure(collectionEl.asType()))) {
            return ServiceModel.ReturnKind.COLLECTION;
        }
        TypeElement iterableEl = elementUtils.getTypeElement("java.lang.Iterable");
        if (iterableEl != null
                && typeUtils.isAssignable(erasedReturn, typeUtils.erasure(iterableEl.asType()))) {
            return ServiceModel.ReturnKind.ITERABLE;
        }
        return ServiceModel.ReturnKind.NONE;
    }

    private void emitPiiWarnings(TypeElement typeElement) {
        String customPatterns = processingEnv.getOptions().get("ai.atlas.pii.patterns");
        String patternsFile = processingEnv.getOptions().get("ai.atlas.pii.patterns.file");
        for (var enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && enclosed.getAnnotation(AgenticField.class) == null) {
                PiiDetector.check(enclosed.getSimpleName().toString(), enclosed,
                        processingEnv.getMessager(), customPatterns, patternsFile);
            }
        }
    }
}

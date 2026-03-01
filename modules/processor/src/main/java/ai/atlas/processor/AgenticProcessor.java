/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.processor;

import ai.atlas.annotations.AgentVisible;
import ai.atlas.annotations.AgentVisibleClass;
import ai.atlas.annotations.AgenticExposed;
import ai.atlas.processor.generator.DtoGenerator;
import ai.atlas.processor.generator.McpToolGenerator;
import ai.atlas.processor.generator.OpenApiGenerator;
import ai.atlas.processor.generator.RestControllerGenerator;
import ai.atlas.processor.model.EntityModel;
import ai.atlas.processor.model.ServiceModel;
import ai.atlas.processor.model.ServiceModel.MethodModel;
import ai.atlas.processor.model.ServiceModel.ParameterModel;
import ai.atlas.processor.util.FieldScanner;
import ai.atlas.processor.util.PiiDetector;
import com.google.auto.service.AutoService;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSR 269 annotation processor for the AI-ATLAS framework.
 *
 * <p>Processing order:
 * <ol>
 *   <li>{@code @AgentVisibleClass} entities → DTO records</li>
 *   <li>{@code @AgenticExposed} services → MCP tools + REST controllers</li>
 * </ol>
 *
 * <p>Registered as AGGREGATING for Gradle incremental builds.
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "ai.atlas.annotations.AgentVisibleClass",
        "ai.atlas.annotations.AgenticExposed"
})
@javax.annotation.processing.SupportedOptions("ai.atlas.pii.patterns")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class AgenticProcessor extends AbstractProcessor {

    /** Maps entity qualified name → EntityModel (for DTO lookup during service processing) */
    private final Map<String, EntityModel> entityRegistry = new HashMap<>();

    /** Collects service models for aggregate OpenAPI generation */
    private final List<ServiceModel> serviceRegistry = new ArrayList<>();

    /** Guard to ensure OpenAPI spec is written only once */
    private boolean openApiGenerated = false;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        // Phase 1: Process @AgentVisibleClass entities → generate DTOs
        processEntities(roundEnv);

        // Phase 2: Process @AgenticExposed services → generate MCP tools + REST controllers
        processServices(roundEnv);

        // Phase 3: Generate aggregate OpenAPI spec (once per compilation)
        if (!openApiGenerated && (!entityRegistry.isEmpty() || !serviceRegistry.isEmpty())) {
            OpenApiGenerator.generate(
                    new ArrayList<>(entityRegistry.values()),
                    serviceRegistry,
                    processingEnv.getFiler(),
                    processingEnv.getMessager()
            );
            openApiGenerated = true;
        }

        return true;
    }

    private void processEntities(RoundEnvironment roundEnv) {
        for (var element : roundEnv.getElementsAnnotatedWith(AgentVisibleClass.class)) {
            if (element.getKind() == ElementKind.INTERFACE) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        "@AgentVisibleClass on interface " + element.getSimpleName()
                                + " is not supported — interfaces have no fields. Skipping.",
                        element
                );
                continue;
            }
            if (element.getKind() == ElementKind.ENUM) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        "@AgentVisibleClass on enum " + element.getSimpleName()
                                + " is not supported — use on concrete classes. Skipping.",
                        element
                );
                continue;
            }
            if (element.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@AgentVisibleClass can only be applied to classes",
                        element
                );
                continue;
            }

            TypeElement typeElement = (TypeElement) element;

            // Warn on abstract classes but still process them (they may have fields)
            if (typeElement.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        "@AgentVisibleClass on abstract class " + typeElement.getSimpleName()
                                + " — DTO will be generated but fromEntity() may not be callable directly",
                        typeElement
                );
            }

            processEntity(typeElement);
        }
    }

    private void processEntity(TypeElement typeElement) {
        var annotation = typeElement.getAnnotation(AgentVisibleClass.class);
        var fields = FieldScanner.scan(typeElement);

        emitPiiWarnings(typeElement);

        if (fields.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "@AgentVisibleClass on " + typeElement.getSimpleName()
                            + " has no @AgentVisible fields — no DTO will be generated",
                    typeElement
            );
            return;
        }

        // Validate: displayName (metadata key) must be unique across all fields.
        // Generated FIELD_METADATA uses Map.ofEntries() which throws on duplicate keys.
        Map<String, String> displayNameToField = new HashMap<>();
        boolean hasDuplicateAlias = false;
        for (var field : fields) {
            String previous = displayNameToField.put(field.displayName(), field.name());
            if (previous != null) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@AgentVisible name \"" + field.displayName()
                                + "\" on field '" + field.name()
                                + "' conflicts with field '" + previous
                                + "' — metadata keys must be unique within "
                                + typeElement.getSimpleName(),
                        typeElement
                );
                hasDuplicateAlias = true;
            }
        }
        if (hasDuplicateAlias) {
            return;
        }

        String simpleName = typeElement.getSimpleName().toString();
        String dtoName = annotation.dtoName().isEmpty()
                ? simpleName + "Dto"
                : annotation.dtoName();

        String sourcePackage = processingEnv.getElementUtils()
                .getPackageOf(typeElement).getQualifiedName().toString();
        String dtoPackage = annotation.packageName().isEmpty()
                ? sourcePackage + ".generated"
                : annotation.packageName();

        // Class-level metadata for enriched JSON and LLM context
        String displayName = annotation.name().isEmpty()
                ? simpleName
                : annotation.name();
        String classDescription = annotation.description();
        boolean includeTypeInfo = annotation.includeTypeInfo();

        ClassName sourceClassName = ClassName.get(typeElement);
        EntityModel model = new EntityModel(
                sourceClassName, dtoName, dtoPackage,
                displayName, classDescription, includeTypeInfo,
                fields
        );

        // Register for service processing lookup
        entityRegistry.put(typeElement.getQualifiedName().toString(), model);

        DtoGenerator.generate(model, processingEnv.getFiler(), processingEnv.getMessager());
    }

    private void processServices(RoundEnvironment roundEnv) {
        // Collect type-level @AgenticExposed
        for (var element : roundEnv.getElementsAnnotatedWith(AgenticExposed.class)) {
            if (element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.INTERFACE) {
                TypeElement typeElement = (TypeElement) element;
                processServiceType(typeElement);
            } else if (element.getKind() == ElementKind.METHOD) {
                // Method-level: collect by enclosing type
                ExecutableElement method = (ExecutableElement) element;
                TypeElement enclosingType = (TypeElement) method.getEnclosingElement();
                processServiceWithMethods(enclosingType, List.of(method));
            }
        }
    }

    private void processServiceType(TypeElement typeElement) {
        // Type-level @AgenticExposed: all public methods are exposed
        List<ExecutableElement> methods = typeElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.METHOD)
                .filter(e -> e.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC))
                .map(e -> (ExecutableElement) e)
                .toList();

        if (methods.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "@AgenticExposed on " + typeElement.getSimpleName()
                            + " has no public methods to expose",
                    typeElement
            );
            return;
        }

        processServiceWithMethods(typeElement, methods);
    }

    private void processServiceWithMethods(TypeElement serviceType, List<ExecutableElement> methods) {
        ClassName serviceClassName = ClassName.get(serviceType);
        String servicePackage = processingEnv.getElementUtils()
                .getPackageOf(serviceType).getQualifiedName().toString();
        String generatedPackage = servicePackage + ".generated";

        // Get type-level annotation (may be null for method-level only)
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

        McpToolGenerator.generate(model, generatedPackage, processingEnv.getFiler(), processingEnv.getMessager());
        RestControllerGenerator.generate(model, generatedPackage, processingEnv.getFiler(), processingEnv.getMessager());
    }

    private MethodModel buildMethodModel(ExecutableElement method, AgenticExposed typeAnnotation) {
        // Method-level annotation takes precedence
        AgenticExposed methodAnnotation = method.getAnnotation(AgenticExposed.class);
        AgenticExposed annotation = methodAnnotation != null ? methodAnnotation : typeAnnotation;

        if (annotation == null) {
            return null;
        }

        String methodName = method.getSimpleName().toString();
        // For type-level annotation, use method name as tool name (type toolName is the service prefix)
        String toolName;
        if (methodAnnotation != null && !methodAnnotation.toolName().isEmpty()) {
            toolName = methodAnnotation.toolName();
        } else {
            toolName = methodName;
        }
        String description = annotation.description().isEmpty()
                ? "Invokes " + methodName
                : annotation.description();

        // Resolve return entity type via MirroredTypeException
        ClassName returnEntityType = resolveReturnEntityType(annotation);
        ClassName returnDtoType = null;
        TypeName returnType = TypeName.get(method.getReturnType());

        // Detect if method returns a collection (List, Collection, etc.)
        boolean collectionReturn = isCollectionReturn(method);

        if (returnEntityType != null) {
            EntityModel entityModel = entityRegistry.get(returnEntityType.canonicalName());
            if (entityModel != null) {
                returnDtoType = entityModel.dtoClassName();
            }
        }

        // Build parameter models
        List<ParameterModel> params = method.getParameters().stream()
                .map(p -> new ParameterModel(
                        p.getSimpleName().toString(),
                        TypeName.get(p.asType()),
                        ""
                ))
                .toList();

        return new MethodModel(methodName, toolName, description, returnType, returnEntityType, returnDtoType, collectionReturn, params);
    }

    /**
     * Checks if a method's return type is a collection (List, Collection, Set, Iterable).
     */
    private boolean isCollectionReturn(ExecutableElement method) {
        TypeMirror returnType = method.getReturnType();
        var typeUtils = processingEnv.getTypeUtils();
        var elementUtils = processingEnv.getElementUtils();

        for (String collectionType : List.of("java.util.List", "java.util.Collection", "java.util.Set", "java.lang.Iterable")) {
            TypeElement collectionElement = elementUtils.getTypeElement(collectionType);
            if (collectionElement != null) {
                TypeMirror erasedReturn = typeUtils.erasure(returnType);
                TypeMirror erasedCollection = typeUtils.erasure(collectionElement.asType());
                if (typeUtils.isAssignable(erasedReturn, erasedCollection)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reads the returnType() attribute from @AgenticExposed, handling
     * MirroredTypeException as required by JSR 269.
     */
    private ClassName resolveReturnEntityType(AgenticExposed annotation) {
        try {
            Class<?> returnType = annotation.returnType();
            if (returnType == void.class) {
                return null;
            }
            return ClassName.get(returnType);
        } catch (MirroredTypeException e) {
            TypeMirror typeMirror = e.getTypeMirror();
            TypeElement typeElement = (TypeElement) processingEnv.getTypeUtils().asElement(typeMirror);
            if (typeElement != null) {
                return ClassName.get(typeElement);
            }
            return null;
        }
    }

    private void emitPiiWarnings(TypeElement typeElement) {
        String customPatterns = processingEnv.getOptions().get("ai.atlas.pii.patterns");
        for (var enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && enclosed.getAnnotation(AgentVisible.class) == null) {
                PiiDetector.check(
                        enclosed.getSimpleName().toString(),
                        enclosed,
                        processingEnv.getMessager(),
                        customPatterns
                );
            }
        }
    }
}

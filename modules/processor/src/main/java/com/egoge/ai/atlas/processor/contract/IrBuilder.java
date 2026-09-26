/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.FieldLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.OperationLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;
import com.egoge.ai.atlas.processor.contract.ContractIr.Return;
import com.egoge.ai.atlas.processor.contract.ContractIr.TypeRef;
import com.egoge.ai.atlas.processor.generator.RestControllerGenerator;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.util.AttributeResolver;
import com.egoge.ai.atlas.processor.util.EntityRefResolver;
import com.egoge.ai.atlas.processor.util.FieldScanner;
import com.egoge.ai.atlas.processor.util.ReturnTypeValidator;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Collects the {@link ContractIr} of a compilation across rounds (FR-001). Entities arrive with
 * every valid field, whatever its lifecycle; operations are read from their elements with
 * method-then-class attribute resolution. Nothing is filtered by the configured major.
 *
 * <p>The builder reports only the diagnostics of attributes generation does not read
 * ({@code openEnum}); the scans and resolutions it shares with generation report theirs there, once.
 */
public final class IrBuilder {

    private static final String API_CHANNEL = "API";
    private static final String GET = "GET";
    private static final String POST = "POST";

    /** Swallows the diagnostics of resolutions already reported by the generation path. */
    private static final Messager SILENT = new Messager() {
        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a,
                                 AnnotationValue v) {
        }
    };

    private final ProcessingEnvironment env;
    private final Map<String, EntityModel> entities = new TreeMap<>();
    private final Map<String, Operation> operations = new TreeMap<>();
    /** {@code entity#field} of every field declared {@code openEnum = true}. */
    private final Set<String> openEnums = new HashSet<>();
    private boolean written = false;

    /**
     * @param env the processing environment of the compilation
     */
    public IrBuilder(ProcessingEnvironment env) {
        this.env = env;
    }

    /**
     * Records an entity with every field of the scan, whatever its lifecycle.
     *
     * @param entity the {@code @AgenticEntity} class
     * @param fields every valid {@code @AgenticField} of the entity, in DTO declaration order
     */
    public void addEntity(TypeElement entity, List<FieldScanner.ScannedField> fields) {
        AgenticEntity annotation = entity.getAnnotation(AgenticEntity.class);
        String className = entity.getQualifiedName().toString();
        for (FieldScanner.ScannedField field : fields) {
            AgenticField fieldAnnotation = field.element().getAnnotation(AgenticField.class);
            if (fieldAnnotation != null && fieldAnnotation.openEnum()) {
                recordOpenEnum(className, field);
            }
        }
        String simpleName = entity.getSimpleName().toString();
        String dtoPackage = annotation.packageName().isEmpty()
                ? env.getElementUtils().getPackageOf(entity).getQualifiedName() + ".generated"
                : annotation.packageName();
        entities.put(className, new EntityModel(ClassName.get(entity),
                annotation.dtoName().isEmpty() ? simpleName + "Dto" : annotation.dtoName(), dtoPackage,
                annotation.name().isEmpty() ? simpleName : annotation.name(), annotation.description(),
                annotation.includeTypeInfo(), fields.stream().map(FieldScanner.ScannedField::model).toList()));
    }

    /** Records {@code openEnum = true}, warning when the field has no values it could apply to (FR-011). */
    private void recordOpenEnum(String className, FieldScanner.ScannedField field) {
        FieldModel model = field.model();
        if (!model.enumType() && model.enumValues().isEmpty()) {
            env.getMessager().printMessage(Diagnostic.Kind.WARNING, "[ai-atlas] @AgenticField(openEnum = true) on field '"
                    + model.name() + "' has no effect — the field is neither an enum nor has allowedValues",
                    field.element());
        }
        openEnums.add(className + "#" + model.name());
    }

    /**
     * Records an exposed method. The caller records only methods whose model is valid, as invalid
     * fields are left out of the scan; the generation path reports why a method is invalid.
     *
     * @param service        the service class declaring the method
     * @param method         the method
     * @param typeAnnotation the service's class-level {@code @AgenticExposed}, or {@code null}
     * @return the operation's identity, {@link Operation#id()}, or {@code null} when the method is
     *         not exposed or its channels cannot be resolved
     */
    public String addOperation(TypeElement service, ExecutableElement method, AgenticExposed typeAnnotation) {
        AgenticExposed methodAnnotation = method.getAnnotation(AgenticExposed.class);
        if (methodAnnotation == null && typeAnnotation == null) {
            return null;
        }
        Set<String> channels = AttributeResolver.resolveChannels(methodAnnotation, typeAnnotation, method, SILENT);
        if (channels == null) {
            return null;
        }

        String methodName = method.getSimpleName().toString();
        String toolName = methodAnnotation != null && !methodAnnotation.toolName().isEmpty()
                ? methodAnnotation.toolName() : methodName;
        List<Parameter> parameters = new ArrayList<>();
        for (VariableElement param : method.getParameters()) {
            parameters.add(new Parameter(param.getSimpleName().toString(), typeString(param.asType()), "",
                    enumConstants(param.asType())));
        }
        Rest rest = null;
        if (channels.contains(API_CHANNEL)) {
            rest = new Rest(parameters.isEmpty() ? GET : POST,
                    "/" + RestControllerGenerator.toKebabCase(service.getSimpleName().toString())
                            + "/" + RestControllerGenerator.toKebabCase(methodName));
        }
        ClassName returnType = AttributeResolver.resolveReturnEntityType(
                methodAnnotation, typeAnnotation, env.getTypeUtils());
        Return returns = new Return(typeString(method.getReturnType()),
                ReturnTypeValidator.resolveReturnKind(method, env.getTypeUtils(), env.getElementUtils()).name(),
                returnType != null ? returnType.canonicalName() : null, null);
        OperationLifecycle lifecycle = new OperationLifecycle(
                AttributeResolver.resolveIntAttr(methodAnnotation, typeAnnotation, AgenticExposed::apiSince, 1),
                AttributeResolver.resolveIntAttr(methodAnnotation, typeAnnotation, AgenticExposed::apiUntil,
                        Integer.MAX_VALUE),
                AttributeResolver.resolveIntAttr(methodAnnotation, typeAnnotation,
                        AgenticExposed::apiDeprecatedSince, 0),
                AttributeResolver.resolveStringAttr(methodAnnotation, typeAnnotation,
                        AgenticExposed::apiReplacement, ""));

        Operation operation = new Operation(service.getQualifiedName().toString(), methodName, toolName,
                channels.stream().sorted().toList(),
                AttributeResolver.resolveDescription(methodAnnotation, typeAnnotation, methodName),
                rest, parameters, returns, lifecycle);
        operations.put(operation.id(), operation);
        return operation.id();
    }

    /**
     * Writes the document to {@link ContractIr#RESOURCE_PATH} in the class output, once per
     * compilation; later calls do nothing (FR-003).
     *
     * @param apiBasePath the configured REST base path
     * @param apiMajor    the configured major
     */
    public void write(String apiBasePath, int apiMajor) {
        if (written) {
            return;
        }
        written = true;
        try {
            var resource = env.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", ContractIr.RESOURCE_PATH);
            try (OutputStream out = resource.openOutputStream()) {
                out.write(IrJson.writeBytes(build(apiBasePath, apiMajor)));
            }
        } catch (IOException e) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] Failed to write the Contract IR " + ContractIr.RESOURCE_PATH + ": " + e.getMessage());
        }
    }

    /**
     * Projects the declarations recorded so far at {@code apiMajor}, resolving class names against
     * the compilation's elements (FR-006).
     *
     * @param apiBasePath the configured REST base path
     * @param apiMajor    the configured major
     * @return the projection the generators consume
     */
    public ContractProjection project(String apiBasePath, int apiMajor) {
        return ContractProjection.of(build(apiBasePath, apiMajor), apiMajor, qualifiedName -> {
            TypeElement type = env.getElementUtils().getTypeElement(qualifiedName);
            return type != null ? ClassName.get(type) : null;
        });
    }

    /**
     * Builds the document, resolving each field's and return's entity reference against every
     * recorded entity.
     *
     * @param apiBasePath the configured REST base path
     * @param apiMajor    the configured major
     * @return the Contract IR, canonically ordered (FR-003)
     */
    public ContractIr build(String apiBasePath, int apiMajor) {
        List<Entity> irEntities = new ArrayList<>();
        for (EntityModel entity : entities.values()) {
            List<Field> fields = new ArrayList<>();
            String className = entity.sourceClassName().canonicalName();
            for (FieldModel field : entity.fields()) {
                fields.add(field(field, openEnums.contains(className + "#" + field.name())));
            }
            irEntities.add(new Entity(className, entity.dtoName(),
                    entity.dtoPackageName(), entity.displayName(), entity.classDescription(),
                    entity.includeTypeInfo(), fields));
        }
        List<Operation> irOperations = new ArrayList<>();
        for (Operation op : operations.values()) {
            Return returns = op.returns();
            EntityModel returned = returns.returnType() != null ? entities.get(returns.returnType()) : null;
            TypeRef reference = returned != null
                    ? new TypeRef(returns.returnType(), returned.dtoClassName().canonicalName()) : null;
            irOperations.add(new Operation(op.service(), op.method(), op.toolName(), op.channels(),
                    op.description(), op.rest(), op.parameters(),
                    new Return(returns.javaType(), returns.returnKind(), returns.returnType(), reference),
                    op.lifecycle()));
        }
        irOperations.sort(Comparator.comparing(Operation::service).thenComparing(Operation::signature));
        return new ContractIr(ContractIr.IR_VERSION, apiBasePath, apiMajor, irEntities, irOperations);
    }

    private Field field(FieldModel field, boolean openEnum) {
        EntityRefResolver.EntityRef ref = EntityRefResolver.resolve(field, entities);
        return new Field(field.name(), field.displayName(), field.typeName().toString(),
                field.collectionKind().name(), typeString(field.elementTypeName()),
                typeString(field.hintTypeName()),
                ref != null ? new TypeRef(ref.entityClass().canonicalName(), ref.dtoClass().canonicalName()) : null,
                field.enumType(), field.enumValues(), openEnum, field.sensitive(), field.checkCircularReference(),
                field.description(), new FieldLifecycle(field.sinceVersion(), field.removedInVersion(),
                        field.deprecatedSinceVersion(), field.deprecatedMessage()));
    }

    /**
     * The canonical source form of a type (FR-005). {@code TypeName.get} does not carry TYPE_USE
     * annotations, so annotating a type, e.g. with {@code @Nullable}, leaves its string unchanged.
     */
    static String typeString(TypeMirror type) {
        return TypeName.get(type).toString();
    }

    private static String typeString(TypeName type) {
        return type != null ? type.toString() : null;
    }

    private static List<String> enumConstants(TypeMirror type) {
        if (type instanceof DeclaredType declared && declared.asElement().getKind() == ElementKind.ENUM) {
            return FieldScanner.extractEnumConstants((TypeElement) declared.asElement());
        }
        return List.of();
    }
}

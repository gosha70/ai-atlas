/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.FieldLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.OperationLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ParameterModel;
import com.egoge.ai.atlas.processor.util.FieldScanner;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeVariableName;
import com.palantir.javapoet.WildcardTypeName;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The projection of a {@link ContractIr} at a major N (FR-006): the entity, field and service
 * models the generators consume, holding only the elements active at N, with deprecation resolved
 * (a field or operation not deprecated at N carries no deprecation), and the OpenAPI
 * {@code operationId} of every active API operation.
 *
 * <p>Java types are parsed back into JavaPoet {@link TypeName}s from the IR's canonical strings.
 * A dotted name is split into package and nested classes by the given class-name resolver when it
 * knows the type, and by the package-lowercase / class-uppercase convention otherwise.
 */
public final class ContractProjection {

    private static final String API_CHANNEL = "API";
    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "short", "int", "long", "char", "float", "double", "void");

    private final ContractIr ir;
    private final int major;
    private final Map<String, EntityModel> entities = new LinkedHashMap<>();
    private final Map<String, MethodModel> methods = new LinkedHashMap<>();
    private final List<ServiceModel> services = new ArrayList<>();
    private final Map<String, String> operationIds;

    private ContractProjection(ContractIr ir, int major, Function<String, ClassName> classNames) {
        this.ir = ir;
        this.major = major;
        TypeParser types = new TypeParser(classNames);
        for (Entity entity : ir.entities()) {
            entities.put(entity.className(), entity(entity, types));
        }
        Map<String, List<MethodModel>> byService = new LinkedHashMap<>();
        for (Operation op : ir.operations()) {
            if (isActive(op.lifecycle(), major)) {
                MethodModel method = method(op, types);
                methods.put(op.id(), method);
                byService.computeIfAbsent(op.service(), s -> new ArrayList<>()).add(method);
            }
        }
        byService.forEach((service, serviceMethods) ->
                services.add(new ServiceModel(types.className(service), serviceMethods)));
        operationIds = assignOperationIds(operationSites());
    }

    /**
     * Projects {@code ir} at {@code major}, splitting dotted type names by convention.
     *
     * @param ir    the Contract IR
     * @param major the major to project at
     * @return the projection
     */
    public static ContractProjection of(ContractIr ir, int major) {
        return of(ir, major, name -> null);
    }

    /**
     * Projects {@code ir} at {@code major}.
     *
     * @param ir         the Contract IR
     * @param major      the major to project at
     * @param classNames resolves a canonical class name to its {@link ClassName}, or returns
     *                   {@code null} when it does not know the type
     * @return the projection
     */
    public static ContractProjection of(ContractIr ir, int major, Function<String, ClassName> classNames) {
        return new ContractProjection(ir, major, classNames);
    }

    /** The major this projection was taken at. */
    public int major() {
        return major;
    }

    /** The projected document. */
    public ContractIr ir() {
        return ir;
    }

    /**
     * Every entity of the IR by qualified class name, in IR order, each with only its fields
     * active at the major; an entity with none has an empty field list.
     */
    public Map<String, EntityModel> entities() {
        return Collections.unmodifiableMap(entities);
    }

    /**
     * @param className qualified name of the entity class
     * @return the projected entity, or {@code null} when the IR has no such entity
     */
    public EntityModel entity(String className) {
        return entities.get(className);
    }

    /**
     * Reports a NOTE, on the field's element, for each scanned field of {@code entity} that this
     * projection leaves out as not active at its major.
     *
     * @param entity   the {@code @AgenticEntity} class
     * @param scanned  every valid {@code @AgenticField} of the entity
     * @param messager receives the notes
     */
    public void reportExcluded(TypeElement entity, List<FieldScanner.ScannedField> scanned, Messager messager) {
        Set<String> active = entities.get(entity.getQualifiedName().toString()).fields().stream()
                .map(FieldModel::name).collect(Collectors.toSet());
        for (FieldScanner.ScannedField field : scanned) {
            FieldModel declared = field.model();
            if (!active.contains(declared.name())) {
                messager.printMessage(Diagnostic.Kind.NOTE, "[ai-atlas] Field '" + declared.name() + "' excluded from "
                        + entity.getSimpleName() + " DTO — not active for apiMajor=" + major
                        + " (sinceVersion=" + declared.sinceVersion()
                        + ", removedInVersion=" + declared.removedInVersion() + ")", field.element());
            }
        }
    }

    /** The services with at least one active operation, in IR order, holding their active operations. */
    public List<ServiceModel> services() {
        return Collections.unmodifiableList(services);
    }

    /**
     * The service declaring {@code operationIds}, holding those active at the major, in the given
     * order; the IR orders operations by identity, the generators by declaration.
     *
     * @param service      the service class
     * @param operationIds IR identities of the service's operations, in declaration order
     * @return the service model, with no methods when none is active
     */
    public ServiceModel service(ClassName service, List<String> operationIds) {
        return new ServiceModel(service, operationIds.stream().map(methods::get).filter(Objects::nonNull).toList());
    }

    /**
     * @param operationId the operation's IR identity, {@link Operation#id()}
     * @return the projected operation, or {@code null} when it is absent or inactive at the major
     */
    public MethodModel method(String operationId) {
        return methods.get(operationId);
    }

    /** The OpenAPI {@code operationId} of every active API operation, keyed by {@link Operation#id()}. */
    public Map<String, String> operationIds() {
        return Collections.unmodifiableMap(operationIds);
    }

    /**
     * The IR identity of a projected operation, equal to {@link Operation#id()} of the operation it
     * was projected from.
     *
     * @param service the service declaring the operation
     * @param method  the operation
     * @return {@code service#method(parameter types)}
     */
    public static String operationKey(ClassName service, MethodModel method) {
        return service.canonicalName() + "#" + method.methodName() + "("
                + method.parameters().stream().map(p -> p.typeName().toString()).collect(Collectors.joining(","))
                + ")";
    }

    /** Whether a field is active at {@code major}: {@code sinceVersion <= major < removedInVersion}. */
    public static boolean isActive(FieldLifecycle lifecycle, int major) {
        return lifecycle.sinceVersion() <= major && major < lifecycle.removedInVersion();
    }

    /** Whether an operation is active at {@code major}: {@code apiSince <= major <= apiUntil}. */
    public static boolean isActive(OperationLifecycle lifecycle, int major) {
        return lifecycle.apiSince() <= major && major <= lifecycle.apiUntil();
    }

    /**
     * Parses a canonical type string of the IR, splitting dotted names by convention.
     *
     * @param type e.g. {@code java.util.Map<java.lang.String, int[]>}
     * @return the type
     * @throws IllegalArgumentException if the string is not a canonical type
     */
    public static TypeName parseType(String type) {
        return new TypeParser(name -> null).parse(type);
    }

    private EntityModel entity(Entity entity, TypeParser types) {
        List<FieldModel> fields = new ArrayList<>();
        for (Field field : entity.fields()) {
            FieldLifecycle life = field.lifecycle();
            if (!isActive(life, major)) {
                continue;
            }
            boolean deprecated = life.deprecatedSinceVersion() > 0 && life.deprecatedSinceVersion() <= major;
            fields.add(new FieldModel(field.name(), field.displayName(), types.parse(field.javaType()),
                    field.description(), field.sensitive(), field.checkCircularReference(), field.enumType(),
                    field.allowedValues(), FieldModel.CollectionKind.valueOf(field.collectionKind()),
                    types.parseNullable(field.elementType()), types.parseNullable(field.typeHint()),
                    life.sinceVersion(), life.removedInVersion(),
                    deprecated ? life.deprecatedSinceVersion() : 0, deprecated ? life.deprecatedMessage() : ""));
        }
        return new EntityModel(types.className(entity.className()), entity.dtoName(), entity.dtoPackage(),
                entity.displayName(), entity.description(), entity.includeTypeInfo(), fields);
    }

    private MethodModel method(Operation op, TypeParser types) {
        OperationLifecycle life = op.lifecycle();
        boolean deprecated = life.apiDeprecatedSince() > 0 && life.apiDeprecatedSince() <= major;
        List<ParameterModel> parameters = new ArrayList<>();
        for (Parameter param : op.parameters()) {
            parameters.add(new ParameterModel(param.name(), types.parse(param.javaType()), param.description()));
        }
        ContractIr.Return returns = op.returns();
        ClassName returnEntity = returns.returnType() != null ? types.className(returns.returnType()) : null;
        ClassName returnDto = null;
        if (returns.reference() != null) {
            EntityModel referenced = entities.get(returns.reference().entity());
            returnDto = referenced != null ? referenced.dtoClassName() : dtoClassName(returns.reference().dto());
        }
        return new MethodModel(op.method(), op.toolName(), op.description(), types.parse(returns.javaType()),
                returnEntity, returnDto, ServiceModel.ReturnKind.valueOf(returns.returnKind()), parameters,
                new LinkedHashSet<>(op.channels()), life.apiSince(), life.apiUntil(),
                deprecated ? life.apiDeprecatedSince() : 0, deprecated ? life.apiReplacement() : "");
    }

    /** A generated DTO's name: its simple name never contains a dot. */
    private static ClassName dtoClassName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return ClassName.get(dot < 0 ? "" : qualified.substring(0, dot), qualified.substring(dot + 1));
    }

    // ---------------------------------------------------------------- operation IDs

    /** One active API operation, in the order the OpenAPI document lists them. */
    private record OperationSite(String operation, String path, String httpMethodName,
                                 String serviceSimpleName, String methodName) {
    }

    private List<OperationSite> operationSites() {
        List<OperationSite> sites = new ArrayList<>();
        for (Operation op : ir.operations()) {
            if (op.rest() == null || !op.channels().contains(API_CHANNEL) || !isActive(op.lifecycle(), major)) {
                continue;
            }
            String service = op.service().substring(op.service().lastIndexOf('.') + 1);
            sites.add(new OperationSite(op.id(), ir.apiBasePath() + "/v" + major + op.rest().path(),
                    op.rest().httpMethod().toLowerCase(Locale.ROOT), service, op.method()));
        }
        return sites;
    }

    /**
     * Assigns a unique operationId to every site: a method name used by one operation only is
     * kept; shared ones, in (path, HTTP method) order, get {@code {Service}_{method}_{httpMethod}}
     * plus the smallest free {@code _N} suffix if taken. Sites sharing both path and HTTP method are
     * duplicate REST mappings, reported as errors; among them IR order decides.
     */
    private static Map<String, String> assignOperationIds(List<OperationSite> entries) {
        Map<String, Long> nameCounts = entries.stream()
                .collect(Collectors.groupingBy(OperationSite::methodName, Collectors.counting()));
        String[] ids = new String[entries.size()];
        Set<String> taken = new HashSet<>();
        List<Integer> shared = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            String methodName = entries.get(i).methodName();
            if (nameCounts.get(methodName) == 1) {
                ids[i] = methodName;
                taken.add(methodName);
            } else {
                shared.add(i);
            }
        }
        shared.sort(Comparator.<Integer, String>comparing(i -> entries.get(i).path())
                .thenComparing(i -> entries.get(i).httpMethodName()));
        for (int i : shared) {
            OperationSite entry = entries.get(i);
            String candidate = entry.serviceSimpleName() + "_" + entry.methodName() + "_" + entry.httpMethodName();
            String id = candidate;
            for (int suffix = 2; taken.contains(id); suffix++) {
                id = candidate + "_" + suffix;
            }
            taken.add(id);
            ids[i] = id;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            result.put(entries.get(i).operation(), ids[i]);
        }
        return result;
    }

    // ---------------------------------------------------------------- type parsing

    /** Parses the canonical source form JavaPoet prints for a {@link TypeName}. */
    private static final class TypeParser {

        private final Function<String, ClassName> classNames;
        private String text;
        private int pos;

        TypeParser(Function<String, ClassName> classNames) {
            this.classNames = classNames;
        }

        TypeName parseNullable(String type) {
            return type == null ? null : parse(type);
        }

        TypeName parse(String type) {
            text = type;
            pos = 0;
            TypeName result = type();
            if (pos != text.length()) {
                throw error();
            }
            return result;
        }

        ClassName className(String qualified) {
            ClassName known = classNames.apply(qualified);
            if (known != null) {
                return known;
            }
            List<String> parts = List.of(qualified.split("\\.", -1));
            int firstClass = 0;
            while (firstClass < parts.size() - 1 && !startsUpper(parts.get(firstClass))) {
                firstClass++;
            }
            String packageName = String.join(".", parts.subList(0, firstClass));
            String[] nested = parts.subList(firstClass + 1, parts.size()).toArray(String[]::new);
            return ClassName.get(packageName, parts.get(firstClass), nested);
        }

        private TypeName type() {
            TypeName type;
            if (peek('?')) {
                pos++;
                if (text.startsWith(" extends ", pos)) {
                    pos += " extends ".length();
                    type = WildcardTypeName.subtypeOf(type());
                } else if (text.startsWith(" super ", pos)) {
                    pos += " super ".length();
                    type = WildcardTypeName.supertypeOf(type());
                } else {
                    type = WildcardTypeName.subtypeOf(Object.class);
                }
                return type;
            }
            String name = qualifiedName();
            if (PRIMITIVES.contains(name)) {
                type = primitive(name);
            } else if (name.indexOf('.') < 0 && classNames.apply(name) == null) {
                type = TypeVariableName.get(name);
            } else {
                type = className(name);
                if (peek('<')) {
                    ParameterizedTypeName parameterized = ParameterizedTypeName.get((ClassName) type, typeArguments());
                    while (peek('.')) {
                        pos++;
                        String nested = identifier();
                        parameterized = peek('<')
                                ? parameterized.nestedClass(nested, List.of(typeArguments()))
                                : parameterized.nestedClass(nested);
                    }
                    type = parameterized;
                }
            }
            while (text.startsWith("[]", pos)) {
                pos += 2;
                type = ArrayTypeName.of(type);
            }
            return type;
        }

        private TypeName[] typeArguments() {
            pos++; // '<'
            List<TypeName> arguments = new ArrayList<>();
            arguments.add(type());
            while (text.startsWith(", ", pos)) {
                pos += 2;
                arguments.add(type());
            }
            if (!peek('>')) {
                throw error();
            }
            pos++;
            return arguments.toArray(TypeName[]::new);
        }

        private String qualifiedName() {
            StringBuilder name = new StringBuilder(identifier());
            while (peek('.') && pos + 1 < text.length() && Character.isJavaIdentifierStart(text.charAt(pos + 1))) {
                pos++;
                name.append('.').append(identifier());
            }
            return name.toString();
        }

        private String identifier() {
            int start = pos;
            if (pos < text.length() && Character.isJavaIdentifierStart(text.charAt(pos))) {
                pos++;
                while (pos < text.length() && Character.isJavaIdentifierPart(text.charAt(pos))) {
                    pos++;
                }
            }
            if (start == pos) {
                throw error();
            }
            return text.substring(start, pos);
        }

        private boolean peek(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }

        private IllegalArgumentException error() {
            return new IllegalArgumentException("Not a canonical type: '" + text + "' (at " + pos + ")");
        }

        private static boolean startsUpper(String part) {
            return !part.isEmpty() && Character.isUpperCase(part.charAt(0));
        }

        private static TypeName primitive(String name) {
            return switch (name) {
                case "boolean" -> TypeName.BOOLEAN;
                case "byte" -> TypeName.BYTE;
                case "short" -> TypeName.SHORT;
                case "int" -> TypeName.INT;
                case "long" -> TypeName.LONG;
                case "char" -> TypeName.CHAR;
                case "float" -> TypeName.FLOAT;
                case "double" -> TypeName.DOUBLE;
                default -> TypeName.VOID;
            };
        }
    }
}

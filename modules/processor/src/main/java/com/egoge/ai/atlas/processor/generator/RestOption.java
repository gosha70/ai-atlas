/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.annotations.AgenticExposed.HttpMethod;
import com.egoge.ai.atlas.annotations.AgenticExposed.RestStyle;
import com.egoge.ai.atlas.annotations.AgenticParam;
import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;
import com.egoge.ai.atlas.processor.util.ReturnedTypes;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spike for epic #23 Phase 5 (§9): the {@code ai.atlas.rest} option, {@code true} or
 * {@code false} in any case, default {@code false}, and the one resolution of an operation's REST
 * mapping, {@link Rest}, that the Contract IR records and the controller, the OpenAPI document and
 * the mapping collision check read.
 *
 * <p>Each of the HTTP method, the path, the status and each parameter's location resolves
 * independently: an explicit declaration, else the CRUD rule the method matches in a
 * {@link RestStyle#CRUD} service, else the RPC rule of today.
 *
 * <p><b>CRUD rules</b>, matched on the method name and the parameter shape only; a scalar is a
 * primitive, its box, {@code String}, an enum, {@code UUID}, {@code BigDecimal} or
 * {@code BigInteger}; an entity is an {@code @AgenticEntity} or a subtype of one:
 * <table>
 *   <tr><th>Method</th><th>Parameters</th><th>Mapping</th><th>Status</th></tr>
 *   <tr><td>{@code findAll}, {@code list}</td><td>none</td><td>{@code GET /<resource>}</td><td>200</td></tr>
 *   <tr><td>{@code findById}, {@code getById}</td><td>scalar {@code p}</td><td>{@code GET /<resource>/{p}}</td><td>200</td></tr>
 *   <tr><td>{@code create}</td><td>entity</td><td>{@code POST /<resource>}, body</td><td>201</td></tr>
 *   <tr><td>{@code update}</td><td>scalar {@code p}, entity</td><td>{@code PUT /<resource>/{p}}, body</td><td>200</td></tr>
 *   <tr><td>{@code delete}, {@code deleteById}</td><td>scalar {@code p}</td><td>{@code DELETE /<resource>/{p}}</td><td>204 when void, else 200</td></tr>
 * </table>
 * Any other method keeps the RPC mapping. Without a rule or declaration, the status is 204 for a
 * {@code void} {@code DELETE} and 200 otherwise.
 *
 * <p>With the option off, the RPC mapping is recorded exactly as before it, with no status and no
 * parameter locations, and any REST declaration is an ERROR.
 */
public final class RestOption {

    /** The processor option. */
    public static final String OPTION = "ai.atlas.rest";

    private static final String PREFIX = "[ai-atlas] ";
    private static final String NOT_DECLARED_PATH = "\0";
    private static final Pattern PATH = Pattern.compile("(/([A-Za-z0-9._~-]+|\\{[A-Za-z_$][A-Za-z0-9_$]*}))*");
    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final Pattern RESOURCE = Pattern.compile("[A-Za-z0-9._~-]+");
    private static final Set<String> SCALARS = Set.of(
            "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
            "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character",
            "java.util.UUID", "java.math.BigDecimal", "java.math.BigInteger");

    /** The 2xx statuses Spring's {@code HttpStatus} names, the only ones a generated controller can declare. */
    private static final Map<Integer, String> SUCCESS_STATUSES = Map.of(
            200, "OK", 201, "CREATED", 202, "ACCEPTED", 203, "NON_AUTHORITATIVE_INFORMATION", 204, "NO_CONTENT",
            205, "RESET_CONTENT", 206, "PARTIAL_CONTENT", 207, "MULTI_STATUS", 208, "ALREADY_REPORTED",
            226, "IM_USED");

    private final boolean enabled;
    private final String option;
    private final Set<String> reportedServices = new HashSet<>();

    private RestOption(boolean enabled, String option) {
        this.enabled = enabled;
        this.option = option;
    }

    /**
     * Reads the option, reporting an ERROR naming it and its value when it is neither
     * {@code true} nor {@code false}.
     *
     * @return the option, or {@code null} after reporting an invalid value
     */
    public static RestOption resolve(String option, ProcessingEnvironment env) {
        String value = env.getOptions().get(option);
        if (value == null || "false".equalsIgnoreCase(value)) {
            return new RestOption(false, option);
        }
        if (!"true".equalsIgnoreCase(value)) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    PREFIX + option + " must be 'true' or 'false'. Got: " + value);
            return null;
        }
        return new RestOption(true, option);
    }

    /** Whether the option is on. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * The REST mapping of an API-channel method.
     *
     * @param service        the service class
     * @param method         the method
     * @param typeAnnotation the service's class-level {@code @AgenticExposed}, or {@code null}
     * @param types          the type utilities
     * @param messager       receives the ERRORs of invalid declarations
     * @return the mapping, or {@code null} after reporting an ERROR
     */
    public Rest map(TypeElement service, ExecutableElement method, AgenticExposed typeAnnotation,
                    Types types, Messager messager) {
        // Read from the annotation mirrors: javac's reflective proxy of a nested annotation value can
        // throw AnnotationTypeMismatchException for declarations generated in a later round
        Decl classRest = typeAnnotation != null ? Decl.of(service) : null;
        Decl methodRest = Decl.of(method);
        String methodName = method.getSimpleName().toString();
        List<? extends VariableElement> params = method.getParameters();
        String rpcPath = "/" + RestControllerGenerator.toKebabCase(methodName);
        String rpcMethod = params.isEmpty() ? "GET" : "POST";

        boolean declared = declaresClassLevel(classRest) || declaresMethodLevel(methodRest)
                || params.stream().anyMatch(p -> declaredIn(p) != AgenticParam.In.DEFAULT);
        if (!enabled) {
            if (declared) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "REST metadata on '" + methodName
                        + "' has no effect unless " + option + "=true: the operation would still be served at "
                        + rpcMethod + " " + rpcPath, method);
                return null;
            }
            String resource = "/" + RestControllerGenerator.toKebabCase(service.getSimpleName().toString());
            return new Rest(rpcMethod, resource + rpcPath);
        }

        boolean valid = validateClassLevel(service, classRest, messager);
        if (methodRest != null && (methodRest.style() != null || methodRest.resource() != null)) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@Rest(style, resource) on method '" + methodName
                    + "' — declare them on the service class", method);
            valid = false;
        }
        String resource = "/" + (classRest != null && classRest.resource() != null && !classRest.resource().isEmpty()
                ? classRest.resource() : RestControllerGenerator.toKebabCase(service.getSimpleName().toString()));
        boolean crud = classRest != null && RestStyle.CRUD.name().equals(classRest.style());
        boolean isVoid = method.getReturnType().getKind() == TypeKind.VOID;

        Convention rule = crud ? convention(methodName, params, isVoid, types) : null;
        String httpMethod = methodRest != null && methodRest.method() != null
                ? methodRest.method() : rule != null ? rule.httpMethod() : rpcMethod;
        String path = methodRest != null && methodRest.path() != null
                ? methodRest.path() : rule != null ? rule.path() : rpcPath;
        boolean conventional = rule != null || declaresMethodLevel(methodRest);

        if (!PATH.matcher(path).matches()) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@Rest(path = \"" + path + "\") on '" + methodName
                    + "' must be empty or '/'-separated segments, each a literal or a {name} variable", method);
            return null;
        }
        Set<String> variables = new LinkedHashSet<>();
        Matcher m = VARIABLE.matcher(path);
        while (m.find()) {
            if (!variables.add(m.group(1))) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Path variable {" + m.group(1) + "} of '"
                        + methodName + "' appears more than once", method);
                valid = false;
            }
        }

        List<String> in = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (VariableElement param : params) {
            String name = param.getSimpleName().toString();
            names.add(name);
            AgenticParam.In declaredIn = declaredIn(param);
            String location;
            if (declaredIn != AgenticParam.In.DEFAULT) {
                location = declaredIn.name();
            } else if (variables.contains(name)) {
                location = Rest.PATH;
            } else if (conventional && ReturnedTypes.entityOf(param.asType(), types) != null) {
                location = Rest.BODY;
            } else {
                location = Rest.QUERY;
            }
            if (Rest.PATH.equals(location) && !variables.contains(name)) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Parameter '" + name + "' of '" + methodName
                        + "' is in the path, but the path " + quote(resource + path) + " has no {" + name + "}", param);
                valid = false;
            }
            if (!Rest.PATH.equals(location) && variables.contains(name)) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Parameter '" + name + "' of '" + methodName
                        + "' is named by the path variable {" + name + "} but declared in " + location, param);
                valid = false;
            }
            if (Rest.PATH.equals(location) && !scalar(param.asType())) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Path parameter '" + name + "' of '"
                        + methodName + "' must be a scalar (a primitive, its box, String, an enum, UUID,"
                        + " BigDecimal or BigInteger), not " + param.asType(), param);
                valid = false;
            }
            in.add(location);
        }
        for (String variable : variables) {
            if (!names.contains(variable)) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Path variable {" + variable + "} of '"
                        + methodName + "' names no parameter", method);
                valid = false;
            }
        }
        long bodies = in.stream().filter(Rest.BODY::equals).count();
        if (bodies > 1) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "'" + methodName + "' has " + bodies
                    + " body parameters — at most one parameter may be in the body", method);
            valid = false;
        }
        if (bodies > 0 && ("GET".equals(httpMethod) || "DELETE".equals(httpMethod))) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + httpMethod + " '" + methodName
                    + "' must not have a request body — declare the parameter in the path or query, or use"
                    + " another HTTP method", method);
            valid = false;
        }

        Integer status;
        if (methodRest != null && methodRest.status() != null) {
            status = methodRest.status();
        } else if (rule != null && rule.httpMethod().equals(httpMethod)) {
            // The rule's status holds only while its HTTP method does: an explicit method drops it
            status = rule.status();
        } else {
            status = isVoid && "DELETE".equals(httpMethod) ? 204 : Rest.DEFAULT_STATUS;
        }
        if (!SUCCESS_STATUSES.containsKey(status)) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@Rest(status = " + status + ") on '" + methodName
                    + "' must be a 2xx success status, one of " + new TreeSet<>(SUCCESS_STATUSES.keySet()), method);
            valid = false;
        } else if (status == 204 && !isVoid) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "'" + methodName + "' returns "
                    + method.getReturnType() + " but its status is 204 No Content, which carries no body", method);
            valid = false;
        }
        return valid ? new Rest(httpMethod, resource + path, status, in) : null;
    }

    /**
     * @param status a status {@link #map} accepted
     * @return its {@code org.springframework.http.HttpStatus} constant name
     */
    public static String httpStatusName(int status) {
        return SUCCESS_STATUSES.get(status);
    }

    /** A matched CRUD rule. */
    private record Convention(String httpMethod, String path, int status) {
    }

    private static Convention convention(String name, List<? extends VariableElement> params, boolean isVoid,
                                         Types types) {
        int n = params.size();
        switch (name) {
            case "findAll", "list" -> {
                return n == 0 ? new Convention("GET", "", 200) : null;
            }
            case "findById", "getById" -> {
                return n == 1 && scalar(params.get(0).asType())
                        ? new Convention("GET", "/{" + params.get(0).getSimpleName() + "}", 200) : null;
            }
            case "create" -> {
                return n == 1 && ReturnedTypes.entityOf(params.get(0).asType(), types) != null
                        ? new Convention("POST", "", 201) : null;
            }
            case "update" -> {
                return n == 2 && scalar(params.get(0).asType())
                        && ReturnedTypes.entityOf(params.get(1).asType(), types) != null
                        ? new Convention("PUT", "/{" + params.get(0).getSimpleName() + "}", 200) : null;
            }
            case "delete", "deleteById" -> {
                return n == 1 && scalar(params.get(0).asType())
                        ? new Convention("DELETE", "/{" + params.get(0).getSimpleName() + "}", isVoid ? 204 : 200)
                        : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** Validates the class-level {@code @Rest} once per service; the result is repeated for each method. */
    private boolean validateClassLevel(TypeElement service, Decl rest, Messager messager) {
        if (rest == null) {
            return true;
        }
        boolean valid = true;
        boolean report = reportedServices.add(service.getQualifiedName().toString());
        if (declaresMethodLevel(rest)) {
            if (report) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@Rest(method, path, status) on class "
                        + service.getSimpleName() + " — declare them on each method", service);
            }
            valid = false;
        }
        if (rest.resource() != null && !RESOURCE.matcher(rest.resource()).matches()) {
            if (report) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@Rest(resource = \"" + rest.resource()
                        + "\") on " + service.getSimpleName() + " must be one path segment of letters, digits,"
                        + " '.', '_', '~' or '-'", service);
            }
            valid = false;
        }
        return valid;
    }

    private static boolean declaresClassLevel(Decl rest) {
        return rest != null && (rest.style() != null || rest.resource() != null || declaresMethodLevel(rest));
    }

    private static boolean declaresMethodLevel(Decl rest) {
        return rest != null && (rest.method() != null || rest.path() != null || rest.status() != null);
    }

    /**
     * The explicitly declared attributes of an element's {@code @AgenticExposed(rest = @Rest(...))},
     * each {@code null} when not declared or declared as its "derive" default.
     */
    private record Decl(String method, String path, Integer status, String style, String resource) {

        /** The declaration on {@code element}, or {@code null} when it declares no {@code rest}. */
        static Decl of(Element element) {
            for (AnnotationMirror exposed : element.getAnnotationMirrors()) {
                if (!((TypeElement) exposed.getAnnotationType().asElement()).getQualifiedName()
                        .contentEquals(AgenticExposed.class.getCanonicalName())) {
                    continue;
                }
                for (var entry : exposed.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("rest")) {
                        return of((AnnotationMirror) entry.getValue().getValue());
                    }
                }
            }
            return null;
        }

        private static Decl of(AnnotationMirror rest) {
            Map<String, Object> values = new HashMap<>();
            rest.getElementValues().forEach((k, v) -> values.put(k.getSimpleName().toString(),
                    v.getValue() instanceof VariableElement constant ? constant.getSimpleName().toString() : v.getValue()));
            String method = (String) values.get("method");
            String path = (String) values.get("path");
            Integer status = (Integer) values.get("status");
            String style = (String) values.get("style");
            return new Decl(HttpMethod.UNSET.name().equals(method) ? null : method,
                    NOT_DECLARED_PATH.equals(path) ? null : path, status != null && status == 0 ? null : status,
                    RestStyle.INHERIT.name().equals(style) ? null : style, (String) values.get("resource"));
        }
    }

    private static AgenticParam.In declaredIn(VariableElement param) {
        AgenticParam annotation = param.getAnnotation(AgenticParam.class);
        return annotation != null ? annotation.in() : AgenticParam.In.DEFAULT;
    }

    /** A primitive, its box, {@code String}, an enum, {@code UUID}, {@code BigDecimal} or {@code BigInteger}. */
    static boolean scalar(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return true;
        }
        if (!(type instanceof DeclaredType declared)) {
            return false;
        }
        TypeElement element = (TypeElement) declared.asElement();
        return element.getKind() == ElementKind.ENUM || SCALARS.contains(element.getQualifiedName().toString());
    }

    private static String quote(String path) {
        return "\"" + path + "\"";
    }
}

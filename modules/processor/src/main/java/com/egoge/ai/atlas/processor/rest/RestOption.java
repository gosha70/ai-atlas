/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.rest;

import com.egoge.ai.atlas.annotations.AgenticParam;
import com.egoge.ai.atlas.processor.contract.ContractProjection;
import com.egoge.ai.atlas.processor.generator.RestControllerGenerator;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.util.FieldScanner;
import com.egoge.ai.atlas.processor.util.VersionSelector;
import com.palantir.javapoet.ClassName;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code ai.atlas.rest} option of a compilation, {@code true} or {@code false} in any case,
 * default {@code false}, and the one resolution of each API operation's REST mapping, a
 * {@link RestOperation}, that the Contract IR records and the controller, the OpenAPI document, the
 * route collision check and the deprecation manifest read.
 *
 * <p>Each of the HTTP method, the path, the status and each parameter's location resolves on its
 * own: an explicit {@code @Rest} or {@code @AgenticParam(in)}, else the CRUD rule the method
 * matches in a {@code RestStyle.CRUD} service, else the RPC mapping. The resource is
 * {@code @Rest(resource)}, else the service's kebab-case name.
 *
 * <p><b>CRUD rules</b>, matched on the method name and the parameter shape; a scalar is a
 * primitive, its box, {@code String}, an enum, {@code UUID}, {@code BigDecimal} or
 * {@code BigInteger}, and an entity is an {@code @AgenticEntity} or a subtype of one:
 * <table>
 *   <caption>CRUD rules</caption>
 *   <tr><th>Method</th><th>Parameters</th><th>Mapping</th><th>Status</th></tr>
 *   <tr><td>{@code findAll}, {@code list}</td><td>none</td><td>{@code GET /<resource>}</td><td>200</td></tr>
 *   <tr><td>{@code findById}, {@code getById}</td><td>scalar {@code p}</td><td>{@code GET /<resource>/{p}}</td><td>200</td></tr>
 *   <tr><td>{@code create}</td><td>entity</td><td>{@code POST /<resource>}, body</td><td>201</td></tr>
 *   <tr><td>{@code update}</td><td>scalar {@code p}, entity</td><td>{@code PUT /<resource>/{p}}, body</td><td>200</td></tr>
 *   <tr><td>{@code delete}, {@code deleteById}</td><td>scalar {@code p}</td><td>{@code DELETE /<resource>/{p}}</td><td>204 when void, else 200</td></tr>
 * </table>
 * A rule's status holds only while the rule's HTTP method is the effective one. Without a declared
 * or rule status, a {@code void} DELETE answers 204 and every other operation 200.
 *
 * <p>A parameter's location: an explicit {@code in}; else the path when a {@code {name}} segment
 * names it; else the body for an {@code @AgenticEntity} of an explicit or CRUD mapping; else the
 * query. An operation on the RPC mapping never has a body. An entity body binds a generated,
 * whitelisted {@link InputRecord} instead of the entity.
 *
 * <p>With the option off, every operation keeps the RPC mapping, and any REST declaration is an
 * ERROR naming the route that is still served.
 */
public final class RestOption {

    /** The API channel. */
    private static final String API = "API";
    private static final String PREFIX = "[ai-atlas] ";
    // A constant string, not the annotation enum: initialising the processor loads no annotation class
    private static final String CRUD = "CRUD";
    private static final Pattern PATH = Pattern.compile("(/([A-Za-z0-9._~-]+|\\{[A-Za-z_$][A-Za-z0-9_$]*}))*");
    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final Pattern RESOURCE = Pattern.compile("[A-Za-z0-9._~-]+");

    /** What {@link #resolve(TypeElement, ExecutableElement, Set, Map)} returns for an invalid method. */
    public static final RestOperation INVALID = new RestOperation("", "", "", 0, List.of(), null);

    private final boolean enabled;
    private final String option;
    private final ProcessingEnvironment env;
    private final String apiBasePath;
    private final int apiMajor;
    private final InputRecords inputs;
    /** The services whose class-level declaration has been validated, and whether it was valid. */
    private final Map<String, Boolean> validatedServices = new HashMap<>();
    /** The resolved mapping of each recorded API operation, by its Contract IR identity. */
    private final Map<String, RestOperation> operations = new HashMap<>();

    private RestOption(boolean enabled, String option, ProcessingEnvironment env, String apiBasePath, int apiMajor,
                       BiFunction<String, String, List<String>> channels, boolean directHints) {
        this.enabled = enabled;
        this.option = option;
        this.env = env;
        this.apiBasePath = apiBasePath;
        this.apiMajor = apiMajor;
        this.inputs = new InputRecords(enabled, option, env, apiMajor, channels, directHints);
    }

    /**
     * Reads the option, reporting an ERROR naming it and its value when it is neither
     * {@code true} nor {@code false}.
     *
     * @param option      the option name
     * @param env         the processing environment
     * @param apiBasePath the configured REST base path, for the routes diagnostics name
     * @param apiMajor    the configured major
     * @param channels    a field's effective channels, sorted, by entity class name and field name
     * @param directHints whether a direct field's {@code @AgenticField(type)} naming an entity makes
     *                    the field refer to it, as with {@code ai.atlas.projections=true}
     * @return the option, or {@code null} after reporting an invalid value
     */
    public static RestOption resolve(String option, ProcessingEnvironment env, String apiBasePath, int apiMajor,
                                     BiFunction<String, String, List<String>> channels, boolean directHints) {
        String value = env.getOptions().get(option);
        if (value == null || "false".equalsIgnoreCase(value)) {
            return new RestOption(false, option, env, apiBasePath, apiMajor, channels, directHints);
        }
        if (!"true".equalsIgnoreCase(value)) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    PREFIX + option + " must be 'true' or 'false'. Got: " + value);
            return null;
        }
        return new RestOption(true, option, env, apiBasePath, apiMajor, channels, directHints);
    }

    /** Whether the option is on. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Records how a request body may set each of an entity's fields. An
     * {@code @AgenticField(input = false)} with the option off is a WARNING, as it has no effect.
     *
     * @param entity  the {@code @AgenticEntity} class
     * @param scanned the entity's recorded {@code @AgenticField} fields
     */
    public void recordEntity(TypeElement entity, List<FieldScanner.ScannedField> scanned) {
        inputs.recordEntity(entity, scanned);
    }

    // ---------------------------------------------------------------- resolution

    /**
     * Resolves the REST mapping of an exposed method: {@link #map} on the API channel, else
     * {@link #checkUnmapped} and no mapping.
     *
     * @param service  the service class
     * @param method   the method
     * @param channels the method's resolved channels
     * @param registry every registered entity, projected at the configured major
     * @return the mapping, {@code null} for a method off the API channel, or {@link #INVALID}
     *         after reporting an ERROR
     */
    public RestOperation resolve(TypeElement service, ExecutableElement method, Set<String> channels,
                                 Map<String, EntityModel> registry) {
        if (!channels.contains(API)) {
            checkUnmapped(method);
            return null;
        }
        RestOperation operation = map(service, method, registry);
        return operation != null ? operation : INVALID;
    }

    /**
     * Resolves the REST mapping of a method on the API channel, reporting each invalid declaration
     * as an ERROR on the method, parameter, class or field it concerns.
     *
     * @param service  the service class
     * @param method   the method
     * @param registry every registered entity, projected at the configured major
     * @return the mapping, or {@code null} after reporting an ERROR
     */
    private RestOperation map(TypeElement service, ExecutableElement method, Map<String, EntityModel> registry) {
        RestDeclaration classRest = RestDeclaration.of(service);
        RestDeclaration methodRest = RestDeclaration.of(method);
        String methodName = method.getSimpleName().toString();
        List<? extends VariableElement> params = method.getParameters();
        String serviceKebab = RestControllerGenerator.toKebabCase(service.getSimpleName().toString());
        RestOperation rpc = RestOperation.rpc(serviceKebab, RestControllerGenerator.toKebabCase(methodName),
                params.size());
        Messager messager = env.getMessager();
        if (!enabled) {
            if (classRest != null || methodRest != null || declaresIn(params)) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "REST metadata on '" + methodName
                        + "' requires " + option + "=true. Without it the operation is still served at "
                        + route(rpc.httpMethod(), rpc.fullPath()) + ". Turn the option on"
                        + " (agentic { rest = true } in Gradle) or remove the declaration", method);
                return null;
            }
            return rpc;
        }

        boolean valid = validateClassLevel(service, classRest);
        if (methodRest != null && methodRest.declaresClassLevel()) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@Rest(style, resource) on method '" + methodName
                    + "' — declare them on the service class", method);
            valid = false;
        }
        String resource = classRest != null && classRest.resource() != null ? classRest.resource() : serviceKebab;
        boolean isVoid = method.getReturnType().getKind() == TypeKind.VOID;
        CrudRules.Rule rule = classRest != null && CRUD.equals(classRest.style())
                ? CrudRules.match(methodName, params, isVoid, env.getTypeUtils()) : null;
        boolean explicit = methodRest != null && methodRest.declaresMethodLevel();
        String httpMethod = explicit && methodRest.method() != null ? methodRest.method()
                : rule != null ? rule.httpMethod() : rpc.httpMethod();
        String path = explicit && methodRest.path() != null ? methodRest.path()
                : rule != null ? rule.path() : rpc.path();
        // An explicit or CRUD mapping; otherwise the operation is on the RPC mapping, which has no body
        boolean mapped = rule != null || explicit;

        if (!PATH.matcher(path).matches()) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@Rest(path = \"" + path + "\") on '" + methodName
                    + "' must be empty or '/'-separated segments, each a literal of letters, digits, '.', '_', '~'"
                    + " or '-', or a {name} variable", method);
            return null;
        }
        String route = route(httpMethod, "/" + resource + path);
        Set<String> variables = new LinkedHashSet<>();
        Matcher m = VARIABLE.matcher(path);
        while (m.find()) {
            if (!variables.add(m.group(1))) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Path variable {" + m.group(1) + "} of '"
                        + methodName + "' appears more than once in " + route, method);
                valid = false;
            }
        }

        List<String> in = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (VariableElement param : params) {
            String name = param.getSimpleName().toString();
            names.add(name);
            AgenticParam.In declared = RestDeclaration.in(param);
            String location;
            if (declared != AgenticParam.In.DEFAULT) {
                location = declared.name();
            } else if (variables.contains(name)) {
                location = RestOperation.PATH;
            } else if (mapped && CrudRules.entity(param.asType(), env.getTypeUtils())) {
                location = RestOperation.BODY;
            } else {
                location = RestOperation.QUERY;
            }
            valid &= validateLocation(param, methodName, location, variables, mapped, route);
            in.add(location);
        }
        for (String variable : variables) {
            if (!names.contains(variable)) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Path variable {" + variable + "} of '"
                        + methodName + "' names no parameter of it", method);
                valid = false;
            }
        }

        ClassName inputRecord = null;
        long bodies = in.stream().filter(RestOperation.BODY::equals).count();
        if (bodies > 1) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "'" + methodName + "' has " + bodies
                    + " body parameters — at most one parameter may be in the body", method);
            valid = false;
        } else if (bodies == 1) {
            if (RestOperation.GET.equals(httpMethod) || RestOperation.DELETE.equals(httpMethod)) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + httpMethod + " '" + methodName
                        + "' must not have a request body — declare the parameter in the path or query, or use"
                        + " another HTTP method", method);
                valid = false;
            }
            VariableElement body = params.get(in.indexOf(RestOperation.BODY));
            InputRecords.Body checked = inputs.checkBody(body, methodName, registry);
            valid &= checked.valid();
            inputRecord = checked.inputRecord();
        }

        int status;
        if (explicit && methodRest.status() != null) {
            status = methodRest.status();
        } else if (rule != null && rule.httpMethod().equals(httpMethod)) {
            // A rule's status holds only while its HTTP method does: an explicit method drops it
            status = rule.status();
        } else {
            status = isVoid && RestOperation.DELETE.equals(httpMethod)
                    ? RestOperation.NO_CONTENT : RestOperation.DEFAULT_STATUS;
        }
        if (!RestOperation.SUCCESS_STATUSES.containsKey(status)) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@Rest(status = " + status + ") on '" + methodName
                    + "' must be a 2xx success status Spring's HttpStatus names, one of "
                    + new TreeSet<>(RestOperation.SUCCESS_STATUSES.keySet()), method);
            valid = false;
        } else if (status == RestOperation.NO_CONTENT && !isVoid) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "'" + methodName + "' returns "
                    + method.getReturnType() + " but its status is 204 No Content, which carries no body."
                    + " Declare another status, or return void", method);
            valid = false;
        }
        return valid ? new RestOperation(httpMethod, resource, path, status, in, inputRecord) : null;
    }

    /**
     * Warns about REST metadata on a method that is not on the API channel, which has no REST
     * mapping; with the option off, it is an ERROR like any other REST declaration.
     *
     * @param method the method, exposed on the AI channel only
     */
    private void checkUnmapped(ExecutableElement method) {
        if (RestDeclaration.of(method) == null && !declaresIn(method.getParameters())) {
            return;
        }
        String methodName = method.getSimpleName().toString();
        if (!enabled) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + "REST metadata on '" + methodName
                    + "' requires " + option + "=true, and has no effect: '" + methodName + "' is not on the API"
                    + " channel. Remove the declaration", method);
            return;
        }
        env.getMessager().printMessage(Diagnostic.Kind.WARNING, PREFIX + "REST metadata on '" + methodName
                + "' has no effect: it is not on the API channel, so it has no REST mapping. Add API to its"
                + " @AgenticExposed(channels), or remove the declaration", method);
    }

    /** Whether any parameter declares an {@code @AgenticParam(in)}. */
    private static boolean declaresIn(List<? extends VariableElement> params) {
        return params.stream().anyMatch(p -> RestDeclaration.in(p) != AgenticParam.In.DEFAULT);
    }

    /** An ERROR on the parameter for each way its location contradicts the path or the mapping. */
    private boolean validateLocation(VariableElement param, String methodName, String location, Set<String> variables,
                                     boolean mapped, String route) {
        String name = param.getSimpleName().toString();
        Messager messager = env.getMessager();
        boolean valid = true;
        if (RestOperation.PATH.equals(location) && !variables.contains(name)) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Parameter '" + name + "' of '" + methodName
                    + "' is in the path, but " + route + " has no {" + name + "}", param);
            valid = false;
        }
        if (!RestOperation.PATH.equals(location) && variables.contains(name)) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Parameter '" + name + "' of '" + methodName
                    + "' is named by the path variable {" + name + "} but declared in the " + lower(location), param);
            valid = false;
        }
        if (RestOperation.PATH.equals(location) && !CrudRules.scalar(param.asType())) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Path parameter '" + name + "' of '"
                    + methodName + "' must be a scalar (" + CrudRules.SCALAR_KINDS + "), not " + param.asType(), param);
            valid = false;
        }
        if (RestOperation.BODY.equals(location) && !mapped) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Parameter '" + name + "' of '" + methodName
                    + "' is declared in the body, but '" + methodName + "' is on the RPC mapping, which carries"
                    + " every parameter in the query. Declare @Rest(method, path) on it to give it a request body",
                    param);
            valid = false;
        }
        return valid;
    }

    /** Validates the class-level declaration once per service; the outcome is repeated for each method. */
    private boolean validateClassLevel(TypeElement service, RestDeclaration rest) {
        if (rest == null) {
            return true;
        }
        return validatedServices.computeIfAbsent(service.getQualifiedName().toString(), key -> {
            boolean valid = true;
            if (rest.declaresMethodLevel()) {
                env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + "@Rest(method, path, status) on class "
                        + service.getSimpleName() + " — declare them on each method", service);
                valid = false;
            }
            if (rest.resource() != null && !RESOURCE.matcher(rest.resource()).matches()) {
                env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + "@Rest(resource = \"" + rest.resource()
                        + "\") on " + service.getSimpleName() + " must be one path segment of letters, digits,"
                        + " '.', '_', '~' or '-'", service);
                valid = false;
            }
            return valid;
        });
    }

    // ---------------------------------------------------------------- the resolved model

    /**
     * Records the mapping {@link #map} resolved for an operation the Contract IR recorded.
     *
     * @param operationId the operation's IR identity
     * @param operation   its mapping, or {@code null} when it is not on the API channel
     */
    public void record(String operationId, RestOperation operation) {
        if (operation != null) {
            operations.put(operationId, operation);
        }
    }

    /**
     * @param operationKey an API operation's IR identity, {@link ContractProjection#operationKey}
     * @return its resolved mapping
     * @throws IllegalStateException when no mapping was recorded for it
     */
    public RestOperation operation(String operationKey) {
        RestOperation operation = operations.get(operationKey);
        if (operation == null) {
            throw new IllegalStateException("No REST mapping resolved for " + operationKey);
        }
        return operation;
    }

    /**
     * Generates the input record of each entity a request body of the service's active API
     * operations binds, once per compilation.
     *
     * @param model the service, projected at the configured major
     */
    public void generateInputRecords(ServiceModel model) {
        for (MethodModel method : model.methods()) {
            if (!method.channels().contains(API) || !VersionSelector.isActive(method, apiMajor)) {
                continue;
            }
            ClassName name = operation(ContractProjection.operationKey(model.serviceClassName(), method)).inputRecord();
            if (name != null) {
                inputs.generate(name);
            }
        }
    }

    /** The input records generated so far, by qualified name. */
    public Collection<InputRecord> generatedInputRecords() {
        return inputs.generated();
    }

    private String route(String httpMethod, String fullPath) {
        return httpMethod + " " + apiBasePath + "/v" + apiMajor + fullPath;
    }

    private static String lower(String location) {
        return location.toLowerCase(Locale.ROOT);
    }
}

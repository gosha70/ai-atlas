/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.annotations.AgenticParam;
import com.egoge.ai.atlas.annotations.Paging;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.generator.PagingContract.Envelope;
import com.egoge.ai.atlas.processor.generator.PagingContract.Style;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.rest.RestOperation;
import com.egoge.ai.atlas.processor.util.VersionSelector;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@code ai.atlas.collections} option of a compilation: {@code true} or {@code false} in any
 * case, default {@code false}. With it on, every exposed method returning a collection, iterable,
 * array, {@code Stream} or {@code Map} is classified:
 *
 * <pre>
 * collection return
 *   ├─ a Spring Data Pageable parameter        → PAGEABLE: page/size inputs; a Page or Slice keeps its metadata
 *   ├─ @AgenticParam(paging = LIMIT)           → LIMIT: a limit the service honours
 *   ├─ @AgenticExposed(maxResults = N)         → DECLARED: a known-small result
 *   └─ otherwise                               → WARNING; an ERROR under ai.atlas.strict for the AI channel
 * </pre>
 *
 * <p>Nothing is synthesised: a wrapper exposes page or limit inputs only where the service accepts
 * them, and never truncates a result. Spring Data types are read by name, so the processor has no
 * dependency on Spring Data.
 *
 * <p>With it off, generation is exactly what it was before this option. A declared
 * {@code maxResults}, paging role or {@code sortable} is an ERROR, as it would silently do nothing,
 * and an exposed {@code Pageable} a WARNING, as its wrappers cannot bind it.
 */
public final class CollectionsOption {

    /** The option's name. */
    public static final String OPTION = "ai.atlas.collections";

    /** Spring Data's {@code Pageable}, which a paged operation takes. */
    public static final String PAGEABLE = PagingContract.DATA_PACKAGE + ".Pageable";
    private static final String PAGE = PagingContract.DATA_PACKAGE + ".Page";
    private static final String SLICE = PagingContract.DATA_PACKAGE + ".Slice";
    private static final Set<String> PAGING_INPUTS = Set.of(PagingContract.PAGE_PARAM, PagingContract.SIZE_PARAM,
            PagingContract.SORT_PARAM);
    private static final String AI = "AI";
    private static final String PREFIX = "[ai-atlas] ";

    private final boolean enabled;
    private final boolean constraints;
    private final ProcessingEnvironment env;
    private final ReturnShapes shapes;
    /** The paging contract of each operation that has one, by IR operation identity. */
    private final Map<String, PagingContract> contracts = new HashMap<>();
    /** The effective result bound of each operation that has a paging contract, by IR operation identity. */
    private final Map<String, ContractIr.Bound> bounds = new HashMap<>();
    /** The services whose class-level {@code maxResults} has been reported. */
    private final Set<String> reportedServices = new HashSet<>();

    private CollectionsOption(boolean enabled, boolean constraints, ProcessingEnvironment env) {
        this.enabled = enabled;
        this.constraints = constraints;
        this.env = env;
        this.shapes = new ReturnShapes(env);
    }

    /**
     * Reads the option, reporting an ERROR naming it and its value when it is neither
     * {@code true} nor {@code false}.
     *
     * @param option      the option name
     * @param constraints whether {@code ai.atlas.constraints} is on, so a {@code @Size(max)} on the
     *                    method is read and a {@code LIMIT} needs a maximum
     * @param env         the processing environment
     * @return the option, or {@code null} after reporting an invalid value
     */
    public static CollectionsOption resolve(String option, boolean constraints, ProcessingEnvironment env) {
        String value = env.getOptions().get(option);
        if (value == null || "false".equalsIgnoreCase(value)) {
            return new CollectionsOption(false, constraints, env);
        }
        if (!"true".equalsIgnoreCase(value)) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    PREFIX + option + " must be 'true' or 'false'. Got: " + value);
            return null;
        }
        return new CollectionsOption(true, constraints, env);
    }

    /** Whether the option is on. */
    public boolean enabled() {
        return enabled;
    }

    /** The paging contracts by IR operation identity, or {@code null} when the option is off. */
    public Map<String, PagingContract> contracts() {
        return enabled ? Collections.unmodifiableMap(contracts) : null;
    }

    /**
     * Classifies an exposed method's return, records its paging contract and reports what it
     * finds. With the option off, reports only the declarations it would ignore and an exposed
     * {@code Pageable}.
     *
     * @param irOperation the method's recorded IR operation
     * @param qualityKind WARNING, or ERROR under {@code ai.atlas.strict}
     * @param apiEntities the entities as the REST DTOs project them, read only for {@code sortable}
     * @param parameterIn each parameter's location in the operation's resolved REST mapping, as
     *                    {@code ai.atlas.rest} decides it, or {@code null} for an operation off the
     *                    API channel, which has none
     */
    public void check(TypeElement serviceType, ExecutableElement method, MethodModel model,
                      AgenticExposed typeAnnotation, ContractIr.Operation irOperation, Diagnostic.Kind qualityKind,
                      int apiMajor, Supplier<List<EntityModel>> apiEntities, List<String> parameterIn) {
        Messager messager = env.getMessager();
        String where = serviceType.getQualifiedName() + "#" + model.methodName();
        List<? extends VariableElement> params = method.getParameters();
        // Read as written, so an explicit maxResults = -1 is not taken for the default
        Integer maxResults = ReturnShapes.explicitMaxResults(method);
        if (typeAnnotation != null && ReturnShapes.explicitMaxResults(serviceType) != null
                && reportedServices.add(serviceType.getQualifiedName().toString())) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@AgenticExposed(maxResults) on "
                    + serviceType.getQualifiedName() + " must be declared on each method, not the class: a bound"
                    + " describes one operation's result", serviceType);
        }
        int pageable = pageable(params, where);
        if (!enabled) {
            reportIgnored(where, method, maxResults, params);
            if (pageable != -1 && VersionSelector.isActive(model, apiMajor)) {
                messager.printMessage(Diagnostic.Kind.WARNING, PREFIX + where + " takes a Spring Data Pageable,"
                        + " which its generated wrappers cannot bind with " + OPTION + " off: the REST endpoint"
                        + " answers 400 and the MCP tool cannot be called. Set " + OPTION + "=true", method);
            }
            return;
        }
        int limit = role(params, Paging.LIMIT, where);
        int cursor = role(params, Paging.CURSOR, where);
        boolean valid = limit != -2 && cursor != -2 && pageable != -2 && checkSortable(params, pageable, where);
        TypeMirror returned = method.getReturnType();
        // An Optional of a collection is a collection: present, it holds every element
        TypeMirror shape = ReturnShapes.optionalContent(returned);
        boolean collection = shapes.collection(shape);
        int bound = bound(method, maxResults, where);
        if (!valid || bound == -2
                || parameterIn != null && !checkLocations(where, params, parameterIn, pageable, limit, cursor)) {
            return;
        }
        if (!collection) {
            if (bound != -1 || limit != -1 || cursor != -1) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares maxResults or a paging"
                        + " role, but returns " + returned + ", which is not a collection", method);
            } else if (pageable >= 0
                    // The wrappers bind the same page, size and sort inputs as for a collection
                    && checkRoles(where, method, params, pageable, limit, cursor, bound)
                    && checkSortableProperties(params.get(pageable), model, where, apiEntities)) {
                // Still bound as page and size, so the wrappers can be called; nothing is bounded
                record(irOperation.id(), contract(Style.NONE, Envelope.NONE, pageable, -1, -1, irOperation,
                        -1, params, returned), params);
            }
            return;
        }
        if (!checkRoles(where, method, params, pageable, limit, cursor, bound)) {
            return;
        }
        if (bound != -1 && pageable < 0 && shapes.assignable(shape, ReturnShapes.BASE_STREAM)) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares a bound of " + bound
                    + " on a Stream result, which ai-atlas can neither publish, as the stream has no schema, nor"
                    + " check, as counting would consume it. Return a List, or take a Pageable", method);
            return;
        }
        Envelope envelope = shapes.assignable(returned, PAGE) ? Envelope.PAGE
                : shapes.assignable(returned, SLICE) ? Envelope.SLICE : Envelope.NONE;
        Style style = pageable >= 0 ? Style.PAGEABLE : limit >= 0 ? Style.LIMIT
                : bound != -1 ? Style.DECLARED : Style.NONE;
        if (pageable >= 0 && !checkSortableProperties(params.get(pageable), model, where, apiEntities)) {
            return;
        }
        if (style != Style.NONE || envelope != Envelope.NONE || cursor >= 0) {
            record(irOperation.id(), contract(style, envelope, pageable, limit, cursor, irOperation, bound,
                    params, returned), params);
        }
        if (!VersionSelector.isActive(model, apiMajor)) {
            return;
        }
        Diagnostic.Kind kind = model.channels().contains(AI) ? qualityKind : Diagnostic.Kind.WARNING;
        if (style == Style.NONE) {
            String cursorNote = cursor != -1 ? " Its CURSOR parameter bounds nothing without a LIMIT." : "";
            messager.printMessage(kind, PREFIX + where + " returns " + returned + " on channels "
                    + model.channels().stream().sorted().toList() + " with no paging contract and no declared"
                    + " bound, so one call can return the whole result set." + cursorNote + " Take a Spring Data"
                    + " Pageable, mark a limit the service honours with @AgenticParam(paging = LIMIT), or declare"
                    + " @AgenticExposed(maxResults = N) when the result is known to be small", method);
        } else if (style == Style.PAGEABLE && bound == -1) {
            // An unbounded input, like a LIMIT without a maximum: MCP accepts any int page size
            messager.printMessage(kind, PREFIX + where + " takes a Pageable with no page-size ceiling, so a client"
                    + " can ask for the whole result set in one page. Declare the largest page size with"
                    + " @AgenticExposed(maxResults = N)", params.get(pageable));
        } else if (style == Style.LIMIT && constraints
                && irOperation.parameters().get(limit).constraints().maximum() == null) {
            messager.printMessage(kind, PREFIX + where + " declares paging = LIMIT on '"
                    + params.get(limit).getSimpleName() + "', which has no maximum, so a client can ask for the"
                    + " whole result set. Declare its ceiling with @Max", params.get(limit));
        }
    }

    private void record(String operationId, PagingContract contract, List<? extends VariableElement> params) {
        contracts.put(operationId, contract);
        String style = contract.style().name();
        String limitParameter = contract.style() == Style.PAGEABLE ? name(params, contract.pageable())
                : contract.style() == Style.LIMIT ? name(params, contract.limit()) : null;
        String cursorParameter = contract.style() == Style.LIMIT ? name(params, contract.cursor()) : null;
        // A LIMIT's ceiling is its parameter's own maximum, which the IR records with its constraints
        Integer maxResults = contract.style() == Style.PAGEABLE ? contract.pageSizeCeiling() : contract.resultBound();
        bounds.put(operationId, new ContractIr.Bound(style, contract.envelope().name(), limitParameter,
                cursorParameter, maxResults));
    }

    private static String name(List<? extends VariableElement> params, int index) {
        return index >= 0 ? params.get(index).getSimpleName().toString() : null;
    }

    /**
     * The effective bound on an operation's result, as the Contract IR records it:
     * {@link ContractIr.Bound#NONE} with the option off, or when the operation has no paging
     * contract, envelope or declared bound.
     *
     * @param operationId the operation's IR identity
     */
    public ContractIr.Bound bound(String operationId) {
        return enabled ? bounds.getOrDefault(operationId, ContractIr.Bound.NONE) : ContractIr.Bound.NONE;
    }

    private PagingContract contract(Style style, Envelope envelope, int pageable, int limit, int cursor,
                                    ContractIr.Operation irOperation, int bound, List<? extends VariableElement> params,
                                    TypeMirror returned) {
        List<String> sortable = pageable >= 0 ? sortable(params.get(pageable)) : List.of();
        return new PagingContract(style, envelope, pageable, limit, cursor,
                cursor >= 0 && !irOperation.parameters().get(cursor).required(), bound, sortable,
                envelope != Envelope.NONE ? ReturnShapes.elementType(returned) : null);
    }

    /** With the option off: an ERROR on each declaration that would silently do nothing. */
    private void reportIgnored(String where, ExecutableElement method, Integer maxResults,
                               List<? extends VariableElement> params) {
        String off = " requires " + OPTION + "=true; with it off the declaration would silently do nothing";
        if (maxResults != null) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    PREFIX + "@AgenticExposed(maxResults) on " + where + off, method);
        }
        for (VariableElement param : params) {
            AgenticParam annotation = param.getAnnotation(AgenticParam.class);
            if (annotation != null && annotation.paging() != Paging.NONE) {
                env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + "@AgenticParam(paging) on parameter '"
                        + param.getSimpleName() + "' of " + where + off, param);
            }
            if (annotation != null && annotation.sortable().length > 0) {
                env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + "@AgenticParam(sortable) on"
                        + " parameter '" + param.getSimpleName() + "' of " + where + off, param);
            }
        }
    }

    /**
     * The effective bound: {@code maxResults}, or with {@code ai.atlas.constraints} on a
     * {@code @Size(max)} on the method, which must agree with it.
     *
     * @param maxResults the {@code maxResults} written on the method, or {@code null} when none is
     * @return the bound, {@code -1} when none, {@code -2} after reporting an ERROR
     */
    private int bound(ExecutableElement method, Integer maxResults, String where) {
        if (maxResults != null && maxResults < 1) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares maxResults = "
                    + maxResults + "; a bound must be at least 1", method);
            return -2;
        }
        Integer size = constraints ? ReturnShapes.sizeMax(method) : null;
        if (size == null) {
            return maxResults != null ? maxResults : -1;
        }
        if (maxResults != null && !maxResults.equals(size)) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares maxResults = "
                    + maxResults + " and @Size(max = " + size + ") on its result; they must agree", method);
            return -2;
        }
        if (size < 1) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares @Size(max = " + size
                    + ") on its result; a bound must be at least 1", method);
            return -2;
        }
        return size;
    }

    /** The misuse ERRORs of a collection method's paging roles; whether there is none. */
    private boolean checkRoles(String where, ExecutableElement method, List<? extends VariableElement> params,
                               int pageable, int limit, int cursor, int bound) {
        Messager messager = env.getMessager();
        if (limit >= 0 && !ReturnShapes.integral(params.get(limit).asType())) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares paging = LIMIT on '"
                    + params.get(limit).getSimpleName() + "' of type " + params.get(limit).asType()
                    + "; a limit must be an int, long or short", params.get(limit));
            return false;
        }
        if (pageable >= 0 && (limit >= 0 || cursor >= 0)) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " takes a Pageable and declares a"
                    + " LIMIT or CURSOR parameter; a method has one paging contract", method);
            return false;
        }
        if (limit >= 0 && bound != -1) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares a LIMIT parameter and"
                    + " maxResults; the limit bounds each call, so declare its ceiling with @Max on '"
                    + params.get(limit).getSimpleName() + "' instead", method);
            return false;
        }
        if (pageable >= 0) {
            for (VariableElement param : params) {
                String name = param.getSimpleName().toString();
                if (PAGING_INPUTS.contains(name)) {
                    messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " takes a Pageable and a"
                            + " parameter named '" + name + "', which would collide with the Pageable's page, size"
                            + " and sort inputs. Rename the parameter", param);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * An ERROR on each paging input a REST mapping binds from the path or the body; whether there
     * is none. The generated checks read {@code page} and {@code size} from the query, as OpenAPI
     * publishes them, and a limit or cursor is a query input on both channels.
     */
    private boolean checkLocations(String where, List<? extends VariableElement> params, List<String> parameterIn,
                                   int pageable, int limit, int cursor) {
        boolean valid = true;
        for (int index : new int[] {pageable, limit, cursor}) {
            if (index < 0 || RestOperation.QUERY.equals(parameterIn.get(index))) {
                continue;
            }
            VariableElement param = params.get(index);
            String role = index == pageable ? "the Pageable" : index == limit ? "the LIMIT" : "the CURSOR";
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " binds " + role + " '"
                    + param.getSimpleName() + "' from the request " + parameterIn.get(index).toLowerCase(Locale.ROOT)
                    + "; a paging input is a query parameter only. Remove its @AgenticParam(in)"
                    + (RestOperation.PATH.equals(parameterIn.get(index)) ? " or the {" + param.getSimpleName() + "} path variable"
                    : ""), param);
            valid = false;
        }
        return valid;
    }

    /** An ERROR on {@code sortable} anywhere but a {@code Pageable}; whether there is none. */
    private boolean checkSortable(List<? extends VariableElement> params, int pageable, String where) {
        boolean valid = true;
        for (int i = 0; i < params.size(); i++) {
            if (i != pageable && !sortable(params.get(i)).isEmpty()) {
                env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + "@AgenticParam(sortable) on"
                        + " parameter '" + params.get(i).getSimpleName() + "' of " + where + " applies only to a"
                        + " Spring Data Pageable", params.get(i));
                valid = false;
            }
        }
        return valid;
    }

    /**
     * An ERROR on each {@code sortable} property that is not a field of the returned entity's REST
     * DTO, or on any when the method returns no entity; whether there is none.
     */
    private boolean checkSortableProperties(VariableElement param, MethodModel model, String where,
                                            Supplier<List<EntityModel>> apiEntities) {
        List<String> sortable = sortable(param);
        if (sortable.isEmpty()) {
            return true;
        }
        if (model.returnEntityType() == null || model.returnDtoType() == null) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + "@AgenticParam(sortable) on " + where
                    + " needs a returned @AgenticEntity to check the properties against; declare"
                    + " @AgenticExposed(returnType)", param);
            return false;
        }
        String entityName = model.returnEntityType().canonicalName();
        Set<String> fields = new LinkedHashSet<>();
        apiEntities.get().stream().filter(e -> e.sourceClassName().canonicalName().equals(entityName))
                .flatMap(e -> e.fields().stream()).map(FieldModel::name).forEach(fields::add);
        List<String> unknown = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String property : sortable) {
            if (!fields.contains(property) || !seen.add(property)) {
                unknown.add(property);
            }
        }
        if (!unknown.isEmpty()) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + "@AgenticParam(sortable) on " + where
                    + " names " + unknown + ", which must each be a distinct field of "
                    + model.returnEntityType().simpleName() + "'s REST DTO: " + fields
                    + ". A sort on a property clients cannot see leaks its values through the order", param);
            return false;
        }
        return true;
    }

    private static List<String> sortable(VariableElement param) {
        AgenticParam annotation = param.getAnnotation(AgenticParam.class);
        return annotation != null ? List.of(annotation.sortable()) : List.of();
    }

    /** The index of the {@code Pageable} parameter, -1 when none, -2 after reporting two. */
    private int pageable(List<? extends VariableElement> params, String where) {
        int found = -1;
        for (int i = 0; i < params.size(); i++) {
            if (isPageable(params.get(i).asType())) {
                if (found != -1) {
                    if (enabled) {
                        env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " takes more"
                                + " than one Pageable; a paging contract has one", params.get(i));
                    }
                    return -2;
                }
                found = i;
            }
        }
        return found;
    }

    /** The index of the parameter declaring {@code role}, -1 when none, -2 after reporting two. */
    private int role(List<? extends VariableElement> params, Paging role, String where) {
        int found = -1;
        for (int i = 0; i < params.size(); i++) {
            AgenticParam annotation = params.get(i).getAnnotation(AgenticParam.class);
            if (annotation != null && annotation.paging() == role) {
                if (found != -1) {
                    env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares paging = "
                            + role + " on more than one parameter", params.get(i));
                    return -2;
                }
                found = i;
            }
        }
        return found;
    }

    /** Whether the type is exactly {@code Pageable}, the type Spring Data's REST resolver binds. */
    private boolean isPageable(TypeMirror type) {
        return type instanceof DeclaredType declared
                && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(PAGEABLE);
    }
}

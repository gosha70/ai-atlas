/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.annotations.AgenticParam;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ReturnKind;
import com.egoge.ai.atlas.processor.util.VersionSelector;
import com.palantir.javapoet.TypeName;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <strong>Spike (Phase 5, epic #23 &sect;8).</strong> The {@code ai.atlas.collections} option of a
 * compilation: {@code true} or {@code false} in any case, default {@code false}. With it on, every
 * active exposed method that returns a collection is classified:
 *
 * <pre>
 * collection return
 *   ├─ Spring Data Pageable parameter      → paged: page/size inputs; a Page or Slice return keeps its metadata
 *   ├─ @AgenticParam(paging = LIMIT)       → paged by a limit the service honours
 *   ├─ @AgenticExposed(maxResults = N)     → declared bounded
 *   └─ otherwise                           → WARNING, an ERROR under ai.atlas.strict
 * </pre>
 *
 * <p>Nothing is synthesised: a wrapper exposes page or limit inputs only where the service accepts
 * them, and never truncates a result. Spring Data types are read by name, so the processor has no
 * dependency on Spring Data.
 *
 * <p>With it off, generation is exactly what it was before this option, and a declared
 * {@code maxResults} or paging role is a WARNING that it is ignored.
 */
public final class CollectionsOption {

    /** The option's name. */
    public static final String OPTION = "ai.atlas.collections";

    static final String PAGEABLE = "org.springframework.data.domain.Pageable";
    static final String PAGE_REQUEST = "org.springframework.data.domain.PageRequest";
    private static final String PAGE = "org.springframework.data.domain.Page";
    private static final String SLICE = "org.springframework.data.domain.Slice";
    private static final String BASE_STREAM = "java.util.stream.BaseStream";
    private static final String MAP = "java.util.Map";
    private static final Set<TypeKind> INTEGRAL = Set.of(TypeKind.INT, TypeKind.LONG, TypeKind.SHORT);
    private static final Set<String> BOXED_INTEGRAL = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short");
    private static final String PREFIX = "[ai-atlas] ";

    private final boolean enabled;
    private final String option;
    private final ProcessingEnvironment env;
    /** The paging contract of each operation that has one, by IR operation identity. */
    private final Map<String, PagingContract> contracts = new HashMap<>();

    private CollectionsOption(boolean enabled, String option, ProcessingEnvironment env) {
        this.enabled = enabled;
        this.option = option;
        this.env = env;
    }

    /**
     * Reads the option, reporting an ERROR naming it and its value when it is neither
     * {@code true} nor {@code false}.
     *
     * @return the option, or {@code null} after reporting an invalid value
     */
    public static CollectionsOption resolve(String option, ProcessingEnvironment env) {
        String value = env.getOptions().get(option);
        if (value == null || "false".equalsIgnoreCase(value)) {
            return new CollectionsOption(false, option, env);
        }
        if (!"true".equalsIgnoreCase(value)) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    PREFIX + option + " must be 'true' or 'false'. Got: " + value);
            return null;
        }
        return new CollectionsOption(true, option, env);
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
     * finds. With the option off, only reports a declaration that is ignored.
     *
     * @param operationId  the method's IR identity
     * @param qualityKind  WARNING, or ERROR under {@code ai.atlas.strict}
     */
    public void check(TypeElement serviceType, ExecutableElement method, MethodModel model,
                      AgenticExposed typeAnnotation, String operationId, Diagnostic.Kind qualityKind, int apiMajor) {
        Messager messager = env.getMessager();
        String where = serviceType.getQualifiedName() + "#" + model.methodName();
        AgenticExposed methodAnnotation = method.getAnnotation(AgenticExposed.class);
        int maxResults = methodAnnotation != null ? methodAnnotation.maxResults() : -1;
        List<? extends VariableElement> params = method.getParameters();
        int limit = role(params, AgenticParam.Paging.LIMIT, where, messager);
        int cursor = role(params, AgenticParam.Paging.CURSOR, where, messager);
        if (!enabled) {
            if (maxResults != -1 || limit != -1 || cursor != -1) {
                messager.printMessage(Diagnostic.Kind.WARNING, PREFIX + where + " declares maxResults or a"
                        + " paging role, which is ignored because " + option + " is off", method);
            }
            return;
        }
        if (typeAnnotation != null && typeAnnotation.maxResults() != -1) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@AgenticExposed(maxResults) on "
                    + serviceType.getQualifiedName() + " must be declared on each method, not the class:"
                    + " a bound describes one operation's result", serviceType);
        }
        if (limit == -2 || cursor == -2) {
            return;
        }
        if (maxResults != -1 && maxResults < 1) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares maxResults = " + maxResults
                    + "; a bound must be at least 1", method);
            return;
        }
        int pageable = -1;
        for (int i = 0; i < params.size(); i++) {
            if (assignable(params.get(i).asType(), PAGEABLE)) {
                if (pageable != -1) {
                    messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where
                            + " takes more than one Pageable; a paging contract has one", method);
                    return;
                }
                pageable = i;
            }
        }
        TypeMirror returned = method.getReturnType();
        PagingContract.Envelope envelope = assignable(returned, PAGE) ? PagingContract.Envelope.PAGE
                : assignable(returned, SLICE) ? PagingContract.Envelope.SLICE : PagingContract.Envelope.NONE;
        boolean collection = model.returnKind() != ReturnKind.NONE
                || assignable(returned, BASE_STREAM) || assignable(returned, MAP);

        if (!collection) {
            if (maxResults != -1 || limit != -1 || cursor != -1) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares maxResults or a"
                        + " paging role, but returns " + returned + ", which is not a collection", method);
            }
            return;
        }
        if (limit != -1 && !integral(params.get(limit).asType())) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares paging = LIMIT on '"
                    + params.get(limit).getSimpleName() + "' of type " + params.get(limit).asType()
                    + "; a limit must be an int, long or short", params.get(limit));
            return;
        }
        if (pageable != -1 && (limit != -1 || cursor != -1)) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " takes a Pageable and declares a"
                    + " LIMIT or CURSOR parameter; a method has one paging contract", method);
            return;
        }
        boolean paged = pageable != -1 || limit != -1;
        if (paged && maxResults != -1) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " is paged and declares maxResults;"
                    + " the page size already bounds each call", method);
            return;
        }
        if (pageable != -1) {
            for (VariableElement param : params) {
                String name = param.getSimpleName().toString();
                if (PagingContract.PAGE_PARAM.equals(name) || PagingContract.SIZE_PARAM.equals(name)) {
                    messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " takes a Pageable and a"
                            + " parameter named '" + name + "', which the Pageable's page and size inputs"
                            + " would shadow", param);
                    return;
                }
            }
        }
        if (paged || maxResults != -1 || envelope != PagingContract.Envelope.NONE) {
            contracts.put(operationId, new PagingContract(envelope, pageable, limit,
                    limit != -1 ? cursor : -1, maxResults, elementType(returned)));
        }
        if (!paged && maxResults == -1 && VersionSelector.isActive(model, apiMajor)) {
            String cursorNote = cursor != -1 ? " Its CURSOR parameter bounds nothing without a LIMIT." : "";
            messager.printMessage(qualityKind, PREFIX + where + " returns " + returned + " on channels "
                    + model.channels().stream().sorted().toList() + " with no paging contract and no declared"
                    + " bound, so one call can return the whole result set." + cursorNote + " Take a Spring"
                    + " Data Pageable, mark a limit the service honours with @AgenticParam(paging = LIMIT), or"
                    + " declare @AgenticExposed(maxResults = N) when the result is known to be small", method);
        }
    }

    /** The index of the parameter declaring {@code role}, -1 when none, -2 after reporting two. */
    private static int role(List<? extends VariableElement> params, AgenticParam.Paging role, String where,
                            Messager messager) {
        int found = -1;
        for (int i = 0; i < params.size(); i++) {
            AgenticParam annotation = params.get(i).getAnnotation(AgenticParam.class);
            if (annotation != null && annotation.paging() == role) {
                if (found != -1) {
                    messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + where + " declares paging = " + role
                            + " on more than one parameter", params.get(i));
                    return -2;
                }
                found = i;
            }
        }
        return found;
    }

    private boolean assignable(TypeMirror type, String qualifiedName) {
        TypeElement element = env.getElementUtils().getTypeElement(qualifiedName);
        if (element == null) {
            return false;
        }
        var types = env.getTypeUtils();
        return types.isAssignable(types.erasure(type), types.erasure(element.asType()));
    }

    private static boolean integral(TypeMirror type) {
        return INTEGRAL.contains(type.getKind()) || BOXED_INTEGRAL.contains(type.toString());
    }

    /** The first type argument of a Page or Slice return, or {@code null} when raw. */
    private static TypeName elementType(TypeMirror returned) {
        if (returned instanceof javax.lang.model.type.DeclaredType declared && !declared.getTypeArguments().isEmpty()) {
            TypeMirror argument = declared.getTypeArguments().get(0);
            if (argument instanceof javax.lang.model.type.WildcardType wildcard) {
                argument = wildcard.getExtendsBound();
            }
            return argument != null ? TypeName.get(argument) : null;
        }
        return null;
    }
}

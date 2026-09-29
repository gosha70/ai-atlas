/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.rest;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.annotations.AgenticExposed.HttpMethod;
import com.egoge.ai.atlas.annotations.AgenticExposed.RestStyle;
import com.egoge.ai.atlas.annotations.AgenticParam;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.HashMap;
import java.util.Map;

/**
 * The attributes an element's {@code @AgenticExposed(rest = @Rest(...))} declares, each
 * {@code null} when left at its "not declared" default.
 *
 * <p>Read from the annotation mirrors: javac's reflective proxy of a nested annotation value can
 * throw {@code AnnotationTypeMismatchException} for a declaration generated in a later round.
 *
 * @param method   the declared HTTP method's name
 * @param path     the declared path
 * @param status   the declared status
 * @param style    the declared style's name
 * @param resource the declared resource
 */
record RestDeclaration(String method, String path, Integer status, String style, String resource) {

    /** {@code @Rest(path)}'s default, which means "not declared". */
    static final String UNDECLARED_PATH = "\0";

    /**
     * @param element a service class or method
     * @return what its {@code @AgenticExposed(rest)} declares, or {@code null} when it declares
     *         no {@code rest} or only defaults
     */
    static RestDeclaration of(Element element) {
        for (AnnotationMirror exposed : element.getAnnotationMirrors()) {
            if (!((TypeElement) exposed.getAnnotationType().asElement()).getQualifiedName()
                    .contentEquals(AgenticExposed.class.getCanonicalName())) {
                continue;
            }
            for (var entry : exposed.getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("rest")
                        && entry.getValue().getValue() instanceof AnnotationMirror rest) {
                    RestDeclaration declaration = of(rest);
                    return declaration.declaresClassLevel() || declaration.declaresMethodLevel() ? declaration : null;
                }
            }
        }
        return null;
    }

    private static RestDeclaration of(AnnotationMirror rest) {
        Map<String, Object> values = new HashMap<>();
        rest.getElementValues().forEach((k, v) -> values.put(k.getSimpleName().toString(),
                v.getValue() instanceof VariableElement constant ? constant.getSimpleName().toString() : v.getValue()));
        String method = (String) values.get("method");
        String path = (String) values.get("path");
        Integer status = (Integer) values.get("status");
        String style = (String) values.get("style");
        String resource = (String) values.get("resource");
        return new RestDeclaration(HttpMethod.UNSET.name().equals(method) ? null : method,
                UNDECLARED_PATH.equals(path) ? null : path, status != null && status == 0 ? null : status,
                RestStyle.INHERIT.name().equals(style) ? null : style,
                resource != null && resource.isEmpty() ? null : resource);
    }

    /** Whether it declares {@code style} or {@code resource}, the class-level attributes. */
    boolean declaresClassLevel() {
        return style != null || resource != null;
    }

    /** Whether it declares {@code method}, {@code path} or {@code status}, the method-level attributes. */
    boolean declaresMethodLevel() {
        return method != null || path != null || status != null;
    }

    /**
     * @param parameter a parameter of an exposed method
     * @return its declared {@code @AgenticParam(in)}, {@link AgenticParam.In#DEFAULT} when none
     */
    static AgenticParam.In in(VariableElement parameter) {
        AgenticParam annotation = parameter.getAnnotation(AgenticParam.class);
        return annotation != null ? annotation.in() : AgenticParam.In.DEFAULT;
    }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.rest;

import com.egoge.ai.atlas.processor.util.ReturnedTypes;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.List;
import java.util.Set;

/**
 * The five rules of the opt-in CRUD convention, matched on a method's name and parameter shape
 * only, and the scalar and entity parameter types they and path parameters are defined by.
 */
final class CrudRules {

    /** How {@link #scalar} is described in diagnostics. */
    static final String SCALAR_KINDS = "a primitive, its box, String, an enum, UUID, BigDecimal or BigInteger";

    private static final Set<String> SCALARS = Set.of(
            "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
            "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character",
            "java.util.UUID", "java.math.BigDecimal", "java.math.BigInteger");

    private CrudRules() {
    }

    /**
     * A matched CRUD rule.
     *
     * @param httpMethod the rule's HTTP method
     * @param path       the rule's path below the resource
     * @param status     the rule's success status, which holds only while its HTTP method does
     */
    record Rule(String httpMethod, String path, int status) {
    }

    /**
     * @param name   the method name
     * @param params the method's parameters
     * @param isVoid whether the method returns {@code void}
     * @param types  the type utilities
     * @return the rule the method matches, or {@code null} when none does
     */
    static Rule match(String name, List<? extends VariableElement> params, boolean isVoid, Types types) {
        int n = params.size();
        return switch (name) {
            case "findAll", "list" -> n == 0 ? new Rule(RestOperation.GET, "", RestOperation.DEFAULT_STATUS) : null;
            case "findById", "getById" -> n == 1 && scalar(params.get(0).asType())
                    ? new Rule(RestOperation.GET, variable(params.get(0)), RestOperation.DEFAULT_STATUS) : null;
            case "create" -> n == 1 && entity(params.get(0).asType(), types)
                    ? new Rule(RestOperation.POST, "", 201) : null;
            case "update" -> n == 2 && scalar(params.get(0).asType()) && entity(params.get(1).asType(), types)
                    ? new Rule("PUT", variable(params.get(0)), RestOperation.DEFAULT_STATUS) : null;
            case "delete", "deleteById" -> n == 1 && scalar(params.get(0).asType())
                    ? new Rule(RestOperation.DELETE, variable(params.get(0)),
                            isVoid ? RestOperation.NO_CONTENT : RestOperation.DEFAULT_STATUS) : null;
            default -> null;
        };
    }

    private static String variable(VariableElement param) {
        return "/{" + param.getSimpleName() + "}";
    }

    /** An {@code @AgenticEntity} or a subtype of one. */
    static boolean entity(TypeMirror type, Types types) {
        return ReturnedTypes.entityOf(type, types) != null;
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
}

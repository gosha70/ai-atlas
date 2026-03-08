/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.annotations.AgenticExposed;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;

/**
 * Validates compatibility between {@code @AgenticExposed(returnType = ...)}
 * declarations and the actual method return type. Prevents generation of
 * cast-based mapping code that would fail at runtime with ClassCastException.
 */
public final class ReturnTypeValidator {

    private ReturnTypeValidator() {
    }

    /**
     * Reads returnType() as a TypeMirror (not ClassName) for assignability checks.
     * Returns null if void.class (the default).
     */
    public static TypeMirror resolveReturnEntityTypeMirror(AgenticExposed annotation) {
        try {
            Class<?> returnType = annotation.returnType();
            if (returnType == void.class) {
                return null;
            }
            return null; // reflective path — won't happen during annotation processing
        } catch (MirroredTypeException e) {
            TypeMirror typeMirror = e.getTypeMirror();
            if (typeMirror.getKind() == TypeKind.VOID) {
                return null;
            }
            return typeMirror;
        }
    }

    /**
     * Result of return type compatibility validation.
     */
    public enum Result {
        /** Types are compatible (element assignable to declared returnType) */
        COMPATIBLE,
        /** Validation inconclusive — raw/wildcard collection, cannot check */
        INCONCLUSIVE,
        /** Types are incompatible — downcast would cause ClassCastException */
        INCOMPATIBLE
    }

    /**
     * Validates that the method's actual return type is assignable TO the declared
     * returnType (upcast only). Downcasts are rejected because generated code casts
     * each element to returnType, which would throw ClassCastException.
     *
     * <p>e.g. {@code List<Internal>} + returnType=Base → COMPATIBLE (Internal → Base)
     * <br>    {@code List<Base>} + returnType=Internal → INCOMPATIBLE (Base → Internal cast)
     * <br>    {@code List<?>} + returnType=anything → INCONCLUSIVE (can't extract element)
     */
    public static Result validateReturnTypeCompat(ExecutableElement method,
                                                   TypeMirror returnEntityMirror,
                                                   boolean collectionReturn,
                                                   Types typeUtils) {
        TypeMirror methodReturnType = method.getReturnType();
        TypeMirror erasedEntity = typeUtils.erasure(returnEntityMirror);

        if (collectionReturn) {
            TypeMirror elementType = extractCollectionElementType(methodReturnType);
            if (elementType == null) {
                return Result.INCONCLUSIVE;
            }
            TypeMirror erasedElement = typeUtils.erasure(elementType);
            return typeUtils.isAssignable(erasedElement, erasedEntity)
                    ? Result.COMPATIBLE : Result.INCOMPATIBLE;
        } else {
            TypeMirror erasedReturn = typeUtils.erasure(methodReturnType);
            return typeUtils.isAssignable(erasedReturn, erasedEntity)
                    ? Result.COMPATIBLE : Result.INCOMPATIBLE;
        }
    }

    /**
     * Extracts the element/component type from a collection/iterable/array return type.
     * Returns null for raw types or unbounded wildcards.
     */
    private static TypeMirror extractCollectionElementType(TypeMirror returnType) {
        if (returnType.getKind() == TypeKind.ARRAY && returnType instanceof ArrayType arrayType) {
            return arrayType.getComponentType();
        }
        if (returnType instanceof DeclaredType declaredType) {
            var typeArgs = declaredType.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                TypeMirror arg = typeArgs.getFirst();
                if (arg instanceof WildcardType wildcard) {
                    return wildcard.getExtendsBound();
                }
                return arg;
            }
        }
        return null;
    }
}

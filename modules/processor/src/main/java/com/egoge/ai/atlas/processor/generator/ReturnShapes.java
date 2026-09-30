/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.palantir.javapoet.TypeName;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import java.util.Map;
import java.util.Set;

/**
 * For {@link CollectionsOption}: the shape of an exposed method's return, as a collection, and the
 * bound declarations it reads from annotations as written.
 */
final class ReturnShapes {

    static final String BASE_STREAM = "java.util.stream.BaseStream";
    static final String MAP = "java.util.Map";
    private static final String ITERABLE = "java.lang.Iterable";
    private static final String OPTIONAL = "java.util.Optional";
    private static final String SIZE = "jakarta.validation.constraints.Size";
    private static final Set<TypeKind> INTEGRAL = Set.of(TypeKind.INT, TypeKind.LONG, TypeKind.SHORT);
    private static final Set<String> BOXED_INTEGRAL = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short");

    private final ProcessingEnvironment env;

    ReturnShapes(ProcessingEnvironment env) {
        this.env = env;
    }

    /**
     * The {@code maxResults} an {@code @AgenticExposed} on the element writes, or {@code null} when it
     * writes none: read from the annotation as written, so an explicit value equal to
     * {@link AgenticExposed#NO_MAX_RESULTS} is still a declaration.
     */
    static Integer explicitMaxResults(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName()
                    .contentEquals(AgenticExposed.class.getCanonicalName())) {
                for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                        : mirror.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("maxResults")) {
                        return (Integer) entry.getValue().getValue();
                    }
                }
            }
        }
        return null;
    }

    /** The {@code max} of a {@code @Size} on the method, a return-value constraint, or {@code null}. */
    static Integer sizeMax(ExecutableElement method) {
        for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(SIZE)) {
                for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                        : mirror.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("max")) {
                        return (Integer) entry.getValue().getValue();
                    }
                }
            }
        }
        return null;
    }

    boolean assignable(TypeMirror type, String qualifiedName) {
        TypeElement element = env.getElementUtils().getTypeElement(qualifiedName);
        if (element == null) {
            return false;
        }
        var types = env.getTypeUtils();
        return types.isAssignable(types.erasure(type), types.erasure(element.asType()));
    }

    /**
     * Whether a return shape is a collection: an array other than a {@code byte[]}, which is a
     * binary payload, an iterable, a {@code Stream} or a {@code Map}.
     */
    boolean collection(TypeMirror shape) {
        if (shape.getKind() == TypeKind.ARRAY) {
            return ((ArrayType) shape).getComponentType().getKind() != TypeKind.BYTE;
        }
        return assignable(shape, ITERABLE) || assignable(shape, BASE_STREAM) || assignable(shape, MAP);
    }

    /** The content type of an {@code Optional}, or the type itself when it is not one. */
    static TypeMirror optionalContent(TypeMirror type) {
        if (type instanceof DeclaredType declared
                && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(OPTIONAL)
                && declared.getTypeArguments().size() == 1) {
            TypeMirror content = declared.getTypeArguments().get(0);
            if (content instanceof WildcardType wildcard) {
                content = wildcard.getExtendsBound();
            }
            return content != null ? content : type;
        }
        return type;
    }

    static boolean integral(TypeMirror type) {
        return INTEGRAL.contains(type.getKind()) || BOXED_INTEGRAL.contains(type.toString());
    }

    /** The first type argument of a Page or Slice return, or {@code null} when raw. */
    static TypeName elementType(TypeMirror returned) {
        if (returned instanceof DeclaredType declared && !declared.getTypeArguments().isEmpty()) {
            TypeMirror argument = declared.getTypeArguments().get(0);
            if (argument instanceof WildcardType wildcard) {
                argument = wildcard.getExtendsBound();
            }
            return argument != null ? TypeName.get(argument) : null;
        }
        return null;
    }
}

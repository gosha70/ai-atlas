/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.palantir.javapoet.ClassName;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Resolves {@code @AgenticExposed} attributes with per-attribute
 * inheritance semantics (method → class → framework default).
 */
public final class AttributeResolver {

    private static final Set<String> FRAMEWORK_DEFAULT_CHANNELS = Set.of("AI", "API");

    private AttributeResolver() {
    }

    /** Resolves description: method non-empty → class non-empty → "Invokes {methodName}". */
    public static String resolveDescription(AgenticExposed methodAnn,
                                             AgenticExposed typeAnn,
                                             String methodName) {
        if (methodAnn != null && !methodAnn.description().isEmpty()) {
            return methodAnn.description();
        }
        if (typeAnn != null && !typeAnn.description().isEmpty()) {
            return typeAnn.description();
        }
        return "Invokes " + methodName;
    }

    /** Resolves returnType: method non-void → class non-void → null. */
    public static ClassName resolveReturnEntityType(AgenticExposed methodAnn,
                                                     AgenticExposed typeAnn,
                                                     Types typeUtils) {
        ClassName fromMethod = methodAnn != null ? extractReturnType(methodAnn, typeUtils) : null;
        if (fromMethod != null) {
            return fromMethod;
        }
        return typeAnn != null ? extractReturnType(typeAnn, typeUtils) : null;
    }

    /** Resolves returnType as TypeMirror for validation: method non-void → class non-void → null. */
    public static TypeMirror resolveReturnEntityTypeMirror(AgenticExposed methodAnn,
                                                            AgenticExposed typeAnn) {
        TypeMirror fromMethod = methodAnn != null
                ? ReturnTypeValidator.resolveReturnEntityTypeMirror(methodAnn) : null;
        if (fromMethod != null) {
            return fromMethod;
        }
        return typeAnn != null ? ReturnTypeValidator.resolveReturnEntityTypeMirror(typeAnn) : null;
    }

    /**
     * Resolves channels with inherit-or-narrow semantics.
     * Returns null on validation error (error already emitted to messager).
     */
    public static Set<String> resolveChannels(AgenticExposed methodAnn,
                                               AgenticExposed typeAnn,
                                               ExecutableElement method,
                                               Messager messager) {
        // Resolve class channels
        Set<String> classChannels;
        if (typeAnn == null || containsOnly(typeAnn.channels(), AgenticExposed.Channel.INHERIT)) {
            classChannels = FRAMEWORK_DEFAULT_CHANNELS;
        } else {
            if (containsInherit(typeAnn.channels())) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "[ai-atlas] channels must not mix INHERIT with explicit values",
                        method.getEnclosingElement());
                return null;
            }
            classChannels = toStringSet(typeAnn.channels());
        }

        // Resolve method channels
        if (methodAnn == null || containsOnly(methodAnn.channels(), AgenticExposed.Channel.INHERIT)) {
            return classChannels;
        }
        if (containsInherit(methodAnn.channels())) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] channels must not mix INHERIT with explicit values", method);
            return null;
        }
        Set<String> methodChannels = toStringSet(methodAnn.channels());
        if (!classChannels.containsAll(methodChannels)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] method channels " + methodChannels
                            + " must be a subset of class-level channels " + classChannels,
                    method);
            return null;
        }
        return methodChannels;
    }

    /** Sentinel-based int resolution: method != INHERIT → class != INHERIT → frameworkDefault. */
    public static int resolveIntAttr(AgenticExposed methodAnn, AgenticExposed typeAnn,
                                      ToIntFunction<AgenticExposed> getter, int frameworkDefault) {
        if (methodAnn != null && getter.applyAsInt(methodAnn) != AgenticExposed.INHERIT) {
            return getter.applyAsInt(methodAnn);
        }
        if (typeAnn != null && getter.applyAsInt(typeAnn) != AgenticExposed.INHERIT) {
            return getter.applyAsInt(typeAnn);
        }
        return frameworkDefault;
    }

    /** Sentinel-based string resolution: method != INHERIT_STR → class != INHERIT_STR → frameworkDefault. */
    public static String resolveStringAttr(AgenticExposed methodAnn, AgenticExposed typeAnn,
                                            Function<AgenticExposed, String> getter,
                                            String frameworkDefault) {
        if (methodAnn != null && !AgenticExposed.INHERIT_STR.equals(getter.apply(methodAnn))) {
            return getter.apply(methodAnn);
        }
        if (typeAnn != null && !AgenticExposed.INHERIT_STR.equals(getter.apply(typeAnn))) {
            return getter.apply(typeAnn);
        }
        return frameworkDefault;
    }

    private static ClassName extractReturnType(AgenticExposed ann, Types typeUtils) {
        try {
            Class<?> rt = ann.returnType();
            return rt == void.class ? null : ClassName.get(rt);
        } catch (MirroredTypeException e) {
            TypeElement te = (TypeElement) typeUtils.asElement(e.getTypeMirror());
            return te != null ? ClassName.get(te) : null;
        }
    }

    private static boolean containsOnly(AgenticExposed.Channel[] channels,
                                          AgenticExposed.Channel value) {
        return channels.length == 1 && channels[0] == value;
    }

    private static boolean containsInherit(AgenticExposed.Channel[] channels) {
        for (AgenticExposed.Channel ch : channels) {
            if (ch == AgenticExposed.Channel.INHERIT) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> toStringSet(AgenticExposed.Channel[] channels) {
        Set<String> set = new LinkedHashSet<>();
        for (AgenticExposed.Channel ch : channels) {
            set.add(ch.name());
        }
        return set;
    }
}

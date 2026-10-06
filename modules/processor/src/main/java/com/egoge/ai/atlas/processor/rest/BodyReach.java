/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.rest;

import com.egoge.ai.atlas.processor.util.ReturnedTypes;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether a request body type reaches an {@code @AgenticEntity}, or a subtype of one, that Jackson
 * would bind in full: through a type argument, a map key or value, an array component, a record
 * component, a field, a setter's, constructor's or {@code @JsonCreator} factory's parameter, a
 * {@code @JsonSetter}, {@code @JsonProperty} or {@code @JsonAnySetter} method's value, a supertype,
 * or a class a Jackson annotation names ({@code @JsonDeserialize(as, contentAs, keyAs, builder)},
 * {@code @JsonSubTypes}), transitively. A Jackson annotation that lets the deserialized class be
 * chosen out of the processor's sight ({@code @JsonDeserialize(using, contentUsing, keyUsing)},
 * {@code @JsonTypeInfo} with class-name or custom ids, {@code @JsonTypeIdResolver}) cannot be
 * checked, so it is reported too. Annotations are read by name: the processor has no Jackson
 * dependency.
 *
 * <p>Members of JDK types are not followed, only their type arguments. Each type is visited once,
 * and a declaration's members are followed once per erasure and set of
 * {@link ReturnedTypes#declarations}, so the walk ends, even on a type that grows through its own
 * supertype.
 */
final class BodyReach {

    private static final String DATABIND = "com.fasterxml.jackson.databind.annotation.";
    private static final String ANNOTATION = "com.fasterxml.jackson.annotation.";
    private static final String JSON_DESERIALIZE = DATABIND + "JsonDeserialize";
    private static final String JSON_TYPE_ID_RESOLVER = DATABIND + "JsonTypeIdResolver";
    private static final String JSON_SUB_TYPES = ANNOTATION + "JsonSubTypes";
    private static final String JSON_TYPE_INFO = ANNOTATION + "JsonTypeInfo";
    private static final String JSON_CREATOR = ANNOTATION + "JsonCreator";
    /** Annotations that make an instance method of any name a property's setter. */
    private static final Set<String> SETTERS = Set.of(ANNOTATION + "JsonSetter", ANNOTATION + "JsonProperty",
            ANNOTATION + "JsonAnySetter");
    /** {@code @JsonDeserialize} attributes naming a class Jackson deserializes. */
    private static final Set<String> TARGETS = Set.of("as", "contentAs", "keyAs", "builder");
    /** {@code @JsonDeserialize} attributes naming a deserializer, whose result the processor cannot see. */
    private static final Set<String> DESERIALIZERS = Set.of("using", "contentUsing", "keyUsing");
    /** A deserializer attribute's "none" value. */
    private static final String NO_DESERIALIZER = "JsonDeserializer.None";
    /** {@code @JsonTypeInfo(use)} values whose type ids name any class, or are resolved out of sight. */
    private static final Set<String> OPEN_TYPE_IDS = Set.of("CLASS", "MINIMAL_CLASS", "CUSTOM");

    /**
     * What a body type reaches.
     *
     * @param description {@code "the @AgenticEntity X through T.a.b"}, or the annotation that cannot be checked
     * @param entity      whether an entity is reached; otherwise an annotation cannot be checked
     */
    record Reach(String description, boolean entity) {
    }

    private final Types types;
    private final Elements elements;
    private final Deque<Map.Entry<TypeMirror, String>> pending = new ArrayDeque<>();

    private BodyReach(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    /**
     * @param type     a body parameter's type
     * @param types    the type utilities
     * @param elements the element utilities
     * @return what the body type reaches, or {@code null} when it reaches no entity and every
     *         Jackson annotation on the way can be checked
     */
    static Reach of(TypeMirror type, Types types, Elements elements) {
        return new BodyReach(types, elements).walk(type);
    }

    private Reach walk(TypeMirror type) {
        pending.add(Map.entry(type, display(type)));
        Set<String> seen = new HashSet<>();
        Map<String, List<Set<String>>> followed = new HashMap<>();
        while (!pending.isEmpty()) {
            Map.Entry<TypeMirror, String> next = pending.removeFirst();
            TypeMirror current = next.getKey();
            String via = next.getValue();
            if (!seen.add(current.toString())) {
                continue;
            }
            if (current instanceof ArrayType array) {
                pending.add(Map.entry(array.getComponentType(), via + "[]"));
                continue;
            }
            if (current instanceof WildcardType wildcard) {
                if (wildcard.getExtendsBound() != null) {
                    pending.add(Map.entry(wildcard.getExtendsBound(), via));
                }
                continue;
            }
            if (current instanceof TypeVariable variable) {
                pending.add(Map.entry(variable.getUpperBound(), via));
                continue;
            }
            if (current instanceof IntersectionType intersection) {
                intersection.getBounds().forEach(bound -> pending.add(Map.entry(bound, via)));
                continue;
            }
            if (!(current instanceof DeclaredType written)) {
                continue;
            }
            // Capture gives each wildcard its effective bound, its declared parameter's included:
            // Unsafe<?> of Unsafe<T extends Order> binds an Order
            DeclaredType declared = written.getTypeArguments().stream().anyMatch(WildcardType.class::isInstance)
                    ? (DeclaredType) types.capture(written) : written;
            if (current != type) {
                TypeElement entity = ReturnedTypes.entityOf(current, types);
                if (entity != null) {
                    return new Reach("the @AgenticEntity " + entity.getSimpleName() + " through " + via, true);
                }
            }
            for (int i = 0; i < declared.getTypeArguments().size(); i++) {
                pending.add(Map.entry(declared.getTypeArguments().get(i),
                        via + "<" + display(written.getTypeArguments().get(i)) + ">"));
            }
            TypeElement element = (TypeElement) declared.asElement();
            Set<String> declarations = ReturnedTypes.declarations(current);
            List<Set<String>> sets = followed.computeIfAbsent(types.erasure(current).toString(), k -> new ArrayList<>());
            if (jdk(element) || sets.stream().anyMatch(set -> set.containsAll(declarations))) {
                continue;
            }
            sets.add(declarations);
            Reach opaque = followMembers(declared, element, via);
            if (opaque != null) {
                return opaque;
            }
        }
        return null;
    }

    /**
     * Enqueues what a non-JDK declaration binds: its supertypes, the classes its Jackson annotations
     * name, and each field, record component, setter, constructor and factory parameter.
     *
     * @return the first Jackson annotation that cannot be checked, or {@code null}
     */
    private Reach followMembers(DeclaredType declared, TypeElement element, String via) {
        // A superclass's private fields are not among the members, but Jackson may bind them
        for (TypeMirror supertype : types.directSupertypes(declared)) {
            pending.add(Map.entry(supertype, via));
        }
        Reach opaque = annotations(element, via);
        for (Element member : elements.getAllMembers(element)) {
            String memberName = member.getSimpleName().toString();
            String at = via + "." + memberName;
            boolean isStatic = member.getModifiers().contains(Modifier.STATIC);
            if (opaque == null && !isStatic) {
                opaque = annotations(member, at);
            }
            if (isStatic && member.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (member.getKind() == ElementKind.FIELD || member.getKind() == ElementKind.RECORD_COMPONENT) {
                pending.add(Map.entry(types.asMemberOf(declared, member), at));
            } else if (member instanceof ExecutableElement method) {
                List<? extends VariableElement> params = method.getParameters();
                boolean creator = isStatic && annotated(method, JSON_CREATOR);
                boolean setter = !isStatic && !params.isEmpty() && (memberName.startsWith("set") && params.size() == 1
                        || SETTERS.stream().anyMatch(name -> annotated(method, name)));
                if (creator || setter) {
                    ExecutableType executable = (ExecutableType) types.asMemberOf(declared, method);
                    // A setter's value is its last parameter: @JsonAnySetter takes the key first
                    int first = creator ? 0 : params.size() - 1;
                    for (int i = first; i < params.size(); i++) {
                        pending.add(Map.entry(executable.getParameterTypes().get(i), at + "()"));
                        opaque = opaque != null ? opaque : annotations(params.get(i), at + "()");
                    }
                }
            }
        }
        for (ExecutableElement constructor : ElementFilter.constructorsIn(element.getEnclosedElements())) {
            ExecutableType creator = (ExecutableType) types.asMemberOf(declared, constructor);
            for (int i = 0; i < creator.getParameterTypes().size(); i++) {
                VariableElement param = constructor.getParameters().get(i);
                String at = via + "(" + param.getSimpleName() + ")";
                pending.add(Map.entry(creator.getParameterTypes().get(i), at));
                opaque = opaque != null ? opaque : annotations(param, at);
            }
        }
        return opaque;
    }

    /**
     * Enqueues each class a Jackson annotation on {@code annotated} names for deserialization.
     *
     * @return the annotation, when one lets the class be chosen out of the processor's sight
     */
    private Reach annotations(Element annotated, String at) {
        for (AnnotationMirror mirror : annotated.getAnnotationMirrors()) {
            String name = ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString();
            Map<? extends ExecutableElement, ? extends AnnotationValue> values = mirror.getElementValues();
            if (JSON_TYPE_ID_RESOLVER.equals(name)) {
                return new Reach("@JsonTypeIdResolver on " + at, false);
            }
            for (var entry : values.entrySet()) {
                String attribute = entry.getKey().getSimpleName().toString();
                Object value = entry.getValue().getValue();
                if (JSON_DESERIALIZE.equals(name) && TARGETS.contains(attribute) && value instanceof TypeMirror target) {
                    pending.add(Map.entry(target, at + " @JsonDeserialize(" + attribute + ")"));
                } else if (JSON_DESERIALIZE.equals(name) && DESERIALIZERS.contains(attribute)
                        && !value.toString().endsWith(NO_DESERIALIZER)) {
                    return new Reach("@JsonDeserialize(" + attribute + " = " + value + ") on " + at, false);
                } else if (JSON_TYPE_INFO.equals(name) && "use".equals(attribute)
                        && OPEN_TYPE_IDS.contains(value.toString())) {
                    return new Reach("@JsonTypeInfo(use = " + value + ") on " + at, false);
                } else if (JSON_SUB_TYPES.equals(name) && "value".equals(attribute) && value instanceof List<?> list) {
                    for (Object type : list) {
                        if (((AnnotationValue) type).getValue() instanceof AnnotationMirror subtype) {
                            subtype.getElementValues().forEach((key, subtypeClass) -> {
                                if ("value".contentEquals(key.getSimpleName())
                                        && subtypeClass.getValue() instanceof TypeMirror target) {
                                    pending.add(Map.entry(target, at + " @JsonSubTypes"));
                                }
                            });
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean annotated(Element element, String annotation) {
        return element.getAnnotationMirrors().stream().anyMatch(mirror ->
                ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation));
    }

    /** A type as a path through types names it: a declared type without its type arguments. */
    private String display(TypeMirror type) {
        return type instanceof DeclaredType ? types.erasure(type).toString() : type.toString();
    }

    /** A type of the JDK, whose members a request body does not bind as properties. */
    private boolean jdk(TypeElement element) {
        String name = elements.getPackageOf(element).getQualifiedName().toString();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.");
    }
}

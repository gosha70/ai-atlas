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
 * a setterless {@code Collection} or {@code Map} getter Jackson fills, an implicit {@code valueOf} or
 * {@code fromString} factory's parameter, or a class a Jackson annotation names
 * ({@code @JsonDeserialize(as, contentAs, keyAs)}, {@code @JsonSubTypes},
 * {@code @JsonTypeInfo(defaultImpl)}), transitively. It fails closed on Jackson's annotations: one
 * that lets code choose or fill the deserialized value out of the processor's sight cannot be
 * checked, so it is reported too. That is any other {@code @JsonDeserialize} class attribute, such
 * as {@code using}, {@code converter} or {@code builder}; {@code @JsonTypeInfo} with class-name or
 * custom ids; {@code @JsonIdentityInfo} with a custom resolver; and every Jackson annotation neither
 * acted on here nor known to leave the bound class alone, such as {@code @JacksonInject},
 * {@code @JsonMerge}, {@code @JsonTypeIdResolver} or one a later Jackson adds. Annotation bundles marked
 * {@code @JacksonAnnotationsInside} are expanded, as Jackson does. Annotations are read by name: the
 * processor has no Jackson dependency.
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
    private static final String JSON_SUB_TYPES = ANNOTATION + "JsonSubTypes";
    private static final String JSON_TYPE_INFO = ANNOTATION + "JsonTypeInfo";
    private static final String JSON_CREATOR = ANNOTATION + "JsonCreator";
    private static final String JSON_PROPERTY = ANNOTATION + "JsonProperty";
    private static final String JSON_IDENTITY_INFO = ANNOTATION + "JsonIdentityInfo";
    private static final String BUNDLE = ANNOTATION + "JacksonAnnotationsInside";
    /** Annotations that make an instance method of any name a property's setter. */
    private static final Set<String> SETTERS = Set.of(ANNOTATION + "JsonSetter", JSON_PROPERTY,
            ANNOTATION + "JsonAnySetter");
    /** The Jackson annotations the walk acts on. */
    private static final Set<String> HANDLED = Set.of(JSON_DESERIALIZE, JSON_SUB_TYPES, JSON_TYPE_INFO, JSON_CREATOR,
            JSON_PROPERTY, JSON_IDENTITY_INFO, BUNDLE, ANNOTATION + "JsonSetter", ANNOTATION + "JsonAnySetter");
    /**
     * The Jackson annotations that cannot change which class a body binds or what fills it. Any
     * other Jackson annotation, such as {@code @JacksonInject} or {@code @JsonMerge}, whose values
     * code chooses, or one a later Jackson adds, cannot be checked.
     */
    private static final Set<String> INERT = Set.of(ANNOTATION + "JacksonAnnotation",
            ANNOTATION + "JacksonAnnotationValue", ANNOTATION + "JsonAlias", ANNOTATION + "JsonAnyGetter",
            ANNOTATION + "JsonAutoDetect", ANNOTATION + "JsonBackReference", ANNOTATION + "JsonClassDescription",
            ANNOTATION + "JsonEnumDefaultValue", ANNOTATION + "JsonFilter", ANNOTATION + "JsonFormat",
            ANNOTATION + "JsonGetter", ANNOTATION + "JsonIdentityReference", ANNOTATION + "JsonIgnore",
            ANNOTATION + "JsonIgnoreProperties", ANNOTATION + "JsonIgnoreType", ANNOTATION + "JsonInclude",
            ANNOTATION + "JsonIncludeProperties", ANNOTATION + "JsonKey", ANNOTATION + "JsonManagedReference",
            ANNOTATION + "JsonPropertyDescription", ANNOTATION + "JsonPropertyOrder", ANNOTATION + "JsonRawValue",
            ANNOTATION + "JsonRootName", ANNOTATION + "JsonTypeId", ANNOTATION + "JsonTypeName",
            ANNOTATION + "JsonUnwrapped", ANNOTATION + "JsonValue", ANNOTATION + "JsonView",
            DATABIND + "JacksonStdImpl", DATABIND + "JsonSerialize", DATABIND + "JsonNaming", DATABIND + "JsonAppend",
            DATABIND + "EnumNaming");
    /** The only {@code @JsonIdentityInfo(resolver)} that resolves ids to objects the body already holds. */
    private static final String DEFAULT_ID_RESOLVER = ANNOTATION + "SimpleObjectIdResolver";
    /** Static factories Jackson may use as creators without {@code @JsonCreator}. */
    private static final Set<String> IMPLICIT_FACTORIES = Set.of("valueOf", "fromString");
    private static final String COLLECTION = "java.util.Collection";
    private static final String MAP = "java.util.Map";
    /**
     * {@code @JsonDeserialize} attributes naming a class Jackson deserializes. Any other class
     * attribute, {@code builder} included, names code whose result cannot be checked.
     */
    private static final Set<String> TARGETS = Set.of("as", "contentAs", "keyAs");
    /**
     * The exact "none" sentinel of each reviewed {@code @JsonDeserialize} code attribute. Any other
     * class, a user class named {@code None} included, and any value of an attribute not listed here
     * names code whose result cannot be checked.
     */
    private static final Map<String, String> NONE = Map.of(
            "using", "com.fasterxml.jackson.databind.JsonDeserializer.None",
            "contentUsing", "com.fasterxml.jackson.databind.JsonDeserializer.None",
            "keyUsing", "com.fasterxml.jackson.databind.KeyDeserializer.None",
            "converter", "com.fasterxml.jackson.databind.util.Converter.None",
            "contentConverter", "com.fasterxml.jackson.databind.util.Converter.None",
            "builder", "java.lang.Void");
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
                boolean creator = isStatic && (annotated(method, JSON_CREATOR)
                        || IMPLICIT_FACTORIES.contains(memberName) && params.size() == 1);
                if (!isStatic && params.isEmpty() && mutableContainer(method.getReturnType())
                        && (memberName.startsWith("get") || annotated(method, JSON_PROPERTY))) {
                    // Jackson fills a setterless Collection or Map through its getter
                    pending.add(Map.entry(((ExecutableType) types.asMemberOf(declared, method)).getReturnType(),
                            at + "()"));
                }
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
        for (AnnotationMirror mirror : effective(annotated)) {
            String name = name(mirror);
            String simpleName = "@" + mirror.getAnnotationType().asElement().getSimpleName();
            if ((name.startsWith(ANNOTATION) || name.startsWith(DATABIND)) && !HANDLED.contains(name)
                    && !INERT.contains(name)) {
                return new Reach(simpleName + " on " + at, false);
            }
            for (var entry : mirror.getElementValues().entrySet()) {
                String attribute = entry.getKey().getSimpleName().toString();
                Object value = entry.getValue().getValue();
                if (JSON_DESERIALIZE.equals(name) && TARGETS.contains(attribute) && value instanceof TypeMirror target) {
                    pending.add(Map.entry(target, at + " @JsonDeserialize(" + attribute + ")"));
                } else if (JSON_DESERIALIZE.equals(name) && value instanceof TypeMirror code
                        && !qualifiedName(code).equals(NONE.get(attribute))) {
                    // using, contentUsing, keyUsing, converter, contentConverter, and any later one
                    return new Reach("@JsonDeserialize(" + attribute + " = " + value + ") on " + at, false);
                } else if (JSON_TYPE_INFO.equals(name) && "use".equals(attribute)
                        && OPEN_TYPE_IDS.contains(value.toString())) {
                    return new Reach("@JsonTypeInfo(use = " + value + ") on " + at, false);
                } else if (JSON_IDENTITY_INFO.equals(name) && "resolver".equals(attribute)
                        && !value.toString().equals(DEFAULT_ID_RESOLVER)) {
                    return new Reach("@JsonIdentityInfo(resolver = " + value + ") on " + at, false);
                } else if (JSON_TYPE_INFO.equals(name) && "defaultImpl".equals(attribute)
                        && value instanceof TypeMirror target) {
                    pending.add(Map.entry(target, at + " @JsonTypeInfo(defaultImpl)"));
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

    /** Whether {@code type} is a {@code Collection} or a {@code Map}, which Jackson fills in place. */
    private boolean mutableContainer(TypeMirror type) {
        return List.of(COLLECTION, MAP).stream().map(elements::getTypeElement).anyMatch(container -> container != null
                && types.isAssignable(types.erasure(type), types.erasure(container.asType())));
    }

    /** A class literal's qualified name, as an annotation value gives it. */
    private static String qualifiedName(TypeMirror type) {
        return type instanceof DeclaredType declared
                ? ((TypeElement) declared.asElement()).getQualifiedName().toString() : type.toString();
    }

    private static boolean annotated(Element element, String annotation) {
        return effective(element).stream().anyMatch(mirror -> name(mirror).equals(annotation));
    }

    /**
     * The annotations Jackson reads on {@code element}: those present, and those inside each
     * {@code @JacksonAnnotationsInside} bundle, recursively; each bundle is expanded once.
     */
    private static List<AnnotationMirror> effective(Element element) {
        List<AnnotationMirror> effective = new ArrayList<>();
        Set<String> expanded = new HashSet<>();
        Deque<AnnotationMirror> pending = new ArrayDeque<>(element.getAnnotationMirrors());
        while (!pending.isEmpty()) {
            AnnotationMirror mirror = pending.removeFirst();
            effective.add(mirror);
            Element type = mirror.getAnnotationType().asElement();
            if (type.getAnnotationMirrors().stream().anyMatch(inner -> name(inner).equals(BUNDLE))
                    && expanded.add(name(mirror))) {
                pending.addAll(type.getAnnotationMirrors());
            }
        }
        return effective;
    }

    private static String name(AnnotationMirror mirror) {
        return ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString();
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

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.rest;

import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.processor.constraints.ConstraintReader;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.rest.InputRecord.InputField;
import com.egoge.ai.atlas.processor.util.EntityRefResolver;
import com.egoge.ai.atlas.processor.util.FieldScanner;
import com.egoge.ai.atlas.processor.util.ReturnedTypes;
import com.palantir.javapoet.ClassName;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The whitelisted {@link InputRecord}s entity request bodies bind: how a body may set each
 * {@code @AgenticField}, each entity's record, validated once, and the records generated.
 *
 * <p>A record holds the entity's fields active at the configured major that are not declared
 * {@code input = false} and are eligible for the API channel. Phase 4's input rule applies: a
 * <em>required</em> field not eligible for the API channel is an ERROR, and an <em>optional</em>
 * one is left out. A field referring to another entity cannot be an input. The entity must be
 * creatable from the record: an accessible no-argument constructor and a setter per component, or
 * an accessible constructor taking every component in order.
 */
final class InputRecords {

    private static final String API = "API";
    private static final String PREFIX = "[ai-atlas] ";
    private static final String CHAR_SEQUENCE = "java.lang.CharSequence";

    private final boolean enabled;
    private final String option;
    private final ProcessingEnvironment env;
    private final ConstraintReader constraintReader;
    private final int apiMajor;
    /** A field's effective channels, sorted, by entity class name and field name. */
    private final BiFunction<String, String, List<String>> channels;
    /** Whether a direct field's entity type hint makes it refer to the entity, as with {@code ai.atlas.projections=true}. */
    private final boolean directHints;
    /** The recorded {@code @AgenticField}s by {@code entity#field}. */
    private final Map<String, EntityField> entityFields = new HashMap<>();
    /** Each entity's input record by entity class name, or {@code null} when it cannot have one. */
    private final Map<String, InputRecord> inputRecords = new HashMap<>();
    /** The input records generated, by qualified name. */
    private final Map<String, InputRecord> generated = new TreeMap<>();

    /** An {@code @AgenticField} as a request body sees it. */
    private record EntityField(VariableElement element, boolean input, boolean required) {
    }

    InputRecords(boolean enabled, String option, ProcessingEnvironment env, int apiMajor,
                 BiFunction<String, String, List<String>> channels, boolean directHints) {
        this.enabled = enabled;
        this.option = option;
        this.env = env;
        this.constraintReader = new ConstraintReader(env);
        this.apiMajor = apiMajor;
        this.channels = channels;
        this.directHints = directHints;
    }

    /**
     * Records how a request body may set each of an entity's fields. An
     * {@code @AgenticField(input = false)} with the option off is a WARNING, as it has no effect.
     *
     * @param entity  the {@code @AgenticEntity} class
     * @param scanned the entity's recorded {@code @AgenticField} fields
     */
    void recordEntity(TypeElement entity, List<FieldScanner.ScannedField> scanned) {
        String className = entity.getQualifiedName().toString();
        for (FieldScanner.ScannedField field : scanned) {
            AgenticField annotation = field.element().getAnnotation(AgenticField.class);
            boolean input = annotation == null || annotation.input();
            if (!input && !enabled) {
                env.getMessager().printMessage(Diagnostic.Kind.WARNING, PREFIX + "@AgenticField(input = false) on"
                        + " field '" + field.model().name() + "' of " + entity.getSimpleName() + " has no effect"
                        + " without " + option + "=true, which adds request bodies", field.element());
            }
            entityFields.put(className + "#" + field.model().name(),
                    new EntityField(field.element(), input, constraintReader.requiredField(field.element())));
        }
    }

    /** The outcome of checking a body parameter. */
    record Body(boolean valid, ClassName inputRecord) {
    }

    /**
     * Checks the type of a body parameter: an entity binds its input record; a {@code String} is
     * read as raw text, and an entity subtype or a collection of entities would bind unwhitelisted
     * properties, so each is an ERROR.
     */
    Body checkBody(VariableElement body, String methodName, Map<String, EntityModel> registry) {
        Types types = env.getTypeUtils();
        Messager messager = env.getMessager();
        TypeMirror type = body.asType();
        String name = body.getSimpleName().toString();
        String subject = "The request body '" + name + "' of '" + methodName + "'";
        TypeElement charSequence = env.getElementUtils().getTypeElement(CHAR_SEQUENCE);
        if (charSequence != null && types.isAssignable(type, charSequence.asType())) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + subject + " is a " + type + ", which Spring reads"
                    + " as raw text, not JSON. Wrap it in a record, or declare it in the query", body);
            return new Body(false, null);
        }
        TypeElement entity = ReturnedTypes.entityOf(type, types);
        if (entity != null) {
            if (!types.isSameType(types.erasure(type), types.erasure(entity.asType()))) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + subject + " is " + type + ", a subtype of the"
                        + " @AgenticEntity " + entity.getSimpleName() + ", which a request body cannot bind through a"
                        + " whitelist. Declare the parameter as " + entity.getSimpleName(), body);
                return new Body(false, null);
            }
            InputRecord input = inputRecord(entity, registry);
            if (input == null) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + subject + " cannot bind the @AgenticEntity "
                        + entity.getSimpleName() + ", which has no valid input record; see the errors on "
                        + entity.getSimpleName(), body);
                return new Body(false, null);
            }
            return new Body(true, input.name());
        }
        TypeMirror element = type.getKind() == TypeKind.DECLARED || type.getKind() == TypeKind.ARRAY
                ? ReturnedTypes.elementType(type, types) : null;
        TypeElement elementEntity = element != null ? ReturnedTypes.entityOf(element, types) : null;
        if (elementEntity != null) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + subject + " is " + type + ", a collection of the"
                    + " @AgenticEntity " + elementEntity.getSimpleName() + ", which a request body cannot bind"
                    + " through a whitelist. Take one " + elementEntity.getSimpleName() + " per request", body);
            return new Body(false, null);
        }
        return new Body(true, null);
    }

    /**
     * The input record of an entity, validated once; its ERRORs are on the entity and its fields.
     *
     * @param entity   an {@code @AgenticEntity} a request body binds
     * @param registry every registered entity, projected at the configured major
     * @return the record, or {@code null} when the entity cannot have one
     */
    InputRecord inputRecord(TypeElement entity, Map<String, EntityModel> registry) {
        String className = entity.getQualifiedName().toString();
        if (!inputRecords.containsKey(className)) {
            inputRecords.put(className, buildInputRecord(entity, registry));
        }
        return inputRecords.get(className);
    }

    private InputRecord buildInputRecord(TypeElement entity, Map<String, EntityModel> registry) {
        Messager messager = env.getMessager();
        String className = entity.getQualifiedName().toString();
        String simpleName = entity.getSimpleName().toString();
        EntityModel model = registry.get(className);
        if (model == null || model.fields().isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "The @AgenticEntity " + simpleName + " is a request"
                    + " body, but has no @AgenticField active for apiMajor=" + apiMajor + " to bind", entity);
            return null;
        }
        boolean valid = true;
        List<FieldModel> fields = new ArrayList<>();
        List<Boolean> required = new ArrayList<>();
        for (FieldModel field : model.fields()) {
            EntityField declared = entityFields.get(className + "#" + field.name());
            if (declared == null || !declared.input()) {
                continue;
            }
            if (!channels.apply(className, field.name()).contains(API)) {
                if (declared.required()) {
                    messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Field '" + field.name() + "' of "
                            + simpleName + " is required (a primitive, or @NotNull, @NotBlank or @NotEmpty), but not"
                            + " eligible for the API channel, so a REST request body cannot set it. Make it"
                            + " eligible for API, or declare @AgenticField(input = false)", declared.element());
                    valid = false;
                }
                continue;
            }
            EntityRefResolver.EntityRef ref = EntityRefResolver.resolve(
                    directHints ? EntityRefResolver.directHinted(field, registry) : field, registry);
            if (ref != null) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Field '" + field.name() + "' of " + simpleName
                        + " refers to the @AgenticEntity " + ref.entityClass().simpleName() + ", which a REST"
                        + " request body cannot bind. Declare @AgenticField(input = false) on it", declared.element());
                valid = false;
                continue;
            }
            fields.add(field);
            required.add(declared.required());
        }
        ClassName name = ClassName.get(model.dtoPackageName(), simpleName + "Input");
        valid &= checkInputName(entity, name, registry);
        if (!valid) {
            return null;
        }
        if (entity.getModifiers().contains(Modifier.ABSTRACT)) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "The @AgenticEntity " + simpleName + " is a request"
                    + " body, but is abstract, so its input record " + name.simpleName() + " cannot create it", entity);
            return null;
        }
        List<String> setters = setters(entity, fields, name.packageName());
        boolean noArgs = ElementFilter.constructorsIn(entity.getEnclosedElements()).stream()
                .anyMatch(c -> c.getParameters().isEmpty() && accessible(c, name.packageName()));
        List<InputField> components = new ArrayList<>();
        if (noArgs && !setters.contains(null)) {
            for (int i = 0; i < fields.size(); i++) {
                components.add(new InputField(fields.get(i), required.get(i), setters.get(i)));
            }
            return new InputRecord(name, model, components, false);
        }
        if (hasMatchingConstructor(entity, fields, name.packageName())) {
            for (int i = 0; i < fields.size(); i++) {
                components.add(new InputField(fields.get(i), required.get(i), null));
            }
            return new InputRecord(name, model, components, true);
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            if (setters.get(i) == null) {
                missing.add(setterName(fields.get(i).name()));
            }
        }
        messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "The @AgenticEntity " + simpleName + " is a request"
                + " body, but its input record " + name.simpleName() + " cannot create it. It needs an accessible"
                + " no-argument constructor" + (noArgs ? "" : " (none found)") + " and a setter for each input field"
                + (missing.isEmpty() ? "" : " (missing: " + String.join(", ", missing) + ")")
                + ", or an accessible constructor taking ("
                + fields.stream().map(f -> f.typeName() + " " + f.name()).collect(Collectors.joining(", "))
                + ") in that order", entity);
        return null;
    }

    /** An ERROR on the entity when its input record's name is taken by another type. */
    private boolean checkInputName(TypeElement entity, ClassName name, Map<String, EntityModel> registry) {
        String collision = null;
        for (InputRecord other : inputRecords.values()) {
            if (other != null && other.name().equals(name)) {
                collision = "the input record of " + other.entity().sourceClassName().simpleName();
            }
        }
        for (EntityModel other : registry.values()) {
            if (other.dtoClassName().equals(name)) {
                collision = "the DTO of " + other.sourceClassName().simpleName();
            }
        }
        if (collision == null && env.getElementUtils().getTypeElement(name.canonicalName()) != null) {
            collision = "a type the compilation declares";
        }
        if (collision == null) {
            return true;
        }
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + "The input record " + name + " of "
                + entity.getSimpleName() + " collides with " + collision + ". Rename the type, or keep "
                + entity.getSimpleName() + " out of the request body", entity);
        return false;
    }

    /** The setter of each field, or {@code null} for a field without an accessible one. */
    private List<String> setters(TypeElement entity, List<FieldModel> fields, String packageName) {
        Types types = env.getTypeUtils();
        Elements elements = env.getElementUtils();
        List<ExecutableElement> methods = ElementFilter.methodsIn(elements.getAllMembers(entity));
        List<String> setters = new ArrayList<>();
        for (FieldModel field : fields) {
            String setter = setterName(field.name());
            TypeMirror fieldType = entityFields.get(entity.getQualifiedName() + "#" + field.name()).element().asType();
            boolean found = methods.stream().anyMatch(method -> method.getSimpleName().contentEquals(setter)
                    && method.getParameters().size() == 1 && !method.getModifiers().contains(Modifier.STATIC)
                    && accessible(method, packageName)
                    && types.isAssignable(fieldType, method.getParameters().get(0).asType()));
            setters.add(found ? setter : null);
        }
        return setters;
    }

    /** Whether the entity has an accessible constructor taking each field, in order. */
    private boolean hasMatchingConstructor(TypeElement entity, List<FieldModel> fields, String packageName) {
        Types types = env.getTypeUtils();
        for (ExecutableElement constructor : ElementFilter.constructorsIn(entity.getEnclosedElements())) {
            List<? extends VariableElement> params = constructor.getParameters();
            if (params.size() != fields.size() || !accessible(constructor, packageName)) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < params.size(); i++) {
                TypeMirror fieldType = entityFields.get(entity.getQualifiedName() + "#" + fields.get(i).name())
                        .element().asType();
                matches &= types.isAssignable(fieldType, params.get(i).asType());
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /** Public, or neither private nor protected and declared in {@code packageName}. */
    private boolean accessible(Element member, String packageName) {
        Set<Modifier> modifiers = member.getModifiers();
        if (modifiers.contains(Modifier.PUBLIC)) {
            return true;
        }
        return !modifiers.contains(Modifier.PRIVATE) && !modifiers.contains(Modifier.PROTECTED)
                && env.getElementUtils().getPackageOf(member).getQualifiedName().contentEquals(packageName);
    }

    private static String setterName(String field) {
        return "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    /**
     * Generates the input record {@code name}, once per compilation.
     *
     * @param name an input record {@link #inputRecord} returned
     */
    void generate(ClassName name) {
        if (generated.containsKey(name.canonicalName())) {
            return;
        }
        InputRecord input = inputRecords.values().stream()
                .filter(r -> r != null && r.name().equals(name)).findFirst().orElseThrow();
        generated.put(name.canonicalName(), input);
        InputRecordGenerator.generate(input, env.getFiler(), env.getMessager());
    }

    /** The input records generated so far, by qualified name. */
    Collection<InputRecord> generated() {
        return generated.values();
    }
}

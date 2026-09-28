/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.processor.contract.ChannelProjection;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.util.EntityRefResolver;
import com.egoge.ai.atlas.processor.util.FieldScanner;
import com.egoge.ai.atlas.processor.util.PiiDetector;
import com.egoge.ai.atlas.processor.util.ReturnedTypes;
import com.palantir.javapoet.ClassName;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code ai.atlas.projections} option of a compilation: {@code true} or {@code false} in any
 * case, default {@code false}. With it on, {@code @AgenticField(channels)} narrows the channels
 * whose responses carry a field, and each response is projected to the fields eligible for the
 * channel serving it:
 *
 * <pre>
 * field eligible channels  x  operation exposed channels  =  effective response projection
 * </pre>
 *
 * <p>The projection is compile-time, a {@link ChannelProjection} of the entities active at the
 * configured major. REST controllers and the OpenAPI document keep each entity's DTO, the API
 * projection; MCP tool classes return the AI projection, a separate record only for an entity that
 * splits. Records are generated in the round that registers their entity, from every entity
 * registered so far. A reference to a type from a later round is already a compile error (the
 * Contract IR cannot record it), so a later round never changes whether an earlier entity splits:
 * the split of each round is the split over every round.
 *
 * <p>With it off, generation is exactly what it was before this option, and an explicit
 * {@code @AgenticField(channels)} is an ERROR: the field would still be served on every channel,
 * turning a safety declaration into a silent no-op.
 */
public final class ProjectionsOption {

    // Constant strings, not the annotation enum: initialising the processor loads no annotation class
    /** The MCP channel. */
    public static final String AI = ChannelProjection.AI;
    /** The REST channel. */
    public static final String API = ChannelProjection.API;
    /** Every channel, sorted: the eligibility of a field that declares none. */
    public static final List<String> EVERY_CHANNEL = ContractIr.Field.EVERY_CHANNEL;

    private static final String PREFIX = "[ai-atlas] ";

    private final boolean enabled;
    private final String option;
    /** Declared eligibility by {@code entity#field}, sorted; a field absent from the map is on every channel. */
    private final Map<String, List<String>> eligibility = new HashMap<>();
    /** {@code @AgenticEntity(aiDtoName)} by entity class name, when declared. */
    private final Map<String, String> aiDtoNames = new HashMap<>();
    /** The field elements by {@code entity#field}, for diagnostics. */
    private final Map<String, VariableElement> fieldElements = new HashMap<>();
    /** The qualified names of the AI records generated so far, mapped to their entity class names. */
    private final Map<String, String> generatedAiRecords = new HashMap<>();

    private ProjectionsOption(boolean enabled, String option) {
        this.enabled = enabled;
        this.option = option;
    }

    /**
     * Reads the option, reporting an ERROR naming it and its value when it is neither
     * {@code true} nor {@code false}.
     *
     * @param option the option name
     * @param env    the processing environment
     * @return the option, or {@code null} after reporting an invalid value
     */
    public static ProjectionsOption resolve(String option, ProcessingEnvironment env) {
        String value = env.getOptions().get(option);
        if (value == null || "false".equalsIgnoreCase(value)) {
            return new ProjectionsOption(false, option);
        }
        if (!"true".equalsIgnoreCase(value)) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    PREFIX + option + " must be 'true' or 'false'. Got: " + value);
            return null;
        }
        return new ProjectionsOption(true, option);
    }

    /** Whether the option is on. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Validates and records the declared eligibility of an entity's fields and its
     * {@code aiDtoName}. An empty {@code channels}, or {@code INHERIT} mixed with explicit values,
     * is an ERROR on the field; so is any explicit value with the option off. A field in error keeps
     * every channel. An {@code aiDtoName} that is not a Java identifier is an ERROR, and one declared
     * with the option off a WARNING, as it has no effect.
     *
     * @param entity   the {@code @AgenticEntity} class
     * @param scanned  the entity's recorded {@code @AgenticField} fields
     * @param messager receives the diagnostics
     */
    public void recordEntity(TypeElement entity, List<FieldScanner.ScannedField> scanned, Messager messager) {
        String className = entity.getQualifiedName().toString();
        for (FieldScanner.ScannedField field : scanned) {
            fieldElements.put(key(className, field.model().name()), field.element());
            List<Channel> declared = Arrays.asList(field.element().getAnnotation(AgenticField.class).channels());
            if (declared.equals(List.of(Channel.INHERIT))) {
                continue;
            }
            String subject = "@AgenticField(channels) on field '" + field.model().name() + "' of "
                    + entity.getSimpleName();
            if (declared.isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + subject + " must not be empty."
                        + " Omit it to keep the field on every channel", field.element());
            } else if (declared.contains(Channel.INHERIT)) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + subject
                        + " must not mix INHERIT with explicit values", field.element());
            } else if (!enabled) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + subject + " requires " + option
                        + "=true. Without it the field is served on every channel. Turn the option on"
                        + " (agentic { projections = true } in Gradle) or remove the declaration", field.element());
            } else {
                eligibility.put(key(className, field.model().name()),
                        declared.stream().map(Enum::name).distinct().sorted().toList());
            }
        }
        String aiDtoName = entity.getAnnotation(AgenticEntity.class).aiDtoName();
        if (aiDtoName.isEmpty()) {
            return;
        }
        if (!SourceVersion.isName(aiDtoName) || aiDtoName.contains(".")) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "@AgenticEntity(aiDtoName) on "
                    + entity.getSimpleName() + " must be a Java identifier. Got: " + aiDtoName, entity);
        } else if (!enabled) {
            messager.printMessage(Diagnostic.Kind.WARNING, PREFIX + "@AgenticEntity(aiDtoName) on "
                    + entity.getSimpleName() + " has no effect without " + option + "=true", entity);
        } else {
            aiDtoNames.put(className, aiDtoName);
        }
    }

    /**
     * The channels whose responses carry a field: its declared eligibility with the option on,
     * every channel otherwise.
     *
     * @param className qualified name of the entity class
     * @param fieldName Java field name
     * @return the channel names, sorted
     */
    public List<String> channels(String className, String fieldName) {
        return eligibility.getOrDefault(key(className, fieldName), EVERY_CHANNEL);
    }

    /**
     * The channel projection of the registered entities.
     *
     * @param registry every registered entity, projected at the configured major
     * @return the projection, naming each split entity's AI record
     */
    public ChannelProjection projection(Map<String, EntityModel> registry) {
        return ChannelProjection.of(registry, this::channels, className -> aiDtoNames.getOrDefault(className,
                ChannelProjection.derivedAiDtoName(registry.get(className).dtoName())));
    }

    /**
     * Warns about each AI-eligible field of an entity whose name matches a PII pattern, as agents
     * receive it; a WARNING whatever {@code ai.atlas.strict} says. Nothing with the option off.
     *
     * @param entity the {@code @AgenticEntity} class
     * @param model  the entity, projected at the configured major
     * @param env    the processing environment
     */
    public void reportAiEligiblePii(TypeElement entity, EntityModel model, ProcessingEnvironment env) {
        if (!enabled) {
            return;
        }
        String className = entity.getQualifiedName().toString();
        for (FieldModel field : model.fields()) {
            if (channels(className, field.name()).contains(AI) && PiiDetector.matches(field.name(), env)) {
                env.getMessager().printMessage(Diagnostic.Kind.WARNING, PREFIX + "Field '" + field.name() + "' of "
                        + entity.getSimpleName() + " matches a PII pattern and is eligible for the AI channel,"
                        + " so MCP tool results carry it into agents' context. If agents must not see it,"
                        + " declare @AgenticField(channels = API)", fieldElements.get(key(className, field.name())));
            }
        }
    }

    // ---------------------------------------------------------------- generation

    /**
     * Generates an entity's records. With the option off, its one DTO, as before. With it on, its
     * API projection under its DTO name, and its AI projection when the entity splits; a channel
     * with no field eligible for it gets no record. A field
     * eligible for a channel that refers to an entity with no field for that channel is an ERROR on
     * the field; an AI record name colliding with another type in its package is an ERROR on the
     * entity. Either leaves that channel's record out.
     *
     * @param className qualified name of the entity class, registered with at least one active field
     * @param registry  every registered entity, projected at the configured major
     * @param apiMajor  the configured major
     * @param env       the processing environment
     */
    public void generateDtos(String className, Map<String, EntityModel> registry, int apiMajor,
                             ProcessingEnvironment env) {
        if (!enabled) {
            DtoGenerator.generate(registry.get(className), registry, apiMajor, env.getFiler(), env.getMessager());
            return;
        }
        ChannelProjection projection = projection(registry);
        for (String channel : List.of(API, AI)) {
            if (AI.equals(channel) && !projection.splits(className)) {
                continue;
            }
            Map<String, EntityModel> view = projection.view(channel);
            EntityModel entity = view.get(className);
            if (entity.fields().isEmpty()) {
                continue;
            }
            boolean valid = checkReferences(className, entity, view, registry, channel, env.getMessager());
            valid &= checkRecordName(className, entity, projection, registry, channel, env);
            if (valid) {
                DtoGenerator.generate(entity, view, apiMajor, env.getFiler(), env.getMessager());
                if (AI.equals(channel)) {
                    generatedAiRecords.put(entity.dtoClassName().canonicalName(), className);
                }
            }
        }
    }

    /** An ERROR on each field of {@code entity} referring to an entity with no field for {@code channel}. */
    private boolean checkReferences(String className, EntityModel entity, Map<String, EntityModel> view,
                                    Map<String, EntityModel> registry, String channel, Messager messager) {
        boolean valid = true;
        for (FieldModel field : entity.fields()) {
            EntityRefResolver.EntityRef ref = EntityRefResolver.resolve(field, view);
            String referenced = ref != null ? ref.entityClass().canonicalName() : null;
            // A referenced entity with no active field at all is reported by the version check
            if (referenced != null && view.get(referenced).fields().isEmpty()
                    && !registry.get(referenced).fields().isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Field '" + field.name() + "' of "
                        + entity.sourceClassName().simpleName() + " is eligible for the " + channel
                        + " channel, but the entity it refers to, " + ref.entityClass().simpleName()
                        + ", has no field eligible for it. Leave " + channel + " out of the field's"
                        + " @AgenticField(channels), or make a field of " + ref.entityClass().simpleName()
                        + " eligible for " + channel, fieldElements.get(key(className, field.name())));
                valid = false;
            }
        }
        return valid;
    }

    /**
     * An ERROR on the entity when its record for {@code channel} would collide with another type:
     * an AI record generated for another entity or, for an AI record, another entity's record or a
     * type the compilation already has.
     */
    private boolean checkRecordName(String className, EntityModel entity, ChannelProjection projection,
                                    Map<String, EntityModel> registry, String channel, ProcessingEnvironment env) {
        ClassName record = entity.dtoClassName();
        String previous = generatedAiRecords.get(record.canonicalName());
        String collision = previous != null ? "the AI record of " + simpleName(previous)
                : AI.equals(channel) ? aiRecordCollision(className, record, projection, registry, env) : null;
        if (collision == null) {
            return true;
        }
        boolean ai = AI.equals(channel);
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + (ai ? "The AI record " : "The DTO ")
                + record.canonicalName() + " of " + entity.sourceClassName().simpleName() + " collides with "
                + collision + ". Name it with @AgenticEntity(" + (ai ? "aiDtoName" : "dtoName") + ")",
                env.getElementUtils().getTypeElement(className));
        return false;
    }

    /**
     * What a new AI record would collide with, or {@code null} when its name is free. Only records
     * that are generated count: a DTO whose API view has a field, an AI record whose AI view has one.
     */
    private static String aiRecordCollision(String className, ClassName record, ChannelProjection projection,
                                            Map<String, EntityModel> registry, ProcessingEnvironment env) {
        Map<String, EntityModel> api = projection.view(API);
        Map<String, EntityModel> ai = projection.view(AI);
        for (String other : registry.keySet()) {
            EntityModel dto = api.get(other);
            if (!dto.fields().isEmpty() && dto.dtoClassName().equals(record)) {
                return "the DTO of " + simpleName(other);
            }
            EntityModel aiRecord = ai.get(other);
            if (!other.equals(className) && projection.splits(other) && !aiRecord.fields().isEmpty()
                    && aiRecord.dtoClassName().equals(record)) {
                return "the AI record of " + simpleName(other);
            }
        }
        return env.getElementUtils().getTypeElement(record.canonicalName()) != null
                ? "a type the compilation declares" : null;
    }

    /**
     * Whether the response of an operation the configured major generates can be projected per
     * channel: always with the option off; with it on, the method maps its result to a DTO
     * ({@link #checkRawEntityReturn}), and each channel it is exposed on leaves the returned entity
     * a field ({@link #checkReturnedFields}). Each failure is an ERROR on the method.
     *
     * @param method           the method
     * @param returnKind       the shape of its return
     * @param returnEntityType the entity its resolved {@code returnType} names, or {@code null}
     * @param returnDto        the DTO its wrappers map the result to, or {@code null} when none resolved
     * @param channels         the method's channels
     * @param registry         every registered entity, projected at the configured major
     * @param env              the processing environment
     * @return whether the response can be projected
     */
    public boolean checkResponse(ExecutableElement method, ServiceModel.ReturnKind returnKind,
                                 ClassName returnEntityType, ClassName returnDto, Set<String> channels,
                                 Map<String, EntityModel> registry, ProcessingEnvironment env) {
        if (!enabled) {
            return true;
        }
        if (!checkRawEntityReturn(method, returnKind, returnDto, env)) {
            return false;
        }
        return returnDto == null || checkReturnedFields(returnEntityType, channels, registry, method, env.getMessager());
    }

    /**
     * An ERROR on the method for each channel it is exposed on that leaves its returned entity with
     * no eligible field.
     *
     * @return whether every channel has a field
     */
    private boolean checkReturnedFields(ClassName returnEntityType, Set<String> channels,
                                       Map<String, EntityModel> registry, ExecutableElement method,
                                       Messager messager) {
        EntityModel returned = registry.get(returnEntityType.canonicalName());
        if (returned == null || returned.fields().isEmpty()) {
            return true;
        }
        ChannelProjection projection = projection(registry);
        boolean valid = true;
        for (String channel : channels.stream().sorted().toList()) {
            if (projection.view(channel).get(returnEntityType.canonicalName()).fields().isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Method '" + method.getSimpleName()
                        + "' is exposed on the " + channel + " channel, but the entity it returns, "
                        + returnEntityType.simpleName() + ", has no field eligible for it. Leave " + channel
                        + " out of the method's @AgenticExposed(channels), or make a field of "
                        + returnEntityType.simpleName() + " eligible for " + channel, method);
                valid = false;
            }
        }
        return valid;
    }

    /**
     * An ERROR on a method that returns an {@code @AgenticEntity} or a subtype of one, or a
     * collection, iterable or array of either, without a resolvable {@code returnType}: its wrapper
     * would return the raw entity, which the channel projection cannot reach.
     *
     * @return whether the method is valid
     */
    private boolean checkRawEntityReturn(ExecutableElement method, ServiceModel.ReturnKind returnKind,
                                        ClassName returnDto, ProcessingEnvironment env) {
        if (returnDto != null) {
            return true;
        }
        TypeMirror returned = method.getReturnType();
        if (returnKind != ServiceModel.ReturnKind.NONE) {
            returned = ReturnedTypes.elementType(returned, env.getTypeUtils());
        }
        if (returned instanceof TypeVariable variable) {
            returned = variable.getUpperBound();
        }
        TypeElement entityType = returned == null ? null : ReturnedTypes.entityOf(returned, env.getTypeUtils());
        if (entityType == null) {
            return true;
        }
        String entity = entityType.getSimpleName().toString();
        var returnedType = env.getTypeUtils().asElement(returned);
        String subject = entityType.equals(returnedType) ? "the @AgenticEntity " + entity
                : returnedType.getSimpleName() + ", a subtype of the @AgenticEntity " + entity + ",";
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + "Method '" + method.getSimpleName()
                + "' returns " + subject + " without a resolvable @AgenticExposed(returnType),"
                + " so its REST and MCP wrappers would return the raw entity, which " + option
                + "=true cannot project per channel. Declare returnType = " + entity + ".class", method);
        return false;
    }

    /**
     * The service as its MCP tool class sees it: the service itself with the option off; with it
     * on, each method returning an entity maps to the entity's AI projection, its AI record when it
     * splits.
     *
     * @param model    the service model the REST controller is generated from
     * @param registry every registered entity, projected at the configured major
     * @return the model for the MCP tool class
     */
    public ServiceModel toolModel(ServiceModel model, Map<String, EntityModel> registry) {
        if (!enabled) {
            return model;
        }
        Map<String, EntityModel> ai = projection(registry).view(AI);
        List<MethodModel> methods = new ArrayList<>();
        for (MethodModel m : model.methods()) {
            EntityModel entity = m.returnEntityType() != null ? ai.get(m.returnEntityType().canonicalName()) : null;
            methods.add(entity == null || m.returnDtoType() == null ? m
                    : new MethodModel(m.methodName(), m.toolName(), m.description(), m.returnType(),
                            m.returnEntityType(), entity.dtoClassName(), m.returnKind(), m.parameters(), m.channels(),
                            m.apiSince(), m.apiUntil(), m.apiDeprecatedSince(), m.apiReplacement()));
        }
        return new ServiceModel(model.serviceClassName(), methods);
    }

    /**
     * The entities the OpenAPI document describes, in registry order: all of them with the option
     * off, as before; with it on, their API projection, leaving out an entity whose active fields
     * are all AI-only.
     *
     * @param registry every registered entity, projected at the configured major
     * @return the component schemas' entities
     */
    public List<EntityModel> openApiEntities(Map<String, EntityModel> registry) {
        if (!enabled) {
            return new ArrayList<>(registry.values());
        }
        List<EntityModel> result = new ArrayList<>();
        projection(registry).view(API).forEach((className, entity) -> {
            if (!entity.fields().isEmpty() || registry.get(className).fields().isEmpty()) {
                result.add(entity);
            }
        });
        return result;
    }

    private static String key(String className, String fieldName) {
        return className + "#" + fieldName;
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}

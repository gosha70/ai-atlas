/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.util.EntityRefResolver;
import com.egoge.ai.atlas.processor.util.FieldScanner;
import com.palantir.javapoet.ClassName;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <strong>Spike (Phase 4, epic #23 §7).</strong> The {@code ai.atlas.projections} option of a
 * compilation: {@code true} or {@code false} in any case, default {@code false}. With it on, each
 * {@code @AgenticField} carries a channel eligibility ({@code @AgenticField(channels)}) and every
 * response is projected to the fields eligible for the channel serving it:
 *
 * <pre>
 * field eligible channels  x  operation exposed channels  =  effective response projection
 * </pre>
 *
 * <p>The API projection keeps the entity's existing DTO name ({@code OrderDto}), so the REST and
 * OpenAPI names do not move. An AI projection is generated as a separate record
 * ({@code OrderAiDto}) only for an entity whose projections differ: its own AI and API field sets
 * differ, or a field of it refers, directly or through a collection, to an entity that splits. The
 * split is computed as a fixpoint so cycles ({@code Order <-> OrderAction}) terminate.
 *
 * <p>With it off, generation is exactly what it was before; an explicit field eligibility draws a
 * WARNING, since the field then stays on every channel.
 */
public final class ProjectionsOption {

    public static final String AI = "AI";
    public static final String API = "API";
    private static final Set<String> ALL = Set.of(AI, API);

    private final boolean enabled;
    /** Explicit eligibility by {@code entity#field}; a field absent from the map is eligible everywhere. */
    private final Map<String, Set<String>> eligibility = new HashMap<>();

    private ProjectionsOption(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Reads the option, reporting an ERROR naming it and its value when it is neither
     * {@code true} nor {@code false}.
     *
     * @return the option, or {@code null} after reporting an invalid value
     */
    public static ProjectionsOption resolve(String option, ProcessingEnvironment env) {
        String value = env.getOptions().get(option);
        if (value == null || "false".equalsIgnoreCase(value)) {
            return new ProjectionsOption(false);
        }
        if (!"true".equalsIgnoreCase(value)) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] " + option + " must be 'true' or 'false'. Got: " + value);
            return null;
        }
        return new ProjectionsOption(true);
    }

    /** Whether the option is on. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Records the declared eligibility of an entity's fields. Mixing {@code INHERIT} with explicit
     * channels is an ERROR, as on {@code @AgenticExposed}; with the option off an explicit value is
     * a WARNING and is not recorded.
     */
    public void recordEntity(TypeElement entity, List<FieldScanner.ScannedField> scanned, Messager messager) {
        String className = entity.getQualifiedName().toString();
        for (FieldScanner.ScannedField field : scanned) {
            AgenticField annotation = field.element().getAnnotation(AgenticField.class);
            AgenticExposed.Channel[] channels = annotation.channels();
            if (channels.length == 1 && channels[0] == AgenticExposed.Channel.INHERIT) {
                continue;
            }
            if (channels.length == 0 || Arrays.asList(channels).contains(AgenticExposed.Channel.INHERIT)) {
                messager.printMessage(Diagnostic.Kind.ERROR, "[ai-atlas] @AgenticField(channels) on field '"
                        + field.model().name() + "' must be INHERIT alone or a non-empty set of explicit channels",
                        field.element());
                continue;
            }
            if (!enabled) {
                messager.printMessage(Diagnostic.Kind.WARNING, "[ai-atlas] @AgenticField(channels) on field '"
                        + field.model().name() + "' has no effect without ai.atlas.projections=true;"
                        + " the field is exposed on every channel", field.element());
                continue;
            }
            eligibility.put(className + "#" + field.model().name(),
                    Arrays.stream(channels).map(Enum::name).collect(Collectors.toSet()));
        }
    }

    /** The channels a field is eligible for. */
    public Set<String> eligible(String entityClassName, String fieldName) {
        return eligibility.getOrDefault(entityClassName + "#" + fieldName, ALL);
    }

    /**
     * The entity models as {@code channel} sees them: each with only its fields eligible for the
     * channel, and named after the channel's DTO ({@link #dtoName}). References inside the returned
     * registry resolve to the same channel's DTOs, so {@link DtoGenerator} generates a channel's
     * records unchanged.
     *
     * @param registry every registered entity, already projected at the configured major
     * @param channel  {@link #AI} or {@link #API}
     */
    public Map<String, EntityModel> view(Map<String, EntityModel> registry, String channel) {
        Set<String> split = splitEntities(registry);
        Map<String, EntityModel> view = new LinkedHashMap<>();
        for (var entry : registry.entrySet()) {
            EntityModel entity = entry.getValue();
            String className = entry.getKey();
            List<FieldModel> fields = entity.fields().stream()
                    .filter(f -> eligible(className, f.name()).contains(channel)).toList();
            String name = AI.equals(channel) && split.contains(className) ? aiDtoName(entity.dtoName()) : entity.dtoName();
            view.put(className, new EntityModel(entity.sourceClassName(), name, entity.dtoPackageName(),
                    entity.displayName(), entity.classDescription(), entity.includeTypeInfo(), fields));
        }
        return view;
    }

    /** Whether {@code className} gets a separate AI projection. */
    public boolean splits(Map<String, EntityModel> registry, String className) {
        return splitEntities(registry).contains(className);
    }

    /**
     * The AI projection's record name: {@code OrderDto -> OrderAiDto}, a custom
     * {@code OrderSummary -> OrderSummaryAiDto}.
     */
    public static String aiDtoName(String dtoName) {
        String base = dtoName.endsWith("Dto") ? dtoName.substring(0, dtoName.length() - 3) : dtoName;
        return base + "AiDto";
    }

    /**
     * Entities whose AI and API projections differ, directly or through a reference: the least
     * fixpoint of "own field sets differ, or a field on either projection refers to a split entity".
     */
    private Set<String> splitEntities(Map<String, EntityModel> registry) {
        Set<String> split = new HashSet<>();
        for (var entry : registry.entrySet()) {
            for (FieldModel field : entry.getValue().fields()) {
                if (!eligible(entry.getKey(), field.name()).equals(ALL)) {
                    split.add(entry.getKey());
                    break;
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var entry : registry.entrySet()) {
                if (split.contains(entry.getKey())) {
                    continue;
                }
                for (FieldModel field : entry.getValue().fields()) {
                    EntityRefResolver.EntityRef ref = EntityRefResolver.resolve(field, registry);
                    if (ref != null && split.contains(ref.entityClass().canonicalName())) {
                        split.add(entry.getKey());
                        changed = true;
                        break;
                    }
                }
            }
        }
        return split;
    }

    // ---------------------------------------------------------------- generation

    /**
     * Generates the API projection of {@code key} under its DTO name, and its AI projection when the
     * two differ. A field eligible for a channel that refers to an entity with no field eligible for
     * it is an ERROR, and that channel's record is not generated.
     */
    public void generateDtos(String key, Map<String, EntityModel> registry, int apiMajor, ProcessingEnvironment env) {
        for (String channel : List.of(API, AI)) {
            Map<String, EntityModel> view = view(registry, channel);
            EntityModel entity = view.get(key);
            boolean needed = API.equals(channel) || splits(registry, key);
            if (!needed || entity.fields().isEmpty()) {
                continue;
            }
            boolean emptyRef = false;
            for (FieldModel field : entity.fields()) {
                EntityRefResolver.EntityRef ref = EntityRefResolver.resolve(field, view);
                if (ref != null && view.get(ref.entityClass().canonicalName()).fields().isEmpty()) {
                    env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "[ai-atlas] Field '" + field.name() + "' of " + entity.sourceClassName().simpleName()
                                    + " is eligible for the " + channel + " channel, but "
                                    + ref.entityClass().simpleName() + " has no field eligible for it");
                    emptyRef = true;
                }
            }
            if (!emptyRef) {
                DtoGenerator.generate(entity, view, apiMajor, env.getFiler(), env.getMessager());
            }
        }
    }

    /**
     * Operation channels x the returned entity's field eligibility must leave at least one field on
     * every channel the operation is exposed on; otherwise an ERROR on the method.
     *
     * @return whether every channel has a field
     */
    public boolean checkReturn(ClassName returnEntityType, Set<String> channels, Map<String, EntityModel> registry,
                               String methodName, Element method, Messager messager) {
        boolean ok = true;
        for (String channel : channels) {
            EntityModel entity = view(registry, channel).get(returnEntityType.canonicalName());
            if (entity != null && entity.fields().isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "[ai-atlas] Method '" + methodName + "' is exposed on the " + channel + " channel, but "
                                + returnEntityType.simpleName() + " has no field eligible for it", method);
                ok = false;
            }
        }
        return ok;
    }

    /** The service with each entity-returning method mapped to the AI projection's DTO, for the MCP tool. */
    public ServiceModel withAiReturns(ServiceModel model, Map<String, EntityModel> registry) {
        Map<String, EntityModel> ai = view(registry, AI);
        List<MethodModel> methods = model.methods().stream().map(m -> {
            EntityModel entity = m.returnEntityType() != null ? ai.get(m.returnEntityType().canonicalName()) : null;
            if (entity == null || m.returnDtoType() == null) {
                return m;
            }
            return new MethodModel(m.methodName(), m.toolName(), m.description(), m.returnType(),
                    m.returnEntityType(), entity.dtoClassName(), m.returnKind(), m.parameters(), m.channels(),
                    m.apiSince(), m.apiUntil(), m.apiDeprecatedSince(), m.apiReplacement());
        }).toList();
        return new ServiceModel(model.serviceClassName(), methods);
    }

    /**
     * The entities the OpenAPI document describes: all of them with the option off, as before;
     * with it on, their API projection, leaving out an entity only the AI channel can see.
     */
    public List<EntityModel> openApiEntities(Map<String, EntityModel> registry) {
        if (!enabled) {
            return new ArrayList<>(registry.values());
        }
        List<EntityModel> result = new ArrayList<>();
        view(registry, API).forEach((key, entity) -> {
            if (!entity.fields().isEmpty() || registry.get(key).fields().isEmpty()) {
                result.add(entity);
            }
        });
        return result;
    }
}

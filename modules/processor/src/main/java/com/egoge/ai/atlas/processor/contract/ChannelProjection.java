/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.util.EntityRefResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The per-channel projection of the entities active at one major. The version projection applies
 * first, then the channel projection: an entity's view on a channel holds its active fields
 * eligible for that channel.
 *
 * <p>The API view keeps each entity's DTO name. An entity <em>splits</em> when its AI view must be a
 * record of its own: its AI and API field sets differ, or a field on either view refers, directly or
 * through a collection, iterable or array, to an entity that splits. That is the least fixpoint of
 * the rule, so reference cycles terminate. The AI view names a split entity by its AI record name
 * and every other entity by its DTO name, so the two channels share one record wherever they agree.
 *
 * <p>A direct field's {@code @AgenticField(type)} hint naming an entity is in effect here, as it is
 * for collection elements ({@link EntityRefResolver#withDirectHints}): the field refers to that
 * entity, and the views give it the entity's type, so it maps through the entity's records.
 */
public final class ChannelProjection {

    /** The MCP channel. */
    public static final String AI = "AI";
    /** The REST channel. */
    public static final String API = "API";

    private final Map<String, EntityModel> entities;
    private final BiFunction<String, String, List<String>> eligibility;
    private final Set<String> split;
    private final Map<String, EntityModel> apiView;
    private final Map<String, EntityModel> aiView;

    private ChannelProjection(Map<String, EntityModel> entities, BiFunction<String, String, List<String>> eligibility,
                              Function<String, String> aiDtoNames) {
        this.entities = EntityRefResolver.withDirectHints(entities);
        this.eligibility = eligibility;
        this.split = splitEntities();
        this.apiView = view(API, className -> entities.get(className).dtoName());
        this.aiView = view(AI, className -> split.contains(className)
                ? aiDtoNames.apply(className) : entities.get(className).dtoName());
    }

    /**
     * Projects entities per channel.
     *
     * @param entities    every entity by qualified class name, each holding its fields active at the
     *                    major; the views keep this iteration order
     * @param eligibility the channels a field is eligible for, by entity class name and field name
     * @param aiDtoNames  the AI record's simple name of an entity, by class name; asked only for
     *                    entities that split
     * @return the projection
     */
    public static ChannelProjection of(Map<String, EntityModel> entities,
                                       BiFunction<String, String, List<String>> eligibility,
                                       Function<String, String> aiDtoNames) {
        return new ChannelProjection(entities, eligibility, aiDtoNames);
    }

    /**
     * The AI record name an entity's DTO name implies: {@code OrderDto} becomes {@code OrderAiDto},
     * and a custom {@code OrderSummary} becomes {@code OrderSummaryAiDto}.
     *
     * @param dtoName the entity's DTO simple name
     * @return the derived AI record simple name
     */
    public static String derivedAiDtoName(String dtoName) {
        String base = dtoName.endsWith("Dto") ? dtoName.substring(0, dtoName.length() - "Dto".length()) : dtoName;
        return base + "AiDto";
    }

    /** Whether an entity's AI view is a record of its own, apart from its DTO. */
    public boolean splits(String className) {
        return split.contains(className);
    }

    /** The class names of the entities that split. */
    public Set<String> split() {
        return Collections.unmodifiableSet(split);
    }

    /**
     * Every entity as {@code channel} sees it, in the order given: only its fields eligible for the
     * channel, and named as that channel's record. References inside the view resolve to the same
     * channel's records, so {@code DtoGenerator} generates a channel's records from it unchanged.
     *
     * @param channel {@link #AI} or {@link #API}
     * @return the view by qualified class name
     */
    public Map<String, EntityModel> view(String channel) {
        return switch (channel) {
            case AI -> aiView;
            case API -> apiView;
            default -> throw new IllegalArgumentException("Not a channel: " + channel);
        };
    }

    /**
     * Whether a field is eligible for a channel.
     *
     * @param className qualified name of the entity class
     * @param fieldName Java field name
     * @param channel   {@link #AI} or {@link #API}
     * @return whether the channel's responses carry the field
     */
    public boolean eligible(String className, String fieldName, String channel) {
        return eligibility.apply(className, fieldName).contains(channel);
    }

    private Map<String, EntityModel> view(String channel, Function<String, String> names) {
        Map<String, EntityModel> view = new LinkedHashMap<>();
        entities.forEach((className, entity) -> {
            List<FieldModel> fields = entity.fields().stream()
                    .filter(field -> eligible(className, field.name(), channel)).toList();
            String name = names.apply(className);
            view.put(className, fields.equals(entity.fields()) && name.equals(entity.dtoName()) ? entity
                    : new EntityModel(entity.sourceClassName(), name, entity.dtoPackageName(), entity.displayName(),
                            entity.classDescription(), entity.includeTypeInfo(), fields));
        });
        return Collections.unmodifiableMap(view);
    }

    /**
     * The least fixpoint of "an active field is not eligible for every channel, or an active field
     * refers to an entity that splits". A field is eligible for at least one channel, so a reference
     * to a split entity puts either a different record or a different field set on some channel.
     */
    private Set<String> splitEntities() {
        Set<String> result = new HashSet<>();
        Map<String, Set<String>> referrers = new HashMap<>();
        for (var entry : entities.entrySet()) {
            String className = entry.getKey();
            for (FieldModel field : entry.getValue().fields()) {
                if (eligible(className, field.name(), AI) != eligible(className, field.name(), API)) {
                    result.add(className);
                }
                EntityRefResolver.EntityRef ref = EntityRefResolver.resolve(field, entities);
                if (ref != null) {
                    referrers.computeIfAbsent(ref.entityClass().canonicalName(), k -> new HashSet<>()).add(className);
                }
            }
        }
        List<String> pending = new ArrayList<>(result);
        while (!pending.isEmpty()) {
            String splitEntity = pending.remove(pending.size() - 1);
            for (String referrer : referrers.getOrDefault(splitEntity, Set.of())) {
                if (result.add(referrer)) {
                    pending.add(referrer);
                }
            }
        }
        return result;
    }
}

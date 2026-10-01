/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A document's channel-aware output reachability at a given major: which entities, fields and
 * operations a channel's clients can receive, extracted from {@link ContractComparison} so the
 * release policy can apply the same rule to a channel an element loses (D4.7, D4.9).
 */
public final class ChannelReachability {

    private ChannelReachability() {
    }

    /**
     * The document's entities clients of {@code channel} can receive at {@code major}: those an
     * operation active on the channel returns, and those a field on the channel refers to from one
     * of them.
     *
     * @param ir      the document
     * @param major   the major clients read the document at
     * @param channel the channel
     * @return the reachable entity class names
     */
    public static Set<String> entities(ContractIr ir, int major, String channel) {
        Map<String, Entity> entitiesByName = new HashMap<>();
        ir.entities().forEach(entity -> entitiesByName.put(entity.className(), entity));
        Deque<String> pending = new ArrayDeque<>();
        for (Operation op : activeOperations(ir, major).values()) {
            if (op.channels().contains(channel) && op.returns().reference() != null) {
                pending.add(op.returns().reference().entity());
            }
        }
        Set<String> result = new HashSet<>();
        while (!pending.isEmpty()) {
            String className = pending.pop();
            if (result.add(className)) {
                for (Field field : activeFields(entitiesByName.get(className), major).values()) {
                    if (field.channels().contains(channel) && field.reference() != null) {
                        pending.add(field.reference().entity());
                    }
                }
            }
        }
        return result;
    }

    /**
     * Whether {@code entityClass#field} is visible to clients of {@code channel} at {@code major}:
     * the field is active and lists the channel, and its entity is reachable on the channel.
     *
     * @param ir          the document
     * @param major       the major
     * @param entityClass the entity's class name
     * @param fieldName   the field's name
     * @param channel     the channel
     * @return whether the field is visible on the channel
     */
    public static boolean fieldVisible(ContractIr ir, int major, String entityClass, String fieldName,
                                       String channel) {
        Entity entity = ir.entities().stream().filter(e -> e.className().equals(entityClass)).findFirst()
                .orElse(null);
        Field field = activeFields(entity, major).get(fieldName);
        return field != null && field.channels().contains(channel) && entities(ir, major, channel).contains(entityClass);
    }

    /**
     * Whether the operation is active at {@code major} and lists {@code channel}.
     *
     * @param ir          the document
     * @param major       the major
     * @param operationId the operation's id
     * @param channel     the channel
     * @return whether the operation lists the channel
     */
    public static boolean operationListed(ContractIr ir, int major, String operationId, String channel) {
        Operation op = activeOperations(ir, major).get(operationId);
        return op != null && op.channels().contains(channel);
    }

    private static Map<String, Operation> activeOperations(ContractIr ir, int major) {
        Map<String, Operation> result = new LinkedHashMap<>();
        for (Operation op : ir.operations()) {
            if (ContractProjection.isActive(op.lifecycle(), major)) {
                result.put(op.id(), op);
            }
        }
        return result;
    }

    private static Map<String, Field> activeFields(Entity entity, int major) {
        Map<String, Field> result = new LinkedHashMap<>();
        if (entity != null) {
            for (Field field : entity.fields()) {
                if (ContractProjection.isActive(field.lifecycle(), major)) {
                    result.put(field.name(), field);
                }
            }
        }
        return result;
    }
}

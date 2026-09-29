/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractGate.Direction;
import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.contract.ContractIr.TypeRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.egoge.ai.atlas.processor.contract.ContractGate.DOCUMENT_PATH;
import static com.egoge.ai.atlas.processor.contract.ContractGate.ENTITY_PATH;
import static com.egoge.ai.atlas.processor.contract.ContractGate.FIELD_PATH;
import static com.egoge.ai.atlas.processor.contract.ContractGate.OPERATION_PATH;

/**
 * One comparison of a baseline and a fresh IR, both projected at the baseline's published major
 * M, classifying every difference by direction (FR-009, FR-010), with input constraints and
 * requiredness compared by narrowing and output constraints and hints informational
 * (constraints-and-hints FR-008, FR-009). A field losing a channel breaks the channel's clients
 * when its entity is reachable, through a chain of fields on that channel, from an operation
 * active on it; gaining a channel is compatible, and an entity's AI record appearing or
 * disappearing is informational, as MCP clients never see its name. The REST mapping and the
 * result bound follow {@link RestComparison} and {@link BoundComparison}. Used through
 * {@link ContractGate#compare}.
 */
final class ContractComparison {

    private static final String C_REMOVED = "removed";
    private static final String C_ADDED = "added";
    private static final String C_API_BASE_PATH = "apiBasePath";
    private static final String C_DTO_NAME = "dtoName";
    private static final String C_DTO_PACKAGE = "dtoPackage";
    private static final String C_INCLUDE_TYPE_INFO = "includeTypeInfo";
    private static final String C_DISPLAY_NAME = "displayName";
    private static final String C_DESCRIPTION = "description";
    private static final String C_JAVA_TYPE = "javaType";
    private static final String C_COLLECTION_KIND = "collectionKind";
    private static final String C_ELEMENT_TYPE = "elementType";
    private static final String C_TYPE_HINT = "typeHint";
    private static final String C_REFERENCE = "reference";
    private static final String C_SENSITIVE = "sensitive";
    private static final String C_ALLOWED_VALUES = "allowedValues";
    private static final String C_ENUM_TYPE = "enumType";
    private static final String C_OPEN_ENUM = "openEnum";
    private static final String C_CHECK_CIRCULAR_REFERENCE = "checkCircularReference";
    private static final String C_LIFECYCLE = "lifecycle";
    private static final String C_TOOL_NAME = "toolName";
    private static final String C_CHANNELS = "channels";
    private static final String C_OPERATION_ID = "operationId";
    private static final String C_PARAMETER = "parameter ";
    private static final String C_RETURN = "returns.";
    private static final String C_RETURN_KIND = "returnKind";
    private static final String C_RETURN_TYPE = "returnType";
    private static final String C_CONSTRAINTS = "constraints";
    private static final String C_HINTS = "hints";
    private static final String C_AI_RECORD = "aiRecord";
    private static final String SEPARATE = "separate";
    private static final String SHARED = "shared";

    private final ContractIr baseline;
    private final ContractIr fresh;
    private final int major;
    private final List<Difference> differences = new ArrayList<>();
    /** The entities of the baseline each channel's clients can receive at M, by channel. */
    private final Map<String, Set<String>> reachable = new HashMap<>();

    ContractComparison(ContractIr baseline, ContractIr fresh) {
        this.baseline = baseline;
        this.fresh = fresh;
        this.major = baseline.apiMajor();
    }

    List<Difference> run() {
        if (!Objects.equals(baseline.apiBasePath(), fresh.apiBasePath())) {
            breaking(DOCUMENT_PATH, C_API_BASE_PATH, Direction.INPUT, baseline.apiBasePath(), fresh.apiBasePath(),
                    "The REST base path prefixes every operation's path", restore());
        }
        compareEntities();
        compareOperations();
        differences.sort(Comparator.comparing(Difference::path).thenComparing(Difference::change));
        return differences;
    }

    // ------------------------------------------------------------ entities and fields (output)

    private void compareEntities() {
        Map<String, Entity> before = activeEntities(baseline);
        Map<String, Entity> after = activeEntities(fresh);
        ChannelProjection channelsBefore = channelProjection(baseline);
        ChannelProjection channelsAfter = channelProjection(fresh);
        for (Entity old : before.values()) {
            Entity current = after.get(old.className());
            if (current != null) {
                compareEntity(old, current);
                informational(ENTITY_PATH + old.className(), C_AI_RECORD, Direction.OUTPUT,
                        channelsBefore.splits(old.className()) ? SEPARATE : SHARED,
                        channelsAfter.splits(old.className()) ? SEPARATE : SHARED);
            } else {
                breaking(ENTITY_PATH + old.className(), C_REMOVED, Direction.OUTPUT,
                        old.dtoPackage() + "." + old.dtoName(), null,
                        "Clients of major " + major + " refer to the entity's DTO by this name", fieldRemedy());
            }
            compareFields(old, current);
        }
        for (Entity current : after.values()) {
            if (!before.containsKey(current.className())) {
                compareFields(null, current);
            }
        }
    }

    /** Entities with at least one field active at M: those a DTO is generated for. */
    private Map<String, Entity> activeEntities(ContractIr ir) {
        Map<String, Entity> result = new LinkedHashMap<>();
        for (Entity entity : ir.entities()) {
            if (!activeFields(entity).isEmpty()) {
                result.put(entity.className(), entity);
            }
        }
        return result;
    }

    private Map<String, Field> activeFields(Entity entity) {
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

    private void compareEntity(Entity old, Entity current) {
        String path = ENTITY_PATH + old.className();
        String dtoReason = "Clients of major " + major + " refer to the entity's DTO by this name";
        diff(path, C_DTO_NAME, Direction.OUTPUT, old.dtoName(), current.dtoName(), true, dtoReason, restore());
        diff(path, C_DTO_PACKAGE, Direction.OUTPUT, old.dtoPackage(), current.dtoPackage(), true, dtoReason,
                restore());
        diff(path, C_INCLUDE_TYPE_INFO, Direction.OUTPUT, str(old.includeTypeInfo()),
                str(current.includeTypeInfo()), true,
                "The type information of every serialized " + old.dtoName() + " changes", restore());
        diff(path, C_DISPLAY_NAME, Direction.OUTPUT, old.displayName(), current.displayName(), false, null, null);
        diff(path, C_DESCRIPTION, Direction.OUTPUT, old.description(), current.description(), false, null, null);
    }

    private void compareFields(Entity old, Entity current) {
        String className = old != null ? old.className() : current.className();
        Map<String, Field> before = activeFields(old);
        Map<String, Field> after = activeFields(current);
        for (Field field : before.values()) {
            String path = FIELD_PATH + className + "#" + field.name();
            Field now = after.get(field.name());
            if (now == null) {
                breaking(path, C_REMOVED, Direction.OUTPUT, field.javaType(), null,
                        "Responses no longer carry the field", fieldRemedy());
            } else {
                compareField(path, field, now);
                compareChannels(path, className, field, now);
            }
        }
        for (Field field : after.values()) {
            if (!before.containsKey(field.name())) {
                compatible(FIELD_PATH + className + "#" + field.name(), C_ADDED, Direction.OUTPUT, null,
                        field.javaType());
            }
        }
    }

    private void compareField(String path, Field old, Field now) {
        String schema = "The field's schema in responses changes";
        diff(path, C_DISPLAY_NAME, Direction.OUTPUT, old.displayName(), now.displayName(), true,
                "Clients read the field under its display name", fieldRemedy());
        diff(path, C_JAVA_TYPE, Direction.OUTPUT, old.javaType(), now.javaType(), true, schema, fieldRemedy());
        diff(path, C_COLLECTION_KIND, Direction.OUTPUT, old.collectionKind(), now.collectionKind(), true, schema,
                fieldRemedy());
        diff(path, C_ELEMENT_TYPE, Direction.OUTPUT, old.elementType(), now.elementType(), true, schema,
                fieldRemedy());
        diff(path, C_TYPE_HINT, Direction.OUTPUT, old.typeHint(), now.typeHint(), true,
                "The DTO the field's elements are mapped to changes", fieldRemedy());
        diff(path, C_REFERENCE, Direction.OUTPUT, ref(old.reference()), ref(now.reference()), true,
                "The DTO the field refers to changes", fieldRemedy());
        if (old.sensitive() != now.sensitive()) {
            diff(path, C_SENSITIVE, Direction.OUTPUT, str(old.sensitive()), str(now.sensitive()),
                    now.sensitive(), "Runtime interceptors start masking the field's value", fieldRemedy());
        }
        if (!old.allowedValues().equals(now.allowedValues())) {
            List<String> added = now.allowedValues().stream().filter(v -> !old.allowedValues().contains(v))
                    .toList();
            diff(path, C_ALLOWED_VALUES, Direction.OUTPUT, list(old.allowedValues()), list(now.allowedValues()),
                    !added.isEmpty() && !now.openEnum(),
                    "Responses may carry " + list(added) + ", unknown to clients of a closed enum",
                    "declare openEnum = true on the field if clients tolerate unknown values");
        }
        diff(path, C_ENUM_TYPE, Direction.OUTPUT, str(old.enumType()), str(now.enumType()), false, null, null);
        diff(path, C_OPEN_ENUM, Direction.OUTPUT, str(old.openEnum()), str(now.openEnum()), false, null, null);
        diff(path, C_CHECK_CIRCULAR_REFERENCE, Direction.OUTPUT, str(old.checkCircularReference()),
                str(now.checkCircularReference()), false, null, null);
        diff(path, C_DESCRIPTION, Direction.OUTPUT, old.description(), now.description(), false, null, null);
        diff(path, C_LIFECYCLE, Direction.OUTPUT, str(old.lifecycle()), str(now.lifecycle()), false, null, null);
        if (old.constraints() != null && now.constraints() != null
                && ConstraintComparison.differ(old.constraints(), now.constraints(), old.javaType())) {
            informational(path, C_CONSTRAINTS, Direction.OUTPUT, render(IrConstraintsJson.write(old.constraints())),
                    render(IrConstraintsJson.write(now.constraints())));
        }
    }

    /**
     * A channel the field loses is breaking when the baseline's clients of that channel can receive
     * the entity, and compatible otherwise; a channel it gains is compatible.
     */
    private void compareChannels(String path, String className, Field old, Field now) {
        for (String channel : Field.EVERY_CHANNEL) {
            boolean before = old.channels().contains(channel);
            boolean after = now.channels().contains(channel);
            String change = C_CHANNELS + "." + channel;
            if (before && !after && reachable(channel).contains(className)) {
                breaking(path, change, Direction.OUTPUT, list(old.channels()), list(now.channels()),
                        "Clients of the " + channel + " channel no longer receive the field",
                        newMajorRemedy(major, "a field's channels have no lifecycle of their own"));
            } else if (before != after) {
                compatible(path, change, Direction.OUTPUT, list(old.channels()), list(now.channels()));
            }
        }
    }

    /**
     * The baseline entities clients of {@code channel} can receive at M: those an operation active
     * on the channel returns, and those a field on the channel refers to from one of them.
     */
    private Set<String> reachable(String channel) {
        return reachable.computeIfAbsent(channel, c -> {
            Map<String, Entity> entities = new HashMap<>();
            baseline.entities().forEach(entity -> entities.put(entity.className(), entity));
            Deque<String> pending = new ArrayDeque<>();
            for (Operation op : activeOperations(baseline).values()) {
                if (op.channels().contains(c) && op.returns().reference() != null) {
                    pending.add(op.returns().reference().entity());
                }
            }
            Set<String> result = new HashSet<>();
            while (!pending.isEmpty()) {
                String className = pending.pop();
                if (result.add(className)) {
                    for (Field field : activeFields(entities.get(className)).values()) {
                        if (field.channels().contains(c) && field.reference() != null) {
                            pending.add(field.reference().entity());
                        }
                    }
                }
            }
            return result;
        });
    }

    /** The channel projection of a document's entities at M, with each field's recorded channels. */
    private ChannelProjection channelProjection(ContractIr ir) {
        Map<String, List<String>> channels = new HashMap<>();
        ir.entities().forEach(entity -> entity.fields().forEach(field ->
                channels.put(entity.className() + "#" + field.name(), field.channels())));
        return ChannelProjection.of(ContractProjection.of(ir, major).entities(),
                (className, fieldName) -> channels.get(className + "#" + fieldName), className -> className);
    }

    /**
     * The remedy for a change to a slot with no lifecycle of its own: a new major, or accepting it.
     *
     * @param major the published major M
     * @param why   why no lifecycle declaration applies, e.g. {@code a field's channels have no
     *              lifecycle of their own}
     */
    static String newMajorRemedy(int major, String why) {
        return "publish it in a new major (" + AgenticProcessor.OPT_API_MAJOR + " = " + (major + 1) + ", then "
                + ContractGate.ACCEPT_TASK + "), as " + why;
    }

    private String fieldRemedy() {
        return "declare @AgenticField(removedInVersion = " + (major + 1) + ") on the old field, with any"
                + " replacement as a new field with sinceVersion = " + (major + 1);
    }

    // ------------------------------------------------------------ operations (input) and returns (output)

    private void compareOperations() {
        Map<String, Operation> before = activeOperations(baseline);
        Map<String, Operation> after = activeOperations(fresh);
        Map<String, String> idsBefore = ContractProjection.of(baseline, major).operationIds();
        Map<String, String> idsAfter = ContractProjection.of(fresh, major).operationIds();
        // API operations that are new to the API channel at M: added, or existing ones that gained it
        List<Operation> newlyApi = idsAfter.keySet().stream().filter(id -> !idsBefore.containsKey(id))
                .map(after::get).toList();
        for (Operation op : before.values()) {
            String path = OPERATION_PATH + op.id();
            Operation now = after.get(op.id());
            if (now == null) {
                breaking(path, C_REMOVED, Direction.INPUT, op.id(), null,
                        "Clients can no longer call the operation", operationRemedy());
                continue;
            }
            compareOperation(path, op, now);
            // operationIds() covers only API-channel operations: a null id is an MCP-only operation, which
            // has no operationId to compare; losing the API channel is reported as a channels change
            String idBefore = idsBefore.get(op.id());
            String idAfter = idsAfter.get(op.id());
            if (idBefore != null && idAfter != null && !idBefore.equals(idAfter)) {
                List<String> causes = newlyApi.stream().filter(o -> o.method().equals(op.method()))
                        .map(o -> OPERATION_PATH + o.id()).toList();
                breaking(path, C_OPERATION_ID, Direction.INPUT, idBefore, idAfter,
                        "Clients generated from the OpenAPI document call the operation by its operationId",
                        (causes.isEmpty() ? "restore the previous operation set"
                                : "give the operation(s) whose addition caused it (" + String.join(", ", causes)
                                + ") a method name that does not collide, or declare apiSince = " + (major + 1)
                                + " on them"));
            }
        }
        for (Operation op : after.values()) {
            if (!before.containsKey(op.id())) {
                compatible(OPERATION_PATH + op.id(), C_ADDED, Direction.INPUT, null, op.id());
            }
        }
    }

    private Map<String, Operation> activeOperations(ContractIr ir) {
        Map<String, Operation> result = new LinkedHashMap<>();
        for (Operation op : ir.operations()) {
            if (ContractProjection.isActive(op.lifecycle(), major)) {
                result.put(op.id(), op);
            }
        }
        return result;
    }

    private void compareOperation(String path, Operation old, Operation now) {
        diff(path, C_TOOL_NAME, Direction.INPUT, old.toolName(), now.toolName(), true,
                "MCP clients call the operation by its tool name", operationRemedy());
        if (!old.channels().equals(now.channels())) {
            List<String> removed = old.channels().stream().filter(c -> !now.channels().contains(c)).toList();
            diff(path, C_CHANNELS, Direction.INPUT, list(old.channels()), list(now.channels()),
                    !removed.isEmpty(), "Clients of the " + list(removed) + " channel lose the operation",
                    operationRemedy());
        }
        RestComparison.compare(path, old, now, major, operationRemedy(), differences);
        if (old.parameters().size() != now.parameters().size()) {
            // Unreachable while Operation.id() carries the parameter types; guards a future change to the identity
            breaking(path, C_PARAMETER + "count", Direction.INPUT, str(old.parameters().size()),
                    str(now.parameters().size()), "The operation's parameter list changes", operationRemedy());
            return;
        }
        for (int i = 0; i < old.parameters().size(); i++) {
            compareParameter(path, i, old.parameters().get(i), now.parameters().get(i),
                    RestComparison.renameIsCompatible(old, now, i));
            ConstraintComparison.compare(old, old.parameters().get(i), now.parameters().get(i), operationRemedy(),
                    differences);
        }
        String returns = "The operation's response schema changes";
        ContractIr.Return r0 = old.returns();
        ContractIr.Return r1 = now.returns();
        diff(path, C_RETURN + C_JAVA_TYPE, Direction.OUTPUT, r0.javaType(), r1.javaType(), true, returns,
                operationRemedy());
        diff(path, C_RETURN + C_RETURN_KIND, Direction.OUTPUT, r0.returnKind(), r1.returnKind(), true, returns,
                operationRemedy());
        diff(path, C_RETURN + C_RETURN_TYPE, Direction.OUTPUT, r0.returnType(), r1.returnType(), true,
                "The DTO the operation returns changes", operationRemedy());
        diff(path, C_RETURN + C_REFERENCE, Direction.OUTPUT, ref(r0.reference()), ref(r1.reference()), true,
                "The DTO the operation returns changes", operationRemedy());
        BoundComparison.compare(path, r0.bound(), r1.bound(), major, differences);
        diff(path, C_DESCRIPTION, Direction.INPUT, old.description(), now.description(), false, null, null);
        diff(path, C_LIFECYCLE, Direction.INPUT, str(old.lifecycle()), str(now.lifecycle()), false, null, null);
        if (old.hints() != null && now.hints() != null) {
            informational(path, C_HINTS, Direction.INPUT, render(IrConstraintsJson.write(old.hints())),
                    render(IrConstraintsJson.write(now.hints())));
        }
    }

    /**
     * Parameters at the same index; the operation identity already fixes their number and types. A
     * rename is breaking unless no client sends the name ({@link RestComparison#renameIsCompatible}).
     */
    private void compareParameter(String path, int index, Parameter old, Parameter now, boolean renameCompatible) {
        String change = C_PARAMETER + index + ".";
        diff(path, change + "name", Direction.INPUT, old.name(), now.name(), !renameCompatible,
                "Clients pass the parameter by its name", operationRemedy());
        diff(path, change + C_DESCRIPTION, Direction.INPUT, old.description(), now.description(), false,
                null, null);
        if (!old.enumConstants().equals(now.enumConstants())) {
            List<String> removed = old.enumConstants().stream()
                    .filter(v -> !now.enumConstants().contains(v)).toList();
            diff(path, change + "enumConstants", Direction.INPUT, list(old.enumConstants()),
                    list(now.enumConstants()), !removed.isEmpty(),
                    "Requests carrying " + list(removed) + " are no longer accepted", operationRemedy());
        }
    }

    private String operationRemedy() {
        return "declare @AgenticExposed(apiUntil = " + major + ") on the old operation, plus a replacement"
                + " with apiSince = " + (major + 1);
    }

    private static String restore() {
        return "restore the previous value";
    }

    // ------------------------------------------------------------ recording

    private void diff(String path, String change, Direction direction, String before, String after,
                      boolean breaking, String reason, String remedy) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (breaking) {
            breaking(path, change, direction, before, after, reason, remedy);
        } else {
            compatible(path, change, direction, before, after);
        }
    }

    private void breaking(String path, String change, Direction direction, String before, String after,
                          String reason, String remedy) {
        differences.add(new Difference(path, change, direction, before, after, Classification.BREAKING,
                reason, remedy));
    }

    private void compatible(String path, String change, Direction direction, String before, String after) {
        differences.add(new Difference(path, change, direction, before, after, Classification.COMPATIBLE,
                null, null));
    }

    /** A difference that never fails the build outside lock mode (FR-009); nothing when the values are equal. */
    private void informational(String path, String change, Direction direction, String before, String after) {
        if (!Objects.equals(before, after)) {
            differences.add(new Difference(path, change, direction, before, after, Classification.INFORMATIONAL,
                    null, null));
        }
    }

    /** The IR's own form of a constraints or hints object, {@code null} when it sets nothing. */
    private static String render(Map<String, Object> slot) {
        return slot.isEmpty() ? null : slot.toString();
    }

    private static String str(Object value) {
        return String.valueOf(value);
    }

    private static String ref(TypeRef ref) {
        return ref != null ? ref.entity() + " as " + ref.dto() : null;
    }

    private static String list(List<String> values) {
        return values.stream().collect(Collectors.joining(", ", "[", "]"));
    }
}

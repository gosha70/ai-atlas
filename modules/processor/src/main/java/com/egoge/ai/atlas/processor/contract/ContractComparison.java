/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractGate.Direction;
import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.contract.ContractIr.TypeRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.egoge.ai.atlas.processor.contract.ContractGate.DOCUMENT_PATH;
import static com.egoge.ai.atlas.processor.contract.ContractGate.ENTITY_PATH;
import static com.egoge.ai.atlas.processor.contract.ContractGate.FIELD_PATH;
import static com.egoge.ai.atlas.processor.contract.ContractGate.OPERATION_PATH;

/**
 * One comparison of a baseline and a fresh IR, both projected at the baseline's published major
 * M, classifying every difference by direction (FR-009, FR-010). Used through
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
    private static final String C_HTTP_METHOD = "rest.httpMethod";
    private static final String C_REST_PATH = "rest.path";
    private static final String C_OPERATION_ID = "operationId";
    private static final String C_PARAMETER = "parameter ";
    private static final String C_RETURN = "returns.";
    private static final String C_RETURN_KIND = "returnKind";
    private static final String C_RETURN_TYPE = "returnType";

    private final ContractIr baseline;
    private final ContractIr fresh;
    private final int major;
    private final List<Difference> differences = new ArrayList<>();

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
        for (Entity old : before.values()) {
            Entity current = after.get(old.className());
            if (current != null) {
                compareEntity(old, current);
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
        if (old.rest() != null && now.rest() != null) {
            diff(path, C_HTTP_METHOD, Direction.INPUT, old.rest().httpMethod(), now.rest().httpMethod(), true,
                    "REST clients call the operation with this HTTP method", operationRemedy());
            diff(path, C_REST_PATH, Direction.INPUT, old.rest().path(), now.rest().path(), true,
                    "REST clients call the operation at this path", operationRemedy());
        }
        if (old.parameters().size() != now.parameters().size()) {
            // Unreachable while Operation.id() carries the parameter types; guards a future change to the identity
            breaking(path, C_PARAMETER + "count", Direction.INPUT, str(old.parameters().size()),
                    str(now.parameters().size()), "The operation's parameter list changes", operationRemedy());
            return;
        }
        for (int i = 0; i < old.parameters().size(); i++) {
            compareParameter(path, i, old.parameters().get(i), now.parameters().get(i));
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
        diff(path, C_DESCRIPTION, Direction.INPUT, old.description(), now.description(), false, null, null);
        diff(path, C_LIFECYCLE, Direction.INPUT, str(old.lifecycle()), str(now.lifecycle()), false, null, null);
    }

    /** Parameters at the same index; the operation identity already fixes their number and types. */
    private void compareParameter(String path, int index, Parameter old, Parameter now) {
        String change = C_PARAMETER + index + ".";
        diff(path, change + "name", Direction.INPUT, old.name(), now.name(), true,
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

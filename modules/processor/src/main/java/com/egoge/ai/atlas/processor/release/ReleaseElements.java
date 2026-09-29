/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractProjection;
import com.egoge.ai.atlas.processor.contract.ReleaseComparison;

/**
 * The field or operation a gate element path names in an IR document, and its deprecation as
 * that document published it.
 */
final class ReleaseElements {

    private ReleaseElements() {
    }

    /** Whether the path names a field, {@code field <class>#<name>}. */
    static boolean isField(String path) {
        return path.startsWith(ContractGate.FIELD_PATH);
    }

    /** Whether the path names an operation, {@code operation <class>#<method>(<types>)}. */
    static boolean isOperation(String path) {
        return path.startsWith(ContractGate.OPERATION_PATH);
    }

    /** Whether the path names an entity, {@code entity <class>}. */
    static boolean isEntity(String path) {
        return path.startsWith(ContractGate.ENTITY_PATH);
    }

    /** The field a field path names, or {@code null} when the document has none. */
    static ContractIr.Field field(ContractIr ir, String path) {
        String target = path.substring(ContractGate.FIELD_PATH.length());
        int hash = target.indexOf('#');
        String className = target.substring(0, hash);
        String name = target.substring(hash + 1);
        return ir.entities().stream().filter(e -> e.className().equals(className))
                .flatMap(e -> e.fields().stream()).filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    /** The operation an operation path names, or {@code null} when the document has none. */
    static ContractIr.Operation operation(ContractIr ir, String path) {
        String id = path.substring(ContractGate.OPERATION_PATH.length());
        return ir.operations().stream().filter(op -> op.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * The deprecation major of the field or operation {@code path} names, when the document
     * published it deprecated: active at the document's own major, with a deprecation in effect
     * there. Otherwise {@code 0}, as for an entity path.
     */
    static int publishedDeprecation(ContractIr ir, String path) {
        int major = ir.apiMajor();
        if (isField(path)) {
            ContractIr.Field f = field(ir, path);
            return f != null && ContractProjection.isActive(f.lifecycle(), major)
                    && ReleaseComparison.deprecatedAt(f.lifecycle().deprecatedSinceVersion(), major)
                    ? f.lifecycle().deprecatedSinceVersion() : 0;
        }
        if (isOperation(path)) {
            ContractIr.Operation op = operation(ir, path);
            return op != null && ContractProjection.isActive(op.lifecycle(), major)
                    && ReleaseComparison.deprecatedAt(op.lifecycle().apiDeprecatedSince(), major)
                    ? op.lifecycle().apiDeprecatedSince() : 0;
        }
        return 0;
    }
}

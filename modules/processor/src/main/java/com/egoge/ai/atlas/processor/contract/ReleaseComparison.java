/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares what two releases published, each reduced to its surface at its own {@code apiMajor},
 * so the gate's own {@link ContractComparison} can compare two releases (D9.1).
 *
 * <p>{@link ContractGate#compare} projects both documents at the baseline's major, where a declared
 * removal ({@code removedInVersion = M+1}, {@code apiUntil = M}) is invisible by design. Between
 * two releases whose majors differ, that removal is exactly what must be reported. So each document
 * is reduced to the elements active at its own major, each active at every major and keeping only
 * the deprecation in effect at its own major. A lifecycle difference between two surfaces is then a
 * deprecation change, and a declared removal is a removal. The reasons of the comparison name the
 * previous release's major, whose clients the differences affect.
 */
public final class ReleaseComparison {

    private ReleaseComparison() {
    }

    /**
     * Compares the surfaces of {@code previous} and {@code current}, each reduced to its own
     * {@code apiMajor}.
     *
     * @param previous the previous release's IR
     * @param current  the current release's IR
     * @return every difference, ordered by element path, then attribute
     * @throws IllegalArgumentException if a document carries a type string that is not canonical
     */
    public static List<Difference> compare(ContractIr previous, ContractIr current) {
        int major = previous.apiMajor();
        return ContractGate.compare(of(previous, major), of(current, major));
    }

    /**
     * Whether a declared deprecation major is in effect at {@code major}.
     *
     * @param deprecatedSince the declared deprecation major, {@code 0} if never
     * @param major           the major
     * @return whether the element is deprecated at {@code major}
     */
    public static boolean deprecatedAt(int deprecatedSince, int major) {
        return deprecatedSince > 0 && deprecatedSince <= major;
    }

    /**
     * What {@code ir} published at its own major, as a document at {@code major} whose elements
     * are active at every major.
     *
     * @param ir    the document
     * @param major the major the reduced document is compared at
     * @return the reduced document
     */
    private static ContractIr of(ContractIr ir, int major) {
        int own = ir.apiMajor();
        List<ContractIr.Entity> entities = new ArrayList<>();
        for (ContractIr.Entity e : ir.entities()) {
            List<ContractIr.Field> fields = new ArrayList<>();
            for (ContractIr.Field f : e.fields()) {
                ContractIr.FieldLifecycle life = f.lifecycle();
                if (ContractProjection.isActive(life, own)) {
                    boolean deprecated = deprecatedAt(life.deprecatedSinceVersion(), own);
                    fields.add(f.withLifecycle(new ContractIr.FieldLifecycle(1, Integer.MAX_VALUE,
                            deprecated ? life.deprecatedSinceVersion() : 0,
                            deprecated ? life.deprecatedMessage() : null)));
                }
            }
            entities.add(new ContractIr.Entity(e.className(), e.dtoName(), e.dtoPackage(), e.displayName(),
                    e.description(), e.includeTypeInfo(), fields));
        }
        List<ContractIr.Operation> operations = new ArrayList<>();
        for (ContractIr.Operation op : ir.operations()) {
            ContractIr.OperationLifecycle life = op.lifecycle();
            if (ContractProjection.isActive(life, own)) {
                boolean deprecated = deprecatedAt(life.apiDeprecatedSince(), own);
                operations.add(op.withLifecycle(new ContractIr.OperationLifecycle(1, Integer.MAX_VALUE,
                        deprecated ? life.apiDeprecatedSince() : 0,
                        deprecated ? life.apiReplacement() : null)));
            }
        }
        return new ContractIr(ir.irVersion(), ir.apiBasePath(), major, entities, operations);
    }
}

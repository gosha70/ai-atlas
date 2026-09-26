/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/**
 * The contract of a compilation that declares no {@code @AgenticEntity} or {@code @AgenticExposed}
 * at all (FR-008, ADR-8). javac does not invoke the processor for such a compilation, so the tools
 * that run it call this check when compilation succeeded without emitting
 * {@link ContractIr#RESOURCE_PATH}: it compares the empty IR with the baseline through the gate's
 * own comparison, so every element the baseline publishes at its major is reported as removed.
 */
public final class EmptyContract {

    private EmptyContract() {
    }

    /**
     * The empty IR document.
     *
     * @param apiBasePath the configured REST base path; trailing slashes are dropped, as the
     *                    processor drops them
     * @param apiMajor    the configured major
     * @return a document with no entities and no operations
     */
    public static ContractIr document(String apiBasePath, int apiMajor) {
        String basePath = apiBasePath;
        while (basePath.endsWith("/") && basePath.length() > 1) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        return new ContractIr(ContractIr.IR_VERSION, basePath, apiMajor, List.of(), List.of());
    }

    /**
     * The empty IR document in the canonical form of {@link IrJson} (FR-003), as {@code atlasAccept}
     * writes it when the sources declare nothing.
     *
     * @param apiBasePath the configured REST base path
     * @param apiMajor    the configured major
     * @return canonical JSON text
     */
    public static String json(String apiBasePath, int apiMajor) {
        return IrJson.write(document(apiBasePath, apiMajor));
    }

    /**
     * Checks the empty contract against the baseline, with the gate's rules and messages, including
     * lock mode (FR-014). When the baseline publishes elements at its major, the per-element errors
     * are preceded by one error saying the compilation declares nothing.
     *
     * @param baselinePath the {@code ai.atlas.contract.baseline} value, or {@code null}
     * @param locked       the {@code ai.atlas.contract.locked} value
     * @param apiBasePath  the configured REST base path
     * @param apiMajor     the configured major
     * @return the gate's outcome; {@link ContractGate.Outcome#failed()} means the build must fail
     */
    public static ContractGate.Outcome check(String baselinePath, boolean locked, String apiBasePath, int apiMajor) {
        ContractGate.Outcome outcome = ContractGate.check(baselinePath, locked, document(apiBasePath, apiMajor));
        // A removal is a breaking difference whose after value is null; apiBasePath and channels changes
        // have non-null after values and are excluded
        long removed = outcome.differences().stream()
                .filter(d -> d.breaking() && d.after() == null).count();
        if (removed == 0) {
            return outcome;
        }
        List<ContractGate.Finding> findings = new ArrayList<>();
        findings.add(new ContractGate.Finding(Diagnostic.Kind.ERROR, null, "[ai-atlas] The compilation declares no"
                + " @AgenticEntity or @AgenticExposed, but the contract baseline " + baselinePath + " publishes "
                + removed + " element(s) at major " + outcome.publishedMajor() + " that would be removed."
                + " Restore the annotations, or accept the removal explicitly with " + ContractGate.ACCEPT_TASK + "."));
        findings.addAll(outcome.findings());
        return new ContractGate.Outcome(findings, outcome.publishedMajor(), outcome.differences(), outcome.diffJson());
    }
}

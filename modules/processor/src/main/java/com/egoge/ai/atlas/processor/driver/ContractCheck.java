/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.EmptyContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The empty-contract check of {@link AtlasGenerator} (FR-008, FR-017). javac does not run the
 * processor for sources that declare no ai-atlas annotation, so a compilation that succeeded
 * without emitting {@link ContractIr#RESOURCE_PATH} is checked with {@link EmptyContract} whenever
 * a baseline or lock option is passed. Its failure fails the run.
 */
final class ContractCheck {

    /** The processor's default major, applied here because the processor was not invoked. */
    private static final String DEFAULT_API_MAJOR = "1";
    /** The processor's default base path, applied here because the processor was not invoked. */
    private static final String DEFAULT_API_BASE_PATH = "/api";

    private ContractCheck() {
    }

    /**
     * @param success     whether the compilation succeeded
     * @param classOutput the compilation's class output directory
     * @param options     the processor options
     * @return the check's findings; empty when it does not apply
     */
    static List<Diagnostic> after(boolean success, Path classOutput, Map<String, String> options) {
        return success && !Files.exists(classOutput.resolve(ContractIr.RESOURCE_PATH)) ? run(options) : List.of();
    }

    /**
     * @param success     whether the compilation succeeded
     * @param classOutput the relative paths of the compilation's class output
     * @param options     the processor options
     * @return the check's findings; empty when it does not apply
     */
    static List<Diagnostic> after(boolean success, Set<String> classOutput, Map<String, String> options) {
        return success && !classOutput.contains(ContractIr.RESOURCE_PATH) ? run(options) : List.of();
    }

    /** Whether the findings let the run succeed. */
    static boolean passed(List<Diagnostic> findings) {
        return findings.stream().noneMatch(Diagnostic::isError);
    }

    /** The compiler's diagnostics followed by the check's findings. */
    static List<Diagnostic> append(List<Diagnostic> compiler, List<Diagnostic> findings) {
        List<Diagnostic> all = new ArrayList<>(compiler);
        all.addAll(findings);
        return all;
    }

    /** Runs the check when a baseline or lock option is passed; its findings carry no source. */
    private static List<Diagnostic> run(Map<String, String> options) {
        String baseline = options.get(AgenticProcessor.OPT_CONTRACT_BASELINE);
        String lockedValue = options.get(AgenticProcessor.OPT_CONTRACT_LOCKED);
        if (baseline == null && lockedValue == null) {
            return List.of();
        }
        Boolean locked = ContractGate.parseLocked(lockedValue);
        if (locked == null) {
            return List.of(error(ContractGate.invalidLockedMessage(lockedValue)));
        }
        String majorValue = options.getOrDefault(AgenticProcessor.OPT_API_MAJOR, DEFAULT_API_MAJOR);
        int major;
        try {
            major = Integer.parseInt(majorValue.trim());
        } catch (NumberFormatException e) {
            return List.of(error("[ai-atlas] " + AgenticProcessor.OPT_API_MAJOR + " must be an integer. Got: "
                    + majorValue));
        }
        String basePath = options.getOrDefault(AgenticProcessor.OPT_API_BASE_PATH, DEFAULT_API_BASE_PATH);
        return EmptyContract.check(baseline, locked, basePath, major).findings().stream()
                .map(f -> new Diagnostic(severity(f.kind()), f.message(), "")).toList();
    }

    private static Diagnostic error(String message) {
        return new Diagnostic(Diagnostic.Severity.ERROR, message, "");
    }

    private static Diagnostic.Severity severity(javax.tools.Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> Diagnostic.Severity.ERROR;
            case WARNING, MANDATORY_WARNING -> Diagnostic.Severity.WARNING;
            case NOTE -> Diagnostic.Severity.NOTE;
            default -> Diagnostic.Severity.OTHER;
        };
    }
}

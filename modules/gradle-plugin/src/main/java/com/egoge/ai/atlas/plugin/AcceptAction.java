/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.EmptyContract;
import com.egoge.ai.atlas.processor.contract.IrJson;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes the current contract to the baseline (FR-015): the IR the processor emitted, byte for
 * byte, or {@link EmptyContract}'s document when the sources declare nothing. Before writing, it
 * prints the differences being accepted, grouped by classification. Loaded from the project's
 * {@code annotationProcessor} classpath in an isolated class loader.
 */
public abstract class AcceptAction implements WorkAction<AcceptAction.Parameters> {

    private static final Logger LOGGER = Logging.getLogger(AcceptAction.class);
    private static final String INDENT = "    ";

    /** The accept step's inputs. */
    public interface Parameters extends WorkParameters {

        /** The baseline file to write. */
        RegularFileProperty getBaseline();

        /** The emitted IR, or unset when the sources declare nothing. */
        RegularFileProperty getFreshIr();

        /** The configured REST base path, for the empty document. */
        Property<String> getApiBasePath();

        /** The configured major, for the empty document. */
        Property<Integer> getApiMajor();
    }

    @Override
    public void execute() {
        Parameters parameters = getParameters();
        Path baseline = parameters.getBaseline().get().getAsFile().toPath();
        try {
            byte[] fresh = parameters.getFreshIr().isPresent()
                    ? Files.readAllBytes(parameters.getFreshIr().get().getAsFile().toPath())
                    : EmptyContract.json(parameters.getApiBasePath().get(), parameters.getApiMajor().get())
                            .getBytes(StandardCharsets.UTF_8);
            report(baseline, IrJson.parse(new String(fresh, StandardCharsets.UTF_8), "the current contract"));
            Path parent = baseline.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(baseline, fresh);
        } catch (IOException | IrJson.IrReadException e) {
            throw new GradleException("atlasAccept failed to write " + baseline + ": " + e.getMessage(), e);
        }
        LOGGER.lifecycle("[ai-atlas] Accepted the current contract as the baseline " + baseline);
    }

    /** Prints the differences from the previous baseline, grouped by classification. */
    private static void report(Path baseline, ContractIr fresh) {
        if (!Files.isRegularFile(baseline)) {
            LOGGER.lifecycle("[ai-atlas] No previous baseline at " + baseline + "; writing it.");
            return;
        }
        List<ContractGate.Difference> differences;
        List<String> documentPaths;
        try {
            ContractIr previous = IrJson.read(baseline);
            differences = ContractGate.compare(previous, fresh);
            documentPaths = ContractGate.documentDifferences(previous, fresh);
        } catch (IrJson.IrReadException | IllegalArgumentException e) {
            LOGGER.lifecycle("[ai-atlas] The previous baseline cannot be compared (" + e.getMessage()
                    + "); replacing it.");
            return;
        }
        Set<String> classified = differences.stream().map(ContractGate.Difference::path).collect(Collectors.toSet());
        List<String> other = documentPaths.stream().filter(p -> !classified.contains(p)).toList();
        if (differences.isEmpty() && other.isEmpty()) {
            LOGGER.lifecycle("[ai-atlas] The contract is unchanged from the baseline " + baseline + ".");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("[ai-atlas] Accepting the contract differences from the baseline " + baseline + ":");
        for (ContractGate.Classification classification : ContractGate.Classification.values()) {
            List<ContractGate.Difference> group = differences.stream()
                    .filter(d -> d.classification() == classification).toList();
            if (!group.isEmpty()) {
                lines.add("  " + classification.name().toLowerCase(Locale.ROOT) + " (" + group.size() + "):");
                group.forEach(d -> lines.add(INDENT + d.path() + ": " + d.change() + " " + value(d.before())
                        + " → " + value(d.after()) + " (" + d.direction().name().toLowerCase(Locale.ROOT) + ")"));
            }
        }
        if (!other.isEmpty()) {
            lines.add("  outside the projection at the published major (" + other.size() + "):");
            other.forEach(p -> lines.add(INDENT + p));
        }
        LOGGER.lifecycle(String.join(System.lineSeparator(), lines));
    }

    private static String value(String value) {
        return value != null ? value : "(none)";
    }
}

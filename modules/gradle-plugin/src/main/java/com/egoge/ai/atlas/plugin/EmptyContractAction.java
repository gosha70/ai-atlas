/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.EmptyContract;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import javax.tools.Diagnostic;
import java.util.List;

/**
 * Runs the processor's {@link EmptyContract} check (FR-008) for a compilation that declares no
 * ai-atlas contract. Loaded from the project's {@code annotationProcessor} classpath in an isolated
 * class loader, so the rules and messages are those of the processor version that compiled.
 */
public abstract class EmptyContractAction implements WorkAction<EmptyContractAction.Parameters> {

    private static final Logger LOGGER = Logging.getLogger(EmptyContractAction.class);

    /** The check's inputs. */
    public interface Parameters extends WorkParameters {

        /** The baseline file; it may not exist. */
        RegularFileProperty getBaseline();

        /** Whether lock mode is on. */
        Property<Boolean> getLocked();

        /** The configured REST base path. */
        Property<String> getApiBasePath();

        /** The configured major. */
        Property<Integer> getApiMajor();
    }

    @Override
    public void execute() {
        Parameters parameters = getParameters();
        ContractGate.Outcome outcome = EmptyContract.check(
                parameters.getBaseline().get().getAsFile().getAbsolutePath(), parameters.getLocked().get(),
                parameters.getApiBasePath().get(), parameters.getApiMajor().get());
        for (ContractGate.Finding finding : outcome.findings()) {
            if (finding.kind() == Diagnostic.Kind.WARNING) {
                LOGGER.warn(finding.message());
            } else if (finding.kind() != Diagnostic.Kind.ERROR) {
                LOGGER.info(finding.message());
            }
        }
        List<String> errors = outcome.findings().stream().filter(f -> f.kind() == Diagnostic.Kind.ERROR)
                .map(ContractGate.Finding::message).toList();
        if (!errors.isEmpty()) {
            throw new GradleException("The ai-atlas contract check failed:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), errors));
        }
    }
}

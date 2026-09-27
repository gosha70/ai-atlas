/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.contract.ContractProjection;
import com.egoge.ai.atlas.processor.model.ServiceModel;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import java.util.List;

/**
 * The {@code ai.atlas.constraints} option of a compilation (FR-012): {@code true} or {@code false}
 * in any case, default {@code false}. With it on, the generated surfaces carry the constraints and
 * declared hints; with it off, generation is exactly what it was before this feature. With it on,
 * an AI-channel method with no declared hint gets a WARNING, an ERROR under {@code ai.atlas.strict}
 * ({@link com.egoge.ai.atlas.processor.util.QualityDiagnostics#reportMissingHints}).
 */
public final class ConstraintsOption {

    private final boolean enabled;
    private final boolean beanValidation;
    private final ProcessingEnvironment env;

    private ConstraintsOption(boolean enabled, boolean beanValidation, ProcessingEnvironment env) {
        this.enabled = enabled;
        this.beanValidation = beanValidation;
        this.env = env;
    }

    /**
     * Reads the option, reporting an ERROR naming it and its value when it is neither
     * {@code true} nor {@code false}.
     *
     * @param option the option name
     * @param env    the processing environment
     * @return the option, or {@code null} after reporting an invalid value
     */
    public static ConstraintsOption resolve(String option, ProcessingEnvironment env) {
        String value = env.getOptions().get(option);
        if (value == null || "false".equalsIgnoreCase(value)) {
            return new ConstraintsOption(false, false, env);
        }
        if (!"true".equalsIgnoreCase(value)) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] " + option + " must be 'true' or 'false'. Got: " + value);
            return null;
        }
        return new ConstraintsOption(true,
                env.getElementUtils().getTypeElement(McpToolGenerator.VALIDATION_API_PROBE) != null, env);
    }

    /** Whether the option is on. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * @param projection the projection the generators consume
     * @return the constraint surfaces over {@code projection}, or {@code null} when the option is off
     */
    public ConstraintSurfaces surfaces(ContractProjection projection) {
        return enabled ? new ConstraintSurfaces(projection, beanValidation, env) : null;
    }

    /**
     * Writes {@link McpToolsResourceGenerator#RESOURCE_PATH} when the option is on (FR-017).
     *
     * @param services   the generated services
     * @param apiMajor   the configured major
     * @param projection the final projection
     * @param env        the processing environment
     */
    public void generateToolSpecifications(List<ServiceModel> services, int apiMajor, ContractProjection projection,
                                           ProcessingEnvironment env) {
        if (enabled) {
            McpToolsResourceGenerator.generate(services, apiMajor, surfaces(projection), env.getFiler(),
                    env.getMessager());
        }
    }
}

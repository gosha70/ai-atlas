/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

import java.util.List;

/**
 * The contract options of the main {@code compileJava} task (FR-016): the baseline's absolute
 * path and lock mode. The baseline is an input, and a missing file is allowed, so creating,
 * editing or accepting it re-runs the compilation and with it the gate.
 */
public abstract class ContractArguments implements CommandLineArgumentProvider {

    // Compile-time constants, inlined by javac: the processor is not on the plugin's class path
    /** The processor option naming the baseline. */
    static final String OPT_BASELINE = AgenticProcessor.OPT_CONTRACT_BASELINE;
    /** The processor option enabling lock mode. */
    static final String OPT_LOCKED = AgenticProcessor.OPT_CONTRACT_LOCKED;

    /** The baseline file; it may not exist yet. */
    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getBaseline();

    /** Whether lock mode is on. */
    @Input
    public abstract Property<Boolean> getLocked();

    @Override
    public Iterable<String> asArguments() {
        return List.of("-A" + OPT_BASELINE + "=" + getBaseline().getSingleFile().getAbsolutePath(),
                "-A" + OPT_LOCKED + "=" + getLocked().get());
    }
}

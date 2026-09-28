/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.process.CommandLineArgumentProvider;

import java.util.List;

/**
 * The projections flag of the main {@code compileJava} task, when it is set. Unlike the contract
 * options it also reaches {@code atlasAcceptCompile}: the Contract IR records each field's
 * effective channels, which the flag decides, so the accepted baseline must be compiled with it.
 */
public abstract class ProjectionsArguments implements CommandLineArgumentProvider {

    // A compile-time constant, inlined by javac: the processor is not on the plugin's class path
    /** The processor option projecting each response to the fields eligible for its channel. */
    static final String OPT_PROJECTIONS = AgenticProcessor.OPT_PROJECTIONS;

    /**
     * Whether responses are projected per channel. Absent unless set, and then not passed, so a
     * value in {@code options.compilerArgs} (javac keeps the last {@code -A}) stands.
     */
    @Input
    @Optional
    public abstract Property<Boolean> getProjections();

    @Override
    public Iterable<String> asArguments() {
        return getProjections().isPresent()
                ? List.of("-A" + OPT_PROJECTIONS + "=" + getProjections().get())
                : List.of();
    }
}

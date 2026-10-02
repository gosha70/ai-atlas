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
 * The REST flag of the main {@code compileJava} task, when it is set. Unlike the contract
 * options it also reaches {@code atlasAcceptCompile}: the Contract IR records each operation's
 * effective REST mapping, which the flag decides, so the accepted baseline must be compiled with it.
 */
public abstract class RestArguments implements CommandLineArgumentProvider {

    // A compile-time constant, inlined by javac: the processor is not on the plugin's class path
    /** The processor option honouring explicit REST metadata and the CRUD convention. */
    static final String OPT_REST = AgenticProcessor.OPT_REST;

    /**
     * Whether REST metadata is honoured. Absent unless set, and then not passed, so a
     * value in {@code options.compilerArgs} (javac keeps the last {@code -A}) stands.
     */
    @Input
    @Optional
    public abstract Property<Boolean> getRest();

    @Override
    public Iterable<String> asArguments() {
        return getRest().isPresent()
                ? List.of("-A" + OPT_REST + "=" + getRest().get())
                : List.of();
    }
}

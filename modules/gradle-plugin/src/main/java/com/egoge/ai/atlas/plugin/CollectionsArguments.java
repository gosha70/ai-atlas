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
 * The collections flag of the main {@code compileJava} task, when it is set. Like the projections
 * flag it also reaches {@code atlasAcceptCompile}: the Contract IR records each operation's
 * effective paging contract and envelope, which the flag decides, so the accepted baseline must be
 * compiled with it.
 */
public abstract class CollectionsArguments implements CommandLineArgumentProvider {

    // A compile-time constant, inlined by javac: the processor is not on the plugin's class path
    /** The processor option recognising paging contracts and declared bounds on collection results. */
    static final String OPT_COLLECTIONS = AgenticProcessor.OPT_COLLECTIONS;

    /**
     * Whether collection results are classified. Absent unless set, and then not passed, so a value
     * in {@code options.compilerArgs} (javac keeps the last {@code -A}) stands.
     */
    @Input
    @Optional
    public abstract Property<Boolean> getCollections();

    @Override
    public Iterable<String> asArguments() {
        return getCollections().isPresent()
                ? List.of("-A" + OPT_COLLECTIONS + "=" + getCollections().get())
                : List.of();
    }
}

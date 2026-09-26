/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;
import java.io.File;

/**
 * {@code atlasContractCheck} (FR-008, FR-016): javac does not invoke the processor for a
 * compilation that declares no {@code @AgenticEntity} or {@code @AgenticExposed}, so the gate
 * cannot run inside {@code compileJava} when a module removes its last ai-atlas annotation. This
 * task runs after it and, when the class output declares no contract, checks the empty contract
 * against the baseline with the processor's own {@code EmptyContract}.
 *
 * <p>It has no outputs, so it is never up-to-date and never loaded from the cache: an unchanged
 * re-run of a failing build fails again.
 */
@DisableCachingByDefault(because = "Checks the compile output against the baseline on every run")
public abstract class AtlasContractCheck extends DefaultTask {

    /** The main compilation's class output. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassesDirs();

    /** The {@code annotationProcessor} classpath the check is loaded from. */
    @Classpath
    public abstract ConfigurableFileCollection getProcessorClasspath();

    /** The baseline file; it may not exist. */
    @Internal
    public abstract RegularFileProperty getBaseline();

    /** Whether lock mode is on. */
    @Input
    public abstract Property<Boolean> getLocked();

    /** The configured REST base path. */
    @Input
    public abstract Property<String> getApiBasePath();

    /** The configured major. */
    @Input
    public abstract Property<Integer> getApiMajor();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    void check() {
        for (File classesDir : getClassesDirs()) {
            if (ContractDeclarations.declared(classesDir)) {
                return; // the processor ran and its gate checked the contract inside compileJava
            }
        }
        getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getProcessorClasspath()))
                .submit(EmptyContractAction.class, parameters -> {
                    parameters.getBaseline().set(getBaseline());
                    parameters.getLocked().set(getLocked());
                    parameters.getApiBasePath().set(getApiBasePath());
                    parameters.getApiMajor().set(getApiMajor());
                });
    }
}

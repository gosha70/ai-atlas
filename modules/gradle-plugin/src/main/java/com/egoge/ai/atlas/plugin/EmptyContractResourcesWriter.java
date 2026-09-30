/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.workers.WorkerExecutor;

import java.io.File;
import java.util.Map;

/**
 * {@code compileJava.doLast} (C3a decision): writes the empty contract when the compilation
 * declares no ai-atlas contract at all, so its class output is up to date and cacheable like any
 * other {@code compileJava} output. A plain compiled class, not a lambda, so the configuration
 * cache accepts it (C3a): it captures no {@code Project}, only the {@code annotationProcessor}
 * classpath and a couple of providers; the destination directory and effective compiler arguments
 * are read from the {@code Task} handed to {@link #execute}, exactly as {@code compileJava} itself
 * sees them, since {@link JavaCompile#getOptions()}{@code .getAllCompilerArgs()} is a plain,
 * eagerly-evaluated getter rather than a lazy {@code Provider}.
 *
 * <p>Never wired into the release workflow: {@code agenticRelease} depends on {@code classes},
 * which already depends on {@code compileJava}, so release validation runs after this writer has
 * had its chance, without ever invoking it itself.
 */
final class EmptyContractResourcesWriter implements Action<Task> {

    private final WorkerExecutor workerExecutor;
    private final FileCollection processorClasspath;
    private final Provider<String> processorVersion;

    EmptyContractResourcesWriter(WorkerExecutor workerExecutor, FileCollection processorClasspath,
                                 Provider<String> processorVersion) {
        this.workerExecutor = workerExecutor;
        this.processorClasspath = processorClasspath;
        this.processorVersion = processorVersion;
    }

    @Override
    public void execute(Task task) {
        JavaCompile compileJava = (JavaCompile) task;
        File destinationDir = compileJava.getDestinationDirectory().get().getAsFile();
        if (ContractDeclarations.declared(destinationDir)) {
            return; // the processor ran and wrote its own contract-resources.json
        }
        Map<String, String> compilerArguments =
                EffectiveCompilerArguments.lastWins(compileJava.getOptions().getAllCompilerArgs());
        workerExecutor.classLoaderIsolation(spec -> spec.getClasspath().from(processorClasspath))
                .submit(EmptyContractResourcesAction.class, parameters -> {
                    parameters.getDestinationDirectory().set(destinationDir);
                    parameters.getCompilerArguments().set(compilerArguments);
                });
        AgenticPlugin.awaitProcessor(workerExecutor, processorVersion);
    }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
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
import java.util.List;

/**
 * {@code atlasAccept} (FR-015): writes the contract of the current sources to the baseline. Its
 * input is a compilation of the main sources without the contract options, so it succeeds while
 * the gate or lock mode fails the build. Only this task writes the baseline.
 */
@DisableCachingByDefault(because = "Writes the committed baseline, a source file")
public abstract class AtlasAccept extends DefaultTask {

    /** The class output of the compilation without the contract options. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassesDirs();

    /** The {@code annotationProcessor} classpath the accept step is loaded from. */
    @Classpath
    public abstract ConfigurableFileCollection getProcessorClasspath();

    /**
     * The baseline file to write. Not an {@code @OutputFile}: the task declares no outputs, so it
     * is never up-to-date and accepting always rewrites the baseline, even one edited by hand.
     */
    @Internal
    public abstract RegularFileProperty getBaseline();

    /**
     * The accept compilation's effective {@code -A} options, last value winning: the REST base path
     * and major of the empty document, including an override the build adds to the compiler
     * arguments after the {@code agentic} extension's.
     */
    @Input
    public abstract MapProperty<String, String> getCompilerArguments();

    /** The processor on the {@code annotationProcessor} classpath, named when this plugin cannot run it. */
    @Internal
    public abstract Property<String> getProcessorVersion();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    void accept() {
        List<File> declaring = getClassesDirs().getFiles().stream().filter(ContractDeclarations::declared).toList();
        List<File> irs = declaring.stream().map(dir -> new File(dir, ContractDeclarations.IR_PATH))
                .filter(File::isFile).toList();
        if (!declaring.isEmpty() && irs.isEmpty()) {
            throw new GradleException("The compilation declares an ai-atlas contract but emitted no "
                    + ContractDeclarations.IR_PATH + " in " + declaring);
        }
        if (irs.size() > 1) {
            throw new GradleException("The compilation emitted more than one " + ContractDeclarations.IR_PATH
                    + ", so the contract to accept is ambiguous: " + irs);
        }
        File ir = irs.isEmpty() ? null : irs.get(0);
        getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getProcessorClasspath()))
                .submit(AcceptAction.class, parameters -> {
                    parameters.getBaseline().set(getBaseline());
                    if (ir != null) {
                        parameters.getFreshIr().set(ir);
                    }
                    parameters.getCompilerArguments().set(getCompilerArguments());
                });
        AgenticPlugin.awaitProcessor(getWorkerExecutor(), getProcessorVersion());
    }
}

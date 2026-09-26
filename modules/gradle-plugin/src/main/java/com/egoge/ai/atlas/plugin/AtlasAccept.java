/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
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

    /** The baseline file to write. */
    @Internal
    public abstract RegularFileProperty getBaseline();

    /** The configured REST base path, for the empty document. */
    @Input
    public abstract Property<String> getApiBasePath();

    /** The configured major, for the empty document. */
    @Input
    public abstract Property<Integer> getApiMajor();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    void accept() {
        File freshIr = null;
        for (File classesDir : getClassesDirs()) {
            if (ContractDeclarations.declared(classesDir)) {
                freshIr = new File(classesDir, ContractDeclarations.IR_PATH);
                if (!freshIr.isFile()) {
                    throw new GradleException("The compilation declares an ai-atlas contract but emitted no "
                            + ContractDeclarations.IR_PATH + " in " + classesDir);
                }
            }
        }
        File ir = freshIr;
        getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getProcessorClasspath()))
                .submit(AcceptAction.class, parameters -> {
                    parameters.getBaseline().set(getBaseline());
                    if (ir != null) {
                        parameters.getFreshIr().set(ir);
                    }
                    parameters.getApiBasePath().set(getApiBasePath());
                    parameters.getApiMajor().set(getApiMajor());
                });
    }
}

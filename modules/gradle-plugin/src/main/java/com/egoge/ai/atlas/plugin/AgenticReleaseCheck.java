/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;
import java.io.File;

/**
 * {@code agenticReleaseCheck}, part of {@code check}: verifies every released contract against the
 * digests of its {@code release.json}, so a released file edited, deleted or added after release
 * fails the build. Given a release version, such as the version CI builds a tag of, it also fails
 * unless that version is released with exactly the contract the build emitted.
 *
 * <p>It reads only the class output and the release directory, and makes no network call. It has
 * no outputs, so it is never up-to-date; with no release directory and no version it does nothing.
 */
@DisableCachingByDefault(because = "Verifies committed release snapshots on every run")
public abstract class AgenticReleaseCheck extends DefaultTask {

    /** The main compilation's class output. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassesDirs();

    /** The {@code annotationProcessor} classpath the check is loaded from. */
    @Classpath
    public abstract ConfigurableFileCollection getProcessorClasspath();

    /** The directory of releases; it may not exist. */
    @Internal
    public abstract DirectoryProperty getReleasesDir();

    /**
     * The released version the build's contract must be, or unset to verify the digests only.
     * Defaults to {@code agentic { release { checkVersion } }}.
     */
    @Input
    @Optional
    @Option(option = "release-version", description = "The released version the build's contract must be, such as"
            + " the version of the tag CI builds.")
    public abstract Property<String> getReleaseVersion();

    /** The processor on the {@code annotationProcessor} classpath, named when this plugin cannot run it. */
    @Internal
    public abstract Property<String> getProcessorVersion();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    void check() {
        if (!getReleaseVersion().isPresent() && !getReleasesDir().get().getAsFile().isDirectory()) {
            return; // nothing released, nothing claimed
        }
        // The class output's own IR: the processor's, or the empty contract compileJava wrote from its
        // effective options, as the release snapshots it
        File declaring = ContractDeclarations.declaringOutput(getClassesDirs().getFiles());
        File classes = declaring != null ? declaring : getClassesDirs().getSingleFile();
        getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getProcessorClasspath()))
                .submit(ReleaseCheckAction.class, parameters -> {
                    parameters.getEmittedIr().set(new File(classes, ContractDeclarations.IR_PATH));
                    parameters.getReleasesDir().set(getReleasesDir());
                    parameters.getReleaseVersion().set(getReleaseVersion());
                });
        AgenticPlugin.awaitProcessor(getWorkerExecutor(), getProcessorVersion());
    }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;

/**
 * {@code agenticRelease}: snapshots the accepted contract of the main compilation as the immutable
 * release {@code <release.directory>/<releaseVersion>/}, compares it with the previous release,
 * checks the deprecation policy, and regenerates the aggregate changelog. It runs after
 * {@code classes}, so the compatibility gate and {@code atlasContractCheck} have passed, and it
 * releases only a contract the build emitted byte for byte as the baseline: {@code atlasAccept}
 * first, then release. It never writes the baseline.
 *
 * <p>It declares no outputs, so it always runs, and releasing a version a second time fails.
 */
@DisableCachingByDefault(because = "Writes an immutable release snapshot, a source file")
public abstract class AgenticRelease extends DefaultTask {

    /**
     * The main compilation's class output, validated as found. Untracked on purpose: as an input
     * it would make this task depend on the compilation, which could regenerate the very resources
     * the release validates. The task has no outputs, so it always runs.
     */
    @Internal
    public abstract ConfigurableFileCollection getClassesDirs();

    /** The {@code annotationProcessor} classpath the release step is loaded from. */
    @Classpath
    public abstract ConfigurableFileCollection getProcessorClasspath();

    /** The accepted baseline; the release refuses a contract that differs from it. */
    @Internal
    public abstract RegularFileProperty getBaseline();

    /** The directory holding one subdirectory per released version. */
    @Internal
    public abstract DirectoryProperty getReleasesDir();

    /** The aggregate changelog to regenerate. */
    @Internal
    public abstract RegularFileProperty getChangelog();

    /** The version to release, {@code MAJOR.MINOR.PATCH}. */
    @Input
    public abstract Property<String> getReleaseVersion();

    /** Whether the version's major must equal the contract's {@code apiMajor}. */
    @Input
    public abstract Property<Boolean> getVersionTracksApiMajor();

    /** Releases an element must have been published in while deprecated before removal. */
    @Input
    public abstract Property<Integer> getMinDeprecatedReleases();

    /** Majors between an element's deprecation major and the major of the release removing it. */
    @Input
    public abstract Property<Integer> getMinApiMajorAdvance();

    /** Whether other breaking differences fail a release within the same {@code apiMajor}. */
    @Input
    public abstract Property<Boolean> getFailOnBreaking();

    /** The processor on the {@code annotationProcessor} classpath, named when this plugin cannot run it. */
    @Internal
    public abstract Property<String> getProcessorVersion();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    void release() {
        getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getProcessorClasspath()))
                .submit(ReleaseAction.class, parameters -> {
                    parameters.getClassesDirs().from(getClassesDirs());
                    parameters.getBaseline().set(getBaseline());
                    parameters.getReleasesDir().set(getReleasesDir());
                    parameters.getChangelog().set(getChangelog());
                    parameters.getReleaseVersion().set(getReleaseVersion());
                    parameters.getVersionTracksApiMajor().set(getVersionTracksApiMajor());
                    parameters.getMinDeprecatedReleases().set(getMinDeprecatedReleases());
                    parameters.getMinApiMajorAdvance().set(getMinApiMajorAdvance());
                    parameters.getFailOnBreaking().set(getFailOnBreaking());
                });
        AgenticPlugin.awaitProcessor(getWorkerExecutor(), getProcessorVersion());
    }
}

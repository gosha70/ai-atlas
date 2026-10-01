/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;

/**
 * {@code agenticReleaseHistoryCheck}, part of {@code check}: verifies only the internal
 * consistency of the committed release snapshots, with no git and no network: every released
 * file against the digests of its {@code release.json}, the {@code previous} chain, and that
 * {@code .atlas/CHANGELOG.md} equals what the release history would generate (AC13, AC14).
 *
 * <p>It takes no version and never reads the build's class output; {@code agenticReleaseVerify}
 * does both, and is not part of {@code check}. With no release directory and no changelog this
 * task succeeds silently.
 */
@DisableCachingByDefault(because = "Verifies committed release snapshots on every run")
public abstract class AgenticReleaseHistoryCheck extends DefaultTask {

    /** The {@code annotationProcessor} classpath the check is loaded from. */
    @Classpath
    public abstract ConfigurableFileCollection getProcessorClasspath();

    /** The directory of releases; it may not exist. */
    @Internal
    public abstract DirectoryProperty getReleasesDir();

    /** The aggregate changelog the release history is checked against. */
    @Internal
    public abstract RegularFileProperty getChangelog();

    /** The processor on the {@code annotationProcessor} classpath, named when this plugin cannot run it. */
    @Internal
    public abstract org.gradle.api.provider.Property<String> getProcessorVersion();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    void check() {
        getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getProcessorClasspath()))
                .submit(ReleaseHistoryCheckAction.class, parameters -> {
                    parameters.getReleasesDir().set(getReleasesDir());
                    parameters.getChangelog().set(getChangelog());
                });
        AgenticPlugin.awaitProcessor(getWorkerExecutor(), getProcessorVersion());
    }
}

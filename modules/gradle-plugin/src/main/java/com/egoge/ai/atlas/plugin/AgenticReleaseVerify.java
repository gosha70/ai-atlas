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
 * {@code agenticReleaseVerify}: proves that {@code agentic { releaseVersion } }'s tag resolves to
 * exactly {@code HEAD}, and that the build matches that release's snapshot byte for byte, IR
 * canonically, and structurally in its effective configuration (E2, D6.2, D8.2, plan §3.5). Unlike
 * {@value AgenticPlugin#RELEASE_HISTORY_CHECK_TASK}, it is not part of {@code check}, needs git,
 * and reads the build's class output; CI runs it against the tag it built.
 *
 * <p>Like {@code agenticRelease}, it declares no outputs and has no task dependency on {@code
 * classes} or {@code compileJava}: {@link #getClassesDirs()} is an {@code @Internal} file
 * collection from a plain provider, ordered only with {@code mustRunAfter}, so it never depends on
 * the compilation it validates. It writes nothing.
 */
@DisableCachingByDefault(because = "Verifies the build against a tagged release on every run")
public abstract class AgenticReleaseVerify extends DefaultTask {

    /**
     * The main compilation's class output, validated as found. Untracked on purpose, like {@link
     * AgenticRelease#getClassesDirs()}: as an input it would make this task depend on the
     * compilation it verifies.
     */
    @Internal
    public abstract ConfigurableFileCollection getClassesDirs();

    /** The {@code annotationProcessor} classpath the verify step is loaded from. */
    @Classpath
    public abstract ConfigurableFileCollection getProcessorClasspath();

    /** The directory of releases. */
    @Internal
    public abstract DirectoryProperty getReleasesDir();

    /** The aggregate changelog. */
    @Internal
    public abstract RegularFileProperty getChangelog();

    /** The version to verify, {@code agentic { releaseVersion } }. */
    @Input
    public abstract Property<String> getReleaseVersion();

    /** The {@code agentic { release { tagName } } } template this release's tag is rendered from. */
    @Input
    public abstract Property<String> getTagNameTemplate();

    /** The processor on the {@code annotationProcessor} classpath, named when this plugin cannot run it. */
    @Internal
    public abstract Property<String> getProcessorVersion();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    void verify() {
        getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getProcessorClasspath()))
                .submit(ReleaseVerifyAction.class, parameters -> {
                    parameters.getClassesDirs().from(getClassesDirs());
                    parameters.getReleasesDir().set(getReleasesDir());
                    parameters.getChangelog().set(getChangelog());
                    parameters.getReleaseVersion().set(getReleaseVersion());
                    parameters.getTagNameTemplate().set(getTagNameTemplate());
                });
        AgenticPlugin.awaitProcessor(getWorkerExecutor(), getProcessorVersion());
    }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/**
 * The release workflow's task wiring, extracted from {@link AgenticPlugin}: {@value
 * AgenticPlugin#RELEASE_TASK} snapshots the accepted contract after the gate passed, and {@value
 * AgenticPlugin#RELEASE_CHECK_TASK}, which {@code check} depends on, verifies the snapshots.
 */
final class ReleaseTasks {

    private ReleaseTasks() {
    }

    /**
     * Registers {@value AgenticPlugin#RELEASE_TASK} and {@value AgenticPlugin#RELEASE_CHECK_TASK},
     * and wires the latter into {@code check}.
     *
     * @param project          the project
     * @param extension        the {@code agentic} extension
     * @param compileJava      the main {@code compileJava} task
     * @param processorPath    the {@code annotationProcessor} configuration
     * @param processorVersion the processor version named in a linkage failure
     */
    static void configure(Project project, AgenticExtension extension, TaskProvider<JavaCompile> compileJava,
                          Configuration processorPath, Provider<String> processorVersion) {
        TaskContainer tasks = project.getTasks();
        ReleaseSpec release = extension.getRelease();
        tasks.register(AgenticPlugin.RELEASE_TASK, AgenticRelease.class, task -> {
            task.setGroup(AgenticPlugin.TASK_GROUP);
            task.setDescription("Releases the accepted contract as an immutable snapshot, with its changelog.");
            // The release validates the class output as it finds it, and so never depends on the
            // compilation that could regenerate it: a plain provider carries no task dependency.
            // Requested together, as in `classes agenticRelease`, the compilation still runs first.
            task.getClassesDirs().from(project.provider(
                    () -> compileJava.get().getDestinationDirectory().get().getAsFile()));
            task.mustRunAfter(tasks.named(JavaPlugin.CLASSES_TASK_NAME), tasks.named(AgenticPlugin.CONTRACT_CHECK_TASK));
            task.getProcessorClasspath().from(processorPath);
            task.getBaseline().set(extension.getContractBaseline());
            task.getReleasesDir().set(release.getDirectory());
            task.getChangelog().set(release.getChangelog());
            task.getReleaseVersion().set(extension.getReleaseVersion());
            task.getVersionTracksApiMajor().set(extension.getReleaseVersionTracksApiMajor());
            task.getMinDeprecatedReleases().set(release.getPolicy().getMinDeprecatedReleases());
            task.getMinApiMajorAdvance().set(release.getPolicy().getMinApiMajorAdvance());
            task.getFailOnBreaking().set(release.getPolicy().getFailOnBreaking());
            task.getApiBasePath().set(extension.getApiBasePath());
            task.getApiMajor().set(extension.getApiMajorVersion());
            task.getProcessorVersion().set(processorVersion);
        });
        TaskProvider<AgenticReleaseCheck> check = tasks.register(AgenticPlugin.RELEASE_CHECK_TASK,
                AgenticReleaseCheck.class, task -> {
            task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
            task.setDescription("Verifies the released contracts' digests, and the build's contract against a"
                    + " given release version.");
            task.getClassesDirs().from(compileJava.flatMap(JavaCompile::getDestinationDirectory));
            task.getProcessorClasspath().from(processorPath);
            task.getReleasesDir().set(release.getDirectory());
            task.getReleaseVersion().convention(release.getCheckVersion());
            task.getApiBasePath().set(extension.getApiBasePath());
            task.getApiMajor().set(extension.getApiMajorVersion());
            task.getProcessorVersion().set(processorVersion);
        });
        tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(task -> task.dependsOn(check));
    }
}

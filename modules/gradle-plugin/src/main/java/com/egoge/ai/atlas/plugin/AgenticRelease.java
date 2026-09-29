/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
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
import java.util.List;

/**
 * SPIKE (epic #23, Phase 5, §10): {@code agenticRelease} snapshots the accepted contract of the
 * main compilation to {@code <releasesDir>/<version>/}, never overwriting a released version. It
 * compares the release with the previous one through the compatibility gate's own comparison,
 * writes a Markdown changelog section from that comparison, and fails when a previously published
 * field or operation is removed without satisfying the deprecation policy.
 *
 * <p>It declares no outputs, so it always runs: releasing a version a second time fails.
 */
@DisableCachingByDefault(because = "Writes an immutable release snapshot, a source file")
public abstract class AgenticRelease extends DefaultTask {

    /** The OpenAPI document of a major, relative to the class output. */
    static final String OPENAPI_PATH = "META-INF/openapi/openapi-v%d.json";
    /** The MCP tool specifications, relative to the class output; present with {@code constraints} on. */
    static final String MCP_TOOLS_PATH = "META-INF/ai-atlas/mcp-tools.json";

    /** The main compilation's class output, the compilation the gate checked. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassesDirs();

    /** The {@code annotationProcessor} classpath the release step is loaded from. */
    @Classpath
    public abstract ConfigurableFileCollection getProcessorClasspath();

    /** The committed baseline; the release refuses a contract that differs from it. */
    @Internal
    public abstract RegularFileProperty getBaseline();

    /** The directory holding one subdirectory per released version. */
    @Internal
    public abstract DirectoryProperty getReleasesDir();

    /** The version to release, {@code MAJOR.MINOR.PATCH}. */
    @Input
    public abstract Property<String> getReleaseVersion();

    /** Released versions an element must have been published in while deprecated before removal. */
    @Input
    public abstract Property<Integer> getMinDeprecatedReleases();

    /** Majors between an element's deprecation major and the major of the release removing it. */
    @Input
    public abstract Property<Integer> getMinDeprecatedMajors();

    /** The processor on the {@code annotationProcessor} classpath, named when this plugin cannot run it. */
    @Internal
    public abstract Property<String> getProcessorVersion();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    void release() {
        String version = getReleaseVersion().get();
        ReleaseVersion.parse(version); // fails on a SNAPSHOT or any other non-release version
        List<File> dirs = getClassesDirs().getFiles().stream()
                .filter(dir -> new File(dir, ContractDeclarations.IR_PATH).isFile()).toList();
        if (dirs.size() != 1) {
            throw new GradleException("agenticRelease needs exactly one " + ContractDeclarations.IR_PATH
                    + " in the main class output, found " + dirs.size() + " in " + getClassesDirs().getFiles()
                    + ". A module that declares no ai-atlas contract has nothing to release.");
        }
        File classes = dirs.get(0);
        File mcpTools = new File(classes, MCP_TOOLS_PATH);
        getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getProcessorClasspath()))
                .submit(ReleaseAction.class, parameters -> {
                    parameters.getEmittedIr().set(new File(classes, ContractDeclarations.IR_PATH));
                    parameters.getClassesDir().set(classes);
                    parameters.getBaseline().set(getBaseline());
                    parameters.getReleasesDir().set(getReleasesDir());
                    parameters.getReleaseVersion().set(version);
                    parameters.getMinDeprecatedReleases().set(getMinDeprecatedReleases());
                    parameters.getMinDeprecatedMajors().set(getMinDeprecatedMajors());
                    if (mcpTools.isFile()) {
                        parameters.getMcpTools().set(mcpTools);
                    }
                });
        AgenticPlugin.awaitProcessor(getWorkerExecutor(), getProcessorVersion());
    }
}

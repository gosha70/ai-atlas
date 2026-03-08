/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;

/**
 * Gradle plugin that configures a Java project to use the AI-ATLAS framework.
 *
 * <p>Applies the Java plugin (if not already present) and adds:
 * <ul>
 *   <li>{@code annotations} to {@code implementation}</li>
 *   <li>{@code processor} to {@code annotationProcessor}</li>
 *   <li>{@code runtime} to {@code implementation} (when MCP or REST is enabled)</li>
 * </ul>
 *
 * <p>Also configures IntelliJ IDEA to recognize generated source directories.
 */
public class AgenticPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        AgenticExtension extension = project.getExtensions()
                .create("agentic", AgenticExtension.class);

        // Defaults
        extension.getVersion().convention(
                project.provider(() -> project.getVersion().toString()));
        extension.getGroup().convention("com.egoge");
        extension.getMcpEnabled().convention(true);
        extension.getRestEnabled().convention(true);
        extension.getOpenApiEnabled().convention(true);

        // Add dependencies and processor options after evaluation (so extension values are resolved)
        project.afterEvaluate(p -> {
            addDependencies(p, extension);
            configureProcessorOptions(p, extension);
        });

        // Configure IntelliJ IDEA generated source directories
        configureIdea(project);
    }

    private void addDependencies(Project project, AgenticExtension extension) {
        String version = extension.getVersion().get();
        String group = extension.getGroup().get();

        // Annotations — always needed
        project.getDependencies().add(
                JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
                group + ":ai-atlas-annotations:" + version);

        // Processor — always needed for annotation processing
        project.getDependencies().add(
                JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME,
                group + ":ai-atlas-processor:" + version);

        // Runtime — needed when MCP or REST features are enabled
        if (extension.getMcpEnabled().get() || extension.getRestEnabled().get()) {
            project.getDependencies().add(
                    JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
                    group + ":ai-atlas-runtime:" + version);
        }
    }

    private void configureProcessorOptions(Project project, AgenticExtension extension) {
        if (extension.getPiiPatternsFile().isPresent()) {
            String filePath = extension.getPiiPatternsFile().get();
            project.getTasks().withType(JavaCompile.class,
                    task -> task.getOptions().getCompilerArgs()
                            .add("-Aai.atlas.pii.patterns.file=" + filePath));
        }
    }

    private void configureIdea(Project project) {
        project.getPluginManager().withPlugin("idea", plugin -> {
            // Mark generated annotation processor output as a source directory
            File generatedDir = new File(project.getLayout().getBuildDirectory().getAsFile().get(),
                    "generated/sources/annotationProcessor/java/main");

            project.getExtensions().configure("idea",
                    idea -> ((org.gradle.plugins.ide.idea.model.IdeaModel) idea)
                            .getModule()
                            .getGeneratedSourceDirs()
                            .add(generatedDir));
        });
    }
}

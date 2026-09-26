/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

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
 * <p>Also configures IntelliJ IDEA to recognize generated source directories, and the contract
 * gate: the {@code contractBaseline} and {@code contractLocked} options of the main
 * {@code compileJava}, the {@value #CONTRACT_CHECK_TASK} task that {@code classes} depends on, and
 * the {@value #ACCEPT_TASK} task.
 */
public class AgenticPlugin implements Plugin<Project> {

    /** The task that checks an empty contract against the baseline. */
    public static final String CONTRACT_CHECK_TASK = "atlasContractCheck";
    /** The task that writes the current contract to the baseline. */
    public static final String ACCEPT_TASK = "atlasAccept";

    private static final String ACCEPT_COMPILE_TASK = "atlasAcceptCompile";
    private static final String TASK_GROUP = "ai-atlas";
    private static final String DEFAULT_CONTRACT_BASELINE = ".atlas/api.ir.json";
    private static final String ACCEPT_DIR = "atlas/accept";
    private static final String CONTRACT_OPTION_PREFIX = "-Aai.atlas.contract.";

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
        extension.getApiBasePath().convention("/api");
        extension.getApiMajorVersion().convention(1);
        extension.getOpenApiInfoVersion().convention(
                extension.getApiMajorVersion().map(major -> major + ".0.0"));
        extension.getStrict().convention(false);
        extension.getContractBaseline().convention(
                project.getLayout().getProjectDirectory().file(DEFAULT_CONTRACT_BASELINE));
        extension.getContractLocked().convention(false);

        // Add dependencies and processor options after evaluation (so extension values are resolved)
        project.afterEvaluate(p -> {
            addDependencies(p, extension);
            configureProcessorOptions(p, extension);
        });

        configureContract(project, extension);

        // Configure IntelliJ IDEA generated source directories
        configureIdea(project);
    }

    /**
     * The contract gate (FR-015, FR-016): the baseline and lock options go to the main
     * {@code compileJava} only, {@code atlasContractCheck} checks an empty contract before
     * {@code classes}, and {@code atlasAccept} writes the baseline.
     */
    private void configureContract(Project project, AgenticExtension extension) {
        TaskContainer tasks = project.getTasks();
        TaskProvider<JavaCompile> compileJava =
                tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile.class);
        Configuration processorPath =
                project.getConfigurations().getByName(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME);

        ContractArguments contractArguments = project.getObjects().newInstance(ContractArguments.class);
        contractArguments.getBaseline().from(extension.getContractBaseline());
        contractArguments.getLocked().set(extension.getContractLocked());
        compileJava.configure(task -> task.getOptions().getCompilerArgumentProviders().add(contractArguments));

        TaskProvider<AtlasContractCheck> check = tasks.register(CONTRACT_CHECK_TASK, AtlasContractCheck.class, task -> {
            task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
            task.setDescription("Checks a compilation that declares no ai-atlas contract against the contract"
                    + " baseline.");
            task.getClassesDirs().from(compileJava.flatMap(JavaCompile::getDestinationDirectory));
            task.getProcessorClasspath().from(processorPath);
            task.getBaseline().set(extension.getContractBaseline());
            task.getLocked().set(extension.getContractLocked());
            task.getApiBasePath().set(extension.getApiBasePath());
            task.getApiMajor().set(extension.getApiMajorVersion());
        });
        tasks.named(JavaPlugin.CLASSES_TASK_NAME).configure(task -> task.dependsOn(check));

        // The sources compiled without the contract options, so accepting works while the gate fails
        TaskProvider<JavaCompile> acceptCompile = tasks.register(ACCEPT_COMPILE_TASK, JavaCompile.class, task -> {
            JavaCompile main = compileJava.get();
            task.setDescription("Compiles the main sources without the contract gate, for " + ACCEPT_TASK + ".");
            task.setSource(main.getSource());
            task.setClasspath(main.getClasspath());
            task.getOptions().setAnnotationProcessorPath(main.getOptions().getAnnotationProcessorPath());
            task.getOptions().setEncoding(main.getOptions().getEncoding());
            task.getOptions().getRelease().set(main.getOptions().getRelease());
            task.getJavaCompiler().set(main.getJavaCompiler());
            task.setSourceCompatibility(main.getSourceCompatibility());
            task.setTargetCompatibility(main.getTargetCompatibility());
            task.getOptions().getCompilerArgs().addAll(main.getOptions().getCompilerArgs().stream()
                    .filter(arg -> !arg.startsWith(CONTRACT_OPTION_PREFIX)).toList());
            task.getOptions().setIncremental(false);
            task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir(ACCEPT_DIR + "/classes"));
            task.getOptions().getGeneratedSourceOutputDirectory()
                    .set(project.getLayout().getBuildDirectory().dir(ACCEPT_DIR + "/generated"));
        });

        tasks.register(ACCEPT_TASK, AtlasAccept.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Writes the contract of the current sources to the contract baseline.");
            task.getClassesDirs().from(acceptCompile.flatMap(JavaCompile::getDestinationDirectory));
            task.getProcessorClasspath().from(processorPath);
            task.getBaseline().set(extension.getContractBaseline());
            task.getApiBasePath().set(extension.getApiBasePath());
            task.getApiMajor().set(extension.getApiMajorVersion());
        });
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
        project.getTasks().withType(JavaCompile.class, task -> {
            var args = task.getOptions().getCompilerArgs();

            if (extension.getPiiPatternsFile().isPresent()) {
                args.add("-Aai.atlas.pii.patterns.file=" + extension.getPiiPatternsFile().get());
            }

            args.add("-Aai.atlas.api.basePath=" + extension.getApiBasePath().get());
            args.add("-Aai.atlas.api.major=" + extension.getApiMajorVersion().get());
            args.add("-Aai.atlas.openapi.infoVersion=" + extension.getOpenApiInfoVersion().get());
            args.add("-Aai.atlas.strict=" + extension.getStrict().get());
        });
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

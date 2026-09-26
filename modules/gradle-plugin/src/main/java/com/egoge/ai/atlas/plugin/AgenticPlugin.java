/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.api.tasks.compile.ForkOptions;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.workers.WorkerExecutionException;
import org.gradle.workers.WorkerExecutor;

import java.io.File;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
    private static final String PROCESSOR_MODULE = "ai-atlas-processor";
    private static final Pattern PROCESSOR_JAR = Pattern.compile(PROCESSOR_MODULE + "-(.+)\\.jar");
    private static final Pattern PLUGIN_JAR = Pattern.compile("-(\\d[^/]*)\\.jar$");

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

        // Named in the message a processor the plugin cannot run fails with
        Provider<String> processorVersion = processorPath.getIncoming().getArtifacts().getResolvedArtifacts()
                .map(AgenticPlugin::processorVersion);

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
            task.getProcessorVersion().set(processorVersion);
        });
        tasks.named(JavaPlugin.CLASSES_TASK_NAME).configure(task -> task.dependsOn(check));

        // The sources compiled as compileJava compiles them, less the contract options, so accepting
        // works while the gate fails. Everything is read from compileJava lazily, when the task graph is
        // built or this task runs, so a change the build makes to compileJava after this task is created
        // still reaches it and the accepted IR is byte-identical to compileJava's (FR-015).
        TaskProvider<JavaCompile> acceptCompile = tasks.register(ACCEPT_COMPILE_TASK, JavaCompile.class, task -> {
            task.setDescription("Compiles the main sources without the contract gate, for " + ACCEPT_TASK + ".");
            // Explicit prerequisites, such as source generators, read when the task graph is built
            task.dependsOn((Callable<Set<Object>>) () -> compileJava.get().getDependsOn());
            task.setSource((Callable<FileTree>) () -> compileJava.get().getSource());
            task.setClasspath(project.files((Callable<FileCollection>) () -> compileJava.get().getClasspath()));
            task.getOptions().setAnnotationProcessorPath(project.files(
                    (Callable<FileCollection>) () -> compileJava.get().getOptions().getAnnotationProcessorPath()));
            task.getOptions().getRelease().set(
                    project.provider(() -> compileJava.get().getOptions().getRelease().getOrNull()));
            task.getJavaCompiler().set(project.provider(() -> compileJava.get().getJavaCompiler().getOrNull()));
            task.getOptions().getCompilerArgumentProviders().add(new MainCompilerArguments(
                    project.provider(() -> compileJava.get().getOptions().getCompilerArgs().stream()
                            .filter(arg -> !arg.startsWith(CONTRACT_OPTION_PREFIX)).toList()),
                    project.provider(() -> compileJava.get().getOptions().getCompilerArgumentProviders().stream()
                            .filter(provider -> provider != contractArguments).toList())));
            // compileJava's JVM argument providers, nested so Gradle fingerprints their own inputs before
            // this task runs; applied when the fork options are, as Gradle only reads them for a fork
            task.getOptions().getForkOptions().getJvmArgumentProviders().add(new MainJvmArguments(
                    project.provider(() -> compileJava.get().getOptions().getForkOptions().getJvmArgumentProviders())));
            Provider<MainCompileSettings> settings = project.provider(() -> MainCompileSettings.of(compileJava.get()));
            task.getInputs().property("mainCompileSettings", settings.map(MainCompileSettings::fingerprint));
            // The JDK compileJava forks, by the content of its release file, as its path is not relocatable
            task.getInputs().files((Callable<Object>) () -> {
                File javaHome = compileJava.get().getOptions().getForkOptions().getJavaHome();
                return javaHome == null ? List.of() : List.of(new File(javaHome, "release"));
            }).withPropertyName("mainForkJavaHome").withPathSensitivity(PathSensitivity.NONE);
            task.doFirst(new ApplyMainCompileSettings(settings));
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
            task.getProcessorVersion().set(processorVersion);
        });
    }

    /**
     * Waits for the work submitted to the processor's classes. A linkage failure there means the
     * {@code annotationProcessor} classpath holds a processor this plugin's version cannot call, so it
     * fails with the two versions and the remedy instead of the raw error.
     *
     * @param workers          the executor the work was submitted to
     * @param processorVersion the processor found on the {@code annotationProcessor} classpath
     */
    static void awaitProcessor(WorkerExecutor workers, Provider<String> processorVersion) {
        try {
            workers.await();
        } catch (WorkerExecutionException e) {
            Throwable linkage = e;
            while (linkage != null && !(linkage instanceof LinkageError || linkage instanceof ClassNotFoundException)) {
                linkage = linkage.getCause();
            }
            if (linkage == null) {
                throw e;
            }
            throw new GradleException("The ai-atlas Gradle plugin " + pluginVersion() + " cannot run "
                    + processorVersion.get() + " on the annotationProcessor classpath (" + linkage + ")."
                    + " The plugin and processor versions must match: align agentic { version } with the"
                    + " plugin's version, or remove the agentic { version } pin.");
        }
    }

    private static String pluginVersion() {
        String version = AgenticPlugin.class.getPackage().getImplementationVersion();
        if (version == null) {
            CodeSource source = AgenticPlugin.class.getProtectionDomain().getCodeSource();
            Matcher jar = source == null ? null : PLUGIN_JAR.matcher(source.getLocation().getPath());
            version = jar != null && jar.find() ? jar.group(1) : null;
        }
        return version != null ? version : "(development build)";
    }

    private static String processorVersion(Set<ResolvedArtifactResult> artifacts) {
        for (ResolvedArtifactResult artifact : artifacts) {
            if (artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier module
                    && module.getModule().equals(PROCESSOR_MODULE)) {
                return PROCESSOR_MODULE + " " + module.getVersion();
            }
            Matcher jar = PROCESSOR_JAR.matcher(artifact.getFile().getName());
            if (jar.matches()) {
                return PROCESSOR_MODULE + " " + jar.group(1);
            }
        }
        return "an " + PROCESSOR_MODULE + " of unknown version";
    }

    /** compileJava's compiler arguments and argument providers, read when the accept compilation runs. */
    public static final class MainCompilerArguments implements CommandLineArgumentProvider {

        private final Provider<List<String>> compilerArgs;
        private final Provider<List<CommandLineArgumentProvider>> argumentProviders;

        MainCompilerArguments(Provider<List<String>> compilerArgs,
                              Provider<List<CommandLineArgumentProvider>> argumentProviders) {
            this.compilerArgs = compilerArgs;
            this.argumentProviders = argumentProviders;
        }

        /**
         * compileJava's compiler arguments, less the contract options.
         *
         * @return the arguments
         */
        @Input
        public List<String> getCompilerArgs() {
            return compilerArgs.get();
        }

        /**
         * compileJava's argument providers, less the contract options.
         *
         * @return the providers
         */
        @Nested
        public List<CommandLineArgumentProvider> getArgumentProviders() {
            return argumentProviders.get();
        }

        @Override
        public Iterable<String> asArguments() {
            List<String> arguments = new ArrayList<>(getCompilerArgs());
            getArgumentProviders().forEach(provider -> provider.asArguments().forEach(arguments::add));
            return arguments;
        }
    }

    /** compileJava's JVM argument providers, read when the accept compilation runs. */
    public static final class MainJvmArguments implements CommandLineArgumentProvider {

        private final Provider<List<CommandLineArgumentProvider>> argumentProviders;

        MainJvmArguments(Provider<List<CommandLineArgumentProvider>> argumentProviders) {
            this.argumentProviders = argumentProviders;
        }

        /**
         * compileJava's JVM argument providers.
         *
         * @return the providers
         */
        @Nested
        public List<CommandLineArgumentProvider> getArgumentProviders() {
            return argumentProviders.get();
        }

        @Override
        public Iterable<String> asArguments() {
            List<String> arguments = new ArrayList<>();
            getArgumentProviders().forEach(provider -> provider.asArguments().forEach(arguments::add));
            return arguments;
        }
    }

    /** compileJava's options that are not lazy properties, applied to the accept compilation as it runs. */
    private record MainCompileSettings(String encoding, String sourceCompatibility, String targetCompatibility,
                                       boolean fork, String executable, File javaHome, String memoryInitialSize,
                                       String memoryMaximumSize, String tempDir, List<String> jvmArgs) {

        static MainCompileSettings of(JavaCompile main) {
            CompileOptions options = main.getOptions();
            ForkOptions fork = options.getForkOptions();
            return new MainCompileSettings(options.getEncoding(), main.getSourceCompatibility(),
                    main.getTargetCompatibility(), options.isFork(), fork.getExecutable(), fork.getJavaHome(),
                    fork.getMemoryInitialSize(), fork.getMemoryMaximumSize(), fork.getTempDir(),
                    fork.getJvmArgs() == null ? null : List.copyOf(fork.getJvmArgs()));
        }

        /**
         * The settings that are inputs of a compilation, so a change re-runs the accept compilation. The
         * Java home is an input by content, and the temporary directory is not one, as in Gradle's own
         * fork options: it does not change the classes compiled, and its path is not relocatable.
         */
        List<String> fingerprint() {
            return Stream.of(encoding, sourceCompatibility, targetCompatibility, fork, executable,
                            memoryInitialSize, memoryMaximumSize, jvmArgs)
                    .map(String::valueOf).toList();
        }

        void applyTo(JavaCompile task) {
            CompileOptions options = task.getOptions();
            options.setEncoding(encoding);
            task.setSourceCompatibility(sourceCompatibility);
            task.setTargetCompatibility(targetCompatibility);
            options.setFork(fork);
            ForkOptions forkOptions = options.getForkOptions();
            forkOptions.setExecutable(executable);
            forkOptions.setJavaHome(javaHome);
            forkOptions.setMemoryInitialSize(memoryInitialSize);
            forkOptions.setMemoryMaximumSize(memoryMaximumSize);
            forkOptions.setTempDir(tempDir);
            forkOptions.setJvmArgs(jvmArgs);
        }
    }

    /** Applies compileJava's final settings; a class, not a lambda, so the task stays cacheable. */
    private static final class ApplyMainCompileSettings implements Action<Task> {

        private final Provider<MainCompileSettings> settings;

        ApplyMainCompileSettings(Provider<MainCompileSettings> settings) {
            this.settings = settings;
        }

        @Override
        public void execute(Task task) {
            settings.get().applyTo((JavaCompile) task);
        }
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
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
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

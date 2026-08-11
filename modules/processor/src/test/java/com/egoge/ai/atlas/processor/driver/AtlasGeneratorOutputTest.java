/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.egoge.ai.atlas.processor.AgenticProcessor;

import static com.egoge.ai.atlas.processor.driver.DriverTestFixtures.ENTITY_SOURCE;
import static com.egoge.ai.atlas.processor.driver.DriverTestFixtures.EXTRA_ENTITY_SOURCE;
import static com.egoge.ai.atlas.processor.driver.DriverTestFixtures.currentClasspath;
import static com.egoge.ai.atlas.processor.driver.DriverTestFixtures.dtoNames;
import static com.egoge.ai.atlas.processor.driver.DriverTestFixtures.generatedSourcesByPath;
import static com.egoge.ai.atlas.processor.driver.DriverTestFixtures.readJavaTree;
import static com.egoge.ai.atlas.processor.driver.DriverTestFixtures.writeSampleSources;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for how {@link AtlasGenerator} manages the output directory it owns: the manifest reflects
 * only the current run, publication is atomic across the two owned roots, concurrent runs sharing an
 * output directory are serialized, and bad inputs are rejected before anything is written.
 */
class AtlasGeneratorOutputTest {

    private static final int CONCURRENT_RUNS = 4;
    private static final int CONCURRENT_TIMEOUT_SECONDS = 180;

    @TempDir
    private Path outputDir;

    @Test
    void capturesCompilationErrorsAsDiagnostics(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("Broken.java"), """
                package test;
                public class Broken { this is not java }
                """, StandardCharsets.UTF_8);

        GenerationResult result = AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir);

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors()).first()
                .satisfies(d -> assertThat(d.source()).endsWith("Broken.java"));
    }

    @Test
    void regeneratingIntoTheSameOutputDirectoryOverwritesResources(@TempDir Path sourceDir) throws IOException {
        generateFromSourceStrings(sourceDir);
        GenerationResult second = generateFromSourceStrings(sourceDir);

        assertThat(second.filesOfKind(GeneratedFile.Kind.OPENAPI)).hasSize(2);
        assertThat(second.filesOfKind(GeneratedFile.Kind.API_VERSION_PROPERTIES)).hasSize(1);
        assertThat(second.openApi()).isNotNull();
    }

    @Test
    void artifactsRemovedFromTheSourcesDisappearFromTheResult(@TempDir Path sourceDir) throws IOException {
        Path packageDir = Files.createDirectories(sourceDir.resolve("test"));
        Path extra = packageDir.resolve("Extra.java");
        Files.writeString(extra, EXTRA_ENTITY_SOURCE, StandardCharsets.UTF_8);
        GenerationResult first = generateFromSourceStrings(sourceDir);
        assertThat(dtoNames(first)).contains("ExtraDto.java");

        Files.delete(extra);
        GenerationResult second = generateFromSourceStrings(sourceDir);

        assertThat(dtoNames(second)).doesNotContain("ExtraDto.java");
        assertThat(second.files()).noneMatch(file -> file.relativePath().endsWith("ExtraDto.java"));
        assertThat(outputDir.resolve(AtlasGenerator.SOURCES_DIR)
                .resolve("test/generated/ExtraDto.java")).doesNotExist();
    }

    @Test
    void aFailedRerunDoesNotReportTheEarlierRunsArtifacts(@TempDir Path sourceDir) throws IOException {
        GenerationResult first = generateFromSourceStrings(sourceDir);
        assertThat(first.files()).isNotEmpty();

        // Snapshot the PUBLISHED trees, not just the manifest. The manifest assertions below pass
        // even against an implementation that publishes unconditionally — it wipes these trees and
        // still returns a manifest without CustomerDto.java. Only comparing the on-disk trees
        // before and after distinguishes "reported nothing" from "destroyed the last good output".
        Map<String, String> sourcesBefore = readTree(outputDir.resolve(AtlasGenerator.SOURCES_DIR));
        Map<String, String> resourcesBefore = readTree(outputDir.resolve(AtlasGenerator.RESOURCES_DIR));
        assertThat(sourcesBefore).isNotEmpty();

        Files.writeString(sourceDir.resolve("test/Customer.java"),
                "package test; public class Customer { this is not java }", StandardCharsets.UTF_8);
        GenerationResult second =
                AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir);

        assertThat(second.success()).isFalse();
        assertThat(second.files()).noneMatch(file -> file.relativePath().endsWith("CustomerDto.java"));
        assertThat(second.diagnostics()).isNotEmpty();
        assertThat(readTree(outputDir.resolve(AtlasGenerator.SOURCES_DIR))).isEqualTo(sourcesBefore);
        assertThat(readTree(outputDir.resolve(AtlasGenerator.RESOURCES_DIR)))
                .isEqualTo(resourcesBefore);
    }

    /**
     * Every regular file under {@code root}, keyed by its relative path — unlike
     * {@link DriverTestFixtures#readJavaTree(Path)} this keeps resources too, which is what a
     * failed rerun must leave untouched alongside the sources. A missing root reads as empty so a
     * run that never published anything is still comparable.
     */
    private static Map<String, String> readTree(Path root) throws IOException {
        Map<String, String> byPath = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return byPath;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                byPath.put(relative, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return byPath;
    }

    @Test
    void concurrentRunsSharingAnOutputDirectoryEachSeeAWholeTree(@TempDir Path sourceDir)
            throws Exception {
        writeSampleSources(sourceDir);
        List<Path> classpath = currentClasspath();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_RUNS);
        try {
            List<Future<GenerationResult>> runs = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_RUNS; i++) {
                runs.add(pool.submit(() ->
                        AtlasGenerator.generate(List.of(sourceDir), classpath, outputDir)));
            }
            for (Future<GenerationResult> run : runs) {
                GenerationResult result = run.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(result.errors()).isEmpty();
                assertThat(result.success()).isTrue();
                // Sources and resources must come from one run — never a mix, and never a tree
                // another run was midway through replacing.
                assertThat(result.filesOfKind(GeneratedFile.Kind.DTO)).isNotEmpty();
                assertThat(result.filesOfKind(GeneratedFile.Kind.REST_CONTROLLER)).isNotEmpty();
                assertThat(result.filesOfKind(GeneratedFile.Kind.OPENAPI)).hasSize(2);
                assertThat(result.filesOfKind(GeneratedFile.Kind.API_VERSION_PROPERTIES)).hasSize(1);
                assertThat(result.filesOfKind(GeneratedFile.Kind.DEPRECATION_MANIFEST)).hasSize(1);
                assertThat(result.openApi()).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aPublicationThatFailsPartWayLeavesThePreviousOutputIntact(@TempDir Path sourceDir)
            throws IOException {
        GenerationResult first = generateFromSourceStrings(sourceDir);
        assertThat(first.files()).isNotEmpty();

        // Replace the resources root with a dangling symlink: it does not "exist" (so it is not
        // moved aside), but the link itself does, so this run's move onto it fails — after the
        // sources root has already been replaced. Without rollback the output would be left
        // half-published: this run's sources beside the previous run's resources.
        Path resourceOut = outputDir.resolve(AtlasGenerator.RESOURCES_DIR);
        deleteTree(resourceOut);
        assumeTrue(createDanglingSymlink(resourceOut), "filesystem does not support symlinks");
        Files.writeString(Files.createDirectories(sourceDir.resolve("test")).resolve("Extra.java"),
                EXTRA_ENTITY_SOURCE, StandardCharsets.UTF_8);

        assertThatThrownBy(() ->
                AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir))
                .isInstanceOf(UncheckedIOException.class);

        Path sourceOut = outputDir.resolve(AtlasGenerator.SOURCES_DIR);
        assertThat(readJavaTree(sourceOut)).as("previous sources tree, restored")
                .isEqualTo(generatedSourcesByPath(first));
        assertThat(sourceOut.resolve("test/generated/ExtraDto.java"))
                .as("the failed run's sources must not survive").doesNotExist();
    }

    @Test
    void returnsAbsolutePathsForARelativeOutputDirectory(@TempDir Path sourceDir) throws IOException {
        writeSampleSources(sourceDir);
        Path relativeOut = Path.of("").toAbsolutePath().relativize(outputDir.toAbsolutePath());
        assertThat(relativeOut.isAbsolute()).as("test drives a relative --out").isFalse();

        GenerationResult result =
                AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), relativeOut);

        assertThat(result.success()).isTrue();
        assertThat(result.outputDir()).isAbsolute();
        assertThat(result.files()).isNotEmpty()
                .allSatisfy(file -> assertThat(file.path()).isAbsolute().exists());
    }

    @Test
    void regeneratesWhenTheOutputDirectoryIsNestedInsideTheSourceRoot(@TempDir Path sourceDir)
            throws IOException {
        writeSampleSources(sourceDir);
        Path nestedOut = sourceDir.resolve("generated");

        GenerationResult first =
                AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), nestedOut);
        GenerationResult second =
                AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), nestedOut);

        assertThat(first.errors()).isEmpty();
        assertThat(second.errors()).isEmpty();
        assertThat(second.success()).isTrue();
        assertThat(generatedSourcesByPath(second)).isEqualTo(generatedSourcesByPath(first));
    }

    @Test
    void rejectsSourcePathThatDoesNotExist() {
        Path missing = outputDir.resolve("nowhere");

        assertThatThrownBy(() -> AtlasGenerator.generate(List.of(missing), currentClasspath(), outputDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void rejectsSourceSetWithoutJavaFiles(@TempDir Path sourceDir) {
        assertThatThrownBy(() -> AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No .java sources");
    }

    @Test
    void reportsNestedServicesUnderTheirQualifiedName(@TempDir Path sourceDir) throws IOException {
        Path packageDir = Files.createDirectories(sourceDir.resolve("test"));
        Files.writeString(packageDir.resolve("Customer.java"), ENTITY_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("Outer.java"), """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                public class Outer {
                    @AgenticExposed(description = "Look customers up", returnType = Customer.class)
                    public static class Inner {
                        public Customer findById(Long id) { return null; }
                    }
                }
                """, StandardCharsets.UTF_8);

        GenerationResult result = AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir);

        assertThat(result.success()).isTrue();
        // The wrapper file is named from the simple name, so reconstructing the service name from
        // artifacts yields "test.Inner". The result must carry the real qualified name.
        assertThat(result.discoveredServices()).contains("test.Outer.Inner");
        assertThat(result.discoveredServices()).doesNotContain("test.Inner");
    }

    @Test
    void reportsServicesWhoseMethodsAreAllFilteredOut(@TempDir Path sourceDir) throws IOException {
        Path packageDir = Files.createDirectories(sourceDir.resolve("test"));
        Files.writeString(packageDir.resolve("Customer.java"), ENTITY_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("RetiredService.java"), """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                @AgenticExposed(description = "Retired before the configured major",
                                returnType = Customer.class, apiUntil = 1)
                public class RetiredService {
                    public Customer findById(Long id) { return null; }
                }
                """, StandardCharsets.UTF_8);

        GenerationResult result = AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir,
                Map.of(AgenticProcessor.OPT_API_MAJOR, "2"));

        assertThat(result.success()).isTrue();
        // Every method is filtered out at major 2, so no wrapper is emitted for this service.
        assertThat(result.files()).noneMatch(f -> f.relativePath().contains("RetiredService"));
        // It is still a discovered @AgenticExposed service and must be reported as one.
        assertThat(result.discoveredServices()).contains("test.RetiredService");
    }

    @Test
    void reportsServicesWithNoPublicMethods(@TempDir Path sourceDir) throws IOException {
        Path packageDir = Files.createDirectories(sourceDir.resolve("test"));
        Files.writeString(packageDir.resolve("Customer.java"), ENTITY_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("HiddenService.java"), """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                @AgenticExposed(description = "Nothing public to expose", returnType = Customer.class)
                public class HiddenService {
                    private Customer lookup(Long id) { return null; }
                }
                """, StandardCharsets.UTF_8);

        GenerationResult result = AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir);

        assertThat(result.success()).isTrue();
        // No public methods means no wrapper — but the type is still a discovered service.
        assertThat(result.files()).noneMatch(f -> f.relativePath().contains("HiddenService"));
        assertThat(result.discoveredServices()).contains("test.HiddenService");
    }

    @Test
    void doesNotEmitAServiceManifestResource(@TempDir Path sourceDir) throws IOException {
        GenerationResult result = generateFromSourceStrings(sourceDir);

        // Discovered service names travel on the processor instance, never as an emitted resource:
        // a manifest file would change the generated output shape of every consumer build.
        assertThat(result.files())
                .noneMatch(f -> f.relativePath().contains("service-manifest"));
        assertThat(outputDir.resolve(AtlasGenerator.RESOURCES_DIR)
                .resolve("META-INF/ai-atlas/service-manifest.properties")).doesNotExist();
        assertThat(result.discoveredServices()).contains("test.CustomerService");
    }

    private GenerationResult generateFromSourceStrings(Path sourceDir) throws IOException {
        writeSampleSources(sourceDir);

        GenerationResult result = AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        return result;
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** Creates a symlink whose target does not exist; {@code false} if the filesystem refuses. */
    private static boolean createDanglingSymlink(Path link) {
        try {
            Files.createSymbolicLink(link, link.resolveSibling("no-such-target"));
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}

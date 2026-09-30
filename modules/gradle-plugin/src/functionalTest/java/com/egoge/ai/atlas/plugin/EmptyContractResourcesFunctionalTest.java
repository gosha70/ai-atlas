/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional smoke test of {@code compileJava.doLast} writing the empty contract (C3b) for a
 * module with no {@code @AgenticEntity} or {@code @AgenticExposed} at all: a real {@code api.ir.json}
 * and {@code contract-resources.json} are written, a second build leaves {@code compileJava}
 * {@code UP-TO-DATE}, and the wiring is configuration-cache safe.
 */
class EmptyContractResourcesFunctionalTest {

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"empty-contract-test\"");
        String repo = System.getProperty("ai.atlas.functionalTest.repo").replace('\\', '/');
        write("build.gradle.kts", """
                plugins {
                    id("com.egoge.ai-atlas")
                }

                repositories {
                    maven { url = uri("%s") }
                    mavenCentral()
                }

                agentic {
                    version.set("%s")
                }
                """.formatted(repo, System.getProperty("ai.atlas.functionalTest.version")));
        write("src/main/java/test/Empty.java", """
                package test;

                public class Empty {
                }
                """);
    }

    @Test
    void anEmptyModuleGetsTheEmptyContractAndIsUpToDateOnASecondBuild() throws IOException {
        BuildResult first = run("compileJava").build();
        assertThat(first.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(Files.readString(ir().toPath())).contains("\"entities\"");
        assertThat(manifest().isFile()).isTrue();
        assertThat(Files.readString(manifest().toPath())).contains("\"contract\": \"empty\"");

        BuildResult second = run("compileJava").build();

        assertThat(second.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
    }

    /**
     * The compile/accept path, the counterpart of {@code ReleaseResourcesFunctionalTest}'s release
     * path: removing every annotation without {@code clean} leaves a fresh empty contract, never the
     * stale non-empty IR, with a manifest digesting those new bytes, and the release then passes
     * with the accepted empty IR in its snapshot.
     */
    @Test
    void removingEveryAnnotationRegeneratesAFreshEmptyContractThatReleases() throws Exception {
        write("src/main/java/test/Empty.java", """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticEntity;
                import com.egoge.ai.atlas.annotations.AgenticField;

                @AgenticEntity(description = "Once a contract")
                public class Empty {
                    @AgenticField(description = "Id") private Long id;

                    public Long getId() { return id; }
                }
                """);
        run("atlasAccept").build();
        run("compileJava").build();
        byte[] stale = Files.readAllBytes(ir().toPath());
        write("src/main/java/test/Empty.java", "package test;\n\npublic class Empty {\n}\n");

        // Incremental, with the baseline unchanged, as in the remove-all-annotations flow
        run("compileJava").build();

        byte[] fresh = Files.readAllBytes(ir().toPath());
        assertThat(fresh).isNotEqualTo(stale);
        assertThat(Files.readString(manifest().toPath())).contains("\"contract\": \"empty\"",
                "\"META-INF/ai-atlas/api.ir.json\": \"" + sha256(fresh) + "\"");

        run("atlasAccept").build();
        assertThat(fresh).isEqualTo(Files.readAllBytes(new File(projectDir, ".atlas/api.ir.json").toPath()));
        run("classes", "agenticRelease", "-Pversion=1.0.0").build();

        assertThat(Files.readAllBytes(new File(projectDir, ".atlas/releases/1.0.0/api.ir.json").toPath()))
                .isEqualTo(fresh);
    }

    /** A major overriding the extension's through compileJava's arguments reaches the empty contract. */
    @Test
    void anOverriddenMajorReachesTheEmptyContractsAcceptAndCheck() throws IOException {
        Files.writeString(new File(projectDir, "build.gradle.kts").toPath(), """

                agentic { contractLocked.set(true) }
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgumentProviders.add(CommandLineArgumentProvider { listOf("-Aai.atlas.api.major=2") })
                }
                """, java.nio.file.StandardOpenOption.APPEND);

        run("atlasAccept").build();
        assertThat(Files.readString(new File(projectDir, ".atlas/api.ir.json").toPath()))
                .contains("\"apiMajor\": 2");

        BuildResult checked = run("classes").build();

        assertThat(checked.task(":atlasContractCheck").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void theWiringIsConfigurationCacheSafe() {
        BuildResult stored = run("compileJava", "--configuration-cache").build();
        assertThat(stored.getOutput()).doesNotContain("problems were found storing the configuration cache");

        BuildResult reused = run("compileJava", "--configuration-cache").build();

        assertThat(reused.getOutput()).contains("Reusing configuration cache");
    }

    private File ir() {
        return new File(projectDir, "build/classes/java/main/META-INF/ai-atlas/api.ir.json");
    }

    private File manifest() {
        return new File(projectDir, "build/classes/java/main/META-INF/ai-atlas/contract-resources.json");
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void write(String path, String content) throws IOException {
        File file = new File(projectDir, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    private GradleRunner run(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(tasks);
    }
}

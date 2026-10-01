/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.release.ReleaseManifest;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code agenticReleaseVerify} end to end (E2, E3, AC14): it proves a release's tag resolves to
 * exactly {@code HEAD}, then that the build matches that release's snapshot byte for byte, IR
 * canonically, and structurally in its effective configuration; it has no task dependency on
 * {@code compileJava} or {@code classes}, so it fails, naming the command to run, when no class
 * output exists; and a consistent rewrite of a published snapshot (digests recomputed to match)
 * passes {@link ReleaseHistoryFunctionalTest}'s git-free history check but fails this one, naming
 * the tag: the division of work between the two tasks (D5.3, D6.1, D8.2).
 */
class ReleaseVerifyFunctionalTest {

    private static final String ORDER = "src/main/java/test/Order.java";
    private static final String SERVICE = "src/main/java/test/OrderService.java";
    private static final String ORDER_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticEntity;
            import com.egoge.ai.atlas.annotations.AgenticField;

            @AgenticEntity(description = "An order")
            public class Order {
                @AgenticField(description = "Id") private Long id;
                public Long getId() { return id; }
                %s
            }
            """;
    private static final String NOTE = """
            @AgenticField(description = "A note") private String note;
            public String getNote() { return note; }
            """;

    @TempDir
    File projectDir;

    GitFixture git;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"verify-test\"");
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
        order("");
        write(SERVICE, """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                @AgenticExposed(description = "Orders", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Finds an order by id")
                    public Order find(Long id) { return null; }
                }
                """);
        git = new GitFixture(projectDir);
    }

    @Test
    void verifyPassesOnTheTaggedCommitWithPversion() {
        releaseAndTag("1.0.0");

        BuildResult result = verify("1.0.0").build();

        assertThat(result.getOutput()).contains("The build matches the released contract 1.0.0", "v1.0.0");
    }

    /** Verify reads the class output's empty contract at the major it was compiled and released with. */
    @Test
    void anEmptyContractReleasedAtAnOverriddenMajorVerifies() throws IOException {
        write(ORDER, "package test;\n\npublic class Order {\n}\n");
        write(SERVICE, "package test;\n\npublic class OrderService {\n}\n");
        append("""

                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgumentProviders.add(CommandLineArgumentProvider { listOf("-Aai.atlas.api.major=2") })
                }
                """);
        releaseAndTag("2.0.0");

        BuildResult result = runner("classes", "agenticReleaseVerify", "-Pversion=2.0.0").build();

        assertThat(result.getOutput()).contains("The build matches the released contract 2.0.0");
    }

    @Test
    void verifyFailsWhenTheTagIsMissing() {
        release("1.0.0").build();
        git.commit("Release 1.0.0, not yet tagged");

        BuildResult result = verify("1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("Tag v1.0.0 does not exist");
    }

    @Test
    void verifyFailsWhenHeadIsNotTheTag() {
        releaseAndTag("1.0.0");
        git.commit("More work after the tag");

        BuildResult result = verify("1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("Tag v1.0.0's commit is not HEAD");
    }

    @Test
    void verifyFailsWhenTheBuildsIrDiffers() throws IOException {
        releaseAndTag("1.0.0");
        order(NOTE);
        runner("atlasAccept").build();

        // agenticReleaseVerify has no task dependency on classes: a source edit needs a rebuild
        // requested explicitly for the build's own class output to reflect it.
        BuildResult result = runner("classes", "agenticReleaseVerify", "-Pversion=1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("differs from the released contract");
    }

    @Test
    void verifyFailsWhenTheBuildProducesAnArtifactTheSnapshotDoesNotHave() throws IOException {
        releaseAndTag("1.0.0");
        append("agentic { constraints.set(true) }\n");

        BuildResult result = runner("classes", "agenticReleaseVerify", "-Pversion=1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("mcp-tools.json", "does not");
    }

    @Test
    void verifyFailsWhenTheSnapshotHasAnArtifactTheBuildDoesNotProduce() throws IOException {
        append("agentic { constraints.set(true) }\n");
        releaseAndTag("1.0.0");
        append("agentic { constraints.set(false) }\n");

        BuildResult result = runner("classes", "agenticReleaseVerify", "-Pversion=1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("mcp-tools.json", "the build does not produce");
    }

    @Test
    void aShallowCloneFails() throws IOException {
        releaseAndTag("1.0.0");
        File clone = Files.createTempDirectory("ai-atlas-verify-shallow").toFile();
        assertThat(clone.delete()).isTrue();
        git.git("clone", "--depth", "1", "file://" + projectDir.getAbsolutePath(), clone.getAbsolutePath());

        BuildResult result = runnerIn(clone, "agenticReleaseVerify", "-Pversion=1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("shallow clone", "Fetch tags and full history");
    }

    @Test
    void verifyAloneWithNoClassOutputFailsNamingTheCommandAndNeverRunsCompileJava() {
        releaseAndTag("1.0.0");
        runner("clean").build();

        BuildResult result = verify("1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("./gradlew classes agenticReleaseVerify");
        assertThat(result.task(":compileJava")).isNull();
    }

    /**
     * The key AC14 test: a published snapshot is rewritten consistently (its file changed, its
     * manifest's digest recomputed to match), which is exactly the case the git-free history check
     * cannot see, but the tag proof underlying verify can: the tagged commit's bytes no longer
     * equal the working copy's.
     */
    @Test
    void aConsistentRewriteOfAPublishedSnapshotPassesHistoryCheckButFailsVerifyNamingTheTag() throws IOException {
        releaseAndTag("1.0.0");
        rewriteConsistently("1.0.0");

        BuildResult historyCheck = runner("agenticReleaseHistoryCheck").build();
        assertThat(historyCheck.task(":agenticReleaseHistoryCheck").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);

        BuildResult result = verify("1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("v1.0.0", "differs from the working copy", "immutable");
    }

    /**
     * The realistic AC14 path: the rewrite of the published 1.0.0 is committed with the next release
     * and tagged v1.1.0, so HEAD is that release's own tag; only v1.0.0's tagged files expose it.
     */
    @Test
    void aRewriteCommittedWithTheNextReleasePassesHistoryCheckButFailsVerify() throws IOException {
        releaseAndTag("1.0.0");
        release("1.1.0").build();
        rewriteConsistently("1.0.0");
        git.commitAndTag("v1.1.0");

        assertThat(runner("agenticReleaseHistoryCheck").build().task(":agenticReleaseHistoryCheck").getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        BuildResult result = verify("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("v1.0.0", "differs from the working copy");
    }

    // ------------------------------------------------------------ helpers

    /** Changes {@code version}'s released IR and updates its release.json digest to match. */
    private void rewriteConsistently(String version) throws IOException {
        Path ir = releaseDir(version).resolve("api.ir.json");
        Path manifestFile = releaseDir(version).resolve("release.json");
        String rewritten = Files.readString(ir).replace("\"Id\"", "\"Identifier\"");
        Files.writeString(ir, rewritten);
        ReleaseManifest original = ReleaseManifest.read(Files.readString(manifestFile));
        Map<String, String> digests = new TreeMap<>(original.digests());
        digests.put("api.ir.json", sha256(rewritten.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(manifestFile, new ReleaseManifest(original.version(), original.apiMajor(),
                original.irVersion(), original.previous(), original.tagName(), original.policy(), digests,
                original.contractResources()).write());
    }

    /**
     * The lowercase hexadecimal SHA-256 of {@code bytes}: this functional test runs the plugin as a
     * consumer would, through TestKit, so it never references the plugin's own internal
     * {@code ReleaseSnapshots}, which computes release.json's digests the same way.
     */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void order(String members) throws IOException {
        write(ORDER, ORDER_SOURCE.formatted(members));
    }

    /** Accepts the current sources, then returns a runner releasing them as {@code version}. */
    private GradleRunner release(String version) {
        runner("atlasAccept").build();
        return runner("classes", "agenticRelease", "-Pversion=" + version);
    }

    /** Releases {@code version}, then commits and tags it as {@code v<version>} (F1). */
    private void releaseAndTag(String version) {
        release(version).build();
        git.commitAndTag("v" + version);
    }

    private GradleRunner verify(String version) {
        return runner("agenticReleaseVerify", "-Pversion=" + version);
    }

    private Path releaseDir(String version) {
        return projectDir.toPath().resolve(".atlas/releases/" + version);
    }

    private void append(String buildScript) throws IOException {
        Files.writeString(new File(projectDir, "build.gradle.kts").toPath(), buildScript, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    private void write(String path, String content) throws IOException {
        File file = new File(projectDir, path);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    private GradleRunner runner(String... tasks) {
        return runnerIn(projectDir, tasks);
    }

    private GradleRunner runnerIn(File dir, String... tasks) {
        return GradleRunner.create()
                .withProjectDir(dir)
                .withPluginClasspath()
                .withArguments(List.of(tasks));
    }
}

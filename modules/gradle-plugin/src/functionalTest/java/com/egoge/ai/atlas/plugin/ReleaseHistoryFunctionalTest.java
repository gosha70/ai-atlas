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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1's git-aware history end to end (AC15, AC16): a new project with no history releases; a
 * shallow clone, an untagged older snapshot, a tag without its snapshot, snapshots with no
 * repository, a tag not an ancestor of {@code HEAD}, and a tag whose snapshot was changed after it
 * was tagged each fail naming what is wrong; a pending release blocks the next one; an annotated
 * tag, a custom {@code tagName}, and rejecting an unconventional tag name all work as designed.
 */
class ReleaseHistoryFunctionalTest {

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
        write("settings.gradle.kts", "rootProject.name = \"history-test\"");
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
    }

    @Test
    void aNewProjectWithNoRepositoryAndNoSnapshotsReleases() {
        // No GitFixture at all: not even `git init`.
        BuildResult result = release("1.0.0").build();

        assertThat(result.getOutput()).contains("Released contract 1.0.0");
    }

    @Test
    void snapshotsWithoutARepositoryFail() {
        // No GitFixture: the first release needs no history, but the second finds one on disk with
        // nothing to prove it, and no repository at all.
        release("1.0.0").build();

        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("no git repository was found",
                "Fetch tags and full history", "fetch-depth: 0", "fetch-tags: true");
    }

    @Test
    void anUntaggedOlderSnapshotFails() {
        git = new GitFixture(projectDir);
        release("1.0.0").build();
        git.commitAndTag("v1.0.0");
        order(NOTE);
        release("1.1.0").build();
        git.commitAndTag("v1.1.0");
        // 1.0.0's tag is removed after the fact: it is no longer the newest, so this is a genuine
        // failure, not merely pending.
        git.deleteTag("v1.0.0");

        BuildResult result = release("1.2.0").buildAndFail();

        assertThat(result.getOutput()).contains("Release 1.0.0 has no tag v1.0.0",
                "Fetch tags and full history");
        assertThat(releaseDir("1.2.0")).doesNotExist();
    }

    @Test
    void aTagWithoutItsSnapshotFails() {
        git = new GitFixture(projectDir);
        release("1.0.0").build();
        git.commitAndTag("v1.0.0");
        // A tag matching the convention, but no .atlas/releases/2.0.0 was ever made.
        git.tag("v2.0.0");

        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("Tag v2.0.0 matches", "2.0.0 snapshot for it",
                "tagName.set(\"api-v{version}\")", "Fetch tags and full history");
        assertThat(releaseDir("1.1.0")).doesNotExist();
    }

    @Test
    void releasedFilesSurviveACheckoutThatConvertsLineEndings() throws IOException {
        git = new GitFixture(projectDir);
        release("1.0.0").build();
        git.commitAndTag("v1.0.0");
        File clone = Files.createTempDirectory("ai-atlas-history-crlf").toFile();
        assertThat(clone.delete()).isTrue();
        // core.autocrlf=true converts LF to CRLF on checkout on any OS, as Git for Windows does by default
        git.git("clone", "-q", "-c", "core.autocrlf=true", "file://" + projectDir.getAbsolutePath(),
                clone.getAbsolutePath());

        assertThat(runnerIn(clone, "agenticReleaseHistoryCheck").build().task(":agenticReleaseHistoryCheck")
                .getOutcome()).isEqualTo(TaskOutcome.SUCCESS);

        // Without the attribute (removed from the index too, which git also reads), the same checkout
        // rewrites the released files and the check fails
        git.git("-C", clone.getAbsolutePath(), "rm", "-q", "--cached", ".gitattributes");
        Files.delete(clone.toPath().resolve(".gitattributes"));
        try (var files = Files.walk(clone.toPath().resolve(".atlas"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        }
        git.git("-C", clone.getAbsolutePath(), "checkout", "--", ".atlas");
        assertThat(runnerIn(clone, "agenticReleaseHistoryCheck").buildAndFail().getOutput())
                .contains("modified after release");
    }

    @Test
    void releasingWithoutTheLineEndingAttributeFailsNamingTheLines() throws IOException {
        git = new GitFixture(projectDir);
        git.git("rm", "-q", "--cached", ".gitattributes");
        Files.delete(projectDir.toPath().resolve(".gitattributes"));

        BuildResult result = release("1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("Add these lines to .gitattributes", ".atlas/releases/** -text",
                ".atlas/CHANGELOG.md -text");
        assertThat(releaseDir("1.0.0")).doesNotExist();
    }

    @Test
    void aLineEndingAttributeWrittenButNotStagedDoesNotCount() {
        // The tagged commit would lack an unstaged line, so a Windows clone would still convert
        git = new GitFixture(projectDir);
        git.git("rm", "-q", "--cached", ".gitattributes");

        BuildResult result = release("1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("Add these lines to .gitattributes");
        assertThat(releaseDir("1.0.0")).doesNotExist();
    }

    @Test
    void aShallowCloneFails() throws IOException, InterruptedException {
        git = new GitFixture(projectDir);
        release("1.0.0").build();
        git.commitAndTag("v1.0.0");
        File clone = Files.createTempDirectory("ai-atlas-history-shallow").toFile();
        assertThat(clone.delete()).isTrue();
        git.git("clone", "--depth", "1", "file://" + projectDir.getAbsolutePath(), clone.getAbsolutePath());

        BuildResult result = runnerIn(clone, "classes", "agenticRelease", "-Pversion=1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("shallow clone", "Fetch tags and full history");
    }

    @Test
    void aTagNotAnAncestorOfHeadFails() {
        git = new GitFixture(projectDir);
        release("1.0.0").build();
        git.commitAndTag("v1.0.0");
        // A new, parentless branch keeps the working tree (so 1.0.0's snapshot is still on disk),
        // but its HEAD shares no history with the tagged commit.
        git.commitOnAnOrphanBranch("unrelated", "Unrelated history");

        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("not an ancestor of HEAD", "Fetch tags and full history");
        assertThat(releaseDir("1.1.0")).doesNotExist();
    }

    @Test
    void aTagWhoseSnapshotWasChangedAfterItWasTaggedFails() throws IOException {
        git = new GitFixture(projectDir);
        release("1.0.0").build();
        git.commitAndTag("v1.0.0");
        // Changed on disk, but never committed: the tag still points at the original bytes.
        Path manifest = releaseDir("1.0.0").resolve("release.json");
        Files.writeString(manifest, Files.readString(manifest) + " ");

        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("differs from the working copy", "immutable",
                "restore it from version control");
    }

    @Test
    void aPendingReleaseBlocksTheNextOne() {
        git = new GitFixture(projectDir);
        release("1.0.0").build();
        // Committed, but never tagged.
        git.commit("Release 1.0.0, not yet tagged");

        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("Release 1.0.0 is pending", "tag its commit as v1.0.0",
                "push the tag, then release");
        assertThat(releaseDir("1.1.0")).doesNotExist();
    }

    @Test
    void anAnnotatedTagWorks() {
        git = new GitFixture(projectDir);
        release("1.0.0").build();
        git.commit("Release 1.0.0");
        git.annotatedTag("v1.0.0");

        BuildResult result = release("1.1.0").build();

        assertThat(result.getOutput()).contains("Released contract 1.1.0");
    }

    @Test
    void aVersionOnlyTagDoesNotSatisfyTheDefaultConvention() {
        git = new GitFixture(projectDir);
        release("1.0.0").build();
        // "1.0.0", not "v1.0.0": never stands in for the default tagName convention.
        git.commit("Release 1.0.0");
        git.tag("1.0.0");

        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("Release 1.0.0 is pending", "tag its commit as v1.0.0");
    }

    @Test
    void aCustomTagNameWorks() throws IOException {
        append("agentic { release { tagName.set(\"release-{version}\") } }\n");
        git = new GitFixture(projectDir);
        release("1.0.0").build();
        git.commitAndTag("release-1.0.0");

        BuildResult result = release("1.1.0").build();

        assertThat(result.getOutput()).contains("Released contract 1.1.0");
        assertThat(Files.readString(releaseDir("1.1.0").resolve("release.json"), StandardCharsets.UTF_8))
                .contains("\"tagName\": \"release-1.1.0\"");
    }

    // ------------------------------------------------------------ helpers

    private void order(String members) {
        write(ORDER, ORDER_SOURCE.formatted(members));
    }

    /** Accepts the current sources, then returns a runner releasing them as {@code version}. */
    private GradleRunner release(String version) {
        runner("atlasAccept").build();
        return runner("classes", "agenticRelease", "-Pversion=" + version);
    }

    private Path releaseDir(String version) {
        return projectDir.toPath().resolve(".atlas/releases/" + version);
    }

    private void append(String buildScript) {
        try {
            Files.writeString(projectDir.toPath().resolve("build.gradle.kts"), buildScript, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(String path, String content) {
        try {
            File file = new File(projectDir, path);
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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

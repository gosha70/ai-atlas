/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GitRepository}: offline, read-only shell-out to {@code git} (D-GIT, plan §3.1), against a
 * real repository {@code git init} creates in a {@code @TempDir}.
 */
class GitRepositoryTest {

    @TempDir
    Path dir;

    private String mainBranch;

    @BeforeEach
    void init() throws IOException, InterruptedException {
        git(dir, "init", "-q");
        git(dir, "config", "user.name", "ai-atlas-test");
        git(dir, "config", "user.email", "ai-atlas-test@example.invalid");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "tag.gpgsign", "false");
        Files.writeString(dir.resolve("README.md"), "init", StandardCharsets.UTF_8);
        git(dir, "add", "README.md");
        git(dir, "commit", "-q", "-m", "init");
        mainBranch = git(dir, "symbolic-ref", "--short", "HEAD");
    }

    @Test
    void aDirectoryThatIsNotARepositoryIsDetected() throws IOException {
        // Outside dir, which the @BeforeEach already made a repository: a subdirectory of a
        // repository is itself inside its work tree, so it would not exercise this case.
        Path notARepo = Files.createTempDirectory("ai-atlas-not-a-repo");
        try {
            assertThat(new GitRepository(notARepo).isInsideWorkTree()).isFalse();
        } finally {
            Files.delete(notARepo);
        }
    }

    @Test
    void aMissingExecutableIsTreatedAsNoRepository() {
        GitRepository repo = new GitRepository(dir, "definitely-not-a-real-git-binary-xyz", Duration.ofSeconds(5));

        assertThat(repo.isInsideWorkTree()).isFalse();
        // Past that answer nothing is queried; a query that runs anyway fails rather than answers
        assertThatThrownBy(repo::tags).isInstanceOf(GradleException.class).hasMessageContaining("could not be started");
    }

    @Test
    void aShallowCloneIsDetected() throws IOException, InterruptedException {
        commit("first");
        Path clone = Files.createTempDirectory("ai-atlas-shallow-clone");
        Files.delete(clone);
        run(dir, "git", "clone", "--depth", "1", "file://" + dir, clone.toString());

        GitRepository repo = new GitRepository(clone);
        assertThat(repo.isInsideWorkTree()).isTrue();
        assertThat(repo.isShallow()).isTrue();
        assertThat(new GitRepository(dir).isShallow()).isFalse();
    }

    @Test
    void tagsArePeeledToTheirCommit() throws IOException, InterruptedException {
        String first = commit("first");
        git(dir, "tag", "v1.0.0", first);
        String second = commit("second");
        git(dir, "tag", "-a", "v1.1.0", "-m", "release 1.1.0", second);

        GitRepository repo = new GitRepository(dir);
        assertThat(repo.tags()).containsExactlyInAnyOrder("v1.0.0", "v1.1.0");
        assertThat(repo.peelToCommit("v1.0.0")).contains(first);
        // An annotated tag peels to the commit it points at, not its own tag object, and that commit
        // is what the ancestor check receives
        assertThat(repo.peelToCommit("v1.1.0")).contains(second);
        assertThat(repo.isAncestorOfHead(repo.peelToCommit("v1.1.0").orElseThrow())).isTrue();
        assertThat(git(dir, "rev-parse", "v1.1.0")).isNotEqualTo(second);
        assertThat(repo.peelToCommit("v9.9.9")).isEmpty();
    }

    @Test
    void ancestryIsAnsweredCorrectly() throws IOException, InterruptedException {
        String first = commit("first");
        String head = commit("second");

        GitRepository repo = new GitRepository(dir);
        assertThat(repo.isAncestorOfHead(first)).isTrue();
        assertThat(repo.isAncestorOfHead(head)).isTrue();

        // A commit on a branch that was never merged into HEAD is not an ancestor.
        git(dir, "checkout", "-q", "-b", "side", first);
        String side = commit("side");
        git(dir, "checkout", "-q", mainBranch);
        assertThat(new GitRepository(dir).isAncestorOfHead(side)).isFalse();
    }

    @Test
    void blobBytesAreRawWhateverTheEolSettingsAndAMissingPathIsEmpty() throws IOException, InterruptedException {
        Files.writeString(dir.resolve("api.ir.json"), "{\"a\":1}\n", StandardCharsets.UTF_8);
        git(dir, "add", "api.ir.json");
        String commit = commit("first");
        // A checkout here would write CRLF; a proof must still see the stored bytes
        git(dir, "config", "core.autocrlf", "true");

        GitRepository repo = new GitRepository(dir);
        assertThat(repo.blobBytes(commit, "api.ir.json")).isEqualTo("{\"a\":1}\n".getBytes(StandardCharsets.UTF_8));
        assertThat(repo.blobBytes(commit, "does-not-exist.json")).isEmpty();
    }

    @Test
    void theCallersGitEnvironmentIsRemoved() {
        Map<String, String> environment = new HashMap<>(Map.of("PATH", "/usr/bin", "GIT_DIR", "/elsewhere/.git",
                "GIT_WORK_TREE", "/elsewhere", "GIT_INDEX_FILE", "/elsewhere/index",
                "GIT_CONFIG_PARAMETERS", "'core.bare'='true'", "GIT_OPTIONAL_LOCKS", "1"));

        GitRepository.isolate(environment);

        assertThat(environment).containsOnlyKeys("PATH", "GIT_TERMINAL_PROMPT", "GIT_OPTIONAL_LOCKS", "LC_ALL")
                .containsEntry("GIT_OPTIONAL_LOCKS", "0").containsEntry("LC_ALL", "C");
    }

    @Test
    void releasesPrefixIsRelativeToTheRepositoryRoot() throws IOException, InterruptedException {
        commit("first");
        Path nested = Files.createDirectories(dir.resolve("sub/.atlas/releases"));

        assertThat(new GitRepository(dir).releasesPrefix(dir)).isEmpty();
        assertThat(new GitRepository(dir).releasesPrefix(dir.resolve(".atlas/releases")))
                .isEqualTo(".atlas/releases/");
        assertThat(new GitRepository(dir).releasesPrefix(nested)).isEqualTo("sub/.atlas/releases/");
        // The releases directory itself need not exist yet: GitRepository is anchored at its
        // nearest existing ancestor.
        Path notYetCreated = dir.resolve("sub/.atlas/releases/does-not-exist-yet");
        GitRepository anchored = new GitRepository(GitRepository.nearestExistingAncestor(notYetCreated));
        assertThat(anchored.releasesPrefix(notYetCreated)).isEqualTo("sub/.atlas/releases/does-not-exist-yet/");
    }

    @Test
    void nearestExistingAncestorWalksUpToWhatExists() throws IOException {
        Path missing = dir.resolve("a/b/c");

        assertThat(GitRepository.nearestExistingAncestor(missing)).isEqualTo(dir.toAbsolutePath().normalize());
        assertThat(GitRepository.nearestExistingAncestor(dir)).isEqualTo(dir.toAbsolutePath().normalize());
    }

    @Test
    void aGitErrorOtherThanNoRepositoryFailsWithGitsMessage() throws IOException {
        // As git answers a checkout owned by another user, common in containerized CI
        Path script = dir.resolveSibling("dubious-git.sh");
        Files.writeString(script, "#!/bin/sh\necho \"fatal: detected dubious ownership in repository at '/work'\" >&2\n"
                + "exit 128\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        GitRepository repo = new GitRepository(dir, script.toString(), Duration.ofSeconds(5));

        assertThatThrownBy(repo::isInsideWorkTree).isInstanceOf(GradleException.class)
                .hasMessageContaining("detected dubious ownership").hasMessageContaining("exit code 128");
        assertThatThrownBy(repo::isShallow).isInstanceOf(GradleException.class)
                .hasMessageContaining("detected dubious ownership");
        // Not "no commit": a refused repository must not read as a tag that is not HEAD
        assertThatThrownBy(repo::headCommit).isInstanceOf(GradleException.class)
                .hasMessageContaining("detected dubious ownership");
    }

    @Test
    void eolConversionIsOffOnlyWhereTheTextAttributeIsUnset() throws IOException {
        GitRepository repo = new GitRepository(dir);
        assertThat(repo.eolConversionOff(dir.resolve(".atlas/releases/1.0.0/release.json"))).isFalse();

        Files.writeString(dir.resolve(".gitattributes"), ".atlas/** -text\n", StandardCharsets.UTF_8);

        assertThat(repo.eolConversionOff(dir.resolve(".atlas/releases/1.0.0/release.json"))).isTrue();
        assertThat(repo.eolConversionOff(dir.resolve(".atlas/CHANGELOG.md"))).isTrue();
        assertThat(repo.eolConversionOff(dir.resolve("README.md"))).isFalse();
    }

    @Test
    void aRepositoryWithoutCommitsHasNoHead() throws IOException, InterruptedException {
        Path empty = Files.createDirectories(dir.resolveSibling("no-commits"));
        git(empty, "init", "-q");

        assertThat(new GitRepository(empty).headCommit()).isEmpty();
    }

    @Test
    void aSlowCommandTimesOut() throws IOException {
        Path script = dir.resolveSibling("slow-git.sh");
        Files.writeString(script, "#!/bin/sh\nsleep 5\necho true\n", StandardCharsets.UTF_8);
        Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(script, perms);
        GitRepository repo = new GitRepository(dir, script.toString(), Duration.ofMillis(200));

        assertThatThrownBy(repo::isInsideWorkTree).isInstanceOf(GradleException.class)
                .hasMessageContaining("did not finish within");
    }

    @Test
    void noCommandWritesToTheRepository() throws IOException, InterruptedException {
        commit("first");
        git(dir, "tag", "v1.0.0");
        byte[] reflogBefore = Files.readAllBytes(dir.resolve(".git/logs/HEAD"));
        String statusBefore = git(dir, "status", "--porcelain");

        GitRepository repo = new GitRepository(dir);
        repo.isInsideWorkTree();
        repo.isShallow();
        repo.tags();
        repo.peelToCommit("v1.0.0");
        repo.isAncestorOfHead("HEAD");
        repo.blobBytes("HEAD", "does-not-exist.json");

        assertThat(Files.readAllBytes(dir.resolve(".git/logs/HEAD"))).isEqualTo(reflogBefore);
        assertThat(git(dir, "status", "--porcelain")).isEqualTo(statusBefore);
    }

    // ------------------------------------------------------------ helpers

    private String commit(String message) throws IOException, InterruptedException {
        Files.writeString(dir.resolve(message + ".txt"), message, StandardCharsets.UTF_8);
        git(dir, "add", message + ".txt");
        git(dir, "commit", "-q", "-m", message);
        return git(dir, "rev-parse", "HEAD");
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        return run(dir, command.toArray(String[]::new));
    }

    private static String run(Path dir, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Command " + List.of(command) + " failed with " + exit + ": " + output);
        }
        return output.strip();
    }
}

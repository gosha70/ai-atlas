/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A throwaway git repository for a functional test's project directory (F1): {@code git init},
 * repo-local identity and signing off (never the machine's global config), and a commit-and-tag
 * step after each release a test expects to be published. Used from {@code src/functionalTest}
 * only, so {@code agenticRelease}'s own tests exercise the same tagged history F1 (D3, D4) proves.
 */
final class GitFixture {

    private final File projectDir;

    /**
     * Initializes a new repository at {@code projectDir}.
     *
     * @param projectDir the functional test's project directory
     */
    GitFixture(File projectDir) {
        this.projectDir = projectDir;
        git("init", "-q");
        git("config", "user.name", "ai-atlas-functional-test");
        git("config", "user.email", "ai-atlas-functional-test@example.invalid");
        git("config", "commit.gpgsign", "false");
        git("config", "tag.gpgsign", "false");
        // As agenticRelease requires: no line-ending conversion of released files on checkout
        try {
            java.nio.file.Files.writeString(new File(projectDir, ".gitattributes").toPath(), ".atlas/** -text\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        git("add", ".gitattributes");
    }

    /**
     * Commits every file in the project directory (including anything not yet tracked), then tags
     * that commit, as CI is expected to do after {@code agenticRelease} succeeds.
     *
     * @param tagName the tag name, such as {@code "v1.0.0"}
     */
    void commitAndTag(String tagName) {
        git("add", "-A");
        git("commit", "-q", "-m", "Release " + tagName, "--allow-empty");
        git("tag", tagName);
    }

    /** Commits every file in the project directory without tagging, for setup before any release. */
    void commit(String message) {
        git("add", "-A");
        git("commit", "-q", "-m", message, "--allow-empty");
    }

    /** A lightweight tag on the current {@code HEAD}. */
    void tag(String tagName) {
        git("tag", tagName);
    }

    /** An annotated tag on the current {@code HEAD} (D6: an annotated tag works, peeled to its commit). */
    void annotatedTag(String tagName) {
        git("tag", "-a", tagName, "-m", "Release " + tagName);
    }

    /** Deletes a tag, so it no longer backs any release. */
    void deleteTag(String tagName) {
        git("tag", "-d", tagName);
    }

    /**
     * Switches to a brand-new, parentless branch, keeping every working-tree file exactly as it is
     * (unlike an ordinary checkout), then commits them: a commit with no common ancestor with any
     * earlier one (D6: a tag not an ancestor of {@code HEAD}).
     */
    void commitOnAnOrphanBranch(String branchName, String message) {
        git("checkout", "-q", "--orphan", branchName);
        git("add", "-A");
        git("commit", "-q", "-m", message);
    }

    /** The current branch's name. */
    String currentBranch() {
        return output("symbolic-ref", "--short", "HEAD");
    }

    /** Runs an arbitrary read-only or repository-local git command, such as {@code clone} or {@code log}. */
    void git(String... args) {
        run(List.of(args));
    }

    /** {@code git <args>}'s standard output, trimmed. */
    private String output(String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", projectDir.getAbsolutePath()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " in " + projectDir + " failed");
            }
            return out;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("git " + String.join(" ", args) + " in " + projectDir + " failed", e);
        }
    }

    private void run(List<String> args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", projectDir.getAbsolutePath()));
        command.addAll(args);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " in " + projectDir + " failed: "
                        + new String(output, StandardCharsets.UTF_8));
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("git " + String.join(" ", args) + " in " + projectDir + " failed", e);
        }
    }
}

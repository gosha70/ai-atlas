/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.GradleException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Offline, read-only access to a git repository, by shelling out to the {@code git} executable
 * (D-GIT, plan §3.1). Never fetches, writes a ref, an object or the config: every command is
 * plumbing that only reads. Used by {@link PublishedHistory} to prove a release's tag.
 *
 * <p>If {@code git} is missing, or {@code directory} is not inside a work tree, this is treated as
 * "no git repository", not a failure of this class: {@link #isInsideWorkTree()} simply answers
 * {@code false}. A bounded timeout fails the caller: a hang is never silently ignored.
 */
final class GitRepository implements GitQuery {

    private static final String PREFIX = "[ai-atlas] ";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Path directory;
    private final String gitCommand;
    private final Duration timeout;

    /**
     * @param directory a directory inside the repository to read, typically the releases directory's
     *                  nearest existing parent
     */
    GitRepository(Path directory) {
        this(directory, "git", DEFAULT_TIMEOUT);
    }

    /**
     * @param directory  a directory inside the repository to read
     * @param gitCommand the executable to run, injectable so a test can simulate "git is missing"
     * @param timeout    how long a command may run before the caller fails
     */
    GitRepository(Path directory, String gitCommand, Duration timeout) {
        this.directory = directory;
        this.gitCommand = gitCommand;
        this.timeout = timeout;
    }

    /** One command's outcome: its exit code and its standard output, raw bytes. */
    private record Result(int exitCode, byte[] stdout) {
        String text() {
            return new String(stdout, StandardCharsets.UTF_8).strip();
        }
    }

    /**
     * Whether {@code directory} is inside a git work tree. {@code false} when {@code git} is not on
     * the path, or the directory is not (yet) part of any repository.
     *
     * @return whether a git repository was found
     */
    @Override
    public boolean isInsideWorkTree() {
        return run("rev-parse", "--is-inside-work-tree").filter(r -> r.exitCode() == 0)
                .map(r -> "true".equals(r.text())).orElse(false);
    }

    /**
     * Whether the repository is a shallow clone, which cannot establish a genuine first release nor
     * prove full history.
     *
     * @return whether {@code git rev-parse --is-shallow-repository} answers {@code true}
     */
    @Override
    public boolean isShallow() {
        return run("rev-parse", "--is-shallow-repository").filter(r -> r.exitCode() == 0)
                .map(r -> "true".equals(r.text())).orElse(false);
    }

    /**
     * Every tag in the repository, however it was created (annotated or lightweight).
     *
     * @return the tags' short names, such as {@code "v1.0.0"}, in no particular order
     */
    @Override
    public List<String> tags() {
        Optional<Result> result = run("for-each-ref", "--format=%(refname:strip=2)", "refs/tags");
        if (result.isEmpty() || result.get().exitCode() != 0) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String line : result.get().text().lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }

    /**
     * Peels {@code tag} to the commit it names, following an annotated tag to the commit it points
     * at.
     *
     * @param tag a tag name
     * @return the commit's full SHA, or empty when {@code tag} does not exist or does not peel to a
     *     commit
     */
    @Override
    public Optional<String> peelToCommit(String tag) {
        Optional<Result> result = run("rev-parse", "--verify", "--quiet", "refs/tags/" + tag + "^{commit}");
        if (result.isEmpty() || result.get().exitCode() != 0) {
            return Optional.empty();
        }
        String sha = result.get().text();
        return sha.isEmpty() ? Optional.empty() : Optional.of(sha);
    }

    /**
     * Whether {@code commit} is an ancestor of {@code HEAD}, or is {@code HEAD} itself.
     *
     * @param commit a commit SHA
     * @return whether {@code git merge-base --is-ancestor <commit> HEAD} exits 0
     * @throws GradleException if the check itself fails, for a reason other than "not an ancestor"
     *                         (exit code 1)
     */
    @Override
    public boolean isAncestorOfHead(String commit) {
        Optional<Result> result = run("merge-base", "--is-ancestor", commit, "HEAD");
        if (result.isEmpty()) {
            return false;
        }
        int exitCode = result.get().exitCode();
        if (exitCode == 0) {
            return true;
        }
        if (exitCode == 1) {
            return false;
        }
        throw new GradleException(PREFIX + "git merge-base --is-ancestor " + commit + " HEAD failed with exit code "
                + exitCode + " in " + directory + ".");
    }

    /**
     * The bytes of {@code path} as {@code commit}'s tree has it, with the same eol and smudge filters
     * a checkout on this machine would apply, so "byte-identical to the working copy" is judged the
     * same way a checkout would be.
     *
     * @param commit the commit
     * @param path   the path, relative to the repository root
     * @return the blob's bytes, or an empty array when {@code path} does not exist at {@code commit}
     */
    @Override
    public byte[] blobBytes(String commit, String path) {
        Optional<Result> exists = run("cat-file", "-e", commit + ":" + path);
        if (exists.isEmpty() || exists.get().exitCode() != 0) {
            return new byte[0];
        }
        Optional<Result> content = run("cat-file", "--filters", commit + ":" + path);
        return content.filter(r -> r.exitCode() == 0).map(Result::stdout).orElse(new byte[0]);
    }

    /**
     * {@code releasesDir}'s path, relative to the repository root, forward-slashed and terminated
     * with {@code /} when non-empty. Works even when {@code releasesDir} itself does not exist yet
     * (no release has ever been made): this repository must have been constructed at, or above, an
     * existing ancestor of it.
     *
     * @param releasesDir the directory of releases
     * @return the prefix to combine with {@code <version>/release.json}, etc.
     */
    String releasesPrefix(Path releasesDir) {
        String showPrefix = run("rev-parse", "--show-prefix").filter(r -> r.exitCode() == 0).map(Result::text)
                .orElse("");
        String fromAnchor = directory.toAbsolutePath().normalize()
                .relativize(releasesDir.toAbsolutePath().normalize()).toString().replace(java.io.File.separatorChar,
                        '/');
        StringBuilder prefix = new StringBuilder(showPrefix);
        if (!fromAnchor.isEmpty() && !".".equals(fromAnchor)) {
            if (prefix.length() > 0 && prefix.charAt(prefix.length() - 1) != '/') {
                prefix.append('/');
            }
            prefix.append(fromAnchor).append('/');
        }
        return prefix.toString();
    }

    /**
     * The nearest ancestor of {@code path} (possibly {@code path} itself) that exists, for
     * constructing a {@link GitRepository} to read a directory of releases that may not have been
     * created yet.
     *
     * @param path a directory, which may not exist
     * @return the nearest existing ancestor, or the filesystem root if none of {@code path}'s
     *     ancestors exist either
     */
    static Path nearestExistingAncestor(Path path) {
        Path candidate = path.toAbsolutePath().normalize();
        while (candidate != null && !java.nio.file.Files.isDirectory(candidate)) {
            candidate = candidate.getParent();
        }
        return candidate != null ? candidate : path.getRoot();
    }

    // ---------------------------------------------------------------- process

    /**
     * Runs {@code git -C <directory> <args>}, with an argument vector and never a shell, and a
     * bounded timeout. Returns empty only when the executable itself could not be started (treated
     * as "no git repository"), never when the command ran and merely exited non-zero.
     */
    private Optional<Result> run(String... args) {
        List<String> command = new ArrayList<>();
        command.add(gitCommand);
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            // git is not on the PATH, or the injected command name does not exist: no repository.
            return Optional.empty();
        }
        // Drained concurrently with waitFor: a command that hangs before producing output must not
        // block this thread from ever reaching (and enforcing) the timeout below.
        StreamDrain stdout = new StreamDrain(process.getInputStream());
        StreamDrain stderr = new StreamDrain(process.getErrorStream());
        stdout.start();
        stderr.start();
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GradleException(PREFIX + "git " + String.join(" ", args) + " in " + directory
                        + " did not finish within " + timeout + ".");
            }
            stdout.join(timeout.toMillis());
            stderr.join(timeout.toMillis());
            return Optional.of(new Result(process.exitValue(), stdout.bytes()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException(PREFIX + "git " + String.join(" ", args) + " in " + directory
                    + " was interrupted.", e);
        }
    }

    /** Drains a process stream on its own thread, so reading it never blocks the caller's timeout. */
    private static final class StreamDrain extends Thread {
        private final InputStream in;
        private volatile byte[] bytes = new byte[0];

        StreamDrain(InputStream in) {
            super("ai-atlas-git-stream-drain");
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                in.transferTo(out);
            } catch (IOException e) {
                // The process was destroyed forcibly after a timeout: nothing more to read.
            }
            bytes = out.toByteArray();
        }

        byte[] bytes() {
            return bytes;
        }
    }
}

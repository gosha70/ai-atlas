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
import java.util.Map;
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
    /** How git, run with {@code LC_ALL=C}, says a directory belongs to no repository. */
    private static final String NOT_A_REPOSITORY = "not a git repository";
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

    /** One command's outcome: its exit code, its standard output as raw bytes, and git's error text. */
    private record Result(int exitCode, byte[] stdout, byte[] stderr) {
        String text() {
            return new String(stdout, StandardCharsets.UTF_8).strip();
        }

        String errorText() {
            return new String(stderr, StandardCharsets.UTF_8).strip();
        }
    }

    /** Runs a command that has no meaningful failure: any non-zero exit fails with git's message. */
    private Result succeeded(String... args) {
        Optional<Result> result = run(args);
        if (result.isEmpty()) {
            throw new GradleException(PREFIX + "git could not be started to run git " + String.join(" ", args) + ".");
        }
        if (result.get().exitCode() != 0) {
            throw failure(result.get(), args);
        }
        return result.get();
    }

    /** A command that failed for a reason other than the answer it exists to give, with git's own message. */
    private GradleException failure(Result result, String... args) {
        return new GradleException(PREFIX + "git " + String.join(" ", args) + " failed in " + directory
                + " with exit code " + result.exitCode() + ": " + result.errorText());
    }

    /**
     * Whether {@code directory} is inside a git work tree. {@code false} only when {@code git} is not
     * on the path, or git answers that the directory is not part of any repository. Any other error,
     * such as git refusing a repository of dubious ownership, fails with git's message: taking it
     * for "no repository" would skip the checks a repository requires.
     *
     * @return whether a git repository was found
     */
    @Override
    public boolean isInsideWorkTree() {
        String[] args = {"rev-parse", "--is-inside-work-tree"};
        Optional<Result> result = run(args);
        if (result.isEmpty()) {
            return false;
        }
        if (result.get().exitCode() == 0) {
            return "true".equals(result.get().text());
        }
        if (result.get().errorText().contains(NOT_A_REPOSITORY)) {
            return false;
        }
        throw failure(result.get(), args);
    }

    /**
     * Whether the repository is a shallow clone, which cannot establish a genuine first release nor
     * prove full history.
     *
     * @return whether {@code git rev-parse --is-shallow-repository} answers {@code true}
     */
    @Override
    public boolean isShallow() {
        return "true".equals(succeeded("rev-parse", "--is-shallow-repository").text());
    }

    /**
     * Every tag in the repository, however it was created (annotated or lightweight).
     *
     * @return the tags' short names, such as {@code "v1.0.0"}, in no particular order
     */
    @Override
    public List<String> tags() {
        List<String> tags = new ArrayList<>();
        for (String line : succeeded("for-each-ref", "--format=%(refname:strip=2)", "refs/tags").text().lines().toList()) {
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
        String[] args = {"rev-parse", "--verify", "--quiet", "refs/tags/" + tag + "^{commit}"};
        Optional<Result> result = run(args);
        if (result.isEmpty() || result.get().exitCode() == 1) {
            return Optional.empty(); // no such tag, or not one peeling to a commit
        }
        if (result.get().exitCode() != 0) {
            throw failure(result.get(), args);
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
        throw failure(result.get(), "merge-base", "--is-ancestor", commit, "HEAD");
    }

    /**
     * The bytes of {@code path} as {@code commit}'s tree stores them, raw: no eol conversion or
     * smudge filter, so a machine's {@code core.autocrlf} or attributes cannot change what a proof
     * compares. Released snapshots are marked binary in {@code .gitattributes} so that a checkout
     * leaves the working copy byte-identical to them.
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
        return succeeded("cat-file", "blob", commit + ":" + path).stdout();
    }

    /**
     * {@code HEAD}'s full commit SHA.
     *
     * @return the SHA, or empty when there is no commit yet (an unborn branch)
     */
    @Override
    public Optional<String> headCommit() {
        String[] args = {"rev-parse", "--verify", "--quiet", "HEAD"};
        Optional<Result> result = run(args);
        if (result.isEmpty() || result.get().exitCode() != 0) {
            return Optional.empty();
        }
        String sha = result.get().text();
        return sha.isEmpty() ? Optional.empty() : Optional.of(sha);
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
        String showPrefix = succeeded("rev-parse", "--show-prefix").text();
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
        isolate(builder.environment());
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
            return Optional.of(new Result(process.exitValue(), stdout.bytes(), stderr.bytes()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException(PREFIX + "git " + String.join(" ", args) + " in " + directory
                    + " was interrupted.", e);
        }
    }

    /**
     * Makes a git environment independent of the caller's: every inherited {@code GIT_*} variable is
     * removed ({@code GIT_DIR}, {@code GIT_WORK_TREE}, {@code GIT_INDEX_FILE},
     * {@code GIT_CONFIG_PARAMETERS} and the rest), so only {@code -C} selects the repository; no
     * prompt; no optional locks, so no command writes even an index refresh; and the C locale for
     * the output that is parsed.
     */
    static void isolate(Map<String, String> environment) {
        environment.keySet().removeIf(name -> name.startsWith("GIT_"));
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        environment.put("LC_ALL", "C");
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

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.EmptyContract;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.egoge.ai.atlas.processor.contract.ReleaseComparison;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Releases the accepted contract as an immutable snapshot, {@code <releases>/<version>/}, and
 * verifies the snapshots already released. The engine behind the Gradle plugin's
 * {@code agenticRelease} and {@code agenticReleaseCheck}; it reads and writes only the files it is
 * given, with no network, database or model call, and writes no timestamp, host or path.
 *
 * <p>A release directory holds:
 * <ul>
 *   <li>{@value #IR_FILE}: the accepted baseline, byte for byte, which the build's IR must equal;</li>
 *   <li>{@code openapi-v<major>.json}, the OpenAPI document of the released major, when generated;</li>
 *   <li>{@value #MCP_TOOLS_FILE}, when generated;</li>
 *   <li>{@value #DIFF_FILE}: the comparison with the previous release, in the gate's form;</li>
 *   <li>{@value #CHANGELOG_FILE}: the release's changelog section;</li>
 *   <li>{@value #MANIFEST_FILE}: the {@link ReleaseManifest}, with a SHA-256 of every other file.</li>
 * </ul>
 * Each release keeps the {@code irVersion} it was written with; {@link IrJson#read} migrates an
 * older one in memory, so released files never change when ai-atlas is upgraded.
 */
public final class ContractRelease {

    /** The released Contract IR, byte-identical to the accepted baseline. */
    public static final String IR_FILE = "api.ir.json";
    /** The comparison with the previous release, in the gate's {@code contract-diff.json} form. */
    public static final String DIFF_FILE = "contract-diff.json";
    /** The release's changelog section. */
    public static final String CHANGELOG_FILE = "CHANGELOG.md";
    /** The release manifest. */
    public static final String MANIFEST_FILE = "release.json";
    /** The MCP tool specifications, when {@code ai.atlas.constraints} generated them. */
    public static final String MCP_TOOLS_FILE = "mcp-tools.json";

    private static final String PREFIX = "[ai-atlas] ";
    private static final String RESTORE = " Released contracts are immutable: restore it from version control.";

    private ContractRelease() {
    }

    /** A release that cannot be made, or a released snapshot that fails verification. */
    public static final class ReleaseException extends Exception {
        private static final long serialVersionUID = 1L;

        ReleaseException(String message) {
            super(PREFIX + message);
        }
    }

    /**
     * What to release.
     *
     * @param version               the version, {@code MAJOR.MINOR.PATCH}
     * @param versionTracksApiMajor whether the version's major must equal the contract's {@code apiMajor}
     * @param policy                the release policy
     * @param baseline              the accepted baseline
     * @param emittedIr             the IR the build emitted, or the empty document when it declares nothing
     * @param openApi               the OpenAPI document of the build's major, or {@code null} when not generated
     * @param mcpTools              the MCP tool specifications, or {@code null} when not generated
     */
    public record Request(String version, boolean versionTracksApiMajor, ReleasePolicy.Policy policy, Path baseline,
                          byte[] emittedIr, byte[] openApi, byte[] mcpTools) {
    }

    /**
     * A release made.
     *
     * @param directory the release's directory
     * @param version   the version
     * @param apiMajor  the contract's {@code apiMajor}
     * @param changelog the release's changelog section
     */
    public record Outcome(Path directory, ReleaseVersion version, int apiMajor, String changelog) {
    }

    /**
     * The file name of a major's OpenAPI document in a release.
     *
     * @param apiMajor the major
     * @return {@code openapi-v<major>.json}
     */
    public static String openApiFile(int apiMajor) {
        return "openapi-v" + apiMajor + ".json";
    }

    /**
     * Releases the accepted contract to {@code <releases>/<version>/}, then regenerates the aggregate
     * changelog. Nothing is written when the release fails. The release directory is written to a
     * temporary sibling and moved into place in one step.
     *
     * @param releases  the directory of releases
     * @param changelog the aggregate changelog to regenerate
     * @param request   what to release
     * @return the release made
     * @throws ReleaseException if the version is not a release version or is already released, the
     *                          build's IR is not the accepted baseline, a released snapshot fails
     *                          verification, the version or major goes backwards, or the policy fails
     * @throws IOException      if a file cannot be read or written
     */
    public static Outcome release(Path releases, Path changelog, Request request) throws ReleaseException, IOException {
        ReleaseVersion version = version(request.version());
        Path target = releases.resolve(version.toString());
        if (Files.exists(target)) {
            throw new ReleaseException("Version " + version + " is already released at " + target + "."
                    + " Released contracts are immutable: release a new version instead.");
        }
        byte[] accepted = accepted(request.baseline(), request.emittedIr());
        String acceptedJson = new String(accepted, StandardCharsets.UTF_8);
        ContractIr current = parse(acceptedJson, request.baseline().toString());

        List<ReleasePolicy.Release> history = new ArrayList<>(history(releases));
        ReleasePolicy.Release previous = history.isEmpty() ? null : history.get(history.size() - 1);
        if (previous != null && version.compareTo(previous.version()) <= 0) {
            throw new ReleaseException("Version " + version + " is not above the latest release " + previous.version()
                    + " in " + releases + ". Releases are ordered: release a higher version.");
        }
        if (previous != null && current.apiMajor() < previous.ir().apiMajor()) {
            throw new ReleaseException("The contract's apiMajor " + current.apiMajor() + " is below the apiMajor "
                    + previous.ir().apiMajor() + " of the latest release " + previous.version()
                    + ". A release never publishes an older major than the one before it.");
        }
        if (request.versionTracksApiMajor() && version.major() != current.apiMajor()) {
            throw new ReleaseException("Version " + version + " has major " + version.major() + ", but the contract's"
                    + " apiMajor is " + current.apiMajor() + ", and releaseVersionTracksApiMajor requires them to be"
                    + " equal. Release " + current.apiMajor() + ".x.y, or change the API major.");
        }

        ContractIr before = previous != null ? previous.ir()
                : EmptyContract.document(current.apiBasePath(), current.apiMajor());
        List<ContractGate.Difference> differences = compare(before, current);
        history.add(new ReleasePolicy.Release(version, current));
        ReleasePolicy.Result verdict = ReleasePolicy.check(history, differences, request.policy());
        if (!verdict.passed()) {
            ReleasePolicy.Policy policy = request.policy();
            List<String> lines = new ArrayList<>();
            lines.add("Release " + version + " violates the release policy (released deprecated in at least "
                    + policy.minReleases() + " release(s), removed at least " + policy.minMajors()
                    + " major(s) after deprecation, failOnBreaking = " + policy.failOnBreaking() + ") at "
                    + verdict.violations().size() + " element(s):");
            verdict.violations().forEach(v -> lines.add("  " + v.message()));
            throw new ReleaseException(String.join(System.lineSeparator(), lines));
        }

        String section = ReleaseChangelog.render(version, current, previous, differences, verdict.evidence());
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(IR_FILE, accepted);
        if (request.openApi() != null) {
            files.put(openApiFile(current.apiMajor()), request.openApi());
        }
        if (request.mcpTools() != null) {
            files.put(MCP_TOOLS_FILE, request.mcpTools());
        }
        files.put(DIFF_FILE, ContractGate.diffJson(previous != null ? previous.ir().apiMajor() : 0, differences)
                .getBytes(StandardCharsets.UTF_8));
        files.put(CHANGELOG_FILE, section.getBytes(StandardCharsets.UTF_8));
        Map<String, String> digests = new LinkedHashMap<>();
        files.forEach((name, bytes) -> digests.put(name, sha256(bytes)));
        ReleaseManifest manifest = new ReleaseManifest(version.toString(), current.apiMajor(),
                ReleaseManifest.irVersionOf(acceptedJson), previous != null ? previous.version().toString() : null,
                request.policy(), digests);
        files.put(MANIFEST_FILE, manifest.write().getBytes(StandardCharsets.UTF_8));

        Files.createDirectories(releases);
        Path staging = releases.resolve("." + version + ".tmp");
        deleteRecursively(staging);
        Files.createDirectory(staging);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Files.write(staging.resolve(file.getKey()), file.getValue());
        }
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        writeChangelog(releases, changelog);
        return new Outcome(target, version, current.apiMajor(), section);
    }

    /**
     * Every release in {@code releases}, oldest first, each verified: its manifest names this
     * release, every file it records has the recorded digest, and it holds no other file. A
     * directory whose name is not {@code MAJOR.MINOR.PATCH} is not a release and is skipped.
     *
     * @param releases the directory of releases, which may not exist
     * @return the releases, with their IR migrated in memory
     * @throws ReleaseException if a release fails verification
     * @throws IOException      if a file cannot be read
     */
    public static List<ReleasePolicy.Release> history(Path releases) throws ReleaseException, IOException {
        List<ReleasePolicy.Release> result = new ArrayList<>();
        for (Path dir : releaseDirs(releases)) {
            ReleaseVersion version = ReleaseVersion.parse(dir.getFileName().toString());
            ReleaseManifest manifest = verify(dir, version);
            ContractIr ir = parse(Files.readString(dir.resolve(IR_FILE), StandardCharsets.UTF_8),
                    dir.resolve(IR_FILE).toString());
            if (ir.apiMajor() != manifest.apiMajor()) {
                throw new ReleaseException("Release manifest " + dir.resolve(MANIFEST_FILE) + " records apiMajor "
                        + manifest.apiMajor() + ", but its " + IR_FILE + " has apiMajor " + ir.apiMajor() + "."
                        + RESTORE);
            }
            result.add(new ReleasePolicy.Release(version, ir));
        }
        return result;
    }

    /**
     * Verifies every release, and that {@code version} is released with exactly the build's IR,
     * as CI checks the version it tags.
     *
     * @param releases  the directory of releases
     * @param version   the version the build claims to be, {@code MAJOR.MINOR.PATCH}
     * @param emittedIr the IR the build emitted, or the empty document when it declares nothing
     * @throws ReleaseException if a release fails verification, the version is not released, or its
     *                          released IR differs from the build's
     * @throws IOException      if a file cannot be read
     */
    public static void checkReleased(Path releases, String version, byte[] emittedIr)
            throws ReleaseException, IOException {
        ReleaseVersion parsed = version(version);
        history(releases);
        Path ir = releases.resolve(parsed.toString()).resolve(IR_FILE);
        if (!Files.isRegularFile(ir)) {
            throw new ReleaseException("Version " + parsed + " is not released: there is no " + ir + ". Run"
                    + " agenticRelease for it and commit the release, then build the tag.");
        }
        if (!Arrays.equals(Files.readAllBytes(ir), emittedIr)) {
            throw new ReleaseException("The contract the build emitted differs from the released contract " + ir
                    + ". The build does not publish the contract version " + parsed + " released: build the"
                    + " sources that were released, or release a new version.");
        }
    }

    /**
     * The aggregate changelog: every release's section, newest first.
     *
     * @param releases the directory of releases, which may not exist
     * @return the document
     * @throws IOException if a section cannot be read
     */
    public static String aggregateChangelog(Path releases) throws IOException {
        List<Path> dirs = new ArrayList<>(releaseDirs(releases));
        Collections.reverse(dirs);
        List<String> sections = new ArrayList<>();
        for (Path dir : dirs) {
            sections.add(Files.readString(dir.resolve(CHANGELOG_FILE), StandardCharsets.UTF_8));
        }
        return ReleaseChangelog.aggregate(sections);
    }

    /**
     * The lowercase hexadecimal SHA-256 of {@code bytes}.
     *
     * @param bytes the content
     * @return 64 hexadecimal digits
     */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // ---------------------------------------------------------------- steps

    private static ReleaseVersion version(String version) throws ReleaseException {
        try {
            return ReleaseVersion.parse(version);
        } catch (IllegalArgumentException e) {
            throw new ReleaseException(e.getMessage() + ".");
        }
    }

    /** The baseline's bytes, when the build emitted exactly that document. */
    private static byte[] accepted(Path baseline, byte[] emitted) throws ReleaseException, IOException {
        if (!Files.isRegularFile(baseline)) {
            throw new ReleaseException("No contract baseline at " + baseline + ". A release snapshots the accepted"
                    + " contract: run " + ContractGate.ACCEPT_TASK + " first, then release.");
        }
        byte[] accepted = Files.readAllBytes(baseline);
        if (!Arrays.equals(accepted, emitted)) {
            throw new ReleaseException("The contract the build emitted differs from the baseline " + baseline
                    + ". A release snapshots the accepted contract: review the difference, run "
                    + ContractGate.ACCEPT_TASK + ", then release.");
        }
        return accepted;
    }

    private static List<ContractGate.Difference> compare(ContractIr before, ContractIr current)
            throws ReleaseException {
        try {
            return ReleaseComparison.compare(before, current);
        } catch (IllegalArgumentException e) {
            throw new ReleaseException("The release cannot be compared with the previous one: " + e.getMessage());
        }
    }

    private static ContractIr parse(String json, String source) throws ReleaseException {
        try {
            return IrJson.parse(json, source);
        } catch (IrJson.IrReadException e) {
            throw new ReleaseException(e.getMessage());
        }
    }

    /** The release directories, oldest version first. */
    private static List<Path> releaseDirs(Path releases) throws IOException {
        if (!Files.isDirectory(releases)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(releases)) {
            return entries.filter(Files::isDirectory).filter(dir -> ReleaseVersion.matches(dir.getFileName().toString()))
                    .sorted(Comparator.comparing(dir -> ReleaseVersion.parse(dir.getFileName().toString())))
                    .toList();
        }
    }

    private static ReleaseManifest verify(Path dir, ReleaseVersion version) throws ReleaseException, IOException {
        Path manifestFile = dir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            throw new ReleaseException("Release " + dir + " has no " + MANIFEST_FILE + ", so it cannot be verified."
                    + RESTORE);
        }
        ReleaseManifest manifest;
        try {
            manifest = ReleaseManifest.read(Files.readString(manifestFile, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new ReleaseException("Release manifest " + manifestFile + " cannot be read: " + e.getMessage() + "."
                    + RESTORE);
        }
        if (!version.toString().equals(manifest.version())) {
            throw new ReleaseException("Release manifest " + manifestFile + " records version " + manifest.version()
                    + ", not " + version + "." + RESTORE);
        }
        for (String required : List.of(IR_FILE, CHANGELOG_FILE)) {
            if (!manifest.digests().containsKey(required)) {
                throw new ReleaseException("Release manifest " + manifestFile + " records no digest of " + required
                        + "." + RESTORE);
            }
        }
        Set<String> present = new TreeSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.map(file -> file.getFileName().toString()).filter(name -> !name.startsWith(".")).forEach(present::add);
        }
        for (Map.Entry<String, String> entry : manifest.digests().entrySet()) {
            Path file = dir.resolve(entry.getKey());
            if (!Files.isRegularFile(file)) {
                throw new ReleaseException("Released file " + file + " was deleted after release." + RESTORE);
            }
            if (!sha256(Files.readAllBytes(file)).equals(entry.getValue())) {
                throw new ReleaseException("Released file " + file + " was modified after release: its SHA-256 is not"
                        + " the one " + MANIFEST_FILE + " records." + RESTORE);
            }
        }
        present.removeAll(manifest.digests().keySet());
        present.remove(MANIFEST_FILE);
        if (!present.isEmpty()) {
            throw new ReleaseException("Release " + dir + " holds " + present + ", which " + MANIFEST_FILE
                    + " does not record: a file was added after release. Remove it.");
        }
        return manifest;
    }

    private static void writeChangelog(Path releases, Path changelog) throws IOException {
        Path parent = changelog.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path staging = changelog.resolveSibling("." + changelog.getFileName() + ".tmp");
        Files.writeString(staging, aggregateChangelog(releases), StandardCharsets.UTF_8);
        Files.move(staging, changelog, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}

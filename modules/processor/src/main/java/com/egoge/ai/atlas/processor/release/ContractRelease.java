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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * @param artifacts             the class output's snapshotted artifacts ({@code
     *                              ContractResources.snapshotted}), keyed by snapshot file name, such as
     *                              {@code openapi-v2.json} or {@code mcp-tools.json}
     * @param contractResourcesJson the class output's contract-resources manifest, verbatim
     */
    public record Request(String version, boolean versionTracksApiMajor, ReleasePolicy.Policy policy, Path baseline,
                          byte[] emittedIr, Map<String, byte[]> artifacts, String contractResourcesJson) {
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

        List<ReleasePolicy.Release> history = new ArrayList<>(ReleaseHistory.history(releases));
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
        // Transitional: published = true until D4 wires the tag-proved publication verdict in (D10.1).
        history.add(new ReleasePolicy.Release(version, current, true));
        ReleasePolicy.Result verdict = ReleasePolicy.check(history, differences, request.policy());
        if (!verdict.passed()) {
            ReleasePolicy.Policy policy = request.policy();
            List<String> lines = new ArrayList<>();
            lines.add("Release " + version + " violates the release policy (released deprecated in at least "
                    + policy.minDeprecatedReleases() + " release(s), removed at least " + policy.minApiMajorAdvance()
                    + " major(s) after deprecation, failOnBreaking = " + policy.failOnBreaking() + ") at "
                    + verdict.violations().size() + " element(s):");
            verdict.violations().forEach(v -> lines.add("  " + v.message()));
            throw new ReleaseException(String.join(System.lineSeparator(), lines));
        }

        String section = ReleaseChangelog.render(version, current, previous, differences, verdict.evidence());
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(IR_FILE, accepted);
        files.putAll(request.artifacts());
        files.put(DIFF_FILE, ContractGate.diffJson(previous != null ? previous.ir().apiMajor() : 0, differences)
                .getBytes(StandardCharsets.UTF_8));
        files.put(CHANGELOG_FILE, section.getBytes(StandardCharsets.UTF_8));
        Map<String, String> digests = new LinkedHashMap<>();
        files.forEach((name, bytes) -> digests.put(name, sha256(bytes)));
        ReleaseManifest manifest = new ReleaseManifest(version.toString(), current.apiMajor(),
                ReleaseManifest.irVersionOf(acceptedJson), previous != null ? previous.version().toString() : null,
                request.policy(), digests, ReleaseManifest.contractResourcesOf(request.contractResourcesJson()));
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
        ReleaseHistory.history(releases);
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

    /**
     * The baseline's bytes, when the build's emitted document is canonically equal to the baseline:
     * both parse (after migrating any older {@code irVersion} in memory) to the same {@link ContractIr}.
     * The baseline's own bytes are always returned, never the emitted ones, so the snapshot keeps
     * exactly what was accepted.
     */
    private static byte[] accepted(Path baseline, byte[] emitted) throws ReleaseException, IOException {
        if (!Files.isRegularFile(baseline)) {
            throw new ReleaseException("No contract baseline at " + baseline + ". A release snapshots the accepted"
                    + " contract: run " + ContractGate.ACCEPT_TASK + " first, then release.");
        }
        byte[] accepted = Files.readAllBytes(baseline);
        ContractIr acceptedIr = parse(new String(accepted, StandardCharsets.UTF_8), baseline.toString());
        ContractIr emittedIr = parse(new String(emitted, StandardCharsets.UTF_8), "the build's emitted contract");
        if (!acceptedIr.equals(emittedIr)) {
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

    private static void writeChangelog(Path releases, Path changelog) throws IOException {
        Path parent = changelog.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path staging = changelog.resolveSibling("." + changelog.getFileName() + ".tmp");
        Files.writeString(staging, ReleaseHistory.aggregateChangelog(releases), StandardCharsets.UTF_8);
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

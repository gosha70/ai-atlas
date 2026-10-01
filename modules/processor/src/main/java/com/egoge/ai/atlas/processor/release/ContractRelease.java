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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Releases the accepted contract as an immutable snapshot, {@code <releases>/<version>/}, and
 * verifies a build's contract against one already released. The engine behind the Gradle plugin's
 * {@code agenticRelease} and {@code agenticReleaseVerify}; it reads and writes only the files it is
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
     * @param tagName               the resolved git tag name this release is proved by, such as
     *                              {@code "v1.4.0"} (F1), recorded verbatim in {@code release.json}
     * @param published             the versions, among the releases already on disk, that a proved
     *                              git tag backs (F1, D10.1): the plugin's {@code PublishedHistory}
     *                              verdict, as
     *                              only the plugin can read git. Only a published release earns
     *                              deprecation credit; the newest release on disk missing from this
     *                              set is pending, and this release refuses to be made until it is
     *                              tagged.
     */
    public record Request(String version, boolean versionTracksApiMajor, ReleasePolicy.Policy policy, Path baseline,
                          byte[] emittedIr, Map<String, byte[]> artifacts, String contractResourcesJson,
                          String tagName, Set<ReleaseVersion> published) {
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
        if (request.tagName() == null || request.tagName().isBlank()) {
            throw new ReleaseException("No tag name was resolved for version " + version + ". Set"
                    + " agentic { release { tagName } }, which defaults to v{version}.");
        }
        Path target = releases.resolve(version.toString());
        if (Files.exists(target)) {
            throw new ReleaseException("Version " + version + " is already released at " + target + "."
                    + " Released contracts are immutable: release a new version instead.");
        }
        byte[] accepted = accepted(request.baseline(), request.emittedIr());
        String acceptedJson = new String(accepted, StandardCharsets.UTF_8);
        ContractIr current = parse(acceptedJson, request.baseline().toString());

        List<ReleasePolicy.Release> history = published(ReleaseHistory.history(releases), request.published());
        if (!history.isEmpty() && !history.get(history.size() - 1).published()) {
            ReleasePolicy.Release pending = history.get(history.size() - 1);
            throw new ReleaseException("Release " + pending.version() + " is pending: tag its commit as "
                    + readTagName(releases, pending.version()) + " and push the tag, then release.");
        }
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
        // This release is not published yet: it has no tag until it is committed and tagged after
        // this call returns, so it earns no credit of its own (irrelevant here: only earlier
        // releases in `history` are consulted for evidence).
        history.add(new ReleasePolicy.Release(version, current, false));
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
        // The build's own IR among the artifacts was only checked: the snapshot keeps the baseline's bytes
        request.artifacts().forEach((name, bytes) -> files.putIfAbsent(name, bytes));
        files.put(DIFF_FILE, ContractGate.diffJson(previous != null ? previous.ir().apiMajor() : 0, differences)
                .getBytes(StandardCharsets.UTF_8));
        files.put(CHANGELOG_FILE, section.getBytes(StandardCharsets.UTF_8));
        Map<String, String> digests = new LinkedHashMap<>();
        files.forEach((name, bytes) -> digests.put(name, sha256(bytes)));
        ReleaseManifest manifest = new ReleaseManifest(version.toString(), current.apiMajor(),
                ReleaseManifest.irVersionOf(acceptedJson), previous != null ? previous.version().toString() : null,
                request.tagName(), request.policy(), digests,
                ReleaseManifest.contractResourcesOf(request.contractResourcesJson()));
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
     * Verifies the release history's internal consistency, and that {@code version}'s release is
     * exactly what the build produced (E2, D8.2, plan §3.5 step 6, OQ-4): the IR canonically equal,
     * every snapshotted artifact byte for byte, the same set of snapshotted files present in both
     * (naming an artifact the build produces the release does not, or the reverse), and the
     * released {@code contractResources.configuration} structurally equal to the build's effective
     * configuration. Reads no git; {@code agenticReleaseVerify} proves the tag and {@code HEAD}
     * separately, in the plugin.
     *
     * @param releases              the directory of releases
     * @param changelog             the aggregate changelog, checked by {@link
     *                              ReleaseHistory#checkConsistency}
     * @param version               the version the build claims to be, {@code MAJOR.MINOR.PATCH}
     * @param emittedIr             the IR the build emitted, or the empty document when it declares
     *                              nothing
     * @param artifacts             the build's snapshotted artifacts ({@code
     *                              ContractResources.snapshotted}), keyed by snapshot file name, the
     *                              IR included under {@value #IR_FILE}
     * @param contractResourcesJson the build's class output's contract-resources manifest, verbatim
     * @throws ReleaseException if the history is inconsistent, the version is not released, or the
     *                          build differs from its release, each naming the difference
     * @throws IOException      if a file cannot be read
     */
    public static void verifyBuild(Path releases, Path changelog, String version, byte[] emittedIr,
                                   Map<String, byte[]> artifacts, String contractResourcesJson)
            throws ReleaseException, IOException {
        ReleaseVersion parsed = version(version);
        ReleaseHistory.checkConsistency(releases, changelog);
        Path dir = releases.resolve(parsed.toString());
        if (!Files.isDirectory(dir)) {
            throw new ReleaseException("Version " + parsed + " is not released: there is no " + dir + ". Run"
                    + " agenticRelease for it and commit the release, then build the tag.");
        }
        Path ir = dir.resolve(IR_FILE);
        ContractIr released = parse(Files.readString(ir, StandardCharsets.UTF_8), ir.toString());
        ContractIr built = parse(new String(emittedIr, StandardCharsets.UTF_8), "the build's emitted contract");
        if (!released.equals(built)) {
            throw new ReleaseException("The contract the build emitted differs from the released contract " + ir
                    + ". The build does not publish the contract version " + parsed + " released: build the"
                    + " sources that were released, or release a new version.");
        }
        Set<String> releasedArtifacts = snapshottedFiles(dir);
        Set<String> builtArtifacts = new LinkedHashSet<>(artifacts.keySet());
        builtArtifacts.remove(IR_FILE);
        Set<String> extra = new LinkedHashSet<>(builtArtifacts);
        extra.removeAll(releasedArtifacts);
        if (!extra.isEmpty()) {
            throw new ReleaseException("The build produces " + extra + ", which release " + parsed + " in " + dir
                    + " does not: the build's effective configuration differs from the one " + parsed + " was"
                    + " released with.");
        }
        Set<String> missing = new LinkedHashSet<>(releasedArtifacts);
        missing.removeAll(builtArtifacts);
        if (!missing.isEmpty()) {
            throw new ReleaseException("Release " + parsed + " in " + dir + " holds " + missing + ", which the build"
                    + " does not produce: the build's effective configuration differs from the one " + parsed
                    + " was released with.");
        }
        for (String name : releasedArtifacts) {
            byte[] releasedBytes = Files.readAllBytes(dir.resolve(name));
            if (!Arrays.equals(releasedBytes, artifacts.get(name))) {
                throw new ReleaseException("The build's " + name + " differs from release " + parsed + "'s " + dir
                        + "/" + name + ": the build no longer produces byte-identical output for the sources"
                        + " released.");
            }
        }
        ReleaseManifest manifest;
        try {
            manifest = ReleaseManifest.read(Files.readString(dir.resolve(MANIFEST_FILE), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new ReleaseException(dir.resolve(MANIFEST_FILE) + " cannot be read: " + e.getMessage());
        }
        Object releasedConfiguration = manifest.contractResources().get("configuration");
        Object builtConfiguration;
        try {
            builtConfiguration = ReleaseManifest.contractResourcesOf(contractResourcesJson).get("configuration");
        } catch (IllegalArgumentException e) {
            throw new ReleaseException("The build's contract-resources manifest cannot be read: " + e.getMessage());
        }
        if (!Objects.equals(releasedConfiguration, builtConfiguration)) {
            throw new ReleaseException("The build's effective ai.atlas.* configuration " + builtConfiguration
                    + " differs from release " + parsed + "'s recorded configuration " + releasedConfiguration
                    + " in " + dir.resolve(MANIFEST_FILE) + ".");
        }
    }

    /** The release directory's own files that a release snapshot keeps as artifacts, by file name. */
    private static Set<String> snapshottedFiles(Path dir) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (ReleaseHistory.isReleaseFile(name) && !name.equals(IR_FILE) && !name.equals(MANIFEST_FILE)
                        && !name.equals(DIFF_FILE) && !name.equals(CHANGELOG_FILE)) {
                    result.add(name);
                }
            }
        }
        return result;
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

    /** {@code history}, with each release's {@code published} flag set from {@code published} (F1, D10.1). */
    private static List<ReleasePolicy.Release> published(List<ReleasePolicy.Release> history,
                                                          Set<ReleaseVersion> published) {
        List<ReleasePolicy.Release> result = new ArrayList<>(history.size());
        for (ReleasePolicy.Release release : history) {
            result.add(new ReleasePolicy.Release(release.version(), release.ir(), published.contains(release.version())));
        }
        return result;
    }

    /** The tag name {@code version}'s own {@value #MANIFEST_FILE} recorded (F1, OQ-8). */
    private static String readTagName(Path releases, ReleaseVersion version) throws ReleaseException, IOException {
        Path manifestFile = releases.resolve(version.toString()).resolve(MANIFEST_FILE);
        try {
            return ReleaseManifest.read(Files.readString(manifestFile, StandardCharsets.UTF_8)).tagName();
        } catch (IllegalArgumentException e) {
            throw new ReleaseException(manifestFile + " cannot be read: " + e.getMessage());
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

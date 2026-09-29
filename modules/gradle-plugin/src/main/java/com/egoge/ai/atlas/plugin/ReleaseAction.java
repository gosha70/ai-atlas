/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractProjection;
import com.egoge.ai.atlas.processor.contract.EmptyContract;
import com.egoge.ai.atlas.processor.contract.IrJson;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import java.io.File;
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
import java.util.Objects;
import java.util.stream.Stream;

/**
 * SPIKE (epic #23, Phase 5): releases the accepted contract. Loaded from the project's
 * {@code annotationProcessor} classpath in an isolated class loader, like {@link AcceptAction}, so
 * the comparison, its classifications and the IR reader are those of the processor that compiled.
 *
 * <p>A release is compared with the previous one by {@link ContractGate#compare}. The gate compares
 * two documents at one major, the baseline's; a release instead compares what each release
 * <em>published</em>: the previous release's projection at its major with this release's projection
 * at its major. {@link #published} reduces each document to that projection, as a document whose
 * elements are active at every major, so the gate's own comparison classifies the difference
 * between the two surfaces. The classification is never re-implemented here.
 */
public abstract class ReleaseAction implements WorkAction<ReleaseAction.Parameters> {

    /** The snapshot's Contract IR, byte-identical to the accepted baseline. */
    static final String IR_FILE = "api.ir.json";
    /** The comparison with the previous release, in the gate's {@code contract-diff.json} form. */
    static final String DIFF_FILE = "contract-diff.json";
    /** The release's changelog section. */
    static final String CHANGELOG_FILE = "CHANGELOG.md";
    /** The release manifest: version, majors, previous release and a digest of every other file. */
    static final String MANIFEST_FILE = "release.json";
    /** The MCP tool specifications. */
    static final String MCP_TOOLS_FILE = "mcp-tools.json";

    private static final Logger LOGGER = Logging.getLogger(ReleaseAction.class);
    private static final String PREFIX = "[ai-atlas] ";

    /** The release step's inputs. */
    public interface Parameters extends WorkParameters {

        /** The IR the gated compilation emitted. */
        RegularFileProperty getEmittedIr();

        /** The class output holding the OpenAPI documents. */
        DirectoryProperty getClassesDir();

        /** The committed baseline. */
        RegularFileProperty getBaseline();

        /** The directory of released versions. */
        DirectoryProperty getReleasesDir();

        /** The version to release. */
        Property<String> getReleaseVersion();

        /** Released versions an element must have been published in while deprecated. */
        Property<Integer> getMinDeprecatedReleases();

        /** Majors between deprecation and removal. */
        Property<Integer> getMinDeprecatedMajors();

        /** The MCP tool specifications, or unset when not generated. */
        RegularFileProperty getMcpTools();
    }

    /** A released version, read back and verified against its manifest. */
    record Release(ReleaseVersion version, ContractIr ir) {
    }

    /** What the release history says about a removed element's deprecation. */
    record Evidence(int deprecatedReleases, int deprecatedSince, String firstDeprecatedRelease) {
    }

    @Override
    public void execute() {
        Parameters parameters = getParameters();
        ReleaseVersion version = ReleaseVersion.parse(parameters.getReleaseVersion().get());
        Path releases = parameters.getReleasesDir().get().getAsFile().toPath();
        Path target = releases.resolve(version.toString());
        if (Files.exists(target)) {
            throw new GradleException(PREFIX + "Version " + version + " is already released at " + target
                    + ". Released contracts are immutable: release a new version instead.");
        }
        try {
            byte[] accepted = acceptedIr(parameters);
            ContractIr current = IrJson.parse(new String(accepted, StandardCharsets.UTF_8), "the accepted contract");
            List<Release> history = history(releases);
            Release previous = history.isEmpty() ? null : history.get(history.size() - 1);
            if (previous != null && version.compareTo(previous.version()) <= 0) {
                throw new GradleException(PREFIX + "Version " + version + " is not above the latest release "
                        + previous.version() + " in " + releases + ". Releases are ordered: release a higher version.");
            }
            if (previous != null && current.apiMajor() < previous.ir().apiMajor()) {
                throw new GradleException(PREFIX + "The contract's apiMajor " + current.apiMajor()
                        + " is below the apiMajor " + previous.ir().apiMajor() + " of the latest release "
                        + previous.version() + ".");
            }
            ContractIr before = previous == null
                    ? EmptyContract.document(current.apiBasePath(), current.apiMajor())
                    : published(previous.ir(), current.apiMajor());
            List<ContractGate.Difference> differences = ContractGate.compare(before, published(current,
                    current.apiMajor()));

            Map<String, Evidence> evidence = new LinkedHashMap<>();
            List<String> violations = new ArrayList<>();
            checkRemovals(parameters, current, history, differences, evidence, violations);
            if (!violations.isEmpty()) {
                throw new GradleException(PREFIX + "Release " + version + " removes " + violations.size()
                        + " previously published element(s) without satisfying the deprecation policy (deprecated in at"
                        + " least " + parameters.getMinDeprecatedReleases().get() + " release(s), and at least "
                        + parameters.getMinDeprecatedMajors().get() + " major(s) between deprecation and removal):"
                        + System.lineSeparator() + String.join(System.lineSeparator(), violations));
            }

            String changelog = ReleaseChangelog.render(version, current, previous, differences, evidence);
            write(parameters, releases, target, version, current, previous, accepted, differences, changelog);
            LOGGER.lifecycle(PREFIX + "Released contract " + version + " (API major " + current.apiMajor() + ") to "
                    + target + System.lineSeparator() + changelog);
        } catch (IOException | IrJson.IrReadException e) {
            throw new GradleException(PREFIX + "agenticRelease failed: " + e.getMessage(), e);
        }
    }

    /**
     * The baseline's bytes, when the gated compilation emitted exactly that document: a release
     * snapshots what was accepted, and what was accepted is what the build generated from.
     */
    private static byte[] acceptedIr(Parameters parameters) throws IOException {
        Path baseline = parameters.getBaseline().get().getAsFile().toPath();
        if (!Files.isRegularFile(baseline)) {
            throw new GradleException(PREFIX + "No contract baseline at " + baseline + ". A release snapshots the"
                    + " accepted contract: run " + ContractGate.ACCEPT_TASK + " first.");
        }
        byte[] accepted = Files.readAllBytes(baseline);
        byte[] emitted = Files.readAllBytes(parameters.getEmittedIr().get().getAsFile().toPath());
        if (!Arrays.equals(accepted, emitted)) {
            throw new GradleException(PREFIX + "The contract the build emitted differs from the baseline " + baseline
                    + ". A release snapshots the accepted contract: review the difference and run "
                    + ContractGate.ACCEPT_TASK + ", then release.");
        }
        return accepted;
    }

    /** Every released version, oldest first, each verified against the digests in its manifest. */
    private static List<Release> history(Path releases) throws IOException, IrJson.IrReadException {
        if (!Files.isDirectory(releases)) {
            return List.of();
        }
        List<Path> dirs;
        try (Stream<Path> entries = Files.list(releases)) {
            dirs = entries.filter(Files::isDirectory)
                    .filter(dir -> ReleaseVersion.matches(dir.getFileName().toString())).toList();
        }
        List<Release> result = new ArrayList<>();
        for (Path dir : dirs) {
            verify(dir);
            result.add(new Release(ReleaseVersion.parse(dir.getFileName().toString()),
                    IrJson.read(dir.resolve(IR_FILE))));
        }
        result.sort(Comparator.comparing(Release::version));
        return result;
    }

    /** Fails when a released file no longer has the digest its manifest recorded. */
    private static void verify(Path dir) throws IOException, IrJson.IrReadException {
        Path manifest = dir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifest)) {
            throw new GradleException(PREFIX + "Release " + dir + " has no " + MANIFEST_FILE
                    + "; it is incomplete or was not written by agenticRelease.");
        }
        Map<String, String> digests = ReleaseManifest.digests(Files.readString(manifest, StandardCharsets.UTF_8));
        if (!digests.containsKey(IR_FILE)) {
            throw new GradleException(PREFIX + "Release manifest " + manifest + " records no digest of " + IR_FILE
                    + "; it was modified after release. Restore it from version control.");
        }
        for (Map.Entry<String, String> entry : digests.entrySet()) {
            Path file = dir.resolve(entry.getKey());
            if (!Files.isRegularFile(file) || !sha256(Files.readAllBytes(file)).equals(entry.getValue())) {
                throw new GradleException(PREFIX + "Released file " + file + " was modified or deleted after release."
                        + " Released contracts are immutable: restore it from version control.");
            }
        }
    }

    /**
     * What {@code ir} published at its own major, as a document at {@code major} whose elements are
     * active at every major: those active at the document's major, each keeping only the deprecation
     * in effect there. The gate's comparison of two such documents compares the two published
     * surfaces, whatever each one's major.
     */
    static ContractIr published(ContractIr ir, int major) {
        int own = ir.apiMajor();
        List<ContractIr.Entity> entities = new ArrayList<>();
        for (ContractIr.Entity e : ir.entities()) {
            List<ContractIr.Field> fields = new ArrayList<>();
            for (ContractIr.Field f : e.fields()) {
                ContractIr.FieldLifecycle life = f.lifecycle();
                if (ContractProjection.isActive(life, own)) {
                    boolean deprecated = deprecatedAt(life.deprecatedSinceVersion(), own);
                    fields.add(new ContractIr.Field(f.name(), f.displayName(), f.javaType(), f.collectionKind(),
                            f.elementType(), f.typeHint(), f.reference(), f.enumType(), f.allowedValues(), f.openEnum(),
                            f.sensitive(), f.checkCircularReference(), f.description(), f.constraints(), f.channels(),
                            new ContractIr.FieldLifecycle(1, Integer.MAX_VALUE,
                                    deprecated ? life.deprecatedSinceVersion() : 0,
                                    deprecated ? life.deprecatedMessage() : null)));
                }
            }
            entities.add(new ContractIr.Entity(e.className(), e.dtoName(), e.dtoPackage(), e.displayName(),
                    e.description(), e.includeTypeInfo(), fields));
        }
        List<ContractIr.Operation> operations = new ArrayList<>();
        for (ContractIr.Operation op : ir.operations()) {
            ContractIr.OperationLifecycle life = op.lifecycle();
            if (ContractProjection.isActive(life, own)) {
                boolean deprecated = deprecatedAt(life.apiDeprecatedSince(), own);
                operations.add(new ContractIr.Operation(op.service(), op.method(), op.toolName(), op.channels(),
                        op.description(), op.rest(), op.parameters(), op.returns(), op.hints(),
                        new ContractIr.OperationLifecycle(1, Integer.MAX_VALUE,
                                deprecated ? life.apiDeprecatedSince() : 0,
                                deprecated ? life.apiReplacement() : null)));
            }
        }
        return new ContractIr(ir.irVersion(), ir.apiBasePath(), major, entities, operations);
    }

    private static boolean deprecatedAt(int deprecatedSince, int major) {
        return deprecatedSince > 0 && deprecatedSince <= major;
    }

    /**
     * Checks every field and operation the comparison reports removed against the policy. An
     * entity's removal is the removal of its fields, so it is checked through them.
     */
    private static void checkRemovals(Parameters parameters, ContractIr current, List<Release> history,
                                      List<ContractGate.Difference> differences, Map<String, Evidence> evidence,
                                      List<String> violations) {
        int minReleases = parameters.getMinDeprecatedReleases().get();
        int minMajors = parameters.getMinDeprecatedMajors().get();
        for (ContractGate.Difference d : differences) {
            boolean field = d.path().startsWith(ContractGate.FIELD_PATH);
            if (!d.breaking() || !"removed".equals(d.change())
                    || !(field || d.path().startsWith(ContractGate.OPERATION_PATH))) {
                continue;
            }
            Evidence found = evidence(d.path(), field, history);
            evidence.put(d.path(), found);
            int majors = found.deprecatedSince() > 0 ? current.apiMajor() - found.deprecatedSince() : -1;
            boolean satisfied = found.deprecatedReleases() >= minReleases
                    && (minMajors == 0 || (found.deprecatedSince() > 0 && majors >= minMajors));
            if (!satisfied) {
                violations.add("  " + d.path() + ": " + (found.deprecatedSince() == 0
                        ? "never released as deprecated"
                        : "deprecated since major " + found.deprecatedSince() + " in " + found.deprecatedReleases()
                        + " release(s), removed in major " + current.apiMajor())
                        + ". Restore it and declare " + (field ? "@AgenticField(deprecatedSinceVersion = N)"
                        : "@AgenticExposed(apiDeprecatedSince = N)") + " in a release before removing it, or"
                        + " relax agentic { releaseMinDeprecatedReleases / releaseMinDeprecatedMajors }.");
            }
        }
    }

    /** Releases that published the element deprecated, and the earliest deprecation major declared in any. */
    private static Evidence evidence(String path, boolean field, List<Release> history) {
        String target = path.substring((field ? ContractGate.FIELD_PATH : ContractGate.OPERATION_PATH).length());
        int count = 0;
        int since = 0;
        String first = null;
        for (Release release : history) {
            int major = release.ir().apiMajor();
            int deprecatedSince;
            boolean active;
            if (field) {
                ContractIr.Field f = findField(release.ir(), target);
                if (f == null) {
                    continue;
                }
                deprecatedSince = f.lifecycle().deprecatedSinceVersion();
                active = ContractProjection.isActive(f.lifecycle(), major);
            } else {
                ContractIr.Operation op = release.ir().operations().stream()
                        .filter(o -> o.id().equals(target)).findFirst().orElse(null);
                if (op == null) {
                    continue;
                }
                deprecatedSince = op.lifecycle().apiDeprecatedSince();
                active = ContractProjection.isActive(op.lifecycle(), major);
            }
            if (deprecatedSince > 0) {
                since = since == 0 ? deprecatedSince : Math.min(since, deprecatedSince);
            }
            if (active && deprecatedAt(deprecatedSince, major)) {
                count++;
                first = first == null ? release.version().toString() : first;
            }
        }
        return new Evidence(count, since, first);
    }

    private static ContractIr.Field findField(ContractIr ir, String target) {
        int hash = target.indexOf('#');
        String className = target.substring(0, hash);
        String name = target.substring(hash + 1);
        return ir.entities().stream().filter(e -> e.className().equals(className))
                .flatMap(e -> e.fields().stream()).filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    /** Writes every file to a temporary sibling directory, then moves it into place in one step. */
    private static void write(Parameters parameters, Path releases, Path target, ReleaseVersion version,
                              ContractIr current, Release previous, byte[] accepted,
                              List<ContractGate.Difference> differences, String changelog) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(IR_FILE, accepted);
        String openApi = AgenticRelease.OPENAPI_PATH.formatted(current.apiMajor());
        File openApiFile = new File(parameters.getClassesDir().get().getAsFile(), openApi);
        if (openApiFile.isFile()) {
            files.put(openApiFile.getName(), Files.readAllBytes(openApiFile.toPath()));
        }
        if (parameters.getMcpTools().isPresent()) {
            files.put(MCP_TOOLS_FILE, Files.readAllBytes(parameters.getMcpTools().get().getAsFile().toPath()));
        }
        int previousMajor = previous != null ? previous.ir().apiMajor() : 0;
        files.put(DIFF_FILE, ContractGate.diffJson(previousMajor, differences).getBytes(StandardCharsets.UTF_8));
        files.put(CHANGELOG_FILE, changelog.getBytes(StandardCharsets.UTF_8));
        Map<String, String> digests = new LinkedHashMap<>();
        files.forEach((name, bytes) -> digests.put(name, sha256(bytes)));
        files.put(MANIFEST_FILE, ReleaseManifest.write(version.toString(), current, previous != null
                        ? previous.version().toString() : null, parameters.getMinDeprecatedReleases().get(),
                parameters.getMinDeprecatedMajors().get(), digests).getBytes(StandardCharsets.UTF_8));

        Files.createDirectories(releases);
        Path staging = releases.resolve("." + version + ".tmp");
        if (Files.exists(staging)) {
            try (Stream<Path> stale = Files.list(staging)) {
                for (Path file : stale.toList()) {
                    Files.delete(file);
                }
            }
            Files.delete(staging);
        }
        Files.createDirectory(staging);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Files.write(staging.resolve(file.getKey()), file.getValue());
        }
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(bytes)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

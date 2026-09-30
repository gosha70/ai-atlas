/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractResources;
import com.egoge.ai.atlas.processor.release.ContractRelease;
import org.gradle.api.GradleException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Validates a compilation's class output against its {@link ContractResources.Manifest}, for the
 * release path only: {@code agenticRelease}'s worker calls {@link #validate}, never
 * {@code compileJava.doLast}'s writer or the processor. This method only reads: it never creates,
 * deletes or otherwise modifies anything under {@code classesDirs}. A stale, missing, mismatched or
 * unlisted reserved resource is a release failure that names the specific path, never a repair —
 * the compile/accept path ({@code compileJava.doLast}'s {@link EmptyContractResourcesAction} and
 * the processor itself, through C2) is the only code allowed to write or regenerate a compilation's
 * reserved resources.
 *
 * <p>Runs inside the release worker's isolated class loader, so it may call processor classes, such
 * as {@link ContractResources} and {@link ContractRelease#sha256}, from the {@code
 * annotationProcessor} classpath the worker was given.
 */
final class ClassOutputResources {

    private static final String PREFIX = "[ai-atlas] ";
    private static final String CONTRACT_DECLARED = "declared";

    private ClassOutputResources() {
    }

    /**
     * A validated class output: the manifest's raw JSON text, verbatim, and the bytes of the
     * artifacts a release snapshot keeps ({@link ContractResources#snapshotted}), keyed by the
     * short (class-output basename) form a release writes them under, such as {@code
     * openapi-v2.json} or {@code mcp-tools.json}.
     *
     * @param contractResourcesJson the manifest's raw JSON text
     * @param artifacts             the snapshotted artifacts' bytes, keyed by snapshot file name
     */
    record Result(String contractResourcesJson, Map<String, byte[]> artifacts) {
    }

    /**
     * Reads the class output's {@link ContractResources#MANIFEST_PATH} and verifies every reserved
     * resource against it. Never writes, deletes or modifies anything in {@code classesDirs}.
     *
     * @param classesDirs the main compilation's class output(s), as {@code agenticRelease} sees them
     * @return the manifest's text and the snapshotted artifacts' bytes
     * @throws GradleException if the manifest is missing or malformed, a listed artifact is missing
     *                         or does not match its recorded digest, a reserved file is present but
     *                         not listed, or a required artifact is not listed (a processor bug)
     * @throws IOException     if a reserved file cannot be read
     */
    static Result validate(Collection<File> classesDirs) throws IOException {
        File classOutput = classOutput(classesDirs);
        File manifestFile = new File(classOutput, ContractResources.MANIFEST_PATH);
        if (!manifestFile.isFile()) {
            throw new GradleException(PREFIX + "The release found no " + manifestFile + ". Run compileJava (or"
                    + " atlasAccept) to produce the contract-resources manifest before releasing.");
        }
        String json = Files.readString(manifestFile.toPath(), StandardCharsets.UTF_8);
        ContractResources.Manifest manifest;
        try {
            manifest = ContractResources.Manifest.read(json);
        } catch (IllegalArgumentException e) {
            throw new GradleException(PREFIX + manifestFile + " is not a valid contract-resources manifest: "
                    + e.getMessage());
        }

        checkListedArtifacts(classOutput, manifestFile, manifest);
        checkNoUnlistedReservedFiles(classOutput, manifestFile, manifest);
        checkRequiredArtifactsListed(manifestFile, manifest);

        Map<String, byte[]> artifacts = new LinkedHashMap<>();
        for (String path : ContractResources.snapshotted(manifest)) {
            artifacts.put(shortName(path), Files.readAllBytes(new File(classOutput, path).toPath()));
        }
        return new Result(json, artifacts);
    }

    /** Every artifact the manifest lists must exist in the class output with a matching digest. */
    private static void checkListedArtifacts(File classOutput, File manifestFile, ContractResources.Manifest manifest)
            throws IOException {
        for (Map.Entry<String, String> artifact : manifest.artifacts().entrySet()) {
            String path = artifact.getKey();
            File file = new File(classOutput, path);
            if (!file.isFile()) {
                throw new GradleException(PREFIX + "The reserved artifact " + path + ", listed in " + manifestFile
                        + ", is missing from " + classOutput + ".");
            }
            String digest = ContractRelease.sha256(Files.readAllBytes(file.toPath()));
            if (!digest.equals(artifact.getValue())) {
                throw new GradleException(PREFIX + "The reserved artifact " + path + " in " + classOutput
                        + " does not match the digest recorded in " + manifestFile + ": it was modified after"
                        + " compileJava ran.");
            }
        }
    }

    /**
     * Every reserved file present in the class output must be listed in the manifest, except the
     * manifest itself: it is written after its own {@code artifacts} are captured, so it never
     * lists itself (a fixed property of {@code writeContractResourcesManifest} and {@link
     * EmptyContractResourcesAction}, not something a release can second-guess).
     */
    private static void checkNoUnlistedReservedFiles(File classOutput, File manifestFile,
                                                      ContractResources.Manifest manifest) throws IOException {
        for (String path : reservedPaths(classOutput)) {
            if (!path.equals(ContractResources.MANIFEST_PATH) && !manifest.artifacts().containsKey(path)) {
                throw new GradleException(PREFIX + "The reserved file " + path + " is present in " + classOutput
                        + " but not listed in " + manifestFile + ". Run clean and rebuild before releasing.");
            }
        }
    }

    /** Every artifact {@link ContractResources#required} must be listed, or the processor has a bug. */
    private static void checkRequiredArtifactsListed(File manifestFile, ContractResources.Manifest manifest) {
        boolean nonEmptyIr = CONTRACT_DECLARED.equals(manifest.contract());
        for (String required : ContractResources.required(manifest, nonEmptyIr)) {
            if (!manifest.artifacts().containsKey(required)) {
                throw new GradleException(PREFIX + "The manifest " + manifestFile + " does not list the required"
                        + " artifact " + required + "; this is a processor bug.");
            }
        }
    }

    /** The class output to validate: the declaring one, or the sole {@code compileJava} output. */
    private static File classOutput(Collection<File> classesDirs) {
        File declaring = ContractDeclarations.declaringOutput(classesDirs);
        if (declaring != null) {
            return declaring;
        }
        if (classesDirs.size() != 1) {
            throw new GradleException(PREFIX + "Expected exactly one class output when no compilation declares an"
                    + " ai-atlas contract, got " + classesDirs + ".");
        }
        return classesDirs.iterator().next();
    }

    /** The reserved, class-output-relative paths present, forward-slashed. */
    private static Set<String> reservedPaths(File classOutput) throws IOException {
        Set<String> reserved = new LinkedHashSet<>();
        if (!classOutput.isDirectory()) {
            return reserved;
        }
        Path root = classOutput.toPath();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace(File.separatorChar, '/'))
                    .filter(ContractResources::isReserved)
                    .forEach(reserved::add);
        }
        return reserved;
    }

    /** The short (basename) form a release snapshot keeps a class-output-relative path under. */
    private static String shortName(String classOutputRelativePath) {
        int slash = classOutputRelativePath.lastIndexOf('/');
        return slash < 0 ? classOutputRelativePath : classOutputRelativePath.substring(slash + 1);
    }
}

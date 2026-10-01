/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractResources;
import com.egoge.ai.atlas.processor.contract.EffectiveOptions;
import com.egoge.ai.atlas.processor.contract.EmptyContract;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.MapProperty;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Writes the empty contract (FR-008) into a compilation's class output, submitted from {@code
 * compileJava.doLast} only after the caller has confirmed, through {@link ContractDeclarations},
 * that the class output declares no {@code @AgenticEntity} or {@code @AgenticExposed} at all: that
 * confirmation, not anything this action does, is what authorizes overwriting a stale {@value
 * ContractIr#RESOURCE_PATH} an earlier, declaring compilation may have left behind (FR-016).
 *
 * <p>It writes exactly two files: a freshly built canonical {@link EmptyContract#json} at {@value
 * ContractIr#RESOURCE_PATH}, and the {@code "empty"} {@link ContractResources.Manifest} at {@value
 * ContractResources#MANIFEST_PATH}, whose only artifact is that IR's digest. Any other reserved
 * path already present (a leftover {@code openapi-v1.json} or {@code mcp-tools.json}, for example)
 * is left untouched, for the release workflow to reject by name, naming {@code clean}.
 *
 * <p>Loaded from the project's {@code annotationProcessor} classpath in an isolated class loader,
 * so the empty IR and manifest shape are those of the processor version that compiled.
 */
public abstract class EmptyContractResourcesAction
        implements WorkAction<EmptyContractResourcesAction.Parameters> {

    private static final Logger LOGGER = Logging.getLogger(EmptyContractResourcesAction.class);
    private static final String SHA_256 = "SHA-256";

    /** The work's inputs. */
    public interface Parameters extends WorkParameters {

        /** {@code compileJava}'s class output. */
        DirectoryProperty getDestinationDirectory();

        /** The compilation's effective {@code -A} arguments, one value per key. */
        MapProperty<String, String> getCompilerArguments();
    }

    @Override
    public void execute() {
        Parameters parameters = getParameters();
        EffectiveOptions options = EffectiveOptions.fromArguments(parameters.getCompilerArguments().get());
        if (options == null) {
            throw new GradleException("[ai-atlas] Cannot write the empty ai-atlas contract: the effective"
                    + " ai.atlas.* configuration is invalid.");
        }
        File destinationDir = parameters.getDestinationDirectory().get().getAsFile();
        List<String> reservedBefore = reservedPaths(destinationDir);

        byte[] ir = EmptyContract.json(options.apiBasePath(), options.apiMajor()).getBytes(StandardCharsets.UTF_8);
        File irFile = new File(destinationDir, ContractIr.RESOURCE_PATH);
        if (reservedBefore.contains(ContractIr.RESOURCE_PATH) && !sameBytes(irFile, ir)) {
            LOGGER.info("[ai-atlas] compileJava is replacing the stale " + irFile + " left by an earlier"
                    + " compilation with the empty contract.");
        }
        write(irFile, ir);

        SortedMap<String, String> artifacts = new TreeMap<>();
        artifacts.put(ContractIr.RESOURCE_PATH, sha256(ir));
        ContractResources.Manifest manifest = new ContractResources.Manifest("empty", options, artifacts);
        write(new File(destinationDir, ContractResources.MANIFEST_PATH),
                manifest.write().getBytes(StandardCharsets.UTF_8));
        // Every other reserved path already present (reservedBefore, less api.ir.json) is left exactly as
        // found: it is not listed in this manifest, so ClassOutputResources (C4) fails the release on it.
    }

    /** The reserved, class-output-relative paths already present, forward-slashed. */
    private static List<String> reservedPaths(File destinationDir) {
        if (!destinationDir.isDirectory()) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(destinationDir.toPath())) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> destinationDir.toPath().relativize(path).toString().replace(File.separatorChar, '/'))
                    .filter(ContractResources::isReserved)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + destinationDir, e);
        }
    }

    private static boolean sameBytes(File file, byte[] bytes) {
        try {
            return file.isFile() && Arrays.equals(Files.readAllBytes(file.toPath()), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    private static void write(File file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256).digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(SHA_256 + " is not available", e);
        }
    }
}

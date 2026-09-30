/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.EmptyContract;
import com.egoge.ai.atlas.processor.release.ContractRelease;
import com.egoge.ai.atlas.processor.release.ReleasePolicy;
import com.egoge.ai.atlas.processor.release.ReleaseVersion;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Releases the accepted contract through the processor's {@link ContractRelease}. Loaded from the
 * project's {@code annotationProcessor} classpath in an isolated class loader, like
 * {@link AcceptAction}, so the comparison, the policy and the IR reader are those of the processor
 * that compiled.
 */
public abstract class ReleaseAction implements WorkAction<ReleaseAction.Parameters> {

    private static final Logger LOGGER = Logging.getLogger(ReleaseAction.class);

    /** The build's contract and the releases, shared with the release check. */
    public interface ContractParameters extends WorkParameters {

        /** The IR the build emitted, or unset when the sources declare nothing. */
        RegularFileProperty getEmittedIr();

        /** The configured REST base path, for the empty document. */
        Property<String> getApiBasePath();

        /** The configured major, for the empty document. */
        Property<Integer> getApiMajor();

        /** The directory of releases. */
        DirectoryProperty getReleasesDir();
    }

    /** The release step's inputs. */
    public interface Parameters extends ContractParameters {

        /** The main compilation's class output(s), for {@link ClassOutputResources#validate}. */
        ConfigurableFileCollection getClassesDirs();

        /** The accepted baseline. */
        RegularFileProperty getBaseline();

        /** The aggregate changelog. */
        RegularFileProperty getChangelog();

        /** The version to release. */
        Property<String> getReleaseVersion();

        /** Whether the version's major must equal the contract's {@code apiMajor}. */
        Property<Boolean> getVersionTracksApiMajor();

        /** The policy's {@code minDeprecatedReleases}. */
        Property<Integer> getMinDeprecatedReleases();

        /** The policy's {@code minApiMajorAdvance}. */
        Property<Integer> getMinApiMajorAdvance();

        /** The policy's {@code failOnBreaking}. */
        Property<Boolean> getFailOnBreaking();
    }

    @Override
    public void execute() {
        Parameters parameters = getParameters();
        String version = version(parameters.getReleaseVersion().get(),
                "Set agentic { releaseVersion }, which defaults to the project version.");
        ReleasePolicy.Policy policy;
        try {
            policy = new ReleasePolicy.Policy(parameters.getMinDeprecatedReleases().get(),
                    parameters.getMinApiMajorAdvance().get(), parameters.getFailOnBreaking().get());
        } catch (IllegalArgumentException e) {
            throw new GradleException("[ai-atlas] " + e.getMessage() + ": check " + ReleasePolicy.CONFIGURATION + ".");
        }
        Path releases = parameters.getReleasesDir().get().getAsFile().toPath();
        try {
            ClassOutputResources.Result resources =
                    ClassOutputResources.validate(parameters.getClassesDirs().getFiles());
            ContractRelease.Outcome outcome = ContractRelease.release(releases,
                    parameters.getChangelog().get().getAsFile().toPath(), new ContractRelease.Request(version,
                            parameters.getVersionTracksApiMajor().get(), policy,
                            parameters.getBaseline().get().getAsFile().toPath(),
                            resources.artifacts().get(ContractRelease.IR_FILE), resources.artifacts(),
                            resources.contractResourcesJson()));
            LOGGER.lifecycle("[ai-atlas] Released contract " + outcome.version() + " (API major " + outcome.apiMajor()
                    + ") to " + outcome.directory() + System.lineSeparator() + outcome.changelog());
        } catch (ContractRelease.ReleaseException e) {
            throw new GradleException(e.getMessage(), e);
        } catch (IOException e) {
            throw new GradleException("[ai-atlas] agenticRelease failed: " + e.getMessage(), e);
        }
    }

    /** The version, checked to be a release version, or a failure ending with {@code remedy}. */
    static String version(String version, String remedy) {
        try {
            return ReleaseVersion.parse(version).toString();
        } catch (IllegalArgumentException e) {
            throw new GradleException("[ai-atlas] " + e.getMessage() + ". " + remedy);
        }
    }

    /** The IR the build emitted, or the empty document when its sources declare nothing. */
    static byte[] emitted(ContractParameters parameters) throws IOException {
        if (parameters.getEmittedIr().isPresent()) {
            return Files.readAllBytes(parameters.getEmittedIr().get().getAsFile().toPath());
        }
        return EmptyContract.json(parameters.getApiBasePath().get(), parameters.getApiMajor().get())
                .getBytes(StandardCharsets.UTF_8);
    }
}

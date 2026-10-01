/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

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
import java.nio.file.Path;

/**
 * Releases the accepted contract through {@link ReleaseSnapshots}. Loaded from the project's
 * {@code annotationProcessor} classpath in an isolated class loader, like {@link AcceptAction}, so
 * the comparison, the policy and the IR reader are those of the processor that compiled.
 */
public abstract class ReleaseAction implements WorkAction<ReleaseAction.Parameters> {

    private static final Logger LOGGER = Logging.getLogger(ReleaseAction.class);

    /** The release step's inputs. */
    public interface Parameters extends WorkParameters {

        /** The directory of releases. */
        DirectoryProperty getReleasesDir();

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

        /** The {@code agentic { release { tagName } } } template, such as {@value TagName#DEFAULT_TEMPLATE}. */
        Property<String> getTagNameTemplate();
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
        TagName tagName = TagName.parse(parameters.getTagNameTemplate().get());
        String resolvedTagName = tagName.render(ReleaseVersion.parse(version));
        Path releases = parameters.getReleasesDir().get().getAsFile().toPath();
        GitRepository git = new GitRepository(GitRepository.nearestExistingAncestor(releases));
        try {
            // The prefix locates snapshots in tagged trees, so it exists only inside a repository
            boolean repository = git.isInsideWorkTree();
            String releasesPrefix = repository ? git.releasesPrefix(releases) : "";
            Path changelog = parameters.getChangelog().get().getAsFile().toPath();
            if (repository) {
                requireNoEolConversion(git, releases.resolve(version).resolve(ReleaseSnapshots.MANIFEST_FILE),
                        changelog, releasesPrefix);
            }
            PublishedHistory.Verdict verdict = PublishedHistory.verify(releases, releasesPrefix, tagName, git);
            ClassOutputResources.Result resources =
                    ClassOutputResources.validate(parameters.getClassesDirs().getFiles());
            ReleaseSnapshots.Outcome outcome = ReleaseSnapshots.release(releases,
                    changelog, new ReleaseSnapshots.Request(version,
                            parameters.getVersionTracksApiMajor().get(), policy,
                            parameters.getBaseline().get().getAsFile().toPath(),
                            resources.artifacts().get(ReleaseSnapshots.IR_FILE), resources.artifacts(),
                            resources.contractResourcesJson(), resolvedTagName, verdict.published()));
            LOGGER.lifecycle("[ai-atlas] Released contract " + outcome.version() + " (API major " + outcome.apiMajor()
                    + ") to " + outcome.directory() + System.lineSeparator() + outcome.changelog());
        } catch (ReleaseSnapshots.ReleaseException e) {
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

    /**
     * Fails unless git converts no line endings in the release's files: a checkout with
     * {@code core.autocrlf}, the default of Git for Windows, would otherwise rewrite them, so the
     * history check and every tag proof would find them modified after release.
     */
    private static void requireNoEolConversion(GitRepository git, Path releaseFile, Path changelog,
                                               String releasesPrefix) {
        if (git.eolConversionOff(releaseFile) && git.eolConversionOff(changelog)) {
            return;
        }
        throw new GradleException("[ai-atlas] git would convert the line endings of released files on checkout, as"
                + " Git for Windows does by default, so they would no longer match the digests release.json records."
                + " Add these lines to .gitattributes, commit them, then release:" + System.lineSeparator()
                + "  " + releasesPrefix + "** -text" + System.lineSeparator()
                + "  " + git.rootRelative(changelog) + " -text");
    }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * {@code agenticReleaseVerify}'s worker: proves {@code agentic { releaseVersion } }'s tag resolves
 * to exactly {@code HEAD} (E2, D8.2, plan §3.5, OQ-3), through the same fail-closed
 * {@link GitRepository} and {@link PublishedHistory} proof {@code agenticRelease} uses, then
 * verifies the build matches that release through {@link ReleaseSnapshots#verifyBuild}.
 * Loaded from the project's {@code annotationProcessor} classpath in an isolated class loader.
 *
 * <p>It never depends on the compilation it validates (plan §3.5, spec fourth round): {@link
 * AgenticReleaseVerify#getClassesDirs()} is populated from a plain provider, ordered
 * only with {@code mustRunAfter}, so this action must itself detect and clearly report a missing
 * class output, rather than relying on a task dependency to have produced one.
 */
public abstract class ReleaseVerifyAction implements WorkAction<ReleaseVerifyAction.Parameters> {

    private static final Logger LOGGER = Logging.getLogger(ReleaseVerifyAction.class);
    private static final String PREFIX = "[ai-atlas] ";

    /** The verify step's inputs. */
    public interface Parameters extends WorkParameters {

        /** The main compilation's class output(s), as found: never a task dependency. */
        ConfigurableFileCollection getClassesDirs();

        /** The directory of releases. */
        DirectoryProperty getReleasesDir();

        /** The aggregate changelog. */
        RegularFileProperty getChangelog();

        /** The version to verify, {@code agentic { releaseVersion } }. */
        Property<String> getReleaseVersion();

        /** The {@code agentic { release { tagName } } } template. */
        Property<String> getTagNameTemplate();
    }

    @Override
    public void execute() {
        Parameters parameters = getParameters();
        String version = ReleaseAction.version(parameters.getReleaseVersion().get(),
                "Set agentic { releaseVersion }, which defaults to the project version.");
        TagName tagName = TagName.parse(parameters.getTagNameTemplate().get());
        String resolvedTagName = tagName.render(ReleaseVersion.parse(version));
        Path releasesDir = parameters.getReleasesDir().get().getAsFile().toPath();
        Path changelog = parameters.getChangelog().get().getAsFile().toPath();
        GitRepository git = new GitRepository(GitRepository.nearestExistingAncestor(releasesDir));
        try {
            String releasesPrefix = git.isInsideWorkTree() ? git.releasesPrefix(releasesDir) : "";
            // Reuses the release-time tag proof: any committed snapshot with an unproved,
            // orphaned or pending tag fails here too, and a shallow clone or any git error fails
            // closed, exactly as agenticRelease's proof does.
            PublishedHistory.verify(releasesDir, releasesPrefix, tagName, git);

            Optional<String> commit = git.peelToCommit(resolvedTagName);
            if (commit.isEmpty()) {
                throw fail("Tag " + resolvedTagName + " does not exist, or does not peel to a commit: release "
                        + version + " is not verified. Tag its commit as " + resolvedTagName + " and push the tag.");
            }
            Optional<String> head = git.headCommit();
            if (head.isEmpty() || !head.get().equals(commit.get())) {
                throw fail("Tag " + resolvedTagName + "'s commit is not HEAD: HEAD must be the exact commit tagged "
                        + resolvedTagName + " to verify release " + version + ".");
            }

            Collection<File> classesDirs = parameters.getClassesDirs().getFiles();
            if (classesDirs.stream().noneMatch(File::isDirectory)) {
                throw fail("No compiled class output was found. Run `./gradlew classes " + AgenticPlugin.RELEASE_VERIFY_TASK
                        + "` (or `./gradlew build " + AgenticPlugin.RELEASE_VERIFY_TASK + "`) to compile first, then"
                        + " verify.");
            }
            ClassOutputResources.Result resources = ClassOutputResources.validate(classesDirs);

            ReleaseSnapshots.verifyBuild(releasesDir, changelog, version, resources.artifacts().get(ReleaseSnapshots.IR_FILE),
                    resources.artifacts(), resources.contractResourcesJson());
            LOGGER.lifecycle(PREFIX + "The build matches the released contract " + version + " (tag " + resolvedTagName
                    + ").");
        } catch (ReleaseSnapshots.ReleaseException e) {
            throw new GradleException(PREFIX + AgenticPlugin.RELEASE_VERIFY_TASK + ": tag " + resolvedTagName
                    + " (release " + version + ") failed verification: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new GradleException(PREFIX + AgenticPlugin.RELEASE_VERIFY_TASK + " failed: " + e.getMessage(), e);
        }
    }

    private static GradleException fail(String message) {
        return new GradleException(PREFIX + message);
    }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.release.ContractRelease;
import com.egoge.ai.atlas.processor.release.ReleaseHistory;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Verifies the internal consistency of the committed release snapshots, through the processor's
 * {@link ReleaseHistory#checkConsistency}: no git, no network, and it never reads the build's
 * class output. Loaded from the project's {@code annotationProcessor} classpath in an isolated
 * class loader.
 */
public abstract class ReleaseHistoryCheckAction implements WorkAction<ReleaseHistoryCheckAction.Parameters> {

    private static final Logger LOGGER = Logging.getLogger(ReleaseHistoryCheckAction.class);

    /** The check's inputs. */
    public interface Parameters extends WorkParameters {

        /** The directory of releases; it may not exist. */
        DirectoryProperty getReleasesDir();

        /** The aggregate changelog. */
        RegularFileProperty getChangelog();
    }

    @Override
    public void execute() {
        Parameters parameters = getParameters();
        Path releases = parameters.getReleasesDir().get().getAsFile().toPath();
        Path changelog = parameters.getChangelog().get().getAsFile().toPath();
        try {
            int count = ReleaseHistory.checkConsistency(releases, changelog);
            LOGGER.info("[ai-atlas] Verified the internal consistency of {} release(s) in {}", count, releases);
        } catch (ContractRelease.ReleaseException e) {
            throw new GradleException(e.getMessage(), e);
        } catch (IOException e) {
            throw new GradleException("[ai-atlas] agenticReleaseHistoryCheck failed: " + e.getMessage(), e);
        }
    }
}

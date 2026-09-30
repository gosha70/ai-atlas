/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.release.ContractRelease;
import com.egoge.ai.atlas.processor.release.ReleaseHistory;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Verifies every release against its digests, and, given a version, that the build's contract is
 * that release's, through the processor's {@link ContractRelease}. Loaded from the project's
 * {@code annotationProcessor} classpath in an isolated class loader.
 */
public abstract class ReleaseCheckAction implements WorkAction<ReleaseCheckAction.Parameters> {

    private static final Logger LOGGER = Logging.getLogger(ReleaseCheckAction.class);

    /** The check's inputs. */
    public interface Parameters extends ReleaseAction.ContractParameters {

        /** The version the build must match, or unset to verify digests only. */
        Property<String> getReleaseVersion();
    }

    @Override
    public void execute() {
        Parameters parameters = getParameters();
        Path releases = parameters.getReleasesDir().get().getAsFile().toPath();
        try {
            if (parameters.getReleaseVersion().isPresent()) {
                String version = ReleaseAction.version(parameters.getReleaseVersion().get(), "Pass"
                        + " --release-version, or set agentic { release { checkVersion } }, to a released"
                        + " MAJOR.MINOR.PATCH.");
                ContractRelease.checkReleased(releases, version, ReleaseAction.emitted(parameters));
                LOGGER.lifecycle("[ai-atlas] The build's contract is the released contract " + version + ".");
            } else {
                int count = ReleaseHistory.history(releases).size();
                LOGGER.info("[ai-atlas] Verified {} release(s) in {}", count, releases);
            }
        } catch (ContractRelease.ReleaseException e) {
            throw new GradleException(e.getMessage(), e);
        } catch (IOException e) {
            throw new GradleException("[ai-atlas] agenticReleaseCheck failed: " + e.getMessage(), e);
        }
    }
}

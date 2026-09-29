/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.Action;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;

import javax.inject.Inject;

/**
 * {@code agentic { release { … } } }: where {@code agenticRelease} writes released contracts, and
 * the deprecation policy.
 *
 * <pre>
 * agentic {
 *     releaseVersion.set("1.4.0")
 *     release {
 *         deprecation {
 *             minReleases.set(2)
 *         }
 *     }
 * }
 * </pre>
 */
public abstract class ReleaseSpec {

    private final DeprecationSpec deprecation;

    /**
     * @param objects creates the nested deprecation policy
     */
    @Inject
    public ReleaseSpec(ObjectFactory objects) {
        this.deprecation = objects.newInstance(DeprecationSpec.class);
    }

    /**
     * The directory holding one immutable subdirectory per released version. Defaults to
     * {@code <projectDir>/.atlas/releases}.
     */
    public abstract DirectoryProperty getDirectory();

    /**
     * The aggregate contract changelog, regenerated from every release, newest first. Defaults to
     * {@code <projectDir>/.atlas/CHANGELOG.md}. The repository's own {@code CHANGELOG.md} is never
     * written.
     */
    public abstract RegularFileProperty getChangelog();

    /** The deprecation policy a release is checked against. */
    public DeprecationSpec getDeprecation() {
        return deprecation;
    }

    /**
     * Configures the deprecation policy.
     *
     * @param action the configuration
     */
    public void deprecation(Action<? super DeprecationSpec> action) {
        action.execute(deprecation);
    }
}

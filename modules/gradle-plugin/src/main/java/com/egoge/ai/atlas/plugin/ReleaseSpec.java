/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.Action;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * {@code agentic { release { … } } }: where {@code agenticRelease} writes released contracts, the
 * version {@code agenticReleaseCheck} matches the build against, and the release policy.
 *
 * <pre>
 * agentic {
 *     releaseVersion.set("1.4.0")
 *     release {
 *         policy {
 *             minDeprecatedReleases.set(2)
 *         }
 *     }
 * }
 * </pre>
 */
public abstract class ReleaseSpec {

    private final PolicySpec policy;

    /**
     * @param objects creates the nested release policy
     */
    @Inject
    public ReleaseSpec(ObjectFactory objects) {
        this.policy = objects.newInstance(PolicySpec.class);
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

    /**
     * The released version {@code agenticReleaseCheck} requires the build's contract to be, such as
     * the version CI builds a tag of. Unset by default, and then only the releases' digests are
     * checked. The task's {@code --release-version} option overrides it.
     */
    public abstract Property<String> getCheckVersion();

    /**
     * The git tag name template a released version is proved by (F1), with exactly one
     * {@code {version}} placeholder. Defaults to {@value TagName#DEFAULT_TEMPLATE}. The rendered
     * name is recorded in each release's {@code release.json}.
     */
    public abstract Property<String> getTagName();

    /** The release policy a release is checked against. */
    public PolicySpec getPolicy() {
        return policy;
    }

    /**
     * Configures the release policy.
     *
     * @param action the configuration
     */
    public void policy(Action<? super PolicySpec> action) {
        action.execute(policy);
    }
}

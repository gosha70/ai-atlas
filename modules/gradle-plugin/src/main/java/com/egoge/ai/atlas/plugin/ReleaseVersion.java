/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.GradleException;

import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SPIKE: a released version, {@code MAJOR.MINOR.PATCH} with no pre-release or build suffix,
 * ordered numerically.
 *
 * @param major the SemVer major
 * @param minor the SemVer minor
 * @param patch the SemVer patch
 */
record ReleaseVersion(int major, int minor, int patch) implements Comparable<ReleaseVersion> {

    private static final Pattern SEMVER = Pattern.compile("(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})");
    private static final Comparator<ReleaseVersion> ORDER = Comparator.comparingInt(ReleaseVersion::major)
            .thenComparingInt(ReleaseVersion::minor).thenComparingInt(ReleaseVersion::patch);

    /**
     * @param version the version string
     * @return the version
     * @throws GradleException if it is not {@code MAJOR.MINOR.PATCH}, naming SNAPSHOT versions
     */
    static ReleaseVersion parse(String version) {
        Matcher m = SEMVER.matcher(version == null ? "" : version);
        if (!m.matches()) {
            String why = version != null && version.endsWith("-SNAPSHOT")
                    ? " A SNAPSHOT is not a release: its contract can still change under the same version."
                    : "";
            throw new GradleException("agenticRelease releases MAJOR.MINOR.PATCH versions only, got '" + version
                    + "'." + why + " Set agentic { releaseVersion } or the project version.");
        }
        return new ReleaseVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
    }

    /** Whether {@code name} is a release directory name. */
    static boolean matches(String name) {
        return SEMVER.matcher(name).matches();
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}

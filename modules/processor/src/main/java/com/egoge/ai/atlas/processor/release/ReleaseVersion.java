/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A released version: strict {@code MAJOR.MINOR.PATCH}, with no pre-release or build suffix,
 * ordered numerically. The version names a release directory, so it is never a {@code -SNAPSHOT}:
 * a snapshot's contract can still change under the same name, which is what a release forbids.
 *
 * @param major the version's major, independent of the contract's {@code apiMajor}
 * @param minor the minor
 * @param patch the patch
 */
public record ReleaseVersion(int major, int minor, int patch) implements Comparable<ReleaseVersion> {

    private static final Pattern FORMAT = Pattern.compile("(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})");
    private static final String SNAPSHOT = "-SNAPSHOT";
    private static final Comparator<ReleaseVersion> ORDER = Comparator.comparingInt(ReleaseVersion::major)
            .thenComparingInt(ReleaseVersion::minor).thenComparingInt(ReleaseVersion::patch);

    /**
     * Parses a release version.
     *
     * @param version the version text
     * @return the version
     * @throws IllegalArgumentException if it is not {@code MAJOR.MINOR.PATCH}; a {@code -SNAPSHOT}
     *                                  gets a message of its own
     */
    public static ReleaseVersion parse(String version) {
        Matcher m = FORMAT.matcher(version == null ? "" : version);
        if (!m.matches()) {
            if (version != null && version.endsWith(SNAPSHOT)) {
                throw new IllegalArgumentException("Version '" + version + "' is a SNAPSHOT, and a SNAPSHOT is"
                        + " never released: its contract can still change under the same name. Release "
                        + version.substring(0, version.length() - SNAPSHOT.length()) + " instead");
            }
            throw new IllegalArgumentException("Version '" + version + "' is not a release version: a release is"
                    + " MAJOR.MINOR.PATCH, such as 1.4.0, with no pre-release or build suffix");
        }
        return new ReleaseVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
    }

    /**
     * Whether {@code name} is a release version, and so a release directory's name.
     *
     * @param name a file name
     * @return whether it is {@code MAJOR.MINOR.PATCH}
     */
    public static boolean matches(String name) {
        return FORMAT.matcher(name).matches();
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

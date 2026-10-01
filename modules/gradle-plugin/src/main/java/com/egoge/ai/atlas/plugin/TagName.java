/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.release.ReleaseVersion;
import org.gradle.api.GradleException;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code agentic { release { tagName } } }: the git tag a released version is proved by (F1). The
 * template has exactly one {@code {version}} placeholder, and renders to a valid git ref name.
 * Only the exact rendered name counts as a match: {@code "1.0.0"} never stands in for
 * {@code "v1.0.0"}, and neither does a pre-release such as {@code "v1.0.0-rc.1"}.
 */
public final class TagName {

    /** The default template, used unless {@code agentic { release { tagName } } } overrides it. */
    public static final String DEFAULT_TEMPLATE = "v{version}";

    private static final String PLACEHOLDER = "{version}";
    private static final Pattern INVALID_REF_CHARS = Pattern.compile("[\\s~^:?*\\[\\\\\\x00-\\x1f]");

    private final String prefix;
    private final String suffix;

    private TagName(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
    }

    /**
     * Parses a {@code tagName} template.
     *
     * @param template the configured template, such as {@value #DEFAULT_TEMPLATE}
     * @return the parsed template
     * @throws GradleException if it has no {@value #PLACEHOLDER}, more than one, or does not render
     *                          a valid git ref name
     */
    public static TagName parse(String template) {
        if (template == null || template.isEmpty()) {
            throw new GradleException("[ai-atlas] agentic { release { tagName } } must not be empty.");
        }
        int first = template.indexOf(PLACEHOLDER);
        if (first < 0) {
            throw new GradleException("[ai-atlas] agentic { release { tagName } } = '" + template + "' must contain"
                    + " exactly one " + PLACEHOLDER + ", found none.");
        }
        int second = template.indexOf(PLACEHOLDER, first + PLACEHOLDER.length());
        if (second >= 0) {
            throw new GradleException("[ai-atlas] agentic { release { tagName } } = '" + template + "' must contain"
                    + " exactly one " + PLACEHOLDER + ", found more than one.");
        }
        String prefix = template.substring(0, first);
        String suffix = template.substring(first + PLACEHOLDER.length());
        String sample = prefix + "0.0.0" + suffix;
        if (!isValidRefName(sample)) {
            throw new GradleException("[ai-atlas] agentic { release { tagName } } = '" + template + "' does not"
                    + " render a valid git tag name, got '" + sample + "'.");
        }
        return new TagName(prefix, suffix);
    }

    /**
     * Renders the tag name for {@code version}.
     *
     * @param version the released version
     * @return the tag name, such as {@code "v1.4.0"}
     */
    public String render(ReleaseVersion version) {
        return prefix + version + suffix;
    }

    /**
     * Whether {@code tag} is exactly this template rendered for a release version.
     *
     * @param tag a tag name read from the repository
     * @return the version it renders, or empty when {@code tag} does not match this template
     */
    public Optional<ReleaseVersion> match(String tag) {
        if (tag == null || !tag.startsWith(prefix) || !tag.endsWith(suffix)
                || tag.length() < prefix.length() + suffix.length()) {
            return Optional.empty();
        }
        String middle = tag.substring(prefix.length(), tag.length() - suffix.length());
        if (!ReleaseVersion.matches(middle)) {
            return Optional.empty();
        }
        return Optional.of(ReleaseVersion.parse(middle));
    }

    /**
     * A basic git ref-name check: no whitespace or {@code ~^:?*[\}, no {@code ..}, no {@code @{}, and
     * it neither starts nor ends with {@code /} nor ends with {@code .} or {@code .lock}.
     */
    private static boolean isValidRefName(String name) {
        return !name.isEmpty() && !INVALID_REF_CHARS.matcher(name).find() && !name.contains("..")
                && !name.contains("@{") && !name.startsWith("/") && !name.endsWith("/") && !name.endsWith(".")
                && !name.endsWith(".lock");
    }
}

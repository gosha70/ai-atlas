/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.release.ReleaseVersion;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link TagName}: the {@code tagName} template, its rendering and its strict matching. */
class TagNameTest {

    @Test
    void aTemplateWithNoPlaceholderIsRefused() {
        assertThatThrownBy(() -> TagName.parse("release"))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("found none");
    }

    @Test
    void aTemplateWithTwoPlaceholdersIsRefused() {
        assertThatThrownBy(() -> TagName.parse("{version}-{version}"))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("found more than one");
    }

    @Test
    void aTemplateThatWouldNotRenderAValidRefNameIsRefused() {
        assertThatThrownBy(() -> TagName.parse("release {version}"))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("does not render a valid git tag name");
    }

    @Test
    void theDefaultTemplateRendersVPrefixed() {
        TagName tagName = TagName.parse(TagName.DEFAULT_TEMPLATE);

        assertThat(tagName.render(ReleaseVersion.parse("1.4.0"))).isEqualTo("v1.4.0");
    }

    @Test
    void onlyTheExactRenderedNameMatches() {
        TagName tagName = TagName.parse(TagName.DEFAULT_TEMPLATE);

        assertThat(tagName.match("v1.0.0")).contains(ReleaseVersion.parse("1.0.0"));
        assertThat(tagName.match("1.0.0")).isEmpty();
        assertThat(tagName.match("v1.0.0-rc.1")).isEmpty();
        assertThat(tagName.match("v1.0.0-SNAPSHOT")).isEmpty();
        assertThat(tagName.match(null)).isEmpty();
    }

    @Test
    void aCustomTemplateWorks() {
        TagName tagName = TagName.parse("release-{version}-final");

        assertThat(tagName.render(ReleaseVersion.parse("2.0.0"))).isEqualTo("release-2.0.0-final");
        assertThat(tagName.match("release-2.0.0-final")).contains(ReleaseVersion.parse("2.0.0"));
        assertThat(tagName.match("release-2.0.0")).isEmpty();
        assertThat(tagName.match("v2.0.0")).isEmpty();
    }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgenticPropertiesApiTest {

    @Test
    void setBasePath_stripsTrailingSlash() {
        var api = new AgenticProperties.Api();
        api.setBasePath("/services/");
        assertThat(api.getBasePath()).isEqualTo("/services");
    }

    @Test
    void setBasePath_stripsMultipleTrailingSlashes() {
        var api = new AgenticProperties.Api();
        api.setBasePath("/services///");
        assertThat(api.getBasePath()).isEqualTo("/services");
    }

    @Test
    void setBasePath_rejectsNoLeadingSlash() {
        var api = new AgenticProperties.Api();
        assertThatThrownBy(() -> api.setBasePath("services"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with '/'");
    }

    @Test
    void setBasePath_rejectsRootSlash() {
        var api = new AgenticProperties.Api();
        assertThatThrownBy(() -> api.setBasePath("/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be '/'");
    }

    @Test
    void setBasePath_rejectsMultipleSlashesNormalizingToRoot() {
        var api = new AgenticProperties.Api();
        assertThatThrownBy(() -> api.setBasePath("///"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be '/'");
    }

    @Test
    void setBasePath_rejectsBlank() {
        var api = new AgenticProperties.Api();
        assertThatThrownBy(() -> api.setBasePath(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void setBasePath_rejectsNull() {
        var api = new AgenticProperties.Api();
        assertThatThrownBy(() -> api.setBasePath(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void setMajor_rejectsZero() {
        var api = new AgenticProperties.Api();
        assertThatThrownBy(() -> api.setMajor(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a positive integer");
    }

    @Test
    void setMajor_rejectsNegative() {
        var api = new AgenticProperties.Api();
        assertThatThrownBy(() -> api.setMajor(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a positive integer");
    }

    @Test
    void defaults_matchProcessorDefaults() {
        var api = new AgenticProperties.Api();
        assertThat(api.getBasePath()).isEqualTo("/api");
        assertThat(api.getMajor()).isEqualTo(1);
    }

    @Test
    void interceptorPattern_matchesGeneratedPaths() {
        var api = new AgenticProperties.Api();
        api.setBasePath("/services");
        api.setMajor(2);
        String pattern = api.getBasePath() + "/v" + api.getMajor() + "/**";
        assertThat(pattern).isEqualTo("/services/v2/**");
    }

    @Test
    void versionNegotiation_defaultDisabled() {
        var api = new AgenticProperties.Api();
        assertThat(api.getVersionNegotiation().isEnabled()).isFalse();
    }

    @Test
    void deprecationHeadersEnabled_defaultTrue() {
        var api = new AgenticProperties.Api();
        assertThat(api.isDeprecationHeadersEnabled()).isTrue();
    }

    @Test
    void deprecationDocUrl_defaultEmpty() {
        var api = new AgenticProperties.Api();
        assertThat(api.getDeprecationDocUrl()).isEqualTo("");
    }
}

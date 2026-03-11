/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.security;

import com.egoge.ai.atlas.runtime.autoconfigure.AgenticProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-style tests verifying filter behaviour end-to-end with real
 * {@link AgenticProperties} configuration. These tests exercise the wiring
 * between configuration values and filter behaviour without requiring a full
 * Spring application context (which would pull in MCP server dependencies
 * that require additional transport configuration).
 */
class DeprecationHeaderIntegrationTest {

    @Test
    void deprecatedEndpoint_receivesDeprecationAndLinkHeaders() throws Exception {
        writeManifest("""
                {
                  "apiMajor":2,
                  "basePath":"/api",
                  "endpoints":[
                    {"method":"POST","path":"/api/v2/order-service/find-by-id",
                     "deprecated":true,"deprecatedSince":1,"replacement":"findV3"}
                  ]
                }
                """);

        var api = new AgenticProperties.Api();
        api.setMajor(2);
        api.setDeprecationDocUrl("https://docs.example.com/deprecations");

        var filter = new DeprecationHeaderFilter(api.getDeprecationDocUrl());
        var request = new MockHttpServletRequest("POST", "/api/v2/order-service/find-by-id");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Deprecation")).isEqualTo("true");
        assertThat(response.getHeader("Link"))
                .isEqualTo("<https://docs.example.com/deprecations>; rel=\"deprecation\"");
    }

    @Test
    void versionNegotiation_rejectsWrongVersion() throws Exception {
        var api = new AgenticProperties.Api();
        api.setMajor(2);

        var filter = new VersionNegotiationFilter(api.getMajor());
        var request = new MockHttpServletRequest("GET", "/api/v2/order-service/find-all");
        request.addHeader("Accept-Version", "99");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("version_mismatch");
    }

    private static void writeManifest(String json) throws Exception {
        URL resource = DeprecationHeaderIntegrationTest.class.getClassLoader().getResource(".");
        if (resource != null) {
            Path metaInf = Path.of(resource.toURI()).resolve("META-INF/ai-atlas");
            Files.createDirectories(metaInf);
            Files.writeString(metaInf.resolve("deprecation-manifest.json"), json,
                    StandardCharsets.UTF_8);
        }
    }
}

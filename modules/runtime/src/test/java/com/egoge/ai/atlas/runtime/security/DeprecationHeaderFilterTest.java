/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.security;

import org.junit.jupiter.api.BeforeEach;
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
 * Unit tests for {@link DeprecationHeaderFilter}.
 * Uses a test deprecation-manifest.json embedded on the test classpath.
 */
class DeprecationHeaderFilterTest {

    /**
     * Manifest JSON used for most tests. Placed on classpath via test resources
     * (src/test/resources/META-INF/ai-atlas/deprecation-manifest.json).
     * Contains:
     *   - POST /api/v2/order-service/find-by-id  → deprecated=true
     *   - GET  /api/v2/order-service/find-all    → deprecated=false
     *   - GET  /api/v2/order-service/find-by-id  → deprecated=false (same path, different method)
     */
    private static final String MANIFEST_JSON = """
            {
              "apiMajor":2,
              "basePath":"/api",
              "endpoints":[
                {"method":"POST","path":"/api/v2/order-service/find-by-id","deprecated":true,"deprecatedSince":1,"replacement":"findV3"},
                {"method":"GET","path":"/api/v2/order-service/find-all","deprecated":false,"deprecatedSince":0,"replacement":""},
                {"method":"GET","path":"/api/v2/order-service/find-by-id","deprecated":false,"deprecatedSince":0,"replacement":""}
              ]
            }
            """;

    private DeprecationHeaderFilter filter;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        writeManifestToTestClasspath(MANIFEST_JSON);
        filter = new DeprecationHeaderFilter("");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @Test
    void deprecatedEndpoint_setsDeprecationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v2/order-service/find-by-id");
        filter.doFilter(request, response, chain);
        assertThat(response.getHeader("Deprecation")).isEqualTo("true");
    }

    @Test
    void deprecatedEndpoint_withDocUrl_setsLinkHeader() throws Exception {
        filter = new DeprecationHeaderFilter("https://docs.example.com/deprecations");
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v2/order-service/find-by-id");
        filter.doFilter(request, response, chain);
        assertThat(response.getHeader("Deprecation")).isEqualTo("true");
        assertThat(response.getHeader("Link"))
                .isEqualTo("<https://docs.example.com/deprecations>; rel=\"deprecation\"");
    }

    @Test
    void nonDeprecatedEndpoint_noHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v2/order-service/find-all");
        filter.doFilter(request, response, chain);
        assertThat(response.getHeader("Deprecation")).isNull();
        assertThat(response.getHeader("Link")).isNull();
    }

    @Test
    void unknownPath_noHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v2/order-service/unknown-method");
        filter.doFilter(request, response, chain);
        assertThat(response.getHeader("Deprecation")).isNull();
    }

    @Test
    void manifestAbsent_noHeaders() throws Exception {
        removeManifestFromTestClasspath();
        DeprecationHeaderFilter noManifestFilter = new DeprecationHeaderFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v2/order-service/find-by-id");
        noManifestFilter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader("Deprecation")).isNull();
        // Restore for other tests
        writeManifestToTestClasspath(MANIFEST_JSON);
    }

    @Test
    void samePathDifferentMethod_correctLookup() throws Exception {
        // POST /find-by-id is deprecated; GET /find-by-id is active
        MockHttpServletRequest postRequest = new MockHttpServletRequest("POST",
                "/api/v2/order-service/find-by-id");
        filter.doFilter(postRequest, response, chain);
        assertThat(response.getHeader("Deprecation")).isEqualTo("true");

        MockHttpServletResponse getResponse = new MockHttpServletResponse();
        MockHttpServletRequest getRequest = new MockHttpServletRequest("GET",
                "/api/v2/order-service/find-by-id");
        filter.doFilter(getRequest, getResponse, new MockFilterChain());
        assertThat(getResponse.getHeader("Deprecation")).isNull();
    }

    @Test
    void contextPath_strippedBeforeLookup() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/app/api/v2/order-service/find-by-id");
        request.setContextPath("/app");
        filter.doFilter(request, response, chain);
        assertThat(response.getHeader("Deprecation")).isEqualTo("true");
    }

    // ---- Helpers ----

    private static Path resolveManifestPath() throws Exception {
        URL resource = DeprecationHeaderFilterTest.class.getClassLoader()
                .getResource(".");
        if (resource == null) {
            throw new IllegalStateException("Test classpath root not found");
        }
        return Path.of(resource.toURI()).resolve("META-INF/ai-atlas/deprecation-manifest.json");
    }

    private static void writeManifestToTestClasspath(String json) throws Exception {
        Path path = resolveManifestPath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static void removeManifestFromTestClasspath() throws Exception {
        Path path = resolveManifestPath();
        Files.deleteIfExists(path);
    }
}

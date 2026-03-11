/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VersionNegotiationFilter}.
 * Verifies the locked parsing contract for the {@code Accept-Version} header.
 */
class VersionNegotiationFilterTest {

    private static final int CONFIGURED_MAJOR = 2;

    private VersionNegotiationFilter filter;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new VersionNegotiationFilter(CONFIGURED_MAJOR);
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @Test
    void noHeader_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/service/method");
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void emptyHeader_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/service/method");
        request.addHeader("Accept-Version", "");
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void blankHeader_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/service/method");
        request.addHeader("Accept-Version", "   ");
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void matchingVersion_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/service/method");
        request.addHeader("Accept-Version", "2");
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void mismatchedVersion_returns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/service/method");
        request.addHeader("Accept-Version", "1");
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("version_mismatch");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void invalidVersionFormat_returns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/service/method");
        request.addHeader("Accept-Version", "latest");
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("invalid_version");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void zeroVersion_returns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/service/method");
        request.addHeader("Accept-Version", "0");
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("invalid_version");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void negativeVersion_returns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/service/method");
        request.addHeader("Accept-Version", "-1");
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("invalid_version");
        assertThat(chain.getRequest()).isNull();
    }
}

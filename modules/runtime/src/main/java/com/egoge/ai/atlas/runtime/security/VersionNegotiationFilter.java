/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Opt-in filter that validates the {@code Accept-Version} request header
 * against the configured major API version.
 *
 * <p>Enabled via {@code ai.atlas.api.version-negotiation.enabled=true}
 * (default {@code false}). When enabled, requests with an {@code Accept-Version}
 * header that does not match the configured major version are rejected with
 * {@code 400 Bad Request} and a structured JSON error body.
 *
 * <p>This filter is a guardrail, not a router — it does not route to different
 * controller versions. It only validates that the client's version expectation
 * matches what is served.
 *
 * <p><strong>Parsing contract (locked):</strong>
 * <ol>
 *   <li>{@code null} or blank → treat as absent, pass through</li>
 *   <li>Non-numeric → 400 {@code invalid_version}</li>
 *   <li>Numeric but &lt; 1 → 400 {@code invalid_version}</li>
 *   <li>Numeric and &ge; 1 but != configuredMajor → 400 {@code version_mismatch}</li>
 *   <li>Numeric and == configuredMajor → pass through</li>
 * </ol>
 */
public class VersionNegotiationFilter extends OncePerRequestFilter {

    private final int configuredMajor;

    public VersionNegotiationFilter(int configuredMajor) {
        this.configuredMajor = configuredMajor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String acceptVersion = request.getHeader("Accept-Version");
        // Treat null and blank as "no version preference" — pass through
        if (acceptVersion != null && !acceptVersion.isBlank()) {
            int requested;
            try {
                requested = Integer.parseInt(acceptVersion.trim());
            } catch (NumberFormatException e) {
                writeError(response, "invalid_version",
                        "Accept-Version header must be a positive integer.", null);
                return;
            }
            if (requested < 1) {
                writeError(response, "invalid_version",
                        "Accept-Version header must be a positive integer. Got: " + requested, null);
                return;
            }
            if (requested != configuredMajor) {
                writeError(response, "version_mismatch",
                        "Requested API version " + requested
                        + " is not available. This server serves version "
                        + configuredMajor + ".",
                        configuredMajor);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static void writeError(HttpServletResponse response,
                                   String error, String message,
                                   Integer availableVersion)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        String body = "{\"error\":\"" + error + "\","
                + "\"message\":\"" + message + "\""
                + (availableVersion != null ? ",\"availableVersion\":" + availableVersion : "")
                + "}";
        response.getWriter().write(body);
    }
}

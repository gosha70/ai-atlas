/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Servlet filter that adds {@code Deprecation: true} and optionally a
 * {@code Link} header to responses from deprecated REST endpoints.
 *
 * <p>Reads {@code META-INF/ai-atlas/deprecation-manifest.json} at construction
 * time — no runtime annotation scanning. The lookup key is
 * {@code "METHOD /path"} (e.g., {@code "POST /api/v2/order-service/find-by-id"})
 * which distinguishes a deprecated POST and an active GET on the same path.
 *
 * <p>A manifest path may be a route template with {@code {name}} variables, such as
 * {@code DELETE /api/v1/orders/{id}}, which {@code ai.atlas.rest} mappings produce. A request is
 * matched as Spring MVC routes it: an endpoint whose path equals the request path first, else the
 * most specific template that matches it, by Spring's {@link PathPattern}.
 *
 * <p>The servlet context path is stripped before lookup so that the filter
 * works correctly under a non-root {@code server.servlet.context-path}.
 */
public class DeprecationHeaderFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DeprecationHeaderFilter.class);
    private static final String MANIFEST_PATH = "META-INF/ai-atlas/deprecation-manifest.json";

    /** Keyed by "METHOD /path" (e.g., "GET /api/v2/order-service/find-all"). */
    private final Map<String, EndpointDeprecation> deprecationMap;
    /** The endpoints whose path is a template, such as "/api/v1/orders/{id}". */
    private final List<TemplateEndpoint> templates;
    private final String deprecationDocUrl;

    public DeprecationHeaderFilter(String deprecationDocUrl) {
        this.deprecationDocUrl = deprecationDocUrl;
        this.deprecationMap = loadManifest();
        this.templates = templates(deprecationMap);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        // Strip servlet context path so the lookup matches application-relative
        // paths recorded in the manifest. In default Spring Boot deployments
        // contextPath is "", so this is a no-op; but under a non-root
        // server.servlet.context-path the URI includes the prefix and must
        // be normalized before lookup.
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()) {
            path = path.substring(contextPath.length());
        }
        String key = request.getMethod() + " " + path;
        EndpointDeprecation dep = deprecationMap.get(key);
        if (dep == null && !templates.isEmpty()) {
            dep = matchTemplate(request.getMethod(), path);
        }
        if (dep != null && dep.deprecated()) {
            response.setHeader("Deprecation", "true");
            if (deprecationDocUrl != null && !deprecationDocUrl.isEmpty()) {
                // addHeader, not setHeader — Link is a multi-valued header
                // and must not clobber existing pagination/canonical/next links
                response.addHeader("Link", "<" + deprecationDocUrl + ">; rel=\"deprecation\"");
            }
        }
        filterChain.doFilter(request, response);
    }

    /** The endpoint of the most specific template matching the request, or {@code null}. */
    private EndpointDeprecation matchTemplate(String method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        TemplateEndpoint best = null;
        for (TemplateEndpoint template : templates) {
            if (template.method().equals(method) && template.pattern().matches(container)
                    && (best == null || PathPattern.SPECIFICITY_COMPARATOR.compare(template.pattern(), best.pattern()) < 0)) {
                best = template;
            }
        }
        return best != null ? best.deprecation() : null;
    }

    private static List<TemplateEndpoint> templates(Map<String, EndpointDeprecation> endpoints) {
        List<TemplateEndpoint> result = new ArrayList<>();
        endpoints.forEach((key, deprecation) -> {
            int space = key.indexOf(' ');
            String path = key.substring(space + 1);
            if (path.indexOf('{') >= 0) {
                try {
                    result.add(new TemplateEndpoint(key.substring(0, space),
                            PathPatternParser.defaultInstance.parse(path), deprecation));
                } catch (RuntimeException e) {
                    log.warn("[ai-atlas] Ignoring deprecation manifest path {}: {}", path, e.getMessage());
                }
            }
        });
        // A deterministic order whatever the map's: the comparator picks among equals by position
        result.sort(Comparator.comparing(t -> t.pattern().getPatternString()));
        return List.copyOf(result);
    }

    private Map<String, EndpointDeprecation> loadManifest() {
        var cl = DeprecationHeaderFilter.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(MANIFEST_PATH)) {
            if (is == null) {
                log.debug("[ai-atlas] No {} found on classpath — deprecation headers disabled",
                        MANIFEST_PATH);
                return Collections.emptyMap();
            }
            var mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);
            JsonNode endpoints = root.path("endpoints");
            if (!endpoints.isArray()) {
                return Collections.emptyMap();
            }
            Map<String, EndpointDeprecation> map = new HashMap<>();
            for (JsonNode ep : endpoints) {
                String method = ep.path("method").asText();
                String path = ep.path("path").asText();
                boolean deprecated = ep.path("deprecated").asBoolean(false);
                int deprecatedSince = ep.path("deprecatedSince").asInt(0);
                String replacement = ep.path("replacement").asText("");
                map.put(method + " " + path,
                        new EndpointDeprecation(deprecated, deprecatedSince, replacement));
            }
            log.debug("[ai-atlas] Loaded {} endpoint entries from {}", map.size(), MANIFEST_PATH);
            return Collections.unmodifiableMap(map);
        } catch (Exception e) {
            log.warn("[ai-atlas] Failed to load {}: {}", MANIFEST_PATH, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** A manifest endpoint whose path is a route template. */
    private record TemplateEndpoint(String method, PathPattern pattern, EndpointDeprecation deprecation) {
    }

    record EndpointDeprecation(
            boolean deprecated,
            int deprecatedSince,
            String replacement
    ) {}
}

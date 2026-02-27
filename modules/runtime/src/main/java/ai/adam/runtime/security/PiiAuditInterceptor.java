/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.runtime.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * Audit interceptor that logs all requests to generated API endpoints.
 * Uses SLF4J MDC for structured logging with correlation IDs.
 */
public class PiiAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PiiAuditInterceptor.class);
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_ENDPOINT = "endpoint";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_CORRELATION_ID, correlationId);
        MDC.put(MDC_ENDPOINT, request.getMethod() + " " + request.getRequestURI());

        log.info("AI-ADAM API request: {} {} from {}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr());

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (ex != null) {
            log.warn("AI-ADAM API request failed: {} {} — {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
        } else {
            log.info("AI-ADAM API response: {} {} — status {}",
                    request.getMethod(), request.getRequestURI(), response.getStatus());
        }
        MDC.remove(MDC_CORRELATION_ID);
        MDC.remove(MDC_ENDPOINT);
    }
}

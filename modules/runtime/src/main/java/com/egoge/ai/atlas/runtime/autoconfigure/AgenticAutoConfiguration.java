/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.autoconfigure;

import com.egoge.ai.atlas.runtime.json.AgentSafeModule;
import com.egoge.ai.atlas.runtime.mcp.AgenticMcpConfiguration;
import com.egoge.ai.atlas.runtime.security.DtoResponseBodyAdvice;
import com.egoge.ai.atlas.runtime.security.PiiAuditInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Boot auto-configuration for the AI-ATLAS framework.
 *
 * <p>Activates when Spring Web is on the classpath. Registers:
 * <ul>
 *   <li>MCP tool auto-discovery via {@link AgenticMcpConfiguration}</li>
 *   <li>PII audit interceptor for generated REST endpoints</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AgenticProperties.class)
@Import(AgenticMcpConfiguration.class)
public class AgenticAutoConfiguration implements WebMvcConfigurer {

    private final AgenticProperties properties;

    public AgenticAutoConfiguration(AgenticProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.atlas.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PiiAuditInterceptor piiAuditInterceptor() {
        return new PiiAuditInterceptor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.atlas.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DtoResponseBodyAdvice dtoResponseBodyAdvice() {
        return new DtoResponseBodyAdvice();
    }

    /**
     * Registers the Hibernate-safe Jackson serialization module for
     * {@code @AgentVisibleClass}-annotated entities.
     */
    @Bean
    @ConditionalOnClass(name = "com.fasterxml.jackson.databind.ObjectMapper")
    public AgentSafeModule agentSafeModule() {
        AgenticProperties.Json json = properties.getJson();
        return new AgentSafeModule(
                json.isEnriched(),
                json.isIncludeDescriptions(),
                json.isIncludeValidValues()
        );
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (properties.getAudit().isEnabled()) {
            registry.addInterceptor(piiAuditInterceptor())
                    .addPathPatterns("/api/v1/**");
        }
    }
}

/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.runtime.autoconfigure;

import ai.adam.runtime.mcp.AgenticMcpConfiguration;
import ai.adam.runtime.security.DtoResponseBodyAdvice;
import ai.adam.runtime.security.PiiAuditInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Boot auto-configuration for the AI-ADAM framework.
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
    @ConditionalOnProperty(prefix = "ai.adam.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PiiAuditInterceptor piiAuditInterceptor() {
        return new PiiAuditInterceptor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.adam.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DtoResponseBodyAdvice dtoResponseBodyAdvice() {
        return new DtoResponseBodyAdvice();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (properties.getAudit().isEnabled()) {
            registry.addInterceptor(piiAuditInterceptor())
                    .addPathPatterns("/api/v1/**");
        }
    }
}

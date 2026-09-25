/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Two-way coverage between the demo's generated OpenAPI document and the generated REST
 * controllers actually mapped in the running application, plus a call to every documented
 * operation made exactly as the document describes it (FR-007b).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiRuntimeConsistencyTest {

    private static final String SPEC_RESOURCE = "/META-INF/openapi/openapi-v2.json";
    private static final String GENERATED_PACKAGE_SUFFIX = ".generated";
    private static final String CONTROLLER_SUFFIX = "RestController";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyGeneratedMappingIsDocumentedAndEveryOperationIsMapped() throws IOException {
        Set<String> mapped = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            Class<?> beanType = entry.getValue().getBeanType();
            if (!beanType.getPackageName().endsWith(GENERATED_PACKAGE_SUFFIX)
                    || !beanType.getSimpleName().endsWith(CONTROLLER_SUFFIX)) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            for (var method : info.getMethodsCondition().getMethods()) {
                for (String path : info.getPathPatternsCondition().getPatternValues()) {
                    mapped.add(method.name() + " " + path);
                }
            }
        }

        assertThat(mapped).isNotEmpty();
        assertThat(mapped).isEqualTo(documentedOperations(readSpec()).keySet());
    }

    @Test
    void everyDocumentedOperationSucceedsAsDocumented() throws Exception {
        Map<String, JsonNode> operations = documentedOperations(readSpec());
        assertThat(operations).isNotEmpty();

        for (Map.Entry<String, JsonNode> entry : operations.entrySet()) {
            String[] mapping = entry.getKey().split(" ", 2);
            JsonNode operation = entry.getValue();
            MockHttpServletRequestBuilder call = request(HttpMethod.valueOf(mapping[0]), mapping[1]);
            for (JsonNode param : operation.path("parameters")) {
                assertThat(param.path("in").asText()).as(entry.getKey()).isEqualTo("query");
                call.param(param.path("name").asText(), sampleValue(param.path("schema")));
            }
            assertThat(operation.has("requestBody")).as(entry.getKey()).isFalse();

            MvcResult result = mockMvc.perform(call).andReturn();

            JsonNode responses = operation.path("responses");
            assertThat(responses.has("200")).as(entry.getKey()).isTrue();
            assertThat(result.getResponse().getStatus()).as(entry.getKey()).isEqualTo(200);
            JsonNode content = responses.path("200").path("content");
            if (!content.isEmpty()) {
                MediaType actual = MediaType.parseMediaType(result.getResponse().getContentType());
                assertThat(content.properties()).as(entry.getKey())
                        .anySatisfy(media -> assertThat(MediaType.parseMediaType(media.getKey())
                                .isCompatibleWith(actual)).isTrue());
            }
        }
    }

    /** "HTTP-method path" → operation, for every operation in the document. */
    private static Map<String, JsonNode> documentedOperations(JsonNode spec) {
        Map<String, JsonNode> operations = new TreeMap<>();
        spec.path("paths").properties().forEach(path -> path.getValue().properties().forEach(
                op -> operations.put(op.getKey().toUpperCase() + " " + path.getKey(), op.getValue())));
        return operations;
    }

    private static String sampleValue(JsonNode schema) {
        return switch (schema.path("type").asText()) {
            case "integer", "number" -> "1";
            case "boolean" -> "true";
            default -> "PENDING";
        };
    }

    private static JsonNode readSpec() throws IOException {
        try (InputStream in = OpenApiRuntimeConsistencyTest.class.getResourceAsStream(SPEC_RESOURCE)) {
            assertThat(in).as(SPEC_RESOURCE + " on the classpath").isNotNull();
            return new ObjectMapper().readTree(in);
        }
    }
}

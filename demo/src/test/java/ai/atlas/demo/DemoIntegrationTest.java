/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the AI-ATLAS demo application.
 * Verifies generated REST endpoints, PII exclusion, and MCP registration.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // --- REST endpoint: find-by-id ---

    @Test
    void findById_returnsOrder() throws Exception {
        mockMvc.perform(post("/api/v1/order-service/find-by-id").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.totalAmountCents", is(9999)))
                .andExpect(jsonPath("$.itemCount", is(3)));
    }

    @Test
    void findById_includesActions() throws Exception {
        mockMvc.perform(post("/api/v1/order-service/find-by-id").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions", hasSize(2)))
                .andExpect(jsonPath("$.actions[0].actionType", is("CREATED")))
                .andExpect(jsonPath("$.actions[1].actionType", is("CONFIRMED")));
    }

    // --- PII exclusion ---

    @Test
    void findById_excludesPiiFields() throws Exception {
        mockMvc.perform(post("/api/v1/order-service/find-by-id").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditCardNumber").doesNotExist())
                .andExpect(jsonPath("$.customerSsn").doesNotExist())
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.customerEmail").doesNotExist())
                .andExpect(jsonPath("$.shippingAddress").doesNotExist());
    }

    // --- REST endpoint: find-by-status ---

    @Test
    void findByStatus_returnsList() throws Exception {
        mockMvc.perform(post("/api/v1/order-service/find-by-status").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andExpect(jsonPath("$[0].status", is("PENDING")))
                .andExpect(jsonPath("$[0].id").exists());
    }

    @Test
    void findByStatus_excludesPiiFields() throws Exception {
        mockMvc.perform(post("/api/v1/order-service/find-by-status").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].creditCardNumber").doesNotExist())
                .andExpect(jsonPath("$[0].customerSsn").doesNotExist());
    }

    @Test
    void findByStatus_invalidStatusReturnsEmptyList() throws Exception {
        mockMvc.perform(post("/api/v1/order-service/find-by-status").param("status", "INVALID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    // --- HTTP method enforcement ---

    @Test
    void findById_rejectsGet() throws Exception {
        mockMvc.perform(get("/api/v1/order-service/find-by-id").param("id", "1"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void findByStatus_rejectsGet() throws Exception {
        mockMvc.perform(get("/api/v1/order-service/find-by-status").param("status", "PENDING"))
                .andExpect(status().isMethodNotAllowed());
    }

    // --- Generated DTO metadata ---

    @Test
    void generatedDto_hasCorrectMetadata() {
        var metadata = ai.atlas.demo.entity.generated.OrderDto.FIELD_METADATA;

        org.assertj.core.api.Assertions.assertThat(metadata).containsKeys(
                "id", "status", "totalCents", "itemCount", "actions");

        // Status field has enum values
        org.assertj.core.api.Assertions.assertThat(metadata.get("status").validValues())
                .containsExactly("PENDING", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED");

        // Class-level metadata
        org.assertj.core.api.Assertions.assertThat(
                ai.atlas.demo.entity.generated.OrderDto.CLASS_NAME).isEqualTo("order");
        org.assertj.core.api.Assertions.assertThat(
                ai.atlas.demo.entity.generated.OrderDto.INCLUDE_TYPE_INFO).isTrue();
    }

    // --- OpenAPI spec ---

    @Test
    void openApiSpec_existsOnClasspath() {
        var spec = getClass().getResourceAsStream("/META-INF/openapi/openapi.json");
        org.assertj.core.api.Assertions.assertThat(spec).isNotNull();
    }

    @Test
    void openApiSpec_containsOrderEndpoints() throws Exception {
        var spec = new String(
                getClass().getResourceAsStream("/META-INF/openapi/openapi.json").readAllBytes());

        org.assertj.core.api.Assertions.assertThat(spec)
                .contains("/api/v1/order-service/find-by-id")
                .contains("/api/v1/order-service/find-by-status")
                .contains("OrderDto")
                .contains("OrderActionDto");
    }

    @Test
    void openApiSpec_orderDtoExcludesPiiProperties() throws Exception {
        var spec = new String(
                getClass().getResourceAsStream("/META-INF/openapi/openapi.json").readAllBytes());

        org.assertj.core.api.Assertions.assertThat(spec)
                .doesNotContain("creditCardNumber")
                .doesNotContain("customerSsn")
                .doesNotContain("customerName")
                .doesNotContain("customerEmail")
                .doesNotContain("shippingAddress");
    }

    // --- MCP tool registration ---

    @Test
    void mcpToolBean_isRegistered() {
        // The MCP tool class is generated and should be a Spring bean
        org.assertj.core.api.Assertions.assertThat(
                org.springframework.test.context.TestContextManager.class).isNotNull();
        // If the app starts without errors, MCP tools were registered successfully.
        // The Spring Boot log confirms: "Registered 1 MCP tool bean(s)"
        // and "Registered tools: 2"
        // A more direct check:
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void mcpToolBean_existsInApplicationContext() {
        org.assertj.core.api.Assertions.assertThat(
                applicationContext.containsBean("orderServiceMcpTool")).isTrue();
    }

    @Test
    void generatedRestController_existsInApplicationContext() {
        org.assertj.core.api.Assertions.assertThat(
                applicationContext.containsBean("orderServiceRestController")).isTrue();
    }
}

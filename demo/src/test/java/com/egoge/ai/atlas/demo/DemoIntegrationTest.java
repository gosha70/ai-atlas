/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the AI-ATLAS demo application.
 * Verifies generated REST endpoints, PII exclusion, versioning,
 * deprecation headers, and version negotiation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    // --- REST endpoint: find-by-id (deprecated since v1) ---

    @Test
    void findById_returnsOrder() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-id").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.totalAmountCents", is(9999)))
                .andExpect(jsonPath("$.itemCount", is(3)));
    }

    @Test
    void findById_includesActions() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-id").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions", hasSize(2)))
                .andExpect(jsonPath("$.actions[0].actionType", is("CREATED")))
                .andExpect(jsonPath("$.actions[1].actionType", is("CONFIRMED")));
    }

    // --- PII exclusion ---

    @Test
    void findById_excludesPiiFields() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-id").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditCardNumber").doesNotExist())
                .andExpect(jsonPath("$.customerSsn").doesNotExist())
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.customerEmail").doesNotExist())
                .andExpect(jsonPath("$.shippingAddress").doesNotExist());
    }

    // --- REST endpoint: find-by-id-v2 (since v2) ---

    @Test
    void findByIdV2_returnsOrder() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-id-v2").param("id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(42)))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.totalMajorUnits", is(99)))
                .andExpect(jsonPath("$.totalMinorUnits", is(99)));
    }

    // --- REST endpoint: find-by-status ---

    @Test
    void findByStatus_returnsList() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-status").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andExpect(jsonPath("$[0].status", is("PENDING")))
                .andExpect(jsonPath("$[0].id").exists());
    }

    @Test
    void findByStatus_excludesPiiFields() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-status").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].creditCardNumber").doesNotExist())
                .andExpect(jsonPath("$[0].customerSsn").doesNotExist());
    }

    @Test
    void findByStatus_invalidStatusReturnsEmptyList() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-status").param("status", "INVALID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    // --- Future method: find-by-priority (apiSince=3, not generated for v2) ---

    @Test
    void findByPriority_notExposedInV2() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-priority").param("priority", "HIGH"))
                .andExpect(status().isNotFound());
    }

    // --- HTTP method enforcement ---

    @Test
    void findById_rejectsGet() throws Exception {
        mockMvc.perform(get("/api/v2/order-service/find-by-id").param("id", "1"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void findByStatus_rejectsGet() throws Exception {
        mockMvc.perform(get("/api/v2/order-service/find-by-status").param("status", "PENDING"))
                .andExpect(status().isMethodNotAllowed());
    }

    // --- Field versioning: v2 fields present, removed fields absent ---

    @Test
    void orderDto_includesV2Fields() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-id-v2").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMajorUnits", is(99)))
                .andExpect(jsonPath("$.totalMinorUnits", is(99)));
    }

    @Test
    void orderDto_excludesRemovedFields() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-id-v2").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legacyNotes").doesNotExist());
    }

    @Test
    void addressDto_includesCountryInV2() throws Exception {
        mockMvc.perform(get("/api/v2/customer-service/get-customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].addresses[0].country", is("US")));
    }

    // --- Generated DTO metadata ---

    @Test
    void generatedDto_hasCorrectMetadata() {
        var metadata = com.egoge.ai.atlas.demo.entity.generated.OrderDto.FIELD_METADATA;

        assertThat(metadata).containsKeys(
                "id", "status", "totalCents", "itemCount", "actions",
                "totalMajorUnits", "totalMinorUnits");

        // legacyNotes removed in v2 — should not be in metadata
        assertThat(metadata).doesNotContainKey("legacyNotes");

        // Status field has enum values
        assertThat(metadata.get("status").validValues())
                .containsExactly("PENDING", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED");

        // totalCents is deprecated in v2
        assertThat(metadata.get("totalCents").deprecated()).isTrue();
        assertThat(metadata.get("totalCents").deprecatedMessage())
                .isEqualTo("Use totalMajorUnits and totalMinorUnits instead");

        // v2 fields are not deprecated
        assertThat(metadata.get("totalMajorUnits").deprecated()).isFalse();

        // Class-level metadata
        assertThat(com.egoge.ai.atlas.demo.entity.generated.OrderDto.CLASS_NAME)
                .isEqualTo("order");
        assertThat(com.egoge.ai.atlas.demo.entity.generated.OrderDto.INCLUDE_TYPE_INFO)
                .isTrue();
    }

    // --- OpenAPI spec ---

    @Test
    void openApiSpec_versionedFileExistsOnClasspath() {
        var spec = getClass().getResourceAsStream("/META-INF/openapi/openapi-v2.json");
        assertThat(spec).as("openapi-v2.json (primary versioned artifact)").isNotNull();
    }

    @Test
    void openApiSpec_aliasExistsAndMatchesVersioned() throws Exception {
        var versioned = new String(
                getClass().getResourceAsStream("/META-INF/openapi/openapi-v2.json").readAllBytes());
        var alias = new String(
                getClass().getResourceAsStream("/META-INF/openapi/openapi.json").readAllBytes());
        assertThat(alias).as("openapi.json alias matches openapi-v2.json").isEqualTo(versioned);
    }

    @Test
    void openApiSpec_containsV2Endpoints() throws Exception {
        var spec = new String(
                getClass().getResourceAsStream("/META-INF/openapi/openapi-v2.json").readAllBytes());

        assertThat(spec)
                .contains("/api/v2/order-service/find-by-id")
                .contains("/api/v2/order-service/find-by-id-v2")
                .contains("/api/v2/order-service/find-by-status")
                .contains("OrderDto")
                .contains("OrderActionDto")
                .doesNotContain("find-by-priority");
    }

    @Test
    void openApiSpec_orderDtoExcludesPiiProperties() throws Exception {
        var spec = new String(
                getClass().getResourceAsStream("/META-INF/openapi/openapi-v2.json").readAllBytes());

        assertThat(spec)
                .doesNotContain("creditCardNumber")
                .doesNotContain("customerSsn")
                .doesNotContain("customerName")
                .doesNotContain("customerEmail")
                .doesNotContain("shippingAddress");
    }

    // --- MCP tool registration ---

    @Test
    void mcpToolBean_existsInApplicationContext() {
        assertThat(applicationContext.containsBean("orderServiceMcpTool")).isTrue();
    }

    @Test
    void generatedRestController_existsInApplicationContext() {
        assertThat(applicationContext.containsBean("orderServiceRestController")).isTrue();
    }

    // --- CustomerService: method-level @AgenticExposed ---

    @Test
    void getCustomers_returnsCustomerList() throws Exception {
        mockMvc.perform(get("/api/v2/customer-service/get-customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(1)))
                .andExpect(jsonPath("$[0].name", is("Alice")))
                .andExpect(jsonPath("$[1].id", is(2)))
                .andExpect(jsonPath("$[1].name", is("Bob")));
    }

    @Test
    void getCustomersV2_returnsCustomerList() throws Exception {
        mockMvc.perform(get("/api/v2/customer-service/get-customers-v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(1)))
                .andExpect(jsonPath("$[0].name", is("Alice")));
    }

    @Test
    void getCustomers_addressesMappedToDtoType() throws Exception {
        mockMvc.perform(get("/api/v2/customer-service/get-customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].addresses", hasSize(2)))
                .andExpect(jsonPath("$[0].addresses[0].street", is("123 Main St")))
                .andExpect(jsonPath("$[0].addresses[0].city", is("Springfield")))
                .andExpect(jsonPath("$[0].addresses[0].state", is("IL")))
                .andExpect(jsonPath("$[0].addresses[0].zipCode", is("62701")));
    }

    @Test
    void getCustomers_excludesPiiFields() throws Exception {
        mockMvc.perform(get("/api/v2/customer-service/get-customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].creditCardNumber").doesNotExist())
                .andExpect(jsonPath("$[0].ssn").doesNotExist());
    }

    @Test
    void getCustomersWithCreditInfo_notExposed() throws Exception {
        mockMvc.perform(get("/api/v2/customer-service/get-customers-with-credit-info"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v2/customer-service/get-customers-with-credit-info"))
                .andExpect(status().isNotFound());
    }

    // --- CustomerService: generated beans and metadata ---

    @Test
    void customerServiceBeans_existInApplicationContext() {
        // CustomerService uses channels={API} so no MCP tool is generated
        assertThat(applicationContext.containsBean("customerServiceMcpTool")).isFalse();
        assertThat(applicationContext.containsBean("customerServiceRestController")).isTrue();
    }

    @Test
    void customerDto_hasCorrectMetadata() {
        var metadata = com.egoge.ai.atlas.demo.entity.generated.CustomerDto.FIELD_METADATA;

        assertThat(metadata).containsKeys("id", "name", "addresses");
        assertThat(metadata).doesNotContainKeys("email", "creditCardNumber", "ssn");

        assertThat(com.egoge.ai.atlas.demo.entity.generated.CustomerDto.CLASS_NAME)
                .isEqualTo("customer");
    }

    // --- Deprecation headers (Phase 4) ---

    @Test
    void deprecatedEndpoint_returnsDeprecationHeaders() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-id").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().exists("Link"));
    }

    @Test
    void nonDeprecatedEndpoint_noDeprecationHeaders() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-status").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Deprecation"));
    }

    @Test
    void deprecatedCustomerEndpoint_returnsDeprecationHeaders() throws Exception {
        mockMvc.perform(get("/api/v2/customer-service/get-customers"))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"));
    }

    @Test
    void newCustomerEndpoint_noDeprecationHeaders() throws Exception {
        mockMvc.perform(get("/api/v2/customer-service/get-customers-v2"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Deprecation"));
    }

    // --- Version negotiation (Phase 4) ---

    @Test
    void acceptVersion_matchingVersion_succeeds() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-status")
                        .header("Accept-Version", "2")
                        .param("status", "PENDING"))
                .andExpect(status().isOk());
    }

    @Test
    void acceptVersion_mismatch_returns400() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-status")
                        .header("Accept-Version", "1")
                        .param("status", "PENDING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptVersion_invalid_returns400() throws Exception {
        mockMvc.perform(post("/api/v2/order-service/find-by-status")
                        .header("Accept-Version", "abc")
                        .param("status", "PENDING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptVersion_absent_succeeds() throws Exception {
        // No Accept-Version header → pass through
        mockMvc.perform(post("/api/v2/order-service/find-by-status")
                        .param("status", "PENDING"))
                .andExpect(status().isOk());
    }

    // --- Deprecation manifest ---

    @Test
    void deprecationManifest_existsOnClasspath() {
        var manifest = getClass().getResourceAsStream(
                "/META-INF/ai-atlas/deprecation-manifest.json");
        assertThat(manifest).isNotNull();
    }

    // --- API version properties ---

    @Test
    void apiVersionProperties_existsOnClasspath() throws Exception {
        var props = getClass().getResourceAsStream(
                "/META-INF/ai-atlas/api-version.properties");
        assertThat(props).isNotNull();
        String content = new String(props.readAllBytes());
        assertThat(content).contains("api.major=2");
    }
}

package com.example.atlas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the AI-ATLAS sample application.
 * Verifies the published plugin wires everything correctly:
 * generated endpoints, field versioning, deprecation headers,
 * version negotiation, and generated resource artifacts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SampleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // --- Endpoints ---

    @Test
    void findById_returnsProduct() throws Exception {
        mockMvc.perform(post("/api/v2/product-service/find-by-id").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Widget 1")))
                .andExpect(jsonPath("$.category", is("electronics")));
    }

    @Test
    void findByIdV2_returnsProduct() throws Exception {
        mockMvc.perform(post("/api/v2/product-service/find-by-id-v2").param("id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(42)))
                .andExpect(jsonPath("$.name", is("Widget 42")));
    }

    @Test
    void listAll_returnsProducts() throws Exception {
        mockMvc.perform(get("/api/v2/product-service/list-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(1)))
                .andExpect(jsonPath("$[1].id", is(2)));
    }

    @Test
    void findByTag_notExposedInV2() throws Exception {
        mockMvc.perform(post("/api/v2/product-service/find-by-tag").param("tag", "sale"))
                .andExpect(status().isNotFound());
    }

    // --- Field versioning ---

    @Test
    void productDto_includesV2Fields() throws Exception {
        mockMvc.perform(post("/api/v2/product-service/find-by-id-v2").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceMajor", is(19)))
                .andExpect(jsonPath("$.priceMinor", is(99)));
    }

    @Test
    void productDto_excludesRemovedFields() throws Exception {
        mockMvc.perform(post("/api/v2/product-service/find-by-id-v2").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legacySku").doesNotExist());
    }

    @Test
    void productDto_excludesPiiFields() throws Exception {
        mockMvc.perform(post("/api/v2/product-service/find-by-id-v2").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierContact").doesNotExist())
                .andExpect(jsonPath("$.internalCostBreakdown").doesNotExist());
    }

    @Test
    void productDto_deprecationMetadata() {
        var metadata = com.example.atlas.entity.generated.ProductDto.FIELD_METADATA;
        assertThat(metadata.get("priceCents").deprecated()).isTrue();
        assertThat(metadata.get("priceCents").deprecatedMessage())
                .isEqualTo("Use priceMajor and priceMinor instead");
        assertThat(metadata.get("priceMajor").deprecated()).isFalse();
        assertThat(metadata).doesNotContainKey("legacySku");
    }

    // --- Deprecation headers ---

    @Test
    void deprecatedEndpoint_returnsDeprecationHeaders() throws Exception {
        mockMvc.perform(post("/api/v2/product-service/find-by-id").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().exists("Link"));
    }

    @Test
    void nonDeprecatedEndpoint_noDeprecationHeaders() throws Exception {
        mockMvc.perform(get("/api/v2/product-service/list-all"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Deprecation"));
    }

    // --- Version negotiation ---

    @Test
    void acceptVersion_matching_succeeds() throws Exception {
        mockMvc.perform(get("/api/v2/product-service/list-all")
                        .header("Accept-Version", "2"))
                .andExpect(status().isOk());
    }

    @Test
    void acceptVersion_mismatch_returns400() throws Exception {
        mockMvc.perform(get("/api/v2/product-service/list-all")
                        .header("Accept-Version", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptVersion_absent_succeeds() throws Exception {
        mockMvc.perform(get("/api/v2/product-service/list-all"))
                .andExpect(status().isOk());
    }

    // --- Generated resource artifacts ---

    @Test
    void openApiSpec_versionedFileExists() {
        var spec = getClass().getResourceAsStream("/META-INF/openapi/openapi-v2.json");
        assertThat(spec).as("openapi-v2.json").isNotNull();
    }

    @Test
    void openApiSpec_aliasMatchesVersioned() throws Exception {
        var versioned = new String(
                getClass().getResourceAsStream("/META-INF/openapi/openapi-v2.json").readAllBytes());
        var alias = new String(
                getClass().getResourceAsStream("/META-INF/openapi/openapi.json").readAllBytes());
        assertThat(alias).isEqualTo(versioned);
    }

    @Test
    void openApiSpec_containsV2Endpoints() throws Exception {
        var spec = new String(
                getClass().getResourceAsStream("/META-INF/openapi/openapi-v2.json").readAllBytes());
        assertThat(spec)
                .contains("/api/v2/product-service/find-by-id")
                .contains("/api/v2/product-service/find-by-id-v2")
                .contains("/api/v2/product-service/list-all")
                .doesNotContain("find-by-tag");
    }

    @Test
    void apiVersionProperties_exists() throws Exception {
        var props = getClass().getResourceAsStream(
                "/META-INF/ai-atlas/api-version.properties");
        assertThat(props).isNotNull();
        String content = new String(props.readAllBytes());
        assertThat(content).contains("api.major=2");
    }

    @Test
    void deprecationManifest_exists() {
        var manifest = getClass().getResourceAsStream(
                "/META-INF/ai-atlas/deprecation-manifest.json");
        assertThat(manifest).isNotNull();
    }
}

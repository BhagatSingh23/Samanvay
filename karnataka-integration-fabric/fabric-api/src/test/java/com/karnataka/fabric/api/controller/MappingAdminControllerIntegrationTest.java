package com.karnataka.fabric.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.mapping.MappingRepository;
import com.karnataka.fabric.adapters.mapping.SchemaMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link MappingAdminController}.
 *
 * <p>Runs against H2 in-memory with test profile. Seed data for
 * FACTORIES and SHOP_ESTAB is loaded via {@code test-schema.sql}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MappingAdminControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MappingRepository mappingRepository;

    @BeforeEach
    void sanityCheck() {
        // Seed data from test-schema.sql should give us at least 2 mappings
        long count = mappingRepository.count();
        assert count >= 2 : "Expected at least 2 seed mappings, got " + count;
    }

    // ── GET /api/v1/mappings?deptId=... ──────────────────────────

    @Test
    @DisplayName("GET mappings for FACTORIES returns seeded mapping")
    void listFactoriesMappings() throws Exception {
        mockMvc.perform(get("/api/v1/mappings")
                        .param("deptId", "FACTORIES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].deptId", is("FACTORIES")))
                .andExpect(jsonPath("$[0].serviceType", is("ADDRESS_CHANGE")))
                .andExpect(jsonPath("$[0].active", is(true)))
                .andExpect(jsonPath("$[0].mappingRules.fields", hasSize(7)))
                .andExpect(jsonPath("$[0].mappingRules.fields[0].canonicalField",
                        is("registeredAddress.line1")))
                .andExpect(jsonPath("$[0].mappingRules.fields[0].targetField",
                        is("addr_line_1")))
                .andExpect(jsonPath("$[0].mappingRules.fields[0].transform",
                        is("UPPERCASE")));
    }

    @Test
    @DisplayName("GET mappings for SHOP_ESTAB returns seeded mapping with CONCAT_ADDRESS_LINES")
    void listShopEstabMappings() throws Exception {
        mockMvc.perform(get("/api/v1/mappings")
                        .param("deptId", "SHOP_ESTAB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].deptId", is("SHOP_ESTAB")))
                .andExpect(jsonPath("$[0].mappingRules.fields[?(@.transform == 'CONCAT_ADDRESS_LINES')]",
                        hasSize(1)));
    }

    @Test
    @DisplayName("GET mappings with serviceType filter narrows results")
    void listWithServiceTypeFilter() throws Exception {
        mockMvc.perform(get("/api/v1/mappings")
                        .param("deptId", "FACTORIES")
                        .param("serviceType", "ADDRESS_CHANGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].serviceType", is("ADDRESS_CHANGE")));
    }

    @Test
    @DisplayName("GET mappings for unknown dept returns empty list")
    void listUnknownDept() throws Exception {
        mockMvc.perform(get("/api/v1/mappings")
                        .param("deptId", "NONEXISTENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET mappings without deptId returns 400")
    void listWithoutDeptId() throws Exception {
        mockMvc.perform(get("/api/v1/mappings"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/v1/mappings ───────────────────────────────────

    @Test
    @DisplayName("POST creates new mapping version and returns 201")
    void createMapping() throws Exception {
        String body = """
                {
                    "deptId": "REVENUE",
                    "serviceType": "LICENSE_RENEWAL",
                    "mappingRules": {
                        "fields": [
                            {"canonicalField": "licenseNumber", "targetField": "lic_no", "transform": "NONE"},
                            {"canonicalField": "expiryDate", "targetField": "exp_date", "transform": "DATE_ISO_TO_EPOCH"}
                        ]
                    }
                }
                """;

        mockMvc.perform(post("/api/v1/mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mappingId").exists())
                .andExpect(jsonPath("$.deptId", is("REVENUE")))
                .andExpect(jsonPath("$.serviceType", is("LICENSE_RENEWAL")))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.mappingRules.fields", hasSize(2)))
                .andExpect(jsonPath("$.mappingRules.fields[1].transform",
                        is("DATE_ISO_TO_EPOCH")));
    }

    @Test
    @DisplayName("POST with missing deptId returns 400")
    void createMappingMissingDeptId() throws Exception {
        String body = """
                {
                    "serviceType": "ADDRESS_CHANGE",
                    "mappingRules": {"fields": []}
                }
                """;

        mockMvc.perform(post("/api/v1/mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /api/v1/mappings/{id} ───────────────────────────────

    @Test
    @DisplayName("PUT updates mapping rules and returns 200")
    void updateMapping() throws Exception {
        // First, create a mapping to update
        SchemaMapping mapping = new SchemaMapping(
                "TEST_DEPT", "TEST_SVC", 1, true,
                "{\"fields\":[{\"canonicalField\":\"name\",\"targetField\":\"nm\",\"transform\":\"NONE\"}]}");
        mapping = mappingRepository.save(mapping);

        String updateBody = """
                {
                    "mappingRules": {
                        "fields": [
                            {"canonicalField": "name", "targetField": "full_name", "transform": "UPPERCASE"}
                        ]
                    }
                }
                """;

        mockMvc.perform(put("/api/v1/mappings/" + mapping.getMappingId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mappingRules.fields[0].targetField",
                        is("full_name")))
                .andExpect(jsonPath("$.mappingRules.fields[0].transform",
                        is("UPPERCASE")));
    }

    @Test
    @DisplayName("PUT deactivates mapping via active=false")
    void deactivateMapping() throws Exception {
        SchemaMapping mapping = new SchemaMapping(
                "DEACT_DEPT", "DEACT_SVC", 1, true,
                "{\"fields\":[]}");
        mapping = mappingRepository.save(mapping);

        mockMvc.perform(put("/api/v1/mappings/" + mapping.getMappingId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)));
    }

    @Test
    @DisplayName("PUT with unknown ID returns 404")
    void updateUnknownMapping() throws Exception {
        mockMvc.perform(put("/api/v1/mappings/00000000-0000-0000-0000-000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/v1/mappings/{id} ───────────────────────────────

    @Test
    @DisplayName("GET by ID returns the correct mapping")
    void getById() throws Exception {
        SchemaMapping mapping = new SchemaMapping(
                "GET_DEPT", "GET_SVC", 1, true,
                "{\"fields\":[{\"canonicalField\":\"a\",\"targetField\":\"b\",\"transform\":\"NONE\"}]}");
        mapping = mappingRepository.save(mapping);

        mockMvc.perform(get("/api/v1/mappings/" + mapping.getMappingId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deptId", is("GET_DEPT")))
                .andExpect(jsonPath("$.mappingRules.fields", hasSize(1)));
    }

    @Test
    @DisplayName("GET by unknown ID returns 404")
    void getByUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/mappings/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }
}

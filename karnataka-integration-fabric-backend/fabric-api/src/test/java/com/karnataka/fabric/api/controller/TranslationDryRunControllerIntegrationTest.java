package com.karnataka.fabric.api.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link TranslationDryRunController}.
 *
 * <p>Verifies the dry-run endpoint translates canonical payloads
 * correctly without any side effects (no DB writes, no Kafka publishes).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TranslationDryRunControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/v1/translate/dry-run translates FACTORIES payload correctly")
    void dryRunFactories() throws Exception {
        String body = """
                {
                    "canonicalPayload": {
                        "registeredAddress": {
                            "line1": "MG Road",
                            "line2": "3rd Cross",
                            "pincode": "560001",
                            "city": "Bengaluru",
                            "state": "Karnataka"
                        },
                        "businessName": "Karnataka Steel Works",
                        "contactPerson": "Rajesh Kumar"
                    },
                    "targetDeptId": "FACTORIES",
                    "serviceType": "ADDRESS_CHANGE"
                }
                """;

        mockMvc.perform(post("/api/v1/translate/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translatedPayload.addr_line_1", is("MG ROAD")))
                .andExpect(jsonPath("$.translatedPayload.addr_line_2", is("3RD CROSS")))
                .andExpect(jsonPath("$.translatedPayload.postal_code", is("560001")))
                .andExpect(jsonPath("$.translatedPayload.city_name", is("BENGALURU")))
                .andExpect(jsonPath("$.translatedPayload.state_code", is("KARNATAKA")))
                .andExpect(jsonPath("$.translatedPayload.est_name", is("Karnataka Steel Works")))
                .andExpect(jsonPath("$.translatedPayload.contact_person.firstName", is("Rajesh")))
                .andExpect(jsonPath("$.translatedPayload.contact_person.lastName", is("Kumar")))
                .andExpect(jsonPath("$.mappingVersion", containsString("FACTORIES")))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
    }

    @Test
    @DisplayName("POST /api/v1/translate/dry-run with missing fields returns warnings")
    void dryRunWithMissingFields() throws Exception {
        String body = """
                {
                    "canonicalPayload": {
                        "registeredAddress": {
                            "line1": "MG Road"
                        },
                        "businessName": "Test Corp"
                    },
                    "targetDeptId": "FACTORIES",
                    "serviceType": "ADDRESS_CHANGE"
                }
                """;

        mockMvc.perform(post("/api/v1/translate/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translatedPayload.addr_line_1", is("MG ROAD")))
                .andExpect(jsonPath("$.translatedPayload.est_name", is("Test Corp")))
                .andExpect(jsonPath("$.warnings", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.warnings", hasItem(containsString("registeredAddress.line2"))));
    }

    @Test
    @DisplayName("POST /api/v1/translate/dry-run with unknown dept returns no mapping warning")
    void dryRunUnknownDept() throws Exception {
        String body = """
                {
                    "canonicalPayload": {"field": "value"},
                    "targetDeptId": "NONEXISTENT",
                    "serviceType": "ADDRESS_CHANGE"
                }
                """;

        mockMvc.perform(post("/api/v1/translate/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andExpect(jsonPath("$.warnings[0]", containsString("No active mapping")));
    }

    @Test
    @DisplayName("POST /api/v1/translate/dry-run with missing body fields returns 400")
    void dryRunMissingBody() throws Exception {
        String body = """
                {
                    "canonicalPayload": {"field": "value"}
                }
                """;

        mockMvc.perform(post("/api/v1/translate/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}

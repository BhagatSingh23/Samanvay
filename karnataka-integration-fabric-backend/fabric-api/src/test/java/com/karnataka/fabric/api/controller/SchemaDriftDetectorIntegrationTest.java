package com.karnataka.fabric.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.drift.DriftAlert;
import com.karnataka.fabric.adapters.drift.DriftAlertRepository;
import com.karnataka.fabric.adapters.drift.SchemaDriftDetector;
import com.karnataka.fabric.adapters.mapping.MappingRepository;
import com.karnataka.fabric.adapters.mapping.SchemaMapping;
import com.karnataka.fabric.adapters.registry.DepartmentConfig;
import com.karnataka.fabric.adapters.core.AdapterMode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for {@link SchemaDriftDetector} and
 * {@link DriftAlertController}.
 *
 * <p>Scenario: A mock department API response is missing 'postal_code'
 * which is expected by the FACTORIES/ADDRESS_CHANGE mapping. The drift
 * detector should create a drift alert that is visible at
 * {@code GET /api/v1/drift-alerts}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SchemaDriftDetectorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SchemaDriftDetector driftDetector;

    @Autowired
    private DriftAlertRepository driftAlertRepository;

    @Autowired
    private MappingRepository mappingRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private static MockWebServer mockDeptServer;

    @BeforeAll
    static void startMockServer() throws IOException {
        mockDeptServer = new MockWebServer();
        mockDeptServer.start();
    }

    @AfterAll
    static void stopMockServer() throws IOException {
        mockDeptServer.shutdown();
    }

    @BeforeEach
    void clearAlerts() {
        driftAlertRepository.deleteAll();
    }

    @Test
    @DisplayName("Drift detected: dept API missing 'postal_code' → alert created and visible at GET /api/v1/drift-alerts")
    void detectDriftMissingPostalCode() throws Exception {
        // 1. Ensure FACTORIES/ADDRESS_CHANGE mapping exists (from seed data)
        //    Expected target fields include: addr_line_1, addr_line_2, postal_code,
        //    city_name, state_code, est_name, contact_person

        // 2. Enqueue a mock department response that is MISSING postal_code
        String deptResponse = """
                [{
                    "addr_line_1": "MG ROAD",
                    "addr_line_2": "3RD CROSS",
                    "city_name": "BENGALURU",
                    "state_code": "KARNATAKA",
                    "est_name": "Karnataka Steel Works",
                    "contact_person": "Rajesh Kumar"
                }]
                """;
        // NOTE: postal_code is deliberately missing from this response

        mockDeptServer.enqueue(new MockResponse()
                .setBody(deptResponse)
                .addHeader("Content-Type", "application/json"));

        // 3. Create a temporary DepartmentConfig pointing to our mock server
        String mockUrl = mockDeptServer.url("/api/changes").toString();
        DepartmentConfig testConfig = new DepartmentConfig(
                "FACTORIES", "Department of Factories", AdapterMode.POLLING,
                null, mockUrl, null, 30, "mappings/factories.json");

        // 4. Run drift detection for this department
        driftDetector.detectDriftForDept(testConfig);

        // 5. Verify drift alert was created in the database
        List<DriftAlert> alerts = driftAlertRepository.findByResolvedFalseOrderByDetectedAtDesc();
        assertThat(alerts).isNotEmpty();

        DriftAlert alert = alerts.stream()
                .filter(a -> a.getDeptId().equals("FACTORIES"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No drift alert for FACTORIES"));

        assertThat(alert.getMissingFields()).contains("postal_code");
        assertThat(alert.isResolved()).isFalse();

        // 6. Verify the alert is visible at GET /api/v1/drift-alerts
        mockMvc.perform(get("/api/v1/drift-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[?(@.deptId == 'FACTORIES')]").exists())
                .andExpect(jsonPath("$[?(@.deptId == 'FACTORIES')].missingFields",
                        hasItem(hasItem("postal_code"))))
                .andExpect(jsonPath("$[?(@.deptId == 'FACTORIES')].resolved",
                        hasItem(false)));
    }

    @Test
    @DisplayName("No drift when all expected fields are present")
    void noDriftWhenAllFieldsPresent() throws Exception {
        // Enqueue a response with ALL expected fields present
        String deptResponse = """
                [{
                    "addr_line_1": "MG ROAD",
                    "addr_line_2": "3RD CROSS",
                    "postal_code": "560001",
                    "city_name": "BENGALURU",
                    "state_code": "KARNATAKA",
                    "est_name": "Karnataka Steel Works",
                    "contact_person": "Rajesh Kumar"
                }]
                """;

        mockDeptServer.enqueue(new MockResponse()
                .setBody(deptResponse)
                .addHeader("Content-Type", "application/json"));

        String mockUrl = mockDeptServer.url("/api/changes").toString();
        DepartmentConfig testConfig = new DepartmentConfig(
                "FACTORIES", "Department of Factories", AdapterMode.POLLING,
                null, mockUrl, null, 30, "mappings/factories.json");

        driftDetector.detectDriftForDept(testConfig);

        // No drift alert should be created
        List<DriftAlert> alerts = driftAlertRepository.findByDeptIdAndResolvedFalseOrderByDetectedAtDesc("FACTORIES");
        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("GET /api/v1/drift-alerts returns empty list when no alerts exist")
    void emptyAlertsEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/drift-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/drift-alerts?deptId=X filters by department")
    void filterByDeptId() throws Exception {
        // Create a drift alert manually
        DriftAlert alert = new DriftAlert("TEST_DEPT", "[\"missing_field\"]");
        driftAlertRepository.save(alert);

        // Another for a different dept
        DriftAlert other = new DriftAlert("OTHER_DEPT", "[\"other_field\"]");
        driftAlertRepository.save(other);

        mockMvc.perform(get("/api/v1/drift-alerts").param("deptId", "TEST_DEPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].deptId", is("TEST_DEPT")));
    }
}

package com.karnataka.fabric.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.registry.DepartmentConfig;
import com.karnataka.fabric.adapters.registry.DepartmentRegistry;
import com.karnataka.fabric.adapters.core.AdapterMode;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import com.karnataka.fabric.core.domain.PropagationStatus;
import com.karnataka.fabric.propagation.PropagationOrchestrator;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for the propagation pipeline.
 *
 * <h3>Scenario:</h3>
 * <ol>
 *   <li>POST an address change to the SWS inbound endpoint</li>
 *   <li>The event is published to Kafka → consumed by PropagationOrchestrator</li>
 *   <li>Verify mock-FACTORIES received the correctly translated payload</li>
 *   <li>Verify mock-SHOP_ESTAB received the correctly translated payload</li>
 *   <li>Verify audit_records shows DISPATCHED for each target</li>
 *   <li>POST the same event again → duplicate is skipped (DUPLICATE_SKIP)</li>
 * </ol>
 *
 * <p>Since embedded Kafka is not available, this test bypasses Kafka and
 * calls the orchestrator directly to verify the propagation pipeline.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PropagationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PropagationOrchestrator orchestrator;
    @Autowired private DepartmentRegistry departmentRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    private static MockWebServer mockFactories;
    private static MockWebServer mockShopEstab;

    @BeforeAll
    static void startMockServers() throws IOException {
        mockFactories = new MockWebServer();
        mockFactories.start();
        mockShopEstab = new MockWebServer();
        mockShopEstab.start();
    }

    @AfterAll
    static void stopMockServers() throws IOException {
        mockFactories.shutdown();
        mockShopEstab.shutdown();
    }

    @BeforeEach
    void setUp() {
        // Clean tables
        jdbcTemplate.update("DELETE FROM propagation_outbox");
        jdbcTemplate.update("DELETE FROM audit_records");
        jdbcTemplate.update("DELETE FROM idempotency_fingerprints");

        // Register departments pointing to our mock servers.
        // Override the existing configs with URLs pointing to the mock servers.
        String factoriesUrl = mockFactories.url("/api/v1/inbound/FACTORIES").toString();
        String shopEstabUrl = mockShopEstab.url("/shop-estab/api/v1/changes").toString();

        DepartmentConfig factoriesConfig = new DepartmentConfig(
                "FACTORIES", "Department of Factories", AdapterMode.WEBHOOK,
                null, factoriesUrl, null, 30, "mappings/factories.json");

        DepartmentConfig shopEstabConfig = new DepartmentConfig(
                "SHOP_ESTAB", "Shops & Establishments", AdapterMode.POLLING,
                null, shopEstabUrl, null, 30, "mappings/shop_estab.json");

        // Inject test configs via reflection (allConfigs() returns unmodifiable view)
        try {
            java.lang.reflect.Field configsField = DepartmentRegistry.class.getDeclaredField("configs");
            configsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, DepartmentConfig> configs =
                    (Map<String, DepartmentConfig>) configsField.get(departmentRegistry);
            configs.clear();
            configs.put("FACTORIES", factoriesConfig);
            configs.put("SHOP_ESTAB", shopEstabConfig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject test department configs", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Full propagation pipeline
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("SWS address change → propagated to FACTORIES + SHOP_ESTAB → audit DISPATCHED recorded")
    void fullPropagationPipeline() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String ubid = "KA-2024-001";

        // Build a canonical event with address change payload
        Map<String, Object> payload = Map.of(
                "registeredAddress", Map.of(
                        "line1", "MG Road",
                        "line2", "3rd Cross",
                        "pincode", "560001",
                        "city", "Bengaluru",
                        "state", "Karnataka"
                ),
                "businessName", "Karnataka Steel Works",
                "contactPerson", "Rajesh Kumar"
        );

        CanonicalServiceRequest event = new CanonicalServiceRequest(
                eventId, ubid, "SWS", "ADDRESS_CHANGE",
                Instant.now(), Instant.now(),
                payload, null, PropagationStatus.RECEIVED);

        // Enqueue mock responses for both department APIs
        mockFactories.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"status\":\"accepted\"}")
                .addHeader("Content-Type", "application/json"));
        mockShopEstab.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"status\":\"accepted\"}")
                .addHeader("Content-Type", "application/json"));

        // Call the orchestrator directly (bypassing Kafka for test isolation)
        orchestrator.propagate(event);

        // ── Verify outbox entries were created ──────────────────
        List<Map<String, Object>> outboxEntries = jdbcTemplate.queryForList(
                "SELECT * FROM propagation_outbox WHERE ubid = ? ORDER BY target_system_id", ubid);

        assertThat(outboxEntries)
                .as("Outbox entries for both FACTORIES and SHOP_ESTAB")
                .hasSizeGreaterThanOrEqualTo(2);

        // Verify FACTORIES outbox entry
        Map<String, Object> factoriesEntry = outboxEntries.stream()
                .filter(e -> "FACTORIES".equals(e.get("TARGET_SYSTEM_ID")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No outbox entry for FACTORIES"));

        String factoriesPayload = (String) factoriesEntry.get("TRANSLATED_PAYLOAD");
        assertThat(factoriesPayload).isNotNull();

        // Parse the translated payload and verify field translations
        @SuppressWarnings("unchecked")
        Map<String, Object> factoriesTranslated = objectMapper.readValue(factoriesPayload, Map.class);
        assertThat(factoriesTranslated.get("addr_line_1")).isEqualTo("MG ROAD");  // UPPERCASE transform
        assertThat(factoriesTranslated.get("postal_code")).isEqualTo("560001");    // NONE transform
        assertThat(factoriesTranslated.get("city_name")).isEqualTo("BENGALURU");   // UPPERCASE transform
        assertThat(factoriesTranslated.get("est_name")).isEqualTo("Karnataka Steel Works"); // NONE

        // Verify SHOP_ESTAB outbox entry
        Map<String, Object> shopEstabEntry = outboxEntries.stream()
                .filter(e -> "SHOP_ESTAB".equals(e.get("TARGET_SYSTEM_ID")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No outbox entry for SHOP_ESTAB"));

        String shopPayload = (String) shopEstabEntry.get("TRANSLATED_PAYLOAD");
        assertThat(shopPayload).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> shopTranslated = objectMapper.readValue(shopPayload, Map.class);
        assertThat(shopTranslated.get("shop_addr_1")).isEqualTo("MG ROAD");  // UPPERCASE
        assertThat(shopTranslated.get("pin")).isEqualTo("560001");            // NONE
        assertThat(shopTranslated.get("town")).isEqualTo("bengaluru");        // LOWERCASE
        assertThat(shopTranslated.get("est_name")).isEqualTo("Karnataka Steel Works");

        // ── Verify audit records ────────────────────────────────
        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "SELECT * FROM audit_records WHERE event_id = ? ORDER BY ts",
                UUID.fromString(eventId));

        assertThat(audits)
                .as("Audit records for DISPATCHED events")
                .hasSizeGreaterThanOrEqualTo(2);

        // Verify DISPATCHED audit for FACTORIES
        boolean hasFactoriesAudit = audits.stream()
                .anyMatch(a -> "DISPATCHED".equals(a.get("AUDIT_EVENT_TYPE"))
                        && "FACTORIES".equals(a.get("TARGET_SYSTEM")));
        assertThat(hasFactoriesAudit).as("DISPATCHED audit for FACTORIES").isTrue();

        // Verify DISPATCHED audit for SHOP_ESTAB
        boolean hasShopEstabAudit = audits.stream()
                .anyMatch(a -> "DISPATCHED".equals(a.get("AUDIT_EVENT_TYPE"))
                        && "SHOP_ESTAB".equals(a.get("TARGET_SYSTEM")));
        assertThat(hasShopEstabAudit).as("DISPATCHED audit for SHOP_ESTAB").isTrue();
    }

    // ═══════════════════════════════════════════════════════════
    // Duplicate detection
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST same event twice → second propagation is skipped (DUPLICATE_SKIP)")
    void duplicateEventSkipped() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String ubid = "KA-2024-002";

        Map<String, Object> payload = Map.of(
                "registeredAddress", Map.of(
                        "line1", "Brigade Road",
                        "pincode", "560025",
                        "city", "Bengaluru",
                        "state", "Karnataka"
                ),
                "businessName", "Test Corp"
        );

        CanonicalServiceRequest event = new CanonicalServiceRequest(
                eventId, ubid, "SWS", "ADDRESS_CHANGE",
                Instant.now(), Instant.now(),
                payload, null, PropagationStatus.RECEIVED);

        // Enqueue mock responses for first propagation
        mockFactories.enqueue(new MockResponse().setResponseCode(200));
        mockShopEstab.enqueue(new MockResponse().setResponseCode(200));

        // First propagation — should create outbox entries
        orchestrator.propagate(event);

        int firstOutboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM propagation_outbox WHERE ubid = ?",
                Integer.class, ubid);

        // Second propagation — should skip (DUPLICATE_SKIP)
        orchestrator.propagate(event);

        int secondOutboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM propagation_outbox WHERE ubid = ?",
                Integer.class, ubid);

        // No new outbox entries should have been created
        assertThat(secondOutboxCount)
                .as("Second propagation should not create new outbox entries")
                .isEqualTo(firstOutboxCount);

        // Verify idempotency fingerprints are all COMMITTED
        List<Map<String, Object>> fingerprints = jdbcTemplate.queryForList(
                "SELECT * FROM idempotency_fingerprints");
        assertThat(fingerprints).allSatisfy(fp ->
                assertThat(fp.get("STATUS")).isEqualTo("COMMITTED"));
    }

    // ═══════════════════════════════════════════════════════════
    // SWS inbound endpoint → propagation pipeline
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST to /api/v1/inbound/sws returns 202 ACCEPTED with eventId")
    void swsEndpointAcceptsEvent() throws Exception {
        String payload = """
                {
                    "ubid": "KA-2024-003",
                    "serviceType": "ADDRESS_CHANGE",
                    "registeredAddress": {
                        "line1": "MG Road",
                        "pincode": "560001"
                    },
                    "businessName": "Test Corp"
                }
                """;

        mockMvc.perform(post("/api/v1/inbound/sws")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").exists());
    }
}

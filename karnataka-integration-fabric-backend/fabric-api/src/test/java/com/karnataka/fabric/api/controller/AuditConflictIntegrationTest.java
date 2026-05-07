package com.karnataka.fabric.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.service.AuditService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for the audit + conflict resolution lifecycle.
 *
 * <h3>Scenario:</h3>
 * <ol>
 *   <li>Insert 3 events for the same UBID into {@code event_ledger}</li>
 *   <li>Record audit entries (RECEIVED) for all 3 events</li>
 *   <li>Create a conflict between event1 and event2</li>
 *   <li>Record CONFLICT_DETECTED audit for event1 and event2</li>
 *   <li>Resolve the conflict (event1 wins)</li>
 *   <li>Record CONFLICT_RESOLVED audit for both events</li>
 *   <li>Call {@code GET /api/v1/audit/ubid/{ubid}} and verify all
 *       audit records are present in correct order with correct types</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditConflictIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuditService auditService;
    @Autowired private ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private static final String TEST_UBID = "KA-AUDIT-TEST-001";

    private String eventId1;
    private String eventId2;
    private String eventId3;
    private UUID conflictId;

    @BeforeEach
    void setUp() {
        // Clean all relevant tables
        jdbcTemplate.update("DELETE FROM audit_records");
        jdbcTemplate.update("DELETE FROM conflict_records");
        jdbcTemplate.update("DELETE FROM event_ledger");
        jdbcTemplate.update("DELETE FROM propagation_outbox");

        // Generate event IDs
        eventId1 = UUID.randomUUID().toString();
        eventId2 = UUID.randomUUID().toString();
        eventId3 = UUID.randomUUID().toString();
        conflictId = UUID.randomUUID();
    }

    // ═══════════════════════════════════════════════════════════
    // Full audit + conflict lifecycle
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("3 events + conflict + resolution → GET /audit/ubid returns all 5+ audit records in order")
    void auditConflictLifecycle() throws Exception {

        Instant baseTime = Instant.parse("2024-07-15T10:00:00Z");

        // ── Step 1: Insert 3 events into event_ledger ──────────
        insertEvent(eventId1, TEST_UBID, "SWS", "ADDRESS_CHANGE",
                baseTime, baseTime.plusSeconds(1));
        insertEvent(eventId2, TEST_UBID, "DEPT_FACTORIES", "ADDRESS_CHANGE",
                baseTime.plusSeconds(5), baseTime.plusSeconds(6));
        insertEvent(eventId3, TEST_UBID, "SWS", "SIGNATORY_UPDATE",
                baseTime.plusSeconds(10), baseTime.plusSeconds(11));

        // ── Step 2: Record RECEIVED audit for all 3 events ─────
        auditService.recordAudit(eventId1, TEST_UBID, "SWS", null,
                AuditEventType.RECEIVED, null, null);

        // Small delay to ensure ordering
        Thread.sleep(10);

        auditService.recordAudit(eventId2, TEST_UBID, "DEPT_FACTORIES", null,
                AuditEventType.RECEIVED, null, null);

        Thread.sleep(10);

        auditService.recordAudit(eventId3, TEST_UBID, "SWS", null,
                AuditEventType.RECEIVED, null, null);

        Thread.sleep(10);

        // ── Step 3: Create a conflict between event1 and event2 ─
        jdbcTemplate.update("""
                INSERT INTO conflict_records
                    (conflict_id, ubid, event1_id, event2_id,
                     resolution_policy, winning_event_id, resolved_at, field_in_dispute)
                VALUES (?, ?, ?, ?, 'SOURCE_PRIORITY', NULL, NULL, 'registeredAddress.line1')
                """,
                conflictId, TEST_UBID,
                UUID.fromString(eventId1), UUID.fromString(eventId2));

        // ── Step 4: Record CONFLICT_DETECTED for both events ───
        auditService.recordAudit(eventId1, TEST_UBID, "SWS", null,
                AuditEventType.CONFLICT_DETECTED,
                Map.of("conflictingEventId", eventId2),
                Map.of("policy", "SOURCE_PRIORITY", "fieldInDispute", "registeredAddress.line1"));

        Thread.sleep(10);

        auditService.recordAudit(eventId2, TEST_UBID, "DEPT_FACTORIES", null,
                AuditEventType.CONFLICT_DETECTED,
                Map.of("conflictingEventId", eventId1),
                Map.of("policy", "SOURCE_PRIORITY", "fieldInDispute", "registeredAddress.line1"));

        Thread.sleep(10);

        // ── Step 5: Resolve the conflict → event1 wins ─────────
        // Update conflict record
        jdbcTemplate.update("""
                UPDATE conflict_records
                SET winning_event_id = ?, resolved_at = ?
                WHERE conflict_id = ?
                """,
                UUID.fromString(eventId1),
                Timestamp.from(Instant.now()),
                conflictId);

        // Mark event2 as SUPERSEDED
        jdbcTemplate.update(
                "UPDATE event_ledger SET status = 'SUPERSEDED' WHERE event_id = ?",
                UUID.fromString(eventId2));

        // ── Step 6: Record CONFLICT_RESOLVED for both events ───
        auditService.recordAudit(eventId1, TEST_UBID, "SWS", null,
                AuditEventType.CONFLICT_RESOLVED,
                null,
                Map.of("policy", "SOURCE_PRIORITY", "winningEventId", eventId1));

        Thread.sleep(10);

        auditService.recordAudit(eventId2, TEST_UBID, "DEPT_FACTORIES", null,
                AuditEventType.CONFLICT_RESOLVED,
                null,
                Map.of("policy", "SOURCE_PRIORITY", "winningEventId", eventId1));

        // ── Step 7: GET /api/v1/audit/ubid/{ubid} ──────────────
        MvcResult result = mockMvc.perform(get("/api/v1/audit/ubid/" + TEST_UBID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ubid").value(TEST_UBID))
                .andExpect(jsonPath("$.events").isArray())
                .andReturn();

        // Parse response
        String body = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.get("events");

        // ── Verify: at least 7 audit records ───────────────────
        // 3 RECEIVED + 2 CONFLICT_DETECTED + 2 CONFLICT_RESOLVED = 7
        assertThat(events)
                .as("Should have at least 7 audit records (3 RECEIVED + 2 DETECTED + 2 RESOLVED)")
                .hasSizeGreaterThanOrEqualTo(7);

        // ── Verify: correct event types are present ────────────
        List<String> auditTypes = events.stream()
                .map(e -> (String) e.get("auditEventType"))
                .toList();

        // Count occurrences
        long receivedCount = auditTypes.stream()
                .filter("RECEIVED"::equals).count();
        long detectedCount = auditTypes.stream()
                .filter("CONFLICT_DETECTED"::equals).count();
        long resolvedCount = auditTypes.stream()
                .filter("CONFLICT_RESOLVED"::equals).count();

        assertThat(receivedCount)
                .as("3 RECEIVED audit records")
                .isEqualTo(3);
        assertThat(detectedCount)
                .as("2 CONFLICT_DETECTED audit records")
                .isEqualTo(2);
        assertThat(resolvedCount)
                .as("2 CONFLICT_RESOLVED audit records")
                .isEqualTo(2);

        // ── Verify: ordering is ASC by timestamp ───────────────
        for (int i = 1; i < events.size(); i++) {
            String prevTs = (String) events.get(i - 1).get("timestamp");
            String currTs = (String) events.get(i).get("timestamp");
            if (prevTs != null && currTs != null) {
                assertThat(Instant.parse(prevTs))
                        .as("Audit records should be ordered ASC by timestamp")
                        .isBeforeOrEqualTo(Instant.parse(currTs));
            }
        }

        // ── Verify: RECEIVED events come before CONFLICT events ──
        int firstReceivedIdx = -1;
        int lastReceivedIdx = -1;
        int firstConflictIdx = -1;

        for (int i = 0; i < events.size(); i++) {
            String type = (String) events.get(i).get("auditEventType");
            if ("RECEIVED".equals(type)) {
                if (firstReceivedIdx == -1) firstReceivedIdx = i;
                lastReceivedIdx = i;
            }
            if ("CONFLICT_DETECTED".equals(type) && firstConflictIdx == -1) {
                firstConflictIdx = i;
            }
        }

        assertThat(firstConflictIdx)
                .as("CONFLICT_DETECTED should appear after RECEIVED events")
                .isGreaterThan(lastReceivedIdx);
    }

    // ═══════════════════════════════════════════════════════════
    // GET /api/v1/audit/event/{eventId}
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /audit/event/{eventId} returns full lifecycle of one event")
    void auditByEventId() throws Exception {

        insertEvent(eventId1, TEST_UBID, "SWS", "ADDRESS_CHANGE",
                Instant.now(), Instant.now());

        auditService.recordAudit(eventId1, TEST_UBID, "SWS", null,
                AuditEventType.RECEIVED, null, null);
        Thread.sleep(10);
        auditService.recordAudit(eventId1, TEST_UBID, "SWS", "FACTORIES",
                AuditEventType.DISPATCHED, null, Map.of("target", "FACTORIES"));

        MvcResult result = mockMvc.perform(get("/api/v1/audit/event/" + eventId1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId1))
                .andExpect(jsonPath("$.events").isArray())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.get("events");

        assertThat(events).hasSizeGreaterThanOrEqualTo(2);

        List<String> types = events.stream()
                .map(e -> (String) e.get("auditEventType"))
                .toList();

        assertThat(types).contains("RECEIVED", "DISPATCHED");
    }

    // ═══════════════════════════════════════════════════════════
    // GET /api/v1/conflicts
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /conflicts?resolved=false returns unresolved conflicts with event summaries")
    void unresolvedConflicts() throws Exception {

        insertEvent(eventId1, TEST_UBID, "SWS", "OWNERSHIP_CHANGE",
                Instant.now(), Instant.now());
        insertEvent(eventId2, TEST_UBID, "DEPT_FACTORIES", "OWNERSHIP_CHANGE",
                Instant.now(), Instant.now());

        // Insert unresolved conflict
        jdbcTemplate.update("""
                INSERT INTO conflict_records
                    (conflict_id, ubid, event1_id, event2_id,
                     resolution_policy, winning_event_id, resolved_at, field_in_dispute)
                VALUES (?, ?, ?, ?, 'HOLD_FOR_REVIEW', NULL, NULL, 'ownerName')
                """,
                conflictId, TEST_UBID,
                UUID.fromString(eventId1), UUID.fromString(eventId2));

        MvcResult result = mockMvc.perform(
                        get("/api/v1/conflicts")
                                .param("resolved", "false")
                                .param("page", "0")
                                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");

        assertThat(content).isNotEmpty();

        Map<String, Object> conflict = content.get(0);
        assertThat(conflict.get("ubid")).isEqualTo(TEST_UBID);
        assertThat(conflict.get("winningEventId")).isNull();
        assertThat(conflict.get("resolutionPolicy")).isEqualTo("HOLD_FOR_REVIEW");
        assertThat(conflict.get("event1Summary")).isNotNull();
        assertThat(conflict.get("event2Summary")).isNotNull();
    }

    // ═══════════════════════════════════════════════════════════
    // POST /api/v1/audit/replay (dry run)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /audit/replay with dryRun=true returns events without writing")
    void replayDryRun() throws Exception {

        Instant baseTime = Instant.parse("2024-01-01T00:00:00Z");
        insertEvent(eventId1, TEST_UBID, "SWS", "ADDRESS_CHANGE",
                baseTime, baseTime);
        insertEvent(eventId2, TEST_UBID, "SWS", "ADDRESS_CHANGE",
                baseTime.plusSeconds(60), baseTime.plusSeconds(60));

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "ubid", TEST_UBID,
                "fromTimestamp", baseTime.toString(),
                "dryRun", true
        ));

        MvcResult result = mockMvc.perform(post("/api/v1/audit/replay")
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ubid").value(TEST_UBID))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.eventsFound").value(2))
                .andReturn();

        // Verify no outbox entries were created (dry run)
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM propagation_outbox WHERE ubid = ?",
                Integer.class, TEST_UBID);
        assertThat(outboxCount).isZero();
    }

    // ═══════════════════════════════════════════════════════════
    // Audit with time window filter
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /audit/ubid/{ubid}?from=ISO&to=ISO filters by time window")
    void auditWithTimeWindowFilter() throws Exception {

        insertEvent(eventId1, TEST_UBID, "SWS", "ADDRESS_CHANGE",
                Instant.now(), Instant.now());

        Instant before = Instant.now().minusSeconds(1);

        auditService.recordAudit(eventId1, TEST_UBID, "SWS", null,
                AuditEventType.RECEIVED, null, null);

        Thread.sleep(50);
        Instant after = Instant.now().plusSeconds(1);

        MvcResult result = mockMvc.perform(
                        get("/api/v1/audit/ubid/" + TEST_UBID)
                                .param("from", before.toString())
                                .param("to", after.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ubid").value(TEST_UBID))
                .andExpect(jsonPath("$.events").isArray())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.get("events");

        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("auditEventType")).isEqualTo("RECEIVED");
    }

    // ── Test helpers ───────────────────────────────────────────

    /**
     * Inserts a test event directly into the event_ledger table.
     */
    private void insertEvent(String eventId, String ubid, String sourceSystemId,
                              String serviceType, Instant eventTimestamp,
                              Instant ingestionTimestamp) {
        jdbcTemplate.update("""
                INSERT INTO event_ledger
                    (event_id, ubid, source_system_id, service_type,
                     event_timestamp, ingestion_timestamp, payload, status)
                VALUES (?, ?, ?, ?, ?, ?, '{}', 'RECEIVED')
                """,
                UUID.fromString(eventId),
                ubid,
                sourceSystemId,
                serviceType,
                Timestamp.from(eventTimestamp),
                Timestamp.from(ingestionTimestamp));
    }
}

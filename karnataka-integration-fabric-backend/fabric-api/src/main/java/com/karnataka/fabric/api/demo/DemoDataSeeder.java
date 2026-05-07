package com.karnataka.fabric.api.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Seeds the database with synthetic demo data when the {@code demo} profile
 * is active.
 *
 * <h3>Scenario data</h3>
 * <ul>
 *   <li><b>Scene 1:</b> KA-2024-001 — Address change via SWS.
 *       FACTORIES + SHOP_ESTAB are UP (200).</li>
 *   <li><b>Scene 2:</b> KA-2024-002 — Signatory change via FACTORIES polling.
 *       Mock polling endpoint has one new record.</li>
 *   <li><b>Scene 3:</b> KA-2024-003 — Two simultaneous ADDRESS_CHANGE events
 *       from SWS and DEPT_FACTORIES. Conflict policy = SOURCE_PRIORITY (SWS wins).</li>
 *   <li><b>Scene 4:</b> KA-2024-004 — DLQ demo.
 *       FACTORIES returns 503 for this UBID for 5 attempts.</li>
 * </ul>
 *
 * <p>Also seeds 50 synthetic businesses (KA-2024-001 through KA-2024-050),
 * each registered in both FACTORIES and SHOP_ESTAB.</p>
 */
@Component
@Profile("demo")
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** UBIDs that get 503 from FACTORIES (for DLQ demo) */
    public static final Set<String> DLQ_UBIDS = Set.of("KA-2024-004");

    /** How many times FACTORIES should 503 before recovering */
    public static final int DLQ_FAILURE_COUNT = 5;

    public DemoDataSeeder(JdbcTemplate jdbcTemplate,
                           AuditService auditService,
                           ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("╔══════════════════════════════════════════════════════");
        log.info("║  DEMO PROFILE ACTIVE — Seeding demo data …");
        log.info("╚══════════════════════════════════════════════════════");
        seedAll();
        log.info("✓ Demo data seeding complete.");
    }

    // ── Public so DemoResetController can re-invoke ──────────

    @Transactional
    public void seedAll() {
        seedBusinesses();
        seedScene1_AddressChange();
        seedScene2_SignatoryPolling();
        seedScene3_ConflictWindow();
        seedScene4_DLQ();
        seedHistoricalAuditTrail();
    }

    // ════════════════════════════════════════════════════════════
    // 50 synthetic businesses
    // ════════════════════════════════════════════════════════════

    private void seedBusinesses() {
        log.info("  Seeding 50 synthetic businesses …");

        for (int i = 1; i <= 50; i++) {
            String ubid = String.format("KA-2024-%03d", i);
            String bizName = BUSINESS_NAMES[i % BUSINESS_NAMES.length];

            // Event in event_ledger marking this UBID as known
            String eventId = UUID.randomUUID().toString();
            Instant ts = Instant.now().minusSeconds(86400L * (50 - i));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("businessName", bizName);
            payload.put("ubid", ubid);
            payload.put("registeredAddress", Map.of(
                    "line1", STREET_NAMES[i % STREET_NAMES.length],
                    "city", CITIES[i % CITIES.length],
                    "pincode", String.format("56%04d", 1 + i),
                    "state", "Karnataka"
            ));
            payload.put("contactPerson", PERSON_NAMES[i % PERSON_NAMES.length]);
            payload.put("registeredSystems", List.of("FACTORIES", "SHOP_ESTAB"));

            insertEvent(eventId, ubid, "SWS", "REGISTRATION",
                    ts, ts, payload, "DELIVERED");
        }

        log.info("  ✓ 50 businesses seeded (KA-2024-001 … KA-2024-050)");
    }

    // ════════════════════════════════════════════════════════════
    // Scene 1: Address change via SWS — happy path
    // ════════════════════════════════════════════════════════════

    private void seedScene1_AddressChange() {
        log.info("  Seeding Scene 1: KA-2024-001 — Address Change via SWS");

        // The UBID already exists from the business seed.
        // We insert a "ready" event that represents the current state,
        // so the demo can fire a new ADDRESS_CHANGE and see it propagate.

        String eventId = UUID.randomUUID().toString();
        Instant ts = Instant.now().minusSeconds(600);

        Map<String, Object> payload = Map.of(
                "registeredAddress", Map.of(
                        "line1", "42 MG Road",
                        "line2", "Near Town Hall",
                        "pincode", "560001",
                        "city", "Bengaluru",
                        "state", "Karnataka"
                ),
                "businessName", "Karnataka Steel Works Pvt Ltd",
                "contactPerson", "Rajesh Kumar"
        );

        insertEvent(eventId, "KA-2024-001", "SWS", "ADDRESS_CHANGE",
                ts, ts, payload, "DELIVERED");

        // Audit trail for the previous successful propagation
        auditService.recordAudit(eventId, "KA-2024-001", "SWS", null,
                AuditEventType.RECEIVED, null, payload);
        auditService.recordAudit(eventId, "KA-2024-001", "SWS", "FACTORIES",
                AuditEventType.DISPATCHED, null, Map.of("target", "FACTORIES"));
        auditService.recordAudit(eventId, "KA-2024-001", "SWS", "SHOP_ESTAB",
                AuditEventType.DISPATCHED, null, Map.of("target", "SHOP_ESTAB"));

        log.info("  ✓ Scene 1 ready — fire POST /api/v1/inbound/sws with UBID KA-2024-001");
    }

    // ════════════════════════════════════════════════════════════
    // Scene 2: Signatory change via FACTORIES polling
    // ════════════════════════════════════════════════════════════

    private void seedScene2_SignatoryPolling() {
        log.info("  Seeding Scene 2: KA-2024-002 — Signatory Change via Polling");

        String eventId = UUID.randomUUID().toString();
        Instant ts = Instant.now().minusSeconds(300);

        Map<String, Object> payload = Map.of(
                "signatoryName", "Arun Patel",
                "signatoryDesignation", "Managing Director",
                "effectiveDate", "2024-08-01"
        );

        // Insert a pending signatory event as if polling discovered it
        insertEvent(eventId, "KA-2024-002", "DEPT_FACTORIES", "SIGNATORY_UPDATE",
                ts, ts, payload, "RECEIVED");

        auditService.recordAudit(eventId, "KA-2024-002", "DEPT_FACTORIES", null,
                AuditEventType.RECEIVED, null, payload);

        log.info("  ✓ Scene 2 ready — SIGNATORY_UPDATE for KA-2024-002 awaiting propagation");
    }

    // ════════════════════════════════════════════════════════════
    // Scene 3: Conflict window — two simultaneous events
    // ════════════════════════════════════════════════════════════

    private void seedScene3_ConflictWindow() {
        log.info("  Seeding Scene 3: KA-2024-003 — Conflict (SOURCE_PRIORITY, SWS wins)");

        Instant ts = Instant.now().minusSeconds(120);

        // Event 1: from SWS
        String event1Id = UUID.randomUUID().toString();
        Map<String, Object> payload1 = Map.of(
                "registeredAddress", Map.of(
                        "line1", "88 Brigade Road",
                        "pincode", "560025",
                        "city", "Bengaluru"
                ),
                "businessName", "Deccan Enterprises"
        );
        insertEvent(event1Id, "KA-2024-003", "SWS", "ADDRESS_CHANGE",
                ts, ts, payload1, "CONFLICT_HELD");

        // Event 2: from DEPT_FACTORIES (arrives within conflict window)
        String event2Id = UUID.randomUUID().toString();
        Map<String, Object> payload2 = Map.of(
                "registeredAddress", Map.of(
                        "line1", "99 Commercial Street",
                        "pincode", "560001",
                        "city", "Bengaluru"
                ),
                "businessName", "Deccan Enterprises"
        );
        insertEvent(event2Id, "KA-2024-003", "DEPT_FACTORIES", "ADDRESS_CHANGE",
                ts.plusSeconds(2), ts.plusSeconds(2), payload2, "CONFLICT_HELD");

        // Insert conflict record (unresolved)
        UUID conflictId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO conflict_records
                    (conflict_id, ubid, event1_id, event2_id,
                     resolution_policy, winning_event_id, resolved_at, field_in_dispute)
                VALUES (?, ?, ?, ?, 'SOURCE_PRIORITY', NULL, NULL, 'registeredAddress.line1')
                """,
                conflictId, "KA-2024-003",
                UUID.fromString(event1Id), UUID.fromString(event2Id));

        // Audit entries for the conflict
        auditService.recordAudit(event1Id, "KA-2024-003", "SWS", null,
                AuditEventType.RECEIVED, null, payload1);
        auditService.recordAudit(event2Id, "KA-2024-003", "DEPT_FACTORIES", null,
                AuditEventType.RECEIVED, null, payload2);
        auditService.recordAudit(event1Id, "KA-2024-003", "SWS", null,
                AuditEventType.CONFLICT_DETECTED,
                Map.of("conflictingEventId", event2Id),
                Map.of("policy", "SOURCE_PRIORITY", "field", "registeredAddress.line1"));

        // Insert into conflict_hold_queue
        jdbcTemplate.update("""
                INSERT INTO conflict_hold_queue
                    (hold_id, conflict_id, event_id, ubid, service_type,
                     source_system_id, payload, status)
                VALUES (?, ?, ?, 'KA-2024-003', 'ADDRESS_CHANGE', 'SWS', ?, 'HELD')
                """,
                UUID.randomUUID(), conflictId, UUID.fromString(event1Id), toJson(payload1));

        jdbcTemplate.update("""
                INSERT INTO conflict_hold_queue
                    (hold_id, conflict_id, event_id, ubid, service_type,
                     source_system_id, payload, status)
                VALUES (?, ?, ?, 'KA-2024-003', 'ADDRESS_CHANGE', 'DEPT_FACTORIES', ?, 'HELD')
                """,
                UUID.randomUUID(), conflictId, UUID.fromString(event2Id), toJson(payload2));

        log.info("  ✓ Scene 3 ready — conflict between events {} and {} (unresolved)",
                event1Id.substring(0, 8), event2Id.substring(0, 8));
    }

    // ════════════════════════════════════════════════════════════
    // Scene 4: DLQ demo — FACTORIES returns 503
    // ════════════════════════════════════════════════════════════

    private void seedScene4_DLQ() {
        log.info("  Seeding Scene 4: KA-2024-004 — DLQ (FACTORIES returns 503)");

        String eventId = UUID.randomUUID().toString();
        Instant ts = Instant.now().minusSeconds(900);

        Map<String, Object> payload = Map.of(
                "registeredAddress", Map.of(
                        "line1", "12 Residency Road",
                        "pincode", "560025",
                        "city", "Bengaluru"
                ),
                "businessName", "Mysore Silks International"
        );

        insertEvent(eventId, "KA-2024-004", "SWS", "ADDRESS_CHANGE",
                ts, ts, payload, "FAILED");

        // Insert failed outbox entry (attempt 5 — exhausted)
        jdbcTemplate.update("""
                INSERT INTO propagation_outbox
                    (outbox_id, event_id, ubid, target_system_id,
                     translated_payload, status, attempt_count,
                     next_attempt_at, last_error, created_at)
                VALUES (?, ?, 'KA-2024-004', 'FACTORIES', ?, 'FAILED', 5, ?, ?, ?)
                """,
                UUID.randomUUID(), UUID.fromString(eventId),
                toJson(Map.of("addr_line_1", "12 RESIDENCY ROAD", "postal_code", "560025")),
                Timestamp.from(ts.plusSeconds(600)),
                "HTTP 503 Service Unavailable — FACTORIES API returned 503 after 5 attempts",
                Timestamp.from(ts));

        // Insert into dead_letter_queue
        jdbcTemplate.update("""
                INSERT INTO dead_letter_queue
                    (dlq_id, event_id, ubid, target_system_id,
                     translated_payload, failure_reason, parked_at, resolved)
                VALUES (?, ?, 'KA-2024-004', 'FACTORIES', ?,
                        'HTTP 503 Service Unavailable — FACTORIES API down. Exhausted 5/5 retries with exponential backoff.',
                        ?, false)
                """,
                UUID.randomUUID(), UUID.fromString(eventId),
                toJson(payload), Timestamp.from(ts.plusSeconds(600)));

        // Audit trail
        auditService.recordAudit(eventId, "KA-2024-004", "SWS", null,
                AuditEventType.RECEIVED, null, payload);
        auditService.recordAudit(eventId, "KA-2024-004", "SWS", "FACTORIES",
                AuditEventType.FAILED, null,
                Map.of("error", "HTTP 503", "attemptCount", 5));
        auditService.recordAudit(eventId, "KA-2024-004", "SWS", "FACTORIES",
                AuditEventType.DLQ_PARKED, null,
                Map.of("reason", "Exhausted 5/5 retries"));

        log.info("  ✓ Scene 4 ready — event {} dead-lettered for FACTORIES",
                eventId.substring(0, 8));
    }

    // ════════════════════════════════════════════════════════════
    // Historical audit trail (for realistic-looking timeline)
    // ════════════════════════════════════════════════════════════

    private void seedHistoricalAuditTrail() {
        log.info("  Seeding historical audit records for timeline richness …");

        // Create some successfully propagated events for other UBIDs
        for (int i = 5; i <= 15; i++) {
            String ubid = String.format("KA-2024-%03d", i);
            String eventId = UUID.randomUUID().toString();
            Instant ts = Instant.now().minusSeconds(3600L * (20 - i));

            Map<String, Object> payload = Map.of(
                    "registeredAddress", Map.of(
                            "line1", STREET_NAMES[i % STREET_NAMES.length],
                            "pincode", String.format("56%04d", i),
                            "city", CITIES[i % CITIES.length]
                    ),
                    "businessName", BUSINESS_NAMES[i % BUSINESS_NAMES.length]
            );

            insertEvent(eventId, ubid, "SWS", "ADDRESS_CHANGE", ts, ts, payload, "DELIVERED");

            auditService.recordAudit(eventId, ubid, "SWS", null,
                    AuditEventType.RECEIVED, null, payload);
            auditService.recordAudit(eventId, ubid, "SWS", "FACTORIES",
                    AuditEventType.DISPATCHED, null, null);
            auditService.recordAudit(eventId, ubid, "SWS", "SHOP_ESTAB",
                    AuditEventType.DISPATCHED, null, null);
            auditService.recordAudit(eventId, ubid, "SWS", "FACTORIES",
                    AuditEventType.CONFIRMED, null, null);
        }

        log.info("  ✓ Historical audit trail seeded");
    }

    // ════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════

    private void insertEvent(String eventId, String ubid, String sourceSystemId,
                              String serviceType, Instant eventTimestamp,
                              Instant ingestionTimestamp, Map<String, Object> payload,
                              String status) {
        jdbcTemplate.update("""
                INSERT INTO event_ledger
                    (event_id, ubid, source_system_id, service_type,
                     event_timestamp, ingestion_timestamp, payload, checksum, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.fromString(eventId), ubid, sourceSystemId, serviceType,
                Timestamp.from(eventTimestamp), Timestamp.from(ingestionTimestamp),
                toJson(payload),
                UUID.randomUUID().toString(), // dummy checksum
                status);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // ── Sample data ──────────────────────────────────────────

    private static final String[] BUSINESS_NAMES = {
            "Karnataka Steel Works Pvt Ltd", "Mysore Silks International",
            "Deccan Enterprises", "Bangalore Tech Solutions", "Mangalore Spices Export Co",
            "Hubli Manufacturing Ltd", "Shimoga Timber Industries",
            "Dharwad Agriculture Coop", "Belgaum Textiles Pvt Ltd",
            "Gulbarga Mining Corporation", "Bijapur Ceramics Ltd",
            "Raichur Power Gen", "Udupi Fisheries Pvt Ltd",
            "Chitradurga Granite Exports", "Tumkur Auto Components",
            "Mandya Sugar Mill Ltd", "Hassan Coffee Estates",
            "Kodagu Plantations Pvt Ltd", "Davangere Cotton Mills",
            "Bellary Iron Ore Pvt Ltd"
    };

    private static final String[] STREET_NAMES = {
            "42 MG Road", "88 Brigade Road", "12 Residency Road",
            "5 Commercial Street", "101 Cunningham Road", "33 Race Course Road",
            "77 Lavelle Road", "21 Infantry Road", "55 St Marks Road",
            "8 Palace Road", "16 Vittal Mallya Road", "90 Church Street",
            "45 Richmond Road", "3 Museum Road", "67 Kasturba Road"
    };

    private static final String[] CITIES = {
            "Bengaluru", "Mysuru", "Mangaluru", "Hubli",
            "Dharwad", "Belgaum", "Gulbarga", "Shimoga",
            "Tumkur", "Davangere"
    };

    private static final String[] PERSON_NAMES = {
            "Rajesh Kumar", "Priya Sharma", "Arun Patel", "Sunita Reddy",
            "Vikram Singh", "Meera Iyer", "Suresh Gowda", "Lakshmi Nair",
            "Ramesh Hegde", "Kavitha Murthy", "Deepak Shetty", "Anita Bhat"
    };
}

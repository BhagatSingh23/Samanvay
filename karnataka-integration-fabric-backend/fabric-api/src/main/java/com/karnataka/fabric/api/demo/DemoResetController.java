package com.karnataka.fabric.api.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo-only endpoint that clears all operational data and re-seeds
 * the database for a fresh demonstration.
 *
 * <p>Only active when the {@code demo} profile is enabled.
 * Allows rapid repeated demos without restarting the application.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * POST /api/v1/demo/reset
 * → 200 OK { "status": "RESET_COMPLETE", "timestamp": "…", "scenes": { … } }
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/demo")
@Profile("demo")
public class DemoResetController {

    private static final Logger log = LoggerFactory.getLogger(DemoResetController.class);

    private final JdbcTemplate jdbcTemplate;
    private final DemoDataSeeder seeder;
    private final StringRedisTemplate redisTemplate;

    public DemoResetController(JdbcTemplate jdbcTemplate, DemoDataSeeder seeder,
                                StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.seeder = seeder;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Clears all event_ledger, audit_records, conflict_records,
     * conflict_hold_queue, propagation_outbox, dead_letter_queue,
     * and idempotency_fingerprints, then re-seeds all demo data.
     *
     * @return confirmation with scene descriptions
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetDemo() {

        log.info("╔══════════════════════════════════════════════════════");
        log.info("║  DEMO RESET — Clearing all data and re-seeding …");
        log.info("╚══════════════════════════════════════════════════════");

        // ── 1. Clear all operational tables ──────────────────
        // Order matters: delete child rows first to avoid FK issues
        // (our schema has no FKs but being safe for future additions)
        String[] tables = {
                "dept_health_history",
                "notifications",
                "conflict_escalations",
                "dead_letter_queue",
                "propagation_outbox",
                "conflict_hold_queue",
                "conflict_records",
                "audit_records",
                "idempotency_fingerprints",
                "event_ledger"
        };

        for (String table : tables) {
            int deleted = jdbcTemplate.update("DELETE FROM " + table);
            log.info("  Cleared {} — {} row(s) deleted", table, deleted);
        }

        // ── 2. Invalidate Redis health cache ────────────────
        try {
            var keys = redisTemplate.keys("health:dept:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("  Cleared {} Redis health cache keys", keys.size());
            }
        } catch (Exception e) {
            log.warn("  Failed to clear Redis health cache: {}", e.getMessage());
        }

        // ── 3. Re-seed ──────────────────────────────────────
        seeder.seedAll();

        // ── 3. Build response ───────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "RESET_COMPLETE");
        result.put("timestamp", Instant.now().toString());
        result.put("scenes", Map.of(
                "scene1", Map.of(
                        "ubid", "KA-2024-001",
                        "description", "Address change via SWS → FACTORIES + SHOP_ESTAB (happy path)",
                        "trigger", "POST /api/v1/inbound/sws with ubid=KA-2024-001, serviceType=ADDRESS_CHANGE"
                ),
                "scene2", Map.of(
                        "ubid", "KA-2024-002",
                        "description", "Signatory update via FACTORIES polling",
                        "trigger", "Already seeded as RECEIVED — will propagate on next orchestrator cycle"
                ),
                "scene3", Map.of(
                        "ubid", "KA-2024-003",
                        "description", "Conflict detection (SOURCE_PRIORITY, SWS wins)",
                        "trigger", "POST /api/v1/conflicts/{conflictId}/resolve to manually resolve"
                ),
                "scene4", Map.of(
                        "ubid", "KA-2024-004",
                        "description", "Dead Letter Queue — FACTORIES returns 503",
                        "trigger", "POST /api/v1/dlq/{dlqId}/redispatch to retry"
                )
        ));

        // Count what we seeded
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_ledger", Integer.class);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_records", Integer.class);
        Integer conflictCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_records", Integer.class);
        Integer dlqCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dead_letter_queue", Integer.class);

        result.put("seeded", Map.of(
                "events", eventCount != null ? eventCount : 0,
                "auditRecords", auditCount != null ? auditCount : 0,
                "conflicts", conflictCount != null ? conflictCount : 0,
                "dlqItems", dlqCount != null ? dlqCount : 0
        ));

        log.info("✓ Demo reset complete — {} events, {} audits, {} conflicts, {} DLQ",
                eventCount, auditCount, conflictCount, dlqCount);

        return ResponseEntity.ok(result);
    }
}

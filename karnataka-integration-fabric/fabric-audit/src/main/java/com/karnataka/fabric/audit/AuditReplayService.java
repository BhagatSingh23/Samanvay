package com.karnataka.fabric.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Service for replaying events for a given UBID from a specified timestamp.
 *
 * <p>Supports dry-run mode (returns what would be re-propagated without
 * writing) and live mode (re-inserts events into {@code propagation_outbox}
 * and records audit entries).</p>
 */
@Service
public class AuditReplayService {

    private static final Logger log = LoggerFactory.getLogger(AuditReplayService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AuditReplayService(JdbcTemplate jdbcTemplate,
                               AuditService auditService,
                               ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Replays events for a UBID from a given timestamp.
     *
     * @param ubid          the business entity identifier
     * @param fromTimestamp  replay from this point
     * @param dryRun        if true, only compute what would be replayed without writing
     * @return map with "eventsFound", "eventsReplayed", and "events" list
     */
    @Transactional
    public Map<String, Object> replay(String ubid, Instant fromTimestamp, boolean dryRun) {
        log.info("Replay request: ubid={}, from={}, dryRun={}", ubid, fromTimestamp, dryRun);

        // Load all events for this UBID from the given timestamp
        List<Map<String, Object>> events = jdbcTemplate.queryForList("""
                SELECT event_id, ubid, source_system_id, service_type,
                       event_timestamp, ingestion_timestamp, payload,
                       checksum, status
                FROM event_ledger
                WHERE ubid = ?
                  AND ingestion_timestamp >= ?
                ORDER BY ingestion_timestamp ASC
                """, ubid, Timestamp.from(fromTimestamp));

        List<Map<String, Object>> replayDetails = new ArrayList<>();
        int replayedCount = 0;

        for (Map<String, Object> event : events) {
            String eventId = getString(event, "EVENT_ID", "event_id");
            String sourceSystemId = getString(event, "SOURCE_SYSTEM_ID", "source_system_id");
            String serviceType = getString(event, "SERVICE_TYPE", "service_type");
            String status = getString(event, "STATUS", "status");

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("eventId", eventId);
            detail.put("sourceSystemId", sourceSystemId);
            detail.put("serviceType", serviceType);
            detail.put("originalStatus", status);
            detail.put("action", dryRun ? "WOULD_REPLAY" : "REPLAYED");

            if (!dryRun && eventId != null) {
                // Re-insert into propagation_outbox for each target
                // The outbox worker will pick up and dispatch
                try {
                    Object payloadObj = event.get("PAYLOAD");
                    if (payloadObj == null) payloadObj = event.get("payload");
                    String payloadJson = payloadObj != null ? payloadObj.toString() : "{}";

                    // Insert a new outbox entry with a generic ALL_TARGETS target
                    // The outbox worker will determine actual targets
                    jdbcTemplate.update("""
                            INSERT INTO propagation_outbox
                                (outbox_id, event_id, ubid, target_system_id,
                                 translated_payload, status, attempt_count,
                                 next_attempt_at, created_at)
                            VALUES (?, ?, ?, 'REPLAY_ALL', ?, 'PENDING', 0, ?, ?)
                            """,
                            UUID.randomUUID(),
                            UUID.fromString(eventId),
                            ubid,
                            payloadJson,
                            Timestamp.from(Instant.now()),
                            Timestamp.from(Instant.now()));

                    // Record audit for the replay
                    auditService.recordAudit(
                            eventId, ubid,
                            sourceSystemId, null,
                            AuditEventType.RETRY_QUEUED,
                            null,
                            Map.of("replayFrom", fromTimestamp.toString(),
                                   "originalStatus", status != null ? status : "UNKNOWN"));

                    detail.put("action", "REPLAYED");
                    replayedCount++;

                } catch (Exception e) {
                    log.error("Failed to replay event {}: {}", eventId, e.getMessage(), e);
                    detail.put("action", "FAILED");
                    detail.put("error", e.getMessage());
                }
            } else if (!dryRun) {
                detail.put("action", "SKIPPED_NULL_ID");
            }

            replayDetails.add(detail);
        }

        if (dryRun) {
            replayedCount = events.size(); // all would be replayed
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ubid", ubid);
        result.put("fromTimestamp", fromTimestamp.toString());
        result.put("dryRun", dryRun);
        result.put("eventsFound", events.size());
        result.put("eventsReplayed", replayedCount);
        result.put("events", replayDetails);

        log.info("Replay complete: ubid={}, found={}, replayed={}, dryRun={}",
                ubid, events.size(), replayedCount, dryRun);

        return result;
    }

    private String getString(Map<String, Object> row, String h2Key, String pgKey) {
        Object val = row.get(h2Key);
        if (val == null) val = row.get(pgKey);
        return val != null ? val.toString() : null;
    }
}

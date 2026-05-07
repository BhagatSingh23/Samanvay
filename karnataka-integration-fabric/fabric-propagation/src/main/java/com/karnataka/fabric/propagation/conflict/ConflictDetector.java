package com.karnataka.fabric.propagation.conflict;

import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Detects field-level conflicts between concurrent events targeting the
 * same business entity ({@code ubid}) within a sliding time window.
 *
 * <h3>Algorithm (Redis ZSET):</h3>
 * <ol>
 *   <li>Key = {@code ubid_window:{ubid}:{serviceType}}</li>
 *   <li>Score = {@code epochMillis} of {@code ingestionTimestamp}</li>
 *   <li>Value = {@code eventId}</li>
 *   <li>ZADD key score eventId</li>
 *   <li>Set TTL on key to {@code CONFLICT_WINDOW_SECONDS}</li>
 *   <li>ZRANGEBYSCORE key (now - windowMs) now → all events in window</li>
 *   <li>If more than 1 event → conflict detected:
 *       <ul>
 *         <li>Load both events from {@code event_ledger}</li>
 *         <li>Compare payload fields to find the disputed field</li>
 *         <li>Load resolution policy from {@code conflict_policies} table</li>
 *       </ul>
 *   </li>
 *   <li>Else → no conflict</li>
 * </ol>
 */
@Service
@ConditionalOnBean(StringRedisTemplate.class)
public class ConflictDetector {

    private static final Logger log = LoggerFactory.getLogger(ConflictDetector.class);

    /** Redis key prefix for UBID conflict windows. */
    private static final String KEY_PREFIX = "ubid_window:";

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    @Value("${conflict.window.seconds:30}")
    private long conflictWindowSeconds;

    public ConflictDetector(StringRedisTemplate redisTemplate,
                             JdbcTemplate jdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Checks whether the incoming event conflicts with any other event
     * for the same UBID within the conflict detection window.
     *
     * @param incomingEvent the event to check
     * @return the conflict check result
     */
    public ConflictCheckResult check(CanonicalServiceRequest incomingEvent) {
        if (incomingEvent == null || incomingEvent.ubid() == null) {
            return ConflictCheckResult.noConflict();
        }

        String ubid = incomingEvent.ubid();
        String serviceType = incomingEvent.serviceType();
        String eventId = incomingEvent.eventId();
        Instant ingestionTs = incomingEvent.ingestionTimestamp() != null
                ? incomingEvent.ingestionTimestamp()
                : Instant.now();

        String key = KEY_PREFIX + ubid + ":" + serviceType;
        double score = (double) ingestionTs.toEpochMilli();

        ZSetOperations<String, String> zOps = redisTemplate.opsForZSet();

        // Step 1: ZADD — add current event to the window
        zOps.add(key, eventId, score);

        // Step 2: Set TTL on the key
        redisTemplate.expire(key, Duration.ofSeconds(conflictWindowSeconds));

        // Step 3: ZRANGEBYSCORE — find all events in the window
        long nowMs = Instant.now().toEpochMilli();
        long windowStartMs = nowMs - (conflictWindowSeconds * 1000);

        Set<String> eventsInWindow = zOps.rangeByScore(key, windowStartMs, nowMs);

        // Step 4: If more than 1 event → conflict detected
        if (eventsInWindow == null || eventsInWindow.size() <= 1) {
            log.debug("No conflict for ubid={}, serviceType={}, eventId={}",
                    ubid, serviceType, eventId);
            return ConflictCheckResult.noConflict();
        }

        // Find the conflicting event (the OTHER event in the window)
        String conflictingEventId = eventsInWindow.stream()
                .filter(id -> !id.equals(eventId))
                .findFirst()
                .orElse(null);

        if (conflictingEventId == null) {
            return ConflictCheckResult.noConflict();
        }

        log.info("Conflict detected: ubid={}, serviceType={}, events=[{}, {}]",
                ubid, serviceType, eventId, conflictingEventId);

        // Step 4a: Load both events from event_ledger and compare payloads
        String fieldInDispute = identifyFieldInDispute(eventId, conflictingEventId);

        // Step 4b: Load resolution policy from conflict_policies table
        ConflictResolutionPolicy policy = loadResolutionPolicy(serviceType, fieldInDispute);

        return ConflictCheckResult.conflict(conflictingEventId, fieldInDispute, policy);
    }

    // ── Field comparison ────────────────────────────────────────

    /**
     * Loads both events from the event_ledger and compares their
     * payload fields to identify which field is in dispute.
     *
     * @return the canonical field name that differs, or "UNKNOWN" if
     *         the events cannot be loaded or compared
     */
    private String identifyFieldInDispute(String eventId1, String eventId2) {
        try {
            Map<String, Object> payload1 = loadEventPayload(eventId1);
            Map<String, Object> payload2 = loadEventPayload(eventId2);

            if (payload1 == null || payload2 == null) {
                log.warn("Cannot compare payloads: one or both events not found in ledger");
                return "UNKNOWN";
            }

            // Compare top-level fields — find the first differing field
            Set<String> allKeys = new LinkedHashSet<>();
            allKeys.addAll(payload1.keySet());
            allKeys.addAll(payload2.keySet());

            for (String field : allKeys) {
                Object v1 = payload1.get(field);
                Object v2 = payload2.get(field);
                if (!Objects.equals(v1, v2)) {
                    return field;
                }
            }

            // All top-level fields are identical — check nested
            return "UNKNOWN";

        } catch (Exception e) {
            log.warn("Failed to identify field in dispute: {}", e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * Loads the payload of an event from the event_ledger by eventId.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadEventPayload(String eventId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT payload FROM event_ledger WHERE event_id = ?",
                    UUID.fromString(eventId));

            if (rows.isEmpty()) {
                return null;
            }

            Object payloadObj = rows.get(0).get("PAYLOAD");
            if (payloadObj == null) {
                return null;
            }

            // Payload is stored as JSONB/CLOB — parse it
            String payloadJson = payloadObj.toString();
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(payloadJson, Map.class);

        } catch (Exception e) {
            log.warn("Failed to load event payload for {}: {}", eventId, e.getMessage());
            return null;
        }
    }

    // ── Policy lookup ───────────────────────────────────────────

    /**
     * Loads the conflict resolution policy from the {@code conflict_policies}
     * table for the given service type and field name.
     *
     * <p>Falls back to {@link ConflictResolutionPolicy#LAST_WRITER_WINS}
     * if no specific policy is configured.</p>
     */
    private ConflictResolutionPolicy loadResolutionPolicy(String serviceType, String fieldName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT policy FROM conflict_policies WHERE service_type = ? AND field_name = ?",
                    serviceType, fieldName);

            if (rows.isEmpty()) {
                log.debug("No conflict policy for serviceType={}, field={} — defaulting to LAST_WRITER_WINS",
                        serviceType, fieldName);
                return ConflictResolutionPolicy.LAST_WRITER_WINS;
            }

            String policyStr = (String) rows.get(0).get("POLICY");
            return ConflictResolutionPolicy.valueOf(policyStr);

        } catch (IllegalArgumentException e) {
            log.warn("Unknown conflict policy in DB, defaulting to LAST_WRITER_WINS: {}", e.getMessage());
            return ConflictResolutionPolicy.LAST_WRITER_WINS;
        } catch (Exception e) {
            log.warn("Failed to load conflict policy: {}", e.getMessage());
            return ConflictResolutionPolicy.LAST_WRITER_WINS;
        }
    }
}

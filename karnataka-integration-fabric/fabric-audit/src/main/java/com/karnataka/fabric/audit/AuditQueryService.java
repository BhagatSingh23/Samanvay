package com.karnataka.fabric.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Read-only query service for audit trail and conflict records.
 *
 * <p>Provides paginated, filtered access to {@code audit_records},
 * {@code conflict_records}, and {@code event_ledger} for the
 * {@link com.karnataka.fabric.api.controller.AuditController}.</p>
 */
@Service
public class AuditQueryService {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditQueryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ── Audit by UBID ──────────────────────────────────────────

    /**
     * Returns all audit records for a given UBID, optionally filtered by
     * a time window, ordered by timestamp ASC.
     *
     * @param ubid the business entity identifier
     * @param from optional start of time window (inclusive)
     * @param to   optional end of time window (inclusive)
     * @return list of audit records as Maps
     */
    public List<Map<String, Object>> findAuditByUbid(String ubid, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT audit_id, event_id, ubid, source_system, target_system,
                       audit_event_type, ts, conflict_policy, superseded_by,
                       before_state, after_state
                FROM audit_records
                WHERE ubid = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(ubid);

        if (from != null) {
            sql.append(" AND ts >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND ts <= ?");
            params.add(Timestamp.from(to));
        }

        sql.append(" ORDER BY ts ASC");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        return rows.stream().map(this::normaliseAuditRow).toList();
    }

    // ── Audit by event ID ──────────────────────────────────────

    /**
     * Returns all audit records for a given event ID, ordered by
     * timestamp ASC (full lifecycle of one event).
     *
     * @param eventId the event UUID
     * @return list of audit records as Maps
     */
    public List<Map<String, Object>> findAuditByEventId(UUID eventId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT audit_id, event_id, ubid, source_system, target_system,
                       audit_event_type, ts, conflict_policy, superseded_by,
                       before_state, after_state
                FROM audit_records
                WHERE event_id = ?
                ORDER BY ts ASC
                """, eventId);
        return rows.stream().map(this::normaliseAuditRow).toList();
    }

    // ── Conflict records (paginated) ───────────────────────────

    /**
     * Returns paginated conflict records, optionally filtered by
     * resolution status.
     *
     * @param resolved if non-null, filter by resolved (true) or unresolved (false)
     * @param page     zero-based page number
     * @param size     page size
     * @return map with "content", "page", "size", "totalElements"
     */
    public Map<String, Object> findConflicts(Boolean resolved, int page, int size) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM conflict_records");
        StringBuilder dataSql = new StringBuilder("""
                SELECT cr.conflict_id, cr.ubid, cr.event1_id, cr.event2_id,
                       cr.resolution_policy, cr.winning_event_id, cr.resolved_at,
                       cr.field_in_dispute
                FROM conflict_records cr
                """);

        List<Object> params = new ArrayList<>();

        if (resolved != null) {
            String filter = resolved
                    ? " WHERE winning_event_id IS NOT NULL"
                    : " WHERE winning_event_id IS NULL";
            countSql.append(filter);
            dataSql.append(filter);
        }

        dataSql.append(" ORDER BY cr.resolved_at DESC NULLS FIRST");
        dataSql.append(" LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);

        Integer total = jdbcTemplate.queryForObject(countSql.toString(), Integer.class);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                dataSql.toString(), params.toArray());

        // Enrich each conflict with event summaries
        List<Map<String, Object>> enriched = rows.stream()
                .map(this::enrichConflictWithEventSummaries)
                .toList();

        return Map.of(
                "content", enriched,
                "page", page,
                "size", size,
                "totalElements", total != null ? total : 0
        );
    }

    // ── Replay: load events for UBID from timestamp ────────────

    /**
     * Loads all events for a UBID from a given timestamp, ordered by
     * ingestion_timestamp ASC for replay.
     *
     * @param ubid          the business entity identifier
     * @param fromTimestamp  replay from this point
     * @return list of event rows
     */
    public List<Map<String, Object>> findEventsForReplay(String ubid, Instant fromTimestamp) {
        return jdbcTemplate.queryForList("""
                SELECT event_id, ubid, source_system_id, service_type,
                       event_timestamp, ingestion_timestamp, payload,
                       checksum, status
                FROM event_ledger
                WHERE ubid = ?
                  AND ingestion_timestamp >= ?
                ORDER BY ingestion_timestamp ASC
                """, ubid, Timestamp.from(fromTimestamp));
    }

    // ── Helpers ────────────────────────────────────────────────

    /**
     * Normalises H2/Postgres column names to camelCase and parses
     * JSON state columns.
     */
    private Map<String, Object> normaliseAuditRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("auditId", getString(row, "AUDIT_ID", "audit_id"));
        result.put("eventId", getString(row, "EVENT_ID", "event_id"));
        result.put("ubid", getString(row, "UBID", "ubid"));
        result.put("sourceSystem", getString(row, "SOURCE_SYSTEM", "source_system"));
        result.put("targetSystem", getString(row, "TARGET_SYSTEM", "target_system"));
        result.put("auditEventType", getString(row, "AUDIT_EVENT_TYPE", "audit_event_type"));
        result.put("timestamp", getTimestamp(row, "TS", "ts"));
        result.put("conflictPolicy", getString(row, "CONFLICT_POLICY", "conflict_policy"));
        result.put("supersededBy", getString(row, "SUPERSEDED_BY", "superseded_by"));
        result.put("beforeState", parseJson(row, "BEFORE_STATE", "before_state"));
        result.put("afterState", parseJson(row, "AFTER_STATE", "after_state"));
        return result;
    }

    private Map<String, Object> enrichConflictWithEventSummaries(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conflictId", getString(row, "CONFLICT_ID", "conflict_id"));
        result.put("ubid", getString(row, "UBID", "ubid"));
        result.put("event1Id", getString(row, "EVENT1_ID", "event1_id"));
        result.put("event2Id", getString(row, "EVENT2_ID", "event2_id"));
        result.put("resolutionPolicy", getString(row, "RESOLUTION_POLICY", "resolution_policy"));
        result.put("winningEventId", getString(row, "WINNING_EVENT_ID", "winning_event_id"));
        result.put("resolvedAt", getTimestamp(row, "RESOLVED_AT", "resolved_at"));
        result.put("fieldInDispute", getString(row, "FIELD_IN_DISPUTE", "field_in_dispute"));

        // Fetch event summaries
        String event1Id = getString(row, "EVENT1_ID", "event1_id");
        String event2Id = getString(row, "EVENT2_ID", "event2_id");
        if (event1Id != null) {
            result.put("event1Summary", loadEventSummary(event1Id));
        }
        if (event2Id != null) {
            result.put("event2Summary", loadEventSummary(event2Id));
        }
        return result;
    }

    private Map<String, Object> loadEventSummary(String eventIdStr) {
        try {
            UUID eventId = UUID.fromString(eventIdStr);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT event_id, ubid, source_system_id, service_type,
                           ingestion_timestamp, status
                    FROM event_ledger
                    WHERE event_id = ?
                    """, eventId);

            if (rows.isEmpty()) {
                return Map.of("eventId", eventIdStr, "status", "NOT_FOUND");
            }

            Map<String, Object> r = rows.get(0);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("eventId", getString(r, "EVENT_ID", "event_id"));
            summary.put("ubid", getString(r, "UBID", "ubid"));
            summary.put("sourceSystemId", getString(r, "SOURCE_SYSTEM_ID", "source_system_id"));
            summary.put("serviceType", getString(r, "SERVICE_TYPE", "service_type"));
            summary.put("ingestionTimestamp", getTimestamp(r, "INGESTION_TIMESTAMP", "ingestion_timestamp"));
            summary.put("status", getString(r, "STATUS", "status"));
            return summary;
        } catch (Exception e) {
            log.warn("Failed to load event summary for {}: {}", eventIdStr, e.getMessage());
            return Map.of("eventId", eventIdStr, "status", "ERROR");
        }
    }

    private String getString(Map<String, Object> row, String h2Key, String pgKey) {
        Object val = row.get(h2Key);
        if (val == null) val = row.get(pgKey);
        return val != null ? val.toString() : null;
    }

    private Object getTimestamp(Map<String, Object> row, String h2Key, String pgKey) {
        Object val = row.get(h2Key);
        if (val == null) val = row.get(pgKey);
        if (val instanceof Timestamp ts) return ts.toInstant().toString();
        return val != null ? val.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private Object parseJson(Map<String, Object> row, String h2Key, String pgKey) {
        Object val = row.get(h2Key);
        if (val == null) val = row.get(pgKey);
        if (val == null) return null;

        String json = val.toString();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return json; // return raw string if not parsable
        }
    }
}

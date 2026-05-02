package com.karnataka.fabric.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * JDBC-backed implementation of {@link AuditService}.
 *
 * <p>Writes audit records directly to the {@code audit_records} table
 * created by Flyway migration {@code V1__init.sql}.</p>
 */
@Service
public class JdbcAuditService implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void recordAudit(String eventId,
                            String ubid,
                            String sourceSystem,
                            String targetSystem,
                            AuditEventType eventType,
                            Map<String, Object> beforeState,
                            Map<String, Object> afterState) {

        UUID auditId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO audit_records
                    (audit_id, event_id, ubid, source_system, target_system,
                     audit_event_type, before_state, after_state)
                VALUES (?, ?::uuid, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """,
                auditId,
                eventId,
                ubid,
                sourceSystem,
                targetSystem,
                eventType.name(),
                toJson(beforeState),
                toJson(afterState)
        );

        log.debug("Audit recorded: auditId={}, eventId={}, type={}", auditId, eventId, eventType);
    }

    private String toJson(Map<String, Object> map) {
        if (map == null) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise audit state to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}

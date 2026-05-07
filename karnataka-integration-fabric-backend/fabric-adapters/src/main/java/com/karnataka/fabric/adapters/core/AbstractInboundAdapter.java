package com.karnataka.fabric.adapters.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import com.karnataka.fabric.core.domain.PropagationStatus;
import com.karnataka.fabric.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Base class for all department inbound adapters.
 *
 * <p>Provides the two critical shared operations:</p>
 * <ol>
 *   <li>{@link #publishCanonical} — enriches, checksums, publishes to Kafka,
 *       and writes the RECEIVED audit record.</li>
 *   <li>{@link #handleUbidNotFound} — parks unresolved events in
 *       {@code pending_ubid_resolution} for later replay.</li>
 * </ol>
 *
 * <p>Concrete adapters (Factories, Shops &amp; Establishments, Labour, …)
 * extend this class and implement the {@link DepartmentChangeAdapter}
 * contract.</p>
 */
public abstract class AbstractInboundAdapter implements DepartmentChangeAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractInboundAdapter.class);

    protected final KafkaTemplate<String, String> kafkaTemplate;
    protected final ObjectMapper objectMapper;
    protected final AuditService auditService;
    protected final JdbcTemplate jdbcTemplate;

    @Value("${fabric.kafka.topics.dept-inbound}")
    private String deptInboundTopic;

    /**
     * Constructor injection — all dependencies provided by the Spring context.
     */
    protected AbstractInboundAdapter(KafkaTemplate<String, String> kafkaTemplate,
                                     ObjectMapper objectMapper,
                                     AuditService auditService,
                                     JdbcTemplate jdbcTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Core publish pipeline ────────────────────────────────────

    /**
     * Enriches and publishes to the default department inbound topic.
     *
     * @param req the partially-filled canonical request from the department adapter
     * @see #publishCanonicalToTopic(CanonicalServiceRequest, String)
     */
    protected void publishCanonical(CanonicalServiceRequest req) {
        publishCanonicalToTopic(req, deptInboundTopic);
    }

    /**
     * Enriches a {@link CanonicalServiceRequest} with fabric-canonical fields,
     * publishes it to the specified Kafka topic, and records an audit trail entry.
     *
     * <ol>
     *   <li>Sets {@code ingestionTimestamp} to {@link Instant#now()} (fabric clock)</li>
     *   <li>Computes SHA-256 checksum over {@code ubid + serviceType + payload JSON}</li>
     *   <li>Sets {@code status} to {@link PropagationStatus#RECEIVED}</li>
     *   <li>Publishes to the given Kafka topic with {@code key = ubid}</li>
     *   <li>Writes {@link AuditEventType#RECEIVED} audit record via {@link AuditService}</li>
     * </ol>
     *
     * @param req   the partially-filled canonical request
     * @param topic the Kafka topic to publish to
     */
    protected void publishCanonicalToTopic(CanonicalServiceRequest req, String topic) {
        // 1. Fabric canonical clock
        Instant ingestionTimestamp = Instant.now();

        // 2. SHA-256 checksum: ubid + serviceType + payload JSON
        String checksum = computeChecksum(req.ubid(), req.serviceType(), req.payload());

        // 3. Reconstruct immutable record with enriched fields
        CanonicalServiceRequest enriched = new CanonicalServiceRequest(
                req.eventId(),
                req.ubid(),
                req.sourceSystemId(),
                req.serviceType(),
                req.eventTimestamp(),
                ingestionTimestamp,
                req.payload(),
                checksum,
                PropagationStatus.RECEIVED
        );

        // 4. Publish to Kafka (key = ubid for partition affinity)
        try {
            String json = objectMapper.writeValueAsString(enriched);
            kafkaTemplate.send(topic, enriched.ubid(), json);
            log.info("Published canonical event {} for ubid={} to topic={}",
                    enriched.eventId(), enriched.ubid(), topic);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise canonical event {}: {}", enriched.eventId(), e.getMessage(), e);
            throw new IllegalStateException("Canonical event serialisation failed", e);
        }

        // 5. Audit trail — RECEIVED
        auditService.recordAudit(
                enriched.eventId(),
                enriched.ubid(),
                enriched.sourceSystemId(),
                null,   // no target system at RECEIVED stage
                AuditEventType.RECEIVED,
                null,   // no before-state at ingestion
                enriched.payload()
        );
    }

    // ── UBID resolution fallback ─────────────────────────────────

    /**
     * Parks a raw department event when the UBID cannot be resolved.
     *
     * <p>The event is written to the {@code pending_ubid_resolution} table
     * and will be replayed once the UBID mapping is established.</p>
     *
     * @param deptId       the department identifier (e.g. {@code "DEPT_FACT"})
     * @param deptRecordId the department-internal record ID that could not be mapped
     */
    protected void handleUbidNotFound(String deptId, String deptRecordId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        jdbcTemplate.update(
                """
                INSERT INTO pending_ubid_resolution
                    (id, dept_id, dept_record_id, status, parked_at)
                VALUES (?, ?, ?, 'PENDING', ?)
                """,
                id, deptId, deptRecordId, now
        );

        log.warn("UBID not found — parked event: dept={}, deptRecordId={}, pendingId={}",
                deptId, deptRecordId, id);
    }

    // ── Checksum computation ─────────────────────────────────────

    /**
     * Computes {@code SHA-256(ubid + serviceType + payloadJson)}.
     */
    private String computeChecksum(String ubid, String serviceType, Map<String, Object> payload) {
        try {
            String payloadJson = (payload != null)
                    ? objectMapper.writeValueAsString(payload)
                    : "";

            String raw = ubid + serviceType + payloadJson;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialise payload for checksum: {}", e.getMessage(), e);
            throw new IllegalStateException("Checksum computation failed", e);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — should never happen
            throw new AssertionError("SHA-256 algorithm not available", e);
        }
    }
}

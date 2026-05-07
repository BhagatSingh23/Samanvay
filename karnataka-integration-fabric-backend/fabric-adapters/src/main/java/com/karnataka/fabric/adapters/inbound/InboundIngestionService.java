package com.karnataka.fabric.adapters.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.core.AbstractInboundAdapter;
import com.karnataka.fabric.adapters.core.AdapterMode;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import com.karnataka.fabric.core.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Shared ingestion service used by webhook controllers to
 * normalise, enrich, and publish inbound events.
 *
 * <p>Exposes the {@code publishCanonical} and {@code publishCanonicalToTopic}
 * pipelines as public methods so REST controllers can delegate to them.</p>
 */
@Service
public class InboundIngestionService extends AbstractInboundAdapter {

    @Value("${fabric.kafka.topics.sws-inbound}")
    private String swsInboundTopic;

    public InboundIngestionService(KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   AuditService auditService,
                                   JdbcTemplate jdbcTemplate) {
        super(kafkaTemplate, objectMapper, auditService, jdbcTemplate);
    }

    // ── DepartmentChangeAdapter (generic facade) ─────────────────

    @Override
    public String getDepartmentId() {
        return "INBOUND_FACADE";
    }

    @Override
    public AdapterMode getMode() {
        return AdapterMode.WEBHOOK;
    }

    @Override
    public Duration getEstimatedLag() {
        return Duration.ofSeconds(1);
    }

    // ── Public ingestion API ─────────────────────────────────────

    /**
     * Ingests a canonical request to the default department inbound topic.
     *
     * @param req the normalised canonical request
     * @return the event ID
     */
    public String ingest(CanonicalServiceRequest req) {
        publishCanonical(req);
        return req.eventId();
    }

    /**
     * Ingests a canonical request to a specific Kafka topic.
     *
     * @param req   the normalised canonical request
     * @param topic the Kafka topic name
     * @return the event ID
     */
    public String ingestToTopic(CanonicalServiceRequest req, String topic) {
        publishCanonicalToTopic(req, topic);
        return req.eventId();
    }

    /**
     * Ingests a canonical request to the SWS inbound topic.
     *
     * @param req the normalised canonical request
     * @return the event ID
     */
    public String ingestSws(CanonicalServiceRequest req) {
        publishCanonicalToTopic(req, swsInboundTopic);
        return req.eventId();
    }

    /**
     * Delegates UBID-not-found handling to the base class.
     */
    public void parkUnresolved(String deptId, String deptRecordId) {
        handleUbidNotFound(deptId, deptRecordId);
    }
}

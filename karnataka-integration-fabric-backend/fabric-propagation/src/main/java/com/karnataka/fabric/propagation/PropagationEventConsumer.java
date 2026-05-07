package com.karnataka.fabric.propagation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that listens on the SWS and department inbound event
 * topics and delegates to {@link PropagationOrchestrator} for
 * downstream propagation.
 *
 * <p>Consumes from two topics:</p>
 * <ul>
 *   <li>{@code sws.inbound.events} — events originating from the Single Window System</li>
 *   <li>{@code dept.inbound.events} — events originating from department systems</li>
 * </ul>
 *
 * <p>Messages are expected to be JSON-serialized
 * {@link CanonicalServiceRequest} records keyed by UBID.</p>
 */
@Component
public class PropagationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PropagationEventConsumer.class);

    private final PropagationOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public PropagationEventConsumer(PropagationOrchestrator orchestrator,
                                    ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    /**
     * Listens on the SWS inbound topic and dept inbound topic.
     *
     * <p>Each message is deserialized to a {@link CanonicalServiceRequest}
     * and handed to the orchestrator for propagation.</p>
     */
    @KafkaListener(
            topics = {
                    "${fabric.kafka.topics.sws-inbound}",
                    "${fabric.kafka.topics.dept-inbound}"
            },
            groupId = "propagation-consumer-group"
    )
    public void onEvent(String message) {
        try {
            CanonicalServiceRequest event = objectMapper.readValue(message, CanonicalServiceRequest.class);

            log.info("Received event for propagation: eventId={}, ubid={}, source={}",
                    event.eventId(), event.ubid(), event.sourceSystemId());

            orchestrator.propagate(event);

        } catch (Exception e) {
            log.error("Failed to process propagation event: {}", e.getMessage(), e);
            // Message will not be re-consumed (auto-commit).
            // In production, consider publishing to a DLQ topic.
        }
    }
}

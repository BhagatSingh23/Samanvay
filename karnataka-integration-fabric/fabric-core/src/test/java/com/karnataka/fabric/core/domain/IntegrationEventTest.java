package com.karnataka.fabric.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationEventTest {

    @Test
    void builderProducesValidEvent() {
        IntegrationEvent event = IntegrationEvent.builder()
                .source("revenue-dept")
                .eventType("TAX_ASSESSMENT_COMPLETED")
                .correlationId("corr-001")
                .payload(Map.of("amount", 15000))
                .metadata(Map.of("region", "Bengaluru"))
                .build();

        assertNotNull(event.getEventId());
        assertNotNull(event.getTimestamp());
        assertEquals("revenue-dept", event.getSource());
        assertEquals("TAX_ASSESSMENT_COMPLETED", event.getEventType());
        assertFalse(event.getPayload().isEmpty());
    }

    @Test
    void defaultFieldsArePopulated() {
        IntegrationEvent event = IntegrationEvent.builder()
                .source("test")
                .eventType("TEST_EVENT")
                .build();

        assertNotNull(event.getEventId());
        assertTrue(event.getTimestamp().isBefore(Instant.now().plusSeconds(1)));
    }
}

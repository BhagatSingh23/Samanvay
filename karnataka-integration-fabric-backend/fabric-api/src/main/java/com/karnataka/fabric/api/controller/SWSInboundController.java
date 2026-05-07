package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.adapters.inbound.InboundIngestionService;
import com.karnataka.fabric.adapters.webhook.WebhookNormaliser;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * SWS (Single Window System) inbound endpoint.
 *
 * <p>Same ingestion pattern as {@link WebhookAdapterController} but
 * publishes to the {@code sws.inbound.events} Kafka topic instead of
 * {@code dept.inbound.events}.</p>
 */
@RestController
@RequestMapping("/api/v1/inbound/sws")
public class SWSInboundController {

    private static final Logger log = LoggerFactory.getLogger(SWSInboundController.class);

    private final InboundIngestionService ingestionService;

    public SWSInboundController(InboundIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receiveSws(
            @RequestBody Map<String, Object> rawPayload) {

        // Extract required fields
        String ubid = extractString(rawPayload, "ubid");
        if (ubid == null || ubid.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required field 'ubid'"));
        }

        String serviceType = extractString(rawPayload, "serviceType");
        if (serviceType == null || serviceType.isBlank()) {
            serviceType = "UNKNOWN";
        }

        // Build canonical request with SWS source
        CanonicalServiceRequest canonical = new CanonicalServiceRequest(
                UUID.randomUUID().toString(),
                ubid,
                "SWS",
                serviceType,
                parseTimestamp(rawPayload.get("eventTimestamp")),
                null,   // set by publishCanonical
                rawPayload,
                null,   // set by publishCanonical
                null    // set by publishCanonical
        );

        // Publish to sws.inbound.events topic
        String eventId = ingestionService.ingestSws(canonical);

        log.info("SWS webhook ingested: eventId={}, ubid={}", eventId, ubid);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("eventId", eventId));
    }

    private String extractString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private Instant parseTimestamp(Object raw) {
        if (raw == null) return Instant.now();
        if (raw instanceof Instant inst) return inst;
        try {
            return Instant.parse(raw.toString());
        } catch (Exception e) {
            return Instant.now();
        }
    }
}

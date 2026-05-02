package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.adapters.inbound.InboundIngestionService;
import com.karnataka.fabric.adapters.registry.DepartmentConfig;
import com.karnataka.fabric.adapters.registry.DepartmentRegistry;
import com.karnataka.fabric.adapters.webhook.WebhookNormaliser;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Generic webhook ingestion endpoint for all department adapters.
 *
 * <p>Receives raw JSON from a department system, looks up the department
 * config from {@link DepartmentRegistry}, normalises the payload via the
 * registered {@link WebhookNormaliser}, and publishes the canonical event
 * to Kafka.</p>
 */
@RestController
@RequestMapping("/api/v1/inbound/{deptId}")
public class WebhookAdapterController {

    private static final Logger log = LoggerFactory.getLogger(WebhookAdapterController.class);

    private final DepartmentRegistry departmentRegistry;
    private final InboundIngestionService ingestionService;

    public WebhookAdapterController(DepartmentRegistry departmentRegistry,
                                    InboundIngestionService ingestionService) {
        this.departmentRegistry = departmentRegistry;
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receiveWebhook(
            @PathVariable String deptId,
            @RequestBody Map<String, Object> rawPayload) {

        // 1. Look up department config
        DepartmentConfig config = departmentRegistry.getConfig(deptId);
        if (config == null) {
            log.warn("Unknown department: {}", deptId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Unknown department: " + deptId));
        }

        // 2. Normalise raw JSON → CanonicalServiceRequest
        WebhookNormaliser normaliser = departmentRegistry.getNormaliser(deptId);
        CanonicalServiceRequest canonical;
        try {
            canonical = normaliser.normalise(deptId, rawPayload);
        } catch (IllegalArgumentException e) {
            log.warn("Normalisation failed for dept={}: {}", deptId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }

        // 3. Publish to Kafka (enrichment + audit inside)
        String eventId = ingestionService.ingest(canonical);

        log.info("Webhook ingested: dept={}, eventId={}", deptId, eventId);

        // 4. Return 202 Accepted
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("eventId", eventId));
    }
}

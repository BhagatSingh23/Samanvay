package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.core.domain.IntegrationEvent;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for submitting and querying integration events.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    @PostMapping
    public ResponseEntity<Map<String, Object>> submitEvent(
            @Valid @RequestBody IntegrationEvent event) {

        // TODO: wire to propagation service
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "eventId", event.getEventId(),
                        "status", "ACCEPTED"
                ));
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<Map<String, Object>> getEventStatus(
            @PathVariable UUID eventId) {

        // TODO: wire to audit / adapter lookup
        return ResponseEntity.ok(Map.of(
                "eventId", eventId,
                "status", "UNKNOWN"
        ));
    }
}

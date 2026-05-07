package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.adapters.drift.DriftAlertResponse;
import com.karnataka.fabric.adapters.drift.SchemaDriftDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller exposing schema drift alerts.
 *
 * <p>Provides visibility into fields that schema_mappings expect but
 * that are no longer present in live department API responses.</p>
 */
@RestController
@RequestMapping("/api/v1/drift-alerts")
public class DriftAlertController {

    private static final Logger log = LoggerFactory.getLogger(DriftAlertController.class);

    private final SchemaDriftDetector driftDetector;

    public DriftAlertController(SchemaDriftDetector driftDetector) {
        this.driftDetector = driftDetector;
    }

    /**
     * Returns all unresolved drift alerts, optionally filtered by department.
     *
     * @param deptId optional department filter
     * @return list of unresolved drift alerts
     */
    @GetMapping
    public ResponseEntity<List<DriftAlertResponse>> getUnresolvedAlerts(
            @RequestParam(name = "deptId", required = false) String deptId) {

        List<DriftAlertResponse> alerts;
        if (deptId != null && !deptId.isBlank()) {
            alerts = driftDetector.getUnresolvedAlerts(deptId);
        } else {
            alerts = driftDetector.getUnresolvedAlerts();
        }

        log.debug("Returning {} unresolved drift alert(s)", alerts.size());
        return ResponseEntity.ok(alerts);
    }
}

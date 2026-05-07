package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.audit.health.DepartmentHealthScore;
import com.karnataka.fabric.audit.health.DepartmentHealthScoringService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the Department Sync Health Scoreboard.
 *
 * <ul>
 *   <li>{@code GET /api/v1/health/departments} — all department scores</li>
 *   <li>{@code GET /api/v1/health/departments/{deptId}/history} — score trend</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/health")
public class DepartmentHealthController {

    private final DepartmentHealthScoringService scoringService;

    public DepartmentHealthController(DepartmentHealthScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @GetMapping("/departments")
    public ResponseEntity<Map<String, Object>> getAllScores() {
        List<DepartmentHealthScore> scores = scoringService.getAllDepartmentScores();
        return ResponseEntity.ok(Map.of(
                "computedAt", Instant.now(),
                "windowHours", 24,
                "departments", scores
        ));
    }

    @GetMapping("/departments/{deptId}/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @PathVariable String deptId,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(scoringService.getDepartmentHistory(deptId, days));
    }
}

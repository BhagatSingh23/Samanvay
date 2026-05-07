package com.karnataka.fabric.audit.health;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Computed health score for a single department over a rolling time window.
 *
 * <p>Score is 0–100 based on a weighted formula considering success rate,
 * DLQ count, conflict count, schema drift alerts, and latency.</p>
 *
 * @param deptId             department identifier (e.g. "FACTORIES")
 * @param deptName           human-readable department name
 * @param score              computed score 0–100
 * @param grade              letter grade (A/B/C/D)
 * @param successRate        ratio of CONFIRMED to DISPATCHED (0.0–1.0)
 * @param dlqCount           events in dead letter queue for this dept
 * @param conflictCount      conflicts involving this dept in the window
 * @param driftAlertCount    open schema drift alerts for this dept
 * @param avgLatencyMs       average dispatch-to-confirm latency in ms
 * @param totalEventsLast24h total DISPATCHED events in the window
 * @param computedAt         when this score was calculated
 */
public record DepartmentHealthScore(

        @JsonProperty("deptId")
        String deptId,

        @JsonProperty("deptName")
        String deptName,

        @JsonProperty("score")
        double score,

        @JsonProperty("grade")
        String grade,

        @JsonProperty("successRate")
        double successRate,

        @JsonProperty("dlqCount")
        int dlqCount,

        @JsonProperty("conflictCount")
        int conflictCount,

        @JsonProperty("driftAlertCount")
        int driftAlertCount,

        @JsonProperty("avgLatencyMs")
        long avgLatencyMs,

        @JsonProperty("totalEventsLast24h")
        int totalEventsLast24h,

        @JsonProperty("computedAt")
        Instant computedAt
) {}

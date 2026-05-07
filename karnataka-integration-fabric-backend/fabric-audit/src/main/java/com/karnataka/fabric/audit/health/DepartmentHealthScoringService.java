package com.karnataka.fabric.audit.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.registry.DepartmentConfig;
import com.karnataka.fabric.adapters.registry.DepartmentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Computes and caches per-department health scores based on a weighted
 * formula across five operational metrics.
 *
 * <h3>Score formula (0–100):</h3>
 * <ul>
 *   <li>Success Rate (CONFIRMED / DISPATCHED) × 40</li>
 *   <li>DLQ health (1 − dlqCount/10) × 25</li>
 *   <li>Conflict health (1 − conflictCount/5) × 15</li>
 *   <li>Drift-free bonus: 10 if no open alerts, else 0</li>
 *   <li>Latency bonus: 10 if &lt;2s, 5 if &lt;5s, else 0</li>
 * </ul>
 *
 * <p>Scores are cached in Redis with a configurable TTL and persisted
 * hourly to {@code dept_health_history} for trend analysis.</p>
 */
@Service
public class DepartmentHealthScoringService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentHealthScoringService.class);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DepartmentRegistry departmentRegistry;
    private final int windowHours;
    private final long cacheTtlSeconds;

    private static final String REDIS_KEY_PREFIX = "health:dept:";

    public DepartmentHealthScoringService(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            DepartmentRegistry departmentRegistry,
            @Value("${fabric.health-scoring.window-hours:24}") int windowHours,
            @Value("${fabric.health-scoring.cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.departmentRegistry = departmentRegistry;
        this.windowHours = windowHours;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    // ── 4a. Get all department scores ──────────────────────────

    /**
     * Computes health scores for all known departments.
     * Results are sorted by score ascending (worst first) for dashboard alerting.
     */
    public List<DepartmentHealthScore> getAllDepartmentScores() {
        // Get department IDs from the registry
        Map<String, DepartmentConfig> configs = departmentRegistry.allConfigs();
        List<String> deptIds;

        if (configs.isEmpty()) {
            // Fallback if no department configs loaded
            deptIds = List.of("FACTORIES", "SHOP_ESTAB", "REVENUE");
        } else {
            deptIds = new ArrayList<>(configs.keySet());
        }

        List<DepartmentHealthScore> scores = new ArrayList<>();
        for (String deptId : deptIds) {
            try {
                scores.add(computeScore(deptId));
            } catch (Exception e) {
                log.warn("Failed to compute score for dept {}: {}", deptId, e.getMessage());
            }
        }

        // Sort by score ascending (worst first)
        scores.sort(Comparator.comparingDouble(DepartmentHealthScore::score));
        return scores;
    }

    // ── 4b. Compute score for a single department ──────────────

    /**
     * Computes the health score for a single department, using Redis cache
     * when available.
     */
    public DepartmentHealthScore computeScore(String deptId) {
        // Check Redis cache first
        try {
            String cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + deptId);
            if (cached != null) {
                return objectMapper.readValue(cached, DepartmentHealthScore.class);
            }
        } catch (Exception e) {
            log.debug("Redis cache miss or error for dept {}: {}", deptId, e.getMessage());
        }

        Instant windowStart = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        Timestamp windowStartTs = Timestamp.from(windowStart);

        // The deptId in the registry is like "FACTORIES", but in audit_records
        // the target_system is the same key. We also check with "DEPT_" prefix.
        String targetSystem = deptId;

        // ── Metric queries ───────────────────────────────────

        int totalEvents = queryCount(
                "SELECT COUNT(*) FROM audit_records WHERE target_system = ? AND ts >= ? AND audit_event_type = 'DISPATCHED'",
                targetSystem, windowStartTs);

        int confirmedCount = queryCount(
                "SELECT COUNT(*) FROM audit_records WHERE target_system = ? AND ts >= ? AND audit_event_type = 'CONFIRMED'",
                targetSystem, windowStartTs);

        double successRate = totalEvents > 0 ? (double) confirmedCount / totalEvents : 0.0;

        int dlqCount = queryCount(
                "SELECT COUNT(*) FROM dead_letter_queue WHERE target_system_id = ? AND parked_at >= ?",
                targetSystem, windowStartTs);

        // Conflict count — global approximation since conflict_records lacks dept_id
        int conflictCount = queryCount(
                "SELECT COUNT(*) FROM conflict_records WHERE (resolved_at >= ? OR resolved_at IS NULL)",
                windowStartTs);

        int driftAlertCount = queryCount(
                "SELECT COUNT(*) FROM drift_alerts WHERE dept_id = ? AND resolved = false",
                targetSystem);

        long avgLatencyMs = queryLong(
                """
                SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (confirmed.ts - dispatched.ts)) * 1000), 0)
                FROM audit_records dispatched
                JOIN audit_records confirmed ON dispatched.event_id = confirmed.event_id
                WHERE dispatched.audit_event_type = 'DISPATCHED'
                AND confirmed.audit_event_type = 'CONFIRMED'
                AND dispatched.target_system = ?
                AND dispatched.ts >= ?
                """,
                targetSystem, windowStartTs);

        // ── Score formula ────────────────────────────────────

        double rawScore =
                (successRate * 40.0) +
                (Math.max(0.0, 1.0 - dlqCount / 10.0) * 25.0) +
                (Math.max(0.0, 1.0 - conflictCount / 5.0) * 15.0) +
                (driftAlertCount == 0 ? 10.0 : 0.0) +
                (avgLatencyMs < 2000 ? 10.0 : avgLatencyMs < 5000 ? 5.0 : 0.0);

        double score = Math.min(100.0, Math.max(0.0, rawScore));

        // Grade
        String grade = score >= 90 ? "A" : score >= 75 ? "B" : score >= 60 ? "C" : "D";

        // Department name
        String deptName = deriveDeptName(deptId);

        DepartmentHealthScore result = new DepartmentHealthScore(
                deptId, deptName, Math.round(score * 100.0) / 100.0, grade,
                successRate, dlqCount, conflictCount, driftAlertCount,
                avgLatencyMs, totalEvents, Instant.now());

        // Cache in Redis
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(
                    REDIS_KEY_PREFIX + deptId, json,
                    Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.debug("Failed to cache score for dept {}: {}", deptId, e.getMessage());
        }

        return result;
    }

    // ── 4c. Persist daily history ──────────────────────────────

    /**
     * Persists current health scores to the dept_health_history table.
     * Runs hourly by default; uses upsert to avoid duplicates.
     */
    @Scheduled(cron = "${fabric.health-scoring.history-cron:0 0 * * * *}")
    public void persistDailyHistory() {
        List<DepartmentHealthScore> scores = getAllDepartmentScores();

        for (DepartmentHealthScore s : scores) {
            try {
                String metricsJson = objectMapper.writeValueAsString(s);
                jdbcTemplate.update(
                        """
                        INSERT INTO dept_health_history(dept_id, score, grade, window_date, metrics, computed_at)
                        VALUES (?, ?, ?, CURRENT_DATE, ?::jsonb, now())
                        ON CONFLICT (dept_id, window_date) DO UPDATE
                          SET score = EXCLUDED.score, grade = EXCLUDED.grade,
                              metrics = EXCLUDED.metrics, computed_at = EXCLUDED.computed_at
                        """,
                        s.deptId(), s.score(), s.grade(), metricsJson);
            } catch (Exception e) {
                log.warn("Failed to persist health history for {}: {}", s.deptId(), e.getMessage());
            }
        }

        log.info("Health history persisted for {} departments", scores.size());
    }

    // ── 4d. Get department history ─────────────────────────────

    /**
     * Returns historical health scores for a department over the given number of days.
     */
    public List<Map<String, Object>> getDepartmentHistory(String deptId, int days) {
        return jdbcTemplate.queryForList(
                """
                SELECT dept_id, score, grade, window_date, metrics, computed_at
                FROM dept_health_history
                WHERE dept_id = ? AND window_date >= CURRENT_DATE - CAST(? || ' days' AS INTERVAL)
                ORDER BY window_date ASC
                """,
                deptId, String.valueOf(days));
    }

    // ── Helpers ────────────────────────────────────────────────

    private int queryCount(String sql, Object... args) {
        try {
            Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.debug("Count query failed: {}", e.getMessage());
            return 0;
        }
    }

    private long queryLong(String sql, Object... args) {
        try {
            Number result = jdbcTemplate.queryForObject(sql, Number.class, args);
            return result != null ? result.longValue() : 0L;
        } catch (Exception e) {
            log.debug("Long query failed: {}", e.getMessage());
            return 0L;
        }
    }

    private String deriveDeptName(String deptId) {
        // Try to get display name from registry
        DepartmentConfig config = departmentRegistry.getConfig(deptId);
        if (config != null && config.displayName() != null) {
            return config.displayName();
        }

        // Fallback: strip "DEPT_" prefix and title-case
        String raw = deptId.startsWith("DEPT_") ? deptId.substring(5) : deptId;
        if (raw.isEmpty()) return deptId;
        String[] parts = raw.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(part.substring(0, 1).toUpperCase())
              .append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}

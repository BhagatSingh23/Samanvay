package com.karnataka.fabric.adapters.polling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.core.AbstractInboundAdapter;
import com.karnataka.fabric.adapters.core.AdapterMode;
import com.karnataka.fabric.adapters.registry.DepartmentConfig;
import com.karnataka.fabric.adapters.registry.DepartmentRegistry;
import com.karnataka.fabric.adapters.webhook.WebhookNormaliser;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import com.karnataka.fabric.core.service.AuditService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polling-based inbound adapter that periodically fetches change records
 * from department systems configured with {@link AdapterMode#POLLING}.
 *
 * <p>Extends {@link AbstractInboundAdapter} to reuse the canonical
 * publish pipeline (enrich → checksum → Kafka → audit).</p>
 *
 * <p>Each polling department is protected by its own Resilience4j
 * {@link CircuitBreaker} named {@code poll-{deptId}}.  On HTTP 429
 * (Too Many Requests) or 503 (Service Unavailable), the adapter skips
 * cursor advancement and doubles the back-off for the next cycle.</p>
 */
@Service
public class PollingAdapter extends AbstractInboundAdapter {

    private static final Logger log = LoggerFactory.getLogger(PollingAdapter.class);

    private final DepartmentRegistry departmentRegistry;
    private final WebClient webClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * Per-department backoff multiplier.  Starts at 1; doubles on 429/503;
     * resets to 1 on a successful poll.
     */
    private final ConcurrentHashMap<String, Integer> backoffMultipliers = new ConcurrentHashMap<>();

    @Autowired
    public PollingAdapter(KafkaTemplate<String, String> kafkaTemplate,
                          ObjectMapper objectMapper,
                          AuditService auditService,
                          JdbcTemplate jdbcTemplate,
                          DepartmentRegistry departmentRegistry,
                          CircuitBreakerRegistry circuitBreakerRegistry) {
        super(kafkaTemplate, objectMapper, auditService, jdbcTemplate);
        this.departmentRegistry = departmentRegistry;
        this.webClient = WebClient.builder().build();
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Package-private constructor for testing — allows injecting a custom
     * {@link WebClient} (e.g. backed by MockWebServer).
     */
    public PollingAdapter(KafkaTemplate<String, String> kafkaTemplate,
                   ObjectMapper objectMapper,
                   AuditService auditService,
                   JdbcTemplate jdbcTemplate,
                   DepartmentRegistry departmentRegistry,
                   CircuitBreakerRegistry circuitBreakerRegistry,
                   WebClient webClient) {
        super(kafkaTemplate, objectMapper, auditService, jdbcTemplate);
        this.departmentRegistry = departmentRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.webClient = webClient;
    }

    // ── DepartmentChangeAdapter contract ─────────────────────────

    @Override
    public String getDepartmentId() {
        return "POLLING_ADAPTER";
    }

    @Override
    public AdapterMode getMode() {
        return AdapterMode.POLLING;
    }

    @Override
    public Duration getEstimatedLag() {
        return Duration.ofSeconds(30);
    }

    // ── Scheduled polling ────────────────────────────────────────

    /**
     * Entry point invoked by Spring's scheduler.  Iterates over all
     * POLLING departments and fetches their change records.
     */
    @Scheduled(fixedDelayString = "${adapter.poll.interval:30000}")
    public void pollAll() {
        departmentRegistry.allConfigs().values().stream()
                .filter(cfg -> cfg.adapterMode() == AdapterMode.POLLING)
                .forEach(this::pollDepartment);
    }

    /**
     * Polls a single department for changes.  Exposed as package-private
     * so integration tests can invoke it directly.
     */
    public void pollDepartment(DepartmentConfig config) {
        String deptId = config.deptId();

        // Apply back-off: skip this cycle if multiplier says so
        int multiplier = backoffMultipliers.getOrDefault(deptId, 1);
        if (multiplier > 1) {
            // Halve the multiplier for the next cycle (exponential decay)
            backoffMultipliers.put(deptId, Math.max(1, multiplier / 2));
            log.info("Backing off dept={}, skipping this cycle (multiplier={})", deptId, multiplier);
            return;
        }

        String lastCursor = readLastCursor(deptId);
        String pollUrl = config.pollUrl();

        log.info("Polling dept={}, url={}, cursor={}", deptId, pollUrl, lastCursor);

        try {
            // Wrap the HTTP call in a per-dept circuit breaker
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("poll-" + deptId);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> records = CircuitBreaker.decorateSupplier(cb, () ->
                    webClient.get()
                            .uri(pollUrl + "?modified_after={cursor}", lastCursor)
                            .retrieve()
                            .bodyToMono(List.class)
                            .block(Duration.ofSeconds(30))
            ).get();

            if (records == null || records.isEmpty()) {
                log.debug("No new records for dept={}", deptId);
                return;
            }

            log.info("Fetched {} record(s) for dept={}", records.size(), deptId);

            // Normalise and publish each record
            WebhookNormaliser normaliser = departmentRegistry.getNormaliser(deptId);
            for (Map<String, Object> record : records) {
                CanonicalServiceRequest canonical = normaliser.normalise(deptId, record);
                publishCanonical(canonical);
            }

            // Update cursor on success
            String newCursor = Instant.now().toString();
            upsertCursor(deptId, newCursor);
            log.info("Cursor updated for dept={}: {}", deptId, newCursor);

            // Reset backoff on success
            backoffMultipliers.remove(deptId);

        } catch (WebClientResponseException e) {
            int statusCode = e.getStatusCode().value();
            if (statusCode == 429 || statusCode == 503) {
                log.warn("Rate-limited/unavailable for dept={} (HTTP {}). " +
                         "Skipping cursor update, doubling backoff.", deptId, statusCode);
                backoffMultipliers.merge(deptId, 2, (old, v) -> Math.min(old * 2, 64));
            } else {
                log.error("HTTP error polling dept={}: {} {}", deptId, statusCode, e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("Failed to poll dept={}: {}", deptId, e.getMessage(), e);
        }
    }

    // ── Cursor persistence ───────────────────────────────────────

    private String readLastCursor(String deptId) {
        List<String> cursors = jdbcTemplate.queryForList(
                "SELECT last_cursor FROM poll_cursors WHERE dept_id = ?",
                String.class,
                deptId
        );
        return cursors.isEmpty() ? Instant.EPOCH.toString() : cursors.getFirst();
    }

    private void upsertCursor(String deptId, String cursor) {
        int updated = jdbcTemplate.update(
                "UPDATE poll_cursors SET last_cursor = ? WHERE dept_id = ?",
                cursor, deptId
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO poll_cursors (dept_id, last_cursor) VALUES (?, ?)",
                    deptId, cursor
            );
        }
    }
}

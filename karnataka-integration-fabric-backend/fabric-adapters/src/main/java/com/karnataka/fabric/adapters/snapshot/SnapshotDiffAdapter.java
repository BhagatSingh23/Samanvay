package com.karnataka.fabric.adapters.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Snapshot-diff-based inbound adapter that periodically fetches a full
 * snapshot of records from a department system, computes SHA-256 hashes
 * for each record, and emits events only for new or changed records.
 *
 * <p>Extends {@link AbstractInboundAdapter} to reuse the canonical
 * publish pipeline (enrich → checksum → Kafka → audit).</p>
 *
 * <p>Record hashes are persisted in the {@code snapshot_hashes} table
 * (V4 migration) so that across restarts the adapter still knows which
 * records have changed.</p>
 *
 * <p>Each snapshot department is protected by its own Resilience4j
 * {@link CircuitBreaker} named {@code snapshot-{deptId}}.</p>
 */
@Service
public class SnapshotDiffAdapter extends AbstractInboundAdapter {

    private static final Logger log = LoggerFactory.getLogger(SnapshotDiffAdapter.class);

    private final DepartmentRegistry departmentRegistry;
    private final WebClient webClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    public SnapshotDiffAdapter(KafkaTemplate<String, String> kafkaTemplate,
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
     * Constructor for testing — allows injecting a custom {@link WebClient}.
     */
    public SnapshotDiffAdapter(KafkaTemplate<String, String> kafkaTemplate,
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
        return "SNAPSHOT_DIFF_ADAPTER";
    }

    @Override
    public AdapterMode getMode() {
        return AdapterMode.SNAPSHOT_DIFF;
    }

    @Override
    public Duration getEstimatedLag() {
        return Duration.ofMinutes(5);
    }

    // ── Scheduled snapshot-diff ──────────────────────────────────

    /**
     * Entry point invoked by Spring's cron scheduler.
     */
    @Scheduled(cron = "${adapter.snapshot.cron:0 */5 * * * *}")
    public void snapshotAll() {
        departmentRegistry.allConfigs().values().stream()
                .filter(cfg -> cfg.adapterMode() == AdapterMode.SNAPSHOT_DIFF)
                .forEach(this::snapshotDepartment);
    }

    /**
     * Fetches a full snapshot for a single department, diffs against
     * stored hashes, and publishes events for changed/new records.
     */
    public void snapshotDepartment(DepartmentConfig config) {
        String deptId = config.deptId();
        String snapshotUrl = config.snapshotUrl();

        log.info("Fetching snapshot for dept={}, url={}", deptId, snapshotUrl);

        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("snapshot-" + deptId);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> records = CircuitBreaker.decorateSupplier(cb, () ->
                    webClient.get()
                            .uri(snapshotUrl)
                            .retrieve()
                            .bodyToMono(List.class)
                            .block(Duration.ofSeconds(60))
            ).get();

            if (records == null || records.isEmpty()) {
                log.debug("Empty snapshot for dept={}", deptId);
                return;
            }

            log.info("Snapshot fetched: {} record(s) for dept={}", records.size(), deptId);

            int changedCount = 0;
            WebhookNormaliser normaliser = departmentRegistry.getNormaliser(deptId);
            Instant now = Instant.now();

            for (Map<String, Object> record : records) {
                String recordKey = extractRecordKey(record);
                String newHash = computeRecordHash(record);

                String storedHash = readStoredHash(deptId, recordKey);

                if (newHash.equals(storedHash)) {
                    // Record unchanged — update last_seen only
                    updateLastSeen(deptId, recordKey, now);
                    continue;
                }

                // New or changed record — normalise and publish
                CanonicalServiceRequest canonical = normaliser.normalise(deptId, record);
                publishCanonical(canonical);
                changedCount++;

                // Upsert hash
                upsertHash(deptId, recordKey, newHash, now);
            }

            log.info("Snapshot diff complete for dept={}: {} changed/new out of {} total",
                    deptId, changedCount, records.size());

        } catch (Exception e) {
            log.error("Failed to process snapshot for dept={}: {}", deptId, e.getMessage(), e);
        }
    }

    // ── Record key extraction ────────────────────────────────────

    /**
     * Extracts the record key (ubid) from a record.
     * Falls back to a hash of the entire record if no ubid field exists.
     */
    private String extractRecordKey(Map<String, Object> record) {
        Object ubid = record.get("ubid");
        if (ubid != null && !ubid.toString().isBlank()) {
            return ubid.toString();
        }
        // Fallback: use the record hash as the key
        return computeRecordHash(record);
    }

    // ── SHA-256 hashing ──────────────────────────────────────────

    /**
     * Computes SHA-256 hash of the entire record JSON.
     */
    private String computeRecordHash(Map<String, Object> record) {
        try {
            String json = objectMapper.writeValueAsString(record);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise record for hashing", e);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    // ── Hash persistence ─────────────────────────────────────────

    private String readStoredHash(String deptId, String recordKey) {
        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT hash FROM snapshot_hashes WHERE dept_id = ? AND record_key = ?",
                String.class,
                deptId, recordKey
        );
        return hashes.isEmpty() ? null : hashes.getFirst();
    }

    private void upsertHash(String deptId, String recordKey, String hash, Instant lastSeen) {
        int updated = jdbcTemplate.update(
                "UPDATE snapshot_hashes SET hash = ?, last_seen = ? WHERE dept_id = ? AND record_key = ?",
                hash, java.sql.Timestamp.from(lastSeen), deptId, recordKey
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO snapshot_hashes (dept_id, record_key, hash, last_seen) VALUES (?, ?, ?, ?)",
                    deptId, recordKey, hash, java.sql.Timestamp.from(lastSeen)
            );
        }
    }

    private void updateLastSeen(String deptId, String recordKey, Instant lastSeen) {
        jdbcTemplate.update(
                "UPDATE snapshot_hashes SET last_seen = ? WHERE dept_id = ? AND record_key = ?",
                java.sql.Timestamp.from(lastSeen), deptId, recordKey
        );
    }
}

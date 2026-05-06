package com.karnataka.fabric.propagation.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent integration test for {@link IdempotencyService}.
 *
 * <p>Verifies that when 10 threads simultaneously call {@code acquireLock}
 * with the same fingerprint, exactly 1 returns {@code LOCK_ACQUIRED}
 * and the remaining 9 return {@code DUPLICATE_SKIP}.</p>
 *
 * <p>Uses an in-memory H2 database in PostgreSQL compatibility mode
 * with {@code MULTI_THREADED=1} for realistic concurrent behavior.</p>
 */
class IdempotencyServiceConcurrentTest {

    private DataSource dataSource;
    private PlatformTransactionManager txManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // H2 in PostgreSQL mode with multi-threaded access and a shared in-memory DB
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:idempotency_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;LOCK_TIMEOUT=10000");
        ds.setUsername("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.txManager = new DataSourceTransactionManager(dataSource);
        this.objectMapper = new ObjectMapper();

        // Create the table
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS idempotency_fingerprints");
        jdbc.execute("""
                CREATE TABLE idempotency_fingerprints (
                    fingerprint         VARCHAR(512)    PRIMARY KEY,
                    event_id            UUID,
                    target_dept_id      VARCHAR(255),
                    status              VARCHAR(50),
                    locked_at           TIMESTAMP,
                    committed_at        TIMESTAMP
                )
                """);
    }

    // ═══════════════════════════════════════════════════════════
    // Concurrent lock acquisition
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("10 concurrent threads: exactly 1 LOCK_ACQUIRED, 9 DUPLICATE_SKIP")
    void concurrentAcquireLock() throws Exception {
        final int threadCount = 10;
        final String ubid = "KA-2024-001";
        final String serviceType = "ADDRESS_CHANGE";
        final Map<String, Object> payload = Map.of(
                "registeredAddress", Map.of("line1", "MG ROAD", "pincode", "560001"),
                "businessName", "Test Corp");
        final String targetDeptId = "FACTORIES";

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<IdempotencyResult>> futures = new ArrayList<>();
        AtomicInteger lockAcquiredCount = new AtomicInteger(0);
        AtomicInteger duplicateSkipCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                // Each thread gets its own JdbcTemplate and TransactionTemplate
                // to simulate independent service calls
                JdbcTemplate threadJdbc = new JdbcTemplate(dataSource);
                TransactionTemplate txTemplate = new TransactionTemplate(txManager);

                // Wait for all threads to be ready before racing
                barrier.await(5, TimeUnit.SECONDS);

                // Execute within a transaction (simulating @Transactional)
                return txTemplate.execute(status -> {
                    IdempotencyService service = new IdempotencyService(threadJdbc, objectMapper);
                    return service.acquireLock(ubid, serviceType, payload, targetDeptId);
                });
            }));
        }

        // Collect results
        List<IdempotencyResult> results = new ArrayList<>();
        for (Future<IdempotencyResult> future : futures) {
            IdempotencyResult result = future.get(10, TimeUnit.SECONDS);
            results.add(result);
            if (result == IdempotencyResult.LOCK_ACQUIRED) {
                lockAcquiredCount.incrementAndGet();
            } else if (result == IdempotencyResult.DUPLICATE_SKIP) {
                duplicateSkipCount.incrementAndGet();
            }
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Exactly 1 thread should have acquired the lock
        assertThat(lockAcquiredCount.get())
                .as("Exactly 1 thread should acquire the lock")
                .isEqualTo(1);

        // The other 9 should get DUPLICATE_SKIP
        assertThat(duplicateSkipCount.get())
                .as("Remaining 9 threads should get DUPLICATE_SKIP")
                .isEqualTo(threadCount - 1);

        // Verify database state: exactly 1 row with status IN_FLIGHT
        JdbcTemplate verifyJdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = verifyJdbc.queryForList(
                "SELECT * FROM idempotency_fingerprints");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("IN_FLIGHT");
    }

    // ═══════════════════════════════════════════════════════════
    // Single-thread lifecycle
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("acquireLock → commitLock → duplicate returns DUPLICATE_SKIP")
    void fullLifecycle() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        IdempotencyService service = new IdempotencyService(jdbc, objectMapper);
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        String ubid = "KA-2024-002";
        String serviceType = "LICENSE_RENEWAL";
        Map<String, Object> payload = Map.of("licenseId", "LIC-123");
        String targetDeptId = "SHOP_ESTAB";

        // First call — should acquire lock
        IdempotencyResult result1 = txTemplate.execute(status ->
                service.acquireLock(ubid, serviceType, payload, targetDeptId));
        assertThat(result1).isEqualTo(IdempotencyResult.LOCK_ACQUIRED);

        // Compute fingerprint to commit
        String fingerprint = service.computeFingerprint(ubid, serviceType, payload, targetDeptId);

        // Commit the lock
        txTemplate.executeWithoutResult(status -> service.commitLock(fingerprint));

        // Second call with same params — should get DUPLICATE_SKIP
        IdempotencyResult result2 = txTemplate.execute(status ->
                service.acquireLock(ubid, serviceType, payload, targetDeptId));
        assertThat(result2).isEqualTo(IdempotencyResult.DUPLICATE_SKIP);
    }

    @Test
    @DisplayName("acquireLock → releaseLock → re-acquire returns LOCK_ACQUIRED")
    void releaseAndReacquire() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        IdempotencyService service = new IdempotencyService(jdbc, objectMapper);
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        String ubid = "KA-2024-003";
        String serviceType = "NAME_CHANGE";
        Map<String, Object> payload = Map.of("newName", "New Corp");
        String targetDeptId = "REVENUE";

        // Acquire lock
        IdempotencyResult result1 = txTemplate.execute(status ->
                service.acquireLock(ubid, serviceType, payload, targetDeptId));
        assertThat(result1).isEqualTo(IdempotencyResult.LOCK_ACQUIRED);

        // Release the lock
        String fingerprint = service.computeFingerprint(ubid, serviceType, payload, targetDeptId);
        txTemplate.executeWithoutResult(status -> service.releaseLock(fingerprint));

        // Re-acquire — should succeed since lock was released
        IdempotencyResult result2 = txTemplate.execute(status ->
                service.acquireLock(ubid, serviceType, payload, targetDeptId));
        assertThat(result2).isEqualTo(IdempotencyResult.LOCK_ACQUIRED);
    }

    // ═══════════════════════════════════════════════════════════
    // Fingerprint determinism
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("fingerprint is deterministic regardless of payload key order")
    void fingerprintDeterminism() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        IdempotencyService service = new IdempotencyService(jdbc, objectMapper);

        Map<String, Object> payload1 = new java.util.LinkedHashMap<>();
        payload1.put("z_field", "last");
        payload1.put("a_field", "first");

        Map<String, Object> payload2 = new java.util.LinkedHashMap<>();
        payload2.put("a_field", "first");
        payload2.put("z_field", "last");

        String fp1 = service.computeFingerprint("ubid", "type", payload1, "dept");
        String fp2 = service.computeFingerprint("ubid", "type", payload2, "dept");

        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).hasSize(64); // SHA-256 hex = 64 chars
    }

    // ═══════════════════════════════════════════════════════════
    // Stale lock reclaim
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("stale IN_FLIGHT lock older than 5 minutes is reclaimed")
    void staleLockReclaim() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        IdempotencyService service = new IdempotencyService(jdbc, objectMapper);
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        String ubid = "KA-2024-004";
        String serviceType = "DISSOLUTION";
        Map<String, Object> payload = Map.of("reason", "voluntary");
        String targetDeptId = "FACTORIES";

        String fingerprint = service.computeFingerprint(ubid, serviceType, payload, targetDeptId);

        // Manually insert a stale IN_FLIGHT row (locked 10 minutes ago)
        java.sql.Timestamp staleTime = java.sql.Timestamp.from(
                java.time.Instant.now().minus(10, java.time.temporal.ChronoUnit.MINUTES));
        jdbc.update(
                "INSERT INTO idempotency_fingerprints (fingerprint, target_dept_id, status, locked_at) VALUES (?, ?, 'IN_FLIGHT', ?)",
                fingerprint, targetDeptId, staleTime);

        // Acquire lock — should reclaim the stale lock
        IdempotencyResult result = txTemplate.execute(status ->
                service.acquireLock(ubid, serviceType, payload, targetDeptId));
        assertThat(result).isEqualTo(IdempotencyResult.LOCK_ACQUIRED);
    }
}

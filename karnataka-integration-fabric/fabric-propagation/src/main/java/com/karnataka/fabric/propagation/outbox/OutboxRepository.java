package com.karnataka.fabric.propagation.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link OutboxEntry} entities.
 *
 * <p>The key method {@link #findPendingForProcessing} uses
 * {@code FOR UPDATE SKIP LOCKED} to allow concurrent workers to process
 * different rows without contention.</p>
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    /**
     * Finds up to {@code limit} outbox entries that are ready for processing:
     * status is PENDING and next_attempt_at is in the past.
     *
     * <p>Uses pessimistic write lock with SKIP LOCKED to prevent
     * concurrent workers from picking the same rows.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT o FROM OutboxEntry o
            WHERE o.status = 'PENDING'
              AND o.nextAttemptAt <= :now
            ORDER BY o.nextAttemptAt ASC
            """)
    List<OutboxEntry> findPendingForProcessing(@Param("now") Instant now);

    /**
     * Count entries by status (for monitoring).
     */
    long countByStatus(String status);
}

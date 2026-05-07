package com.karnataka.fabric.propagation.dlq;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link DeadLetterEntry} entities.
 */
@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetterEntry, UUID> {

    /**
     * Find all unresolved dead-letter entries, ordered by most recent first.
     */
    List<DeadLetterEntry> findByResolvedFalseOrderByParkedAtDesc();

    /**
     * Count unresolved dead-letter entries.
     */
    long countByResolvedFalse();
}

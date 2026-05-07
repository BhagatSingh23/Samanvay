package com.karnataka.fabric.propagation.outbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping to the {@code propagation_outbox} table.
 *
 * <p>Represents a single outbound delivery attempt for a translated
 * event payload destined for a specific department API.</p>
 *
 * <p>Status lifecycle: PENDING → IN_FLIGHT → DELIVERED | FAILED.
 * On failure, the entry is reset to PENDING with an updated
 * {@code nextAttemptAt} based on exponential backoff, until
 * {@code attemptCount} reaches the maximum (5), at which point
 * the entry is moved to the dead letter queue.</p>
 */
@Entity
@Table(name = "propagation_outbox")
public class OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "outbox_id", updatable = false, nullable = false)
    private UUID outboxId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "ubid", nullable = false)
    private String ubid;

    @Column(name = "target_system_id", nullable = false)
    private String targetSystemId;

    @Column(name = "translated_payload", nullable = false, columnDefinition = "jsonb")
    private String translatedPayload;

    @Column(name = "status")
    private String status = "PENDING";

    @Column(name = "attempt_count")
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at")
    private Instant createdAt;

    // ── Constructors ─────────────────────────────────────────────

    protected OutboxEntry() {
        // JPA
    }

    public OutboxEntry(UUID eventId, String ubid, String targetSystemId,
                        String translatedPayload) {
        this.eventId = eventId;
        this.ubid = ubid;
        this.targetSystemId = targetSystemId;
        this.translatedPayload = translatedPayload;
        this.status = "PENDING";
        this.attemptCount = 0;
        this.nextAttemptAt = Instant.now();
        this.createdAt = Instant.now();
    }

    // ── Accessors ────────────────────────────────────────────────

    public UUID getOutboxId() { return outboxId; }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getUbid() { return ubid; }
    public void setUbid(String ubid) { this.ubid = ubid; }

    public String getTargetSystemId() { return targetSystemId; }
    public void setTargetSystemId(String targetSystemId) { this.targetSystemId = targetSystemId; }

    public String getTranslatedPayload() { return translatedPayload; }
    public void setTranslatedPayload(String translatedPayload) { this.translatedPayload = translatedPayload; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

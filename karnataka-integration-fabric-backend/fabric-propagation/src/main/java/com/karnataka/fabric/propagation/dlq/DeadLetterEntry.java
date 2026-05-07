package com.karnataka.fabric.propagation.dlq;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping to the {@code dead_letter_queue} table.
 *
 * <p>Stores events that failed all retry attempts (default: 5).
 * Operators can inspect and manually resolve these entries.</p>
 */
@Entity
@Table(name = "dead_letter_queue")
public class DeadLetterEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "dlq_id", updatable = false, nullable = false)
    private UUID dlqId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "ubid")
    private String ubid;

    @Column(name = "target_system_id")
    private String targetSystemId;

    @Column(name = "translated_payload", columnDefinition = "jsonb")
    private String translatedPayload;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "parked_at")
    private Instant parkedAt;

    @Column(name = "resolved")
    private boolean resolved = false;

    // ── Constructors ─────────────────────────────────────────────

    protected DeadLetterEntry() {
        // JPA
    }

    public DeadLetterEntry(UUID eventId, String ubid, String targetSystemId,
                            String translatedPayload, String failureReason) {
        this.eventId = eventId;
        this.ubid = ubid;
        this.targetSystemId = targetSystemId;
        this.translatedPayload = translatedPayload;
        this.failureReason = failureReason;
        this.parkedAt = Instant.now();
        this.resolved = false;
    }

    // ── Accessors ────────────────────────────────────────────────

    public UUID getDlqId() { return dlqId; }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getUbid() { return ubid; }
    public void setUbid(String ubid) { this.ubid = ubid; }

    public String getTargetSystemId() { return targetSystemId; }
    public void setTargetSystemId(String targetSystemId) { this.targetSystemId = targetSystemId; }

    public String getTranslatedPayload() { return translatedPayload; }
    public void setTranslatedPayload(String translatedPayload) { this.translatedPayload = translatedPayload; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Instant getParkedAt() { return parkedAt; }
    public void setParkedAt(Instant parkedAt) { this.parkedAt = parkedAt; }

    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }
}

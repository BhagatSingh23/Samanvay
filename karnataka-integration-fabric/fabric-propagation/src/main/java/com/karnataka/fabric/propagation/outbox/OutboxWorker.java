package com.karnataka.fabric.propagation.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.service.AuditService;
import com.karnataka.fabric.propagation.dispatch.OutboundDispatcher;
import com.karnataka.fabric.propagation.dlq.DeadLetterEntry;
import com.karnataka.fabric.propagation.dlq.DeadLetterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox worker that polls the {@code propagation_outbox} table and
 * dispatches translated payloads to department APIs.
 *
 * <p>Runs every 5 seconds via {@code @Scheduled(fixedDelay = 5000)}.
 * Each cycle selects up to 20 PENDING entries that are due for
 * processing (i.e., {@code next_attempt_at <= now()}).</p>
 *
 * <h3>Processing flow:</h3>
 * <ol>
 *   <li>SELECT with FOR UPDATE SKIP LOCKED (via pessimistic lock)</li>
 *   <li>Set status = IN_FLIGHT</li>
 *   <li>Call {@link OutboundDispatcher#dispatch}</li>
 *   <li>On success: status = DELIVERED, write audit CONFIRMED</li>
 *   <li>On failure: increment attempt_count, compute backoff,
 *       set status = PENDING (or FAILED + DLQ if attempts >= 5)</li>
 * </ol>
 *
 * <h3>Backoff schedule:</h3>
 * <ul>
 *   <li>Attempt 1 → retry in 30 seconds</li>
 *   <li>Attempt 2 → retry in 2 minutes</li>
 *   <li>Attempt 3 → retry in 10 minutes</li>
 *   <li>Attempt 4 → retry in 1 hour</li>
 *   <li>Attempt 5 → move to dead letter queue</li>
 * </ul>
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    /** Maximum number of delivery attempts before moving to DLQ. */
    static final int MAX_ATTEMPTS = 5;

    /** Maximum number of rows to process per cycle. */
    private static final int BATCH_SIZE = 20;

    /**
     * Backoff durations indexed by attempt number (1-based).
     * Index 0 is unused; index 5 means DLQ.
     */
    static final Duration[] BACKOFF_SCHEDULE = {
            Duration.ZERO,              // index 0 — unused
            Duration.ofSeconds(30),     // attempt 1 → retry in 30s
            Duration.ofMinutes(2),      // attempt 2 → retry in 2m
            Duration.ofMinutes(10),     // attempt 3 → retry in 10m
            Duration.ofHours(1),        // attempt 4 → retry in 1h
            Duration.ZERO              // attempt 5 → DLQ (no further retry)
    };

    private final OutboxRepository outboxRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final OutboundDispatcher outboundDispatcher;
    private final AuditService auditService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${fabric.kafka.topics.audit-trail}")
    private String auditTrailTopic;

    @Value("${fabric.kafka.topics.dlq}")
    private String dlqTopic;

    public OutboxWorker(OutboxRepository outboxRepository,
                         DeadLetterRepository deadLetterRepository,
                         OutboundDispatcher outboundDispatcher,
                         AuditService auditService,
                         KafkaTemplate<String, String> kafkaTemplate,
                         ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.outboundDispatcher = outboundDispatcher;
        this.auditService = auditService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    // ── Scheduled polling ───────────────────────────────────────

    /**
     * Main processing loop. Runs every 5 seconds.
     */
    @Scheduled(fixedDelayString = "${outbox.worker.fixedDelay:5000}")
    public void processOutbox() {
        List<OutboxEntry> entries = outboxRepository.findPendingForProcessing(Instant.now());

        if (entries.isEmpty()) {
            return;
        }

        // Limit to BATCH_SIZE
        List<OutboxEntry> batch = entries.size() > BATCH_SIZE
                ? entries.subList(0, BATCH_SIZE)
                : entries;

        log.info("Outbox worker processing {} entries", batch.size());

        for (OutboxEntry entry : batch) {
            processEntry(entry);
        }
    }

    // ── Per-entry processing ────────────────────────────────────

    /**
     * Processes a single outbox entry. Package-private for testing.
     */
    @Transactional
    void processEntry(OutboxEntry entry) {
        try {
            // Mark as IN_FLIGHT
            entry.setStatus("IN_FLIGHT");
            outboxRepository.save(entry);

            // Dispatch to department API
            outboundDispatcher.dispatch(entry.getTargetSystemId(),
                    entry.getTranslatedPayload());

            // Success — mark as DELIVERED
            onSuccess(entry);

        } catch (Exception e) {
            // Failure — handle retry or DLQ
            onFailure(entry, e);
        }
    }

    // ── Success handling ────────────────────────────────────────

    private void onSuccess(OutboxEntry entry) {
        entry.setStatus("DELIVERED");
        entry.setLastError(null);
        outboxRepository.save(entry);

        log.info("Delivered outbox_id={} to dept={} (event_id={})",
                entry.getOutboxId(), entry.getTargetSystemId(), entry.getEventId());

        // Write audit CONFIRMED
        try {
            auditService.recordAudit(
                    entry.getEventId().toString(),
                    entry.getUbid(),
                    "FABRIC",
                    entry.getTargetSystemId(),
                    AuditEventType.CONFIRMED,
                    null,
                    Map.of("status", "DELIVERED",
                           "attemptCount", entry.getAttemptCount()));
        } catch (Exception e) {
            log.warn("Failed to write audit for delivered entry: {}", e.getMessage());
        }
    }

    // ── Failure handling ────────────────────────────────────────

    private void onFailure(OutboxEntry entry, Exception error) {
        int newAttemptCount = entry.getAttemptCount() + 1;
        entry.setAttemptCount(newAttemptCount);
        entry.setLastError(truncateError(error.getMessage()));

        if (newAttemptCount >= MAX_ATTEMPTS) {
            // Move to DLQ
            moveToDlq(entry, error.getMessage());
        } else {
            // Compute backoff and reschedule
            Duration backoff = BACKOFF_SCHEDULE[newAttemptCount];
            entry.setStatus("PENDING");
            entry.setNextAttemptAt(Instant.now().plus(backoff));
            outboxRepository.save(entry);

            log.warn("Outbox entry {} failed (attempt {}/{}), retry in {}: {}",
                    entry.getOutboxId(), newAttemptCount, MAX_ATTEMPTS,
                    backoff, error.getMessage());
        }
    }

    // ── Dead letter queue ───────────────────────────────────────

    private void moveToDlq(OutboxEntry entry, String failureReason) {
        // Create DLQ entry
        DeadLetterEntry dlqEntry = new DeadLetterEntry(
                entry.getEventId(),
                entry.getUbid(),
                entry.getTargetSystemId(),
                entry.getTranslatedPayload(),
                truncateError(failureReason));
        deadLetterRepository.save(dlqEntry);

        // Mark outbox entry as FAILED
        entry.setStatus("FAILED");
        outboxRepository.save(entry);

        log.error("Outbox entry {} moved to DLQ after {} attempts: {}",
                entry.getOutboxId(), MAX_ATTEMPTS, failureReason);

        // Write audit DLQ_PARKED
        try {
            auditService.recordAudit(
                    entry.getEventId().toString(),
                    entry.getUbid(),
                    "FABRIC",
                    entry.getTargetSystemId(),
                    AuditEventType.DLQ_PARKED,
                    Map.of("attemptCount", entry.getAttemptCount(),
                           "lastError", truncateError(failureReason)),
                    null);
        } catch (Exception e) {
            log.warn("Failed to write DLQ audit: {}", e.getMessage());
        }

        // Publish to DLQ Kafka topic
        publishDlqEvent(entry, failureReason);
    }

    private void publishDlqEvent(OutboxEntry entry, String failureReason) {
        try {
            Map<String, Object> dlqEvent = Map.of(
                    "eventId", entry.getEventId().toString(),
                    "ubid", entry.getUbid(),
                    "targetSystemId", entry.getTargetSystemId(),
                    "failureReason", truncateError(failureReason),
                    "attemptCount", entry.getAttemptCount(),
                    "parkedAt", Instant.now().toString());

            kafkaTemplate.send(dlqTopic, entry.getUbid(),
                    objectMapper.writeValueAsString(dlqEvent));
        } catch (JsonProcessingException e) {
            log.error("Failed to publish DLQ event: {}", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Truncates error messages to 500 characters for DB storage.
     */
    private String truncateError(String error) {
        if (error == null) return "Unknown error";
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /**
     * Computes the backoff duration for a given attempt number.
     * Exposed for testing.
     */
    static Duration getBackoffDuration(int attemptNumber) {
        if (attemptNumber < 1 || attemptNumber >= BACKOFF_SCHEDULE.length) {
            return Duration.ZERO;
        }
        return BACKOFF_SCHEDULE[attemptNumber];
    }
}

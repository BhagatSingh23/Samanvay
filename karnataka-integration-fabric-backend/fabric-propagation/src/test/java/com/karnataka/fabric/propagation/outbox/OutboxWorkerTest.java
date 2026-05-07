package com.karnataka.fabric.propagation.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.core.service.AuditService;
import com.karnataka.fabric.propagation.dispatch.OutboundDispatcher;
import com.karnataka.fabric.propagation.dlq.DeadLetterEntry;
import com.karnataka.fabric.propagation.dlq.DeadLetterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OutboxWorker}.
 *
 * <p>Tests cover: successful delivery, retry with backoff,
 * DLQ after max attempts, and the backoff schedule.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxWorkerTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private DeadLetterRepository deadLetterRepository;
    @Mock private OutboundDispatcher outboundDispatcher;
    @Mock private AuditService auditService;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxWorker worker;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        worker = new OutboxWorker(
                outboxRepository, deadLetterRepository, outboundDispatcher,
                auditService, kafkaTemplate, objectMapper);
        // Set @Value fields that aren't injected in unit tests
        ReflectionTestUtils.setField(worker, "auditTrailTopic", "audit.trail.events");
        ReflectionTestUtils.setField(worker, "dlqTopic", "dlq.events");
    }

    private OutboxEntry createEntry(int attemptCount) {
        OutboxEntry entry = new OutboxEntry(
                UUID.randomUUID(), "KA-2024-001", "FACTORIES",
                "{\"addr_line_1\":\"MG ROAD\"}");
        entry.setAttemptCount(attemptCount);
        return entry;
    }

    // ═══════════════════════════════════════════════════════════
    // Successful delivery
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Successful delivery")
    class SuccessTests {

        @Test
        @DisplayName("successful dispatch sets status to DELIVERED")
        void successfulDispatch() {
            OutboxEntry entry = createEntry(0);

            // Dispatch succeeds (no exception)
            doNothing().when(outboundDispatcher).dispatch(anyString(), anyString());
            when(outboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            worker.processEntry(entry);

            // Verify status transitions: IN_FLIGHT → DELIVERED
            ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
            verify(outboxRepository, atLeast(2)).save(captor.capture());

            OutboxEntry finalState = captor.getAllValues().stream()
                    .filter(e -> "DELIVERED".equals(e.getStatus()))
                    .findFirst()
                    .orElseThrow();

            assertThat(finalState.getStatus()).isEqualTo("DELIVERED");
            assertThat(finalState.getLastError()).isNull();
        }

        @Test
        @DisplayName("successful dispatch writes CONFIRMED audit")
        void successfulAudit() {
            OutboxEntry entry = createEntry(0);
            doNothing().when(outboundDispatcher).dispatch(anyString(), anyString());
            when(outboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            worker.processEntry(entry);

            verify(auditService).recordAudit(
                    eq(entry.getEventId().toString()),
                    eq("KA-2024-001"),
                    eq("FABRIC"),
                    eq("FACTORIES"),
                    eq(com.karnataka.fabric.core.domain.AuditEventType.CONFIRMED),
                    isNull(),
                    anyMap());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Retry with backoff
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Retry with backoff")
    class RetryTests {

        @Test
        @DisplayName("first failure increments attempt and schedules retry in 30s")
        void firstRetry() {
            OutboxEntry entry = createEntry(0);
            doThrow(new OutboundDispatcher.DispatchException("Connection refused"))
                    .when(outboundDispatcher).dispatch(anyString(), anyString());
            when(outboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            worker.processEntry(entry);

            assertThat(entry.getAttemptCount()).isEqualTo(1);
            assertThat(entry.getStatus()).isEqualTo("PENDING");
            assertThat(entry.getLastError()).contains("Connection refused");

            // nextAttemptAt should be ~30s in the future
            assertThat(entry.getNextAttemptAt())
                    .isAfter(Instant.now().plusSeconds(25))
                    .isBefore(Instant.now().plusSeconds(35));
        }

        @Test
        @DisplayName("second failure schedules retry in 2 minutes")
        void secondRetry() {
            OutboxEntry entry = createEntry(1); // already attempted once
            doThrow(new OutboundDispatcher.DispatchException("Timeout"))
                    .when(outboundDispatcher).dispatch(anyString(), anyString());
            when(outboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            worker.processEntry(entry);

            assertThat(entry.getAttemptCount()).isEqualTo(2);
            assertThat(entry.getStatus()).isEqualTo("PENDING");

            // nextAttemptAt should be ~2 minutes in the future
            assertThat(entry.getNextAttemptAt())
                    .isAfter(Instant.now().plusSeconds(115))
                    .isBefore(Instant.now().plusSeconds(125));
        }

        @Test
        @DisplayName("fourth failure schedules retry in 1 hour")
        void fourthRetry() {
            OutboxEntry entry = createEntry(3);
            doThrow(new OutboundDispatcher.DispatchException("503 Service Unavailable"))
                    .when(outboundDispatcher).dispatch(anyString(), anyString());
            when(outboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            worker.processEntry(entry);

            assertThat(entry.getAttemptCount()).isEqualTo(4);
            assertThat(entry.getStatus()).isEqualTo("PENDING");

            // nextAttemptAt should be ~1 hour in the future
            assertThat(entry.getNextAttemptAt())
                    .isAfter(Instant.now().plusSeconds(3595))
                    .isBefore(Instant.now().plusSeconds(3605));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Dead letter queue
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Dead letter queue")
    class DlqTests {

        @Test
        @DisplayName("fifth failure moves entry to DLQ")
        void movedToDlq() {
            OutboxEntry entry = createEntry(4); // 4 previous attempts
            doThrow(new OutboundDispatcher.DispatchException("Persistent failure"))
                    .when(outboundDispatcher).dispatch(anyString(), anyString());
            when(outboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(deadLetterRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            worker.processEntry(entry);

            // Outbox entry should be FAILED
            assertThat(entry.getStatus()).isEqualTo("FAILED");
            assertThat(entry.getAttemptCount()).isEqualTo(5);

            // DLQ entry should be created
            ArgumentCaptor<DeadLetterEntry> dlqCaptor =
                    ArgumentCaptor.forClass(DeadLetterEntry.class);
            verify(deadLetterRepository).save(dlqCaptor.capture());

            DeadLetterEntry dlqEntry = dlqCaptor.getValue();
            assertThat(dlqEntry.getEventId()).isEqualTo(entry.getEventId());
            assertThat(dlqEntry.getUbid()).isEqualTo("KA-2024-001");
            assertThat(dlqEntry.getTargetSystemId()).isEqualTo("FACTORIES");
            assertThat(dlqEntry.getFailureReason()).contains("Persistent failure");
            assertThat(dlqEntry.isResolved()).isFalse();
        }

        @Test
        @DisplayName("DLQ writes DLQ_PARKED audit")
        void dlqAudit() {
            OutboxEntry entry = createEntry(4);
            doThrow(new OutboundDispatcher.DispatchException("Fatal"))
                    .when(outboundDispatcher).dispatch(anyString(), anyString());
            when(outboxRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(deadLetterRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            worker.processEntry(entry);

            verify(auditService).recordAudit(
                    eq(entry.getEventId().toString()),
                    eq("KA-2024-001"),
                    eq("FABRIC"),
                    eq("FACTORIES"),
                    eq(com.karnataka.fabric.core.domain.AuditEventType.DLQ_PARKED),
                    anyMap(),
                    isNull());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Backoff schedule
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Backoff schedule")
    class BackoffTests {

        @Test
        @DisplayName("backoff durations match specification")
        void backoffDurations() {
            assertThat(OutboxWorker.getBackoffDuration(1))
                    .isEqualTo(Duration.ofSeconds(30));
            assertThat(OutboxWorker.getBackoffDuration(2))
                    .isEqualTo(Duration.ofMinutes(2));
            assertThat(OutboxWorker.getBackoffDuration(3))
                    .isEqualTo(Duration.ofMinutes(10));
            assertThat(OutboxWorker.getBackoffDuration(4))
                    .isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("MAX_ATTEMPTS is 5")
        void maxAttempts() {
            assertThat(OutboxWorker.MAX_ATTEMPTS).isEqualTo(5);
        }
    }
}

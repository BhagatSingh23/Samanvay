package com.karnataka.fabric.core.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares all Kafka topics used by the Karnataka Integration Fabric.
 *
 * <p>Topic names and partition counts are externalised to
 * {@code application.yml}; nothing is hardcoded.</p>
 */
@Configuration
public class KafkaTopicConfig {

    // ── Topic names ──────────────────────────────────────────────

    @Value("${fabric.kafka.topics.sws-inbound}")
    private String swsInboundTopic;

    @Value("${fabric.kafka.topics.dept-inbound}")
    private String deptInboundTopic;

    @Value("${fabric.kafka.topics.propagation-outbound}")
    private String propagationOutboundTopic;

    @Value("${fabric.kafka.topics.audit-trail}")
    private String auditTrailTopic;

    @Value("${fabric.kafka.topics.conflict-detected}")
    private String conflictDetectedTopic;

    @Value("${fabric.kafka.topics.dlq}")
    private String dlqTopic;

    // ── Partition counts ─────────────────────────────────────────

    @Value("${fabric.kafka.partitions.high:6}")
    private int highPartitions;

    @Value("${fabric.kafka.partitions.low:3}")
    private int lowPartitions;

    // ── Topic beans ──────────────────────────────────────────────

    /** SWS inbound events — keyed by ubid, 6 partitions. */
    @Bean
    public NewTopic swsInboundEvents() {
        return TopicBuilder.name(swsInboundTopic)
                .partitions(highPartitions)
                .replicas(1)
                .build();
    }

    /** Department inbound events — keyed by ubid, 6 partitions. */
    @Bean
    public NewTopic deptInboundEvents() {
        return TopicBuilder.name(deptInboundTopic)
                .partitions(highPartitions)
                .replicas(1)
                .build();
    }

    /** Propagation outbound events — 6 partitions. */
    @Bean
    public NewTopic propagationOutboundEvents() {
        return TopicBuilder.name(propagationOutboundTopic)
                .partitions(highPartitions)
                .replicas(1)
                .build();
    }

    /** Audit trail events — 3 partitions. */
    @Bean
    public NewTopic auditTrailEvents() {
        return TopicBuilder.name(auditTrailTopic)
                .partitions(lowPartitions)
                .replicas(1)
                .build();
    }

    /** Conflict detected events — 3 partitions. */
    @Bean
    public NewTopic conflictDetectedEvents() {
        return TopicBuilder.name(conflictDetectedTopic)
                .partitions(lowPartitions)
                .replicas(1)
                .build();
    }

    /** Dead-letter queue — 3 partitions. */
    @Bean
    public NewTopic dlqEvents() {
        return TopicBuilder.name(dlqTopic)
                .partitions(lowPartitions)
                .replicas(1)
                .build();
    }
}

package com.karnataka.fabric.core.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic swsInboundEvents() {
        return TopicBuilder.name("sws-inbound").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic deptInboundEvents() {
        return TopicBuilder.name("dept-inbound").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic propagationOutboundEvents() {
        return TopicBuilder.name("propagation-outbound").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic auditTrailEvents() {
        return TopicBuilder.name("audit-trail").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic conflictDetectedEvents() {
        return TopicBuilder.name("conflict-detected").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic dlqEvents() {
        return TopicBuilder.name("dlq").partitions(3).replicas(1).build();
    }
}

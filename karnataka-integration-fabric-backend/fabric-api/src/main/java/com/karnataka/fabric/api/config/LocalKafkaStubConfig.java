package com.karnataka.fabric.api.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Provides a stub {@link KafkaTemplate} when the {@code local} profile is active.
 *
 * <p>Logs every message to the console instead of sending to a real broker.</p>
 */
@Configuration
@Profile("local")
public class LocalKafkaStubConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalKafkaStubConfig.class);

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        // Dummy factory — never actually used because we override send()
        var props = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        var factory = new DefaultKafkaProducerFactory<String, String>(props);

        return new KafkaTemplate<>(factory) {
            @Override
            public CompletableFuture<SendResult<String, String>> send(
                    String topic, String key, String data) {

                log.info("╔══════════════════════════════════════════════════════");
                log.info("║ [STUB KAFKA] topic  = {}", topic);
                log.info("║ [STUB KAFKA] key    = {}", key);
                log.info("║ [STUB KAFKA] value  = {}",
                        data.length() > 300 ? data.substring(0, 300) + "..." : data);
                log.info("╚══════════════════════════════════════════════════════");

                RecordMetadata meta = new RecordMetadata(
                        new TopicPartition(topic, 0), 0, 0, 0, 0, 0);
                ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, data);
                return CompletableFuture.completedFuture(new SendResult<>(record, meta));
            }
        };
    }
}

package com.karnataka.fabric.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for the Factories webhook ingestion pipeline.
 *
 * <p>Verifies the full path: HTTP POST → normalise → enrich →
 * Kafka publish → audit record written.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookAdapterControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SuppressWarnings("unchecked")
    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void postFactoriesPayload_returns202_publishesToKafka_writesAudit() throws Exception {

        // Arrange — mock Kafka send to return a completed future
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        Map<String, Object> payload = Map.of(
                "ubid", "UBID-KA-FACT-00042",
                "serviceType", "ADDRESS_CHANGE",
                "factoryName", "Bengaluru Steel Works",
                "addressLine1", "Peenya Industrial Area",
                "pincode", "560058"
        );

        String json = objectMapper.writeValueAsString(payload);

        // Act — POST to Factories webhook
        var result = mockMvc.perform(post("/api/v1/inbound/FACTORIES")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))

                // Assert — HTTP 202 Accepted
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").isNotEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String eventId = objectMapper.readTree(responseBody).get("eventId").asText();

        // Assert — Kafka publish: verify send() was called with correct topic and key
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate).send(
                topicCaptor.capture(),
                keyCaptor.capture(),
                valueCaptor.capture()
        );

        assertThat(topicCaptor.getValue()).isEqualTo("dept.inbound.events");
        assertThat(keyCaptor.getValue()).isEqualTo("UBID-KA-FACT-00042");

        // Verify the Kafka message body contains the enriched event
        String kafkaMessage = valueCaptor.getValue();
        var kafkaJson = objectMapper.readTree(kafkaMessage);
        assertThat(kafkaJson.get("eventId").asText()).isEqualTo(eventId);
        assertThat(kafkaJson.get("ubid").asText()).isEqualTo("UBID-KA-FACT-00042");
        assertThat(kafkaJson.get("sourceSystemId").asText()).isEqualTo("DEPT_FACTORIES");
        assertThat(kafkaJson.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(kafkaJson.get("checksum").asText()).isNotBlank();
        assertThat(kafkaJson.get("ingestionTimestamp").asText()).isNotBlank();

        // Assert — Audit record written to database
        List<Map<String, Object>> auditRows = jdbcTemplate.queryForList(
                "SELECT * FROM audit_records WHERE event_id = ?::uuid", eventId
        );
        assertThat(auditRows).hasSize(1);

        Map<String, Object> auditRow = auditRows.getFirst();
        assertThat(auditRow.get("UBID").toString()).isEqualTo("UBID-KA-FACT-00042");
        assertThat(auditRow.get("SOURCE_SYSTEM").toString()).isEqualTo("DEPT_FACTORIES");
        assertThat(auditRow.get("AUDIT_EVENT_TYPE").toString()).isEqualTo("RECEIVED");
    }

    @Test
    void postUnknownDepartment_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/inbound/UNKNOWN_DEPT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ubid\": \"UBID-TEST\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Unknown department: UNKNOWN_DEPT"));
    }

    @Test
    void postMissingUbid_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/inbound/FACTORIES")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceType\": \"ADDRESS_CHANGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}

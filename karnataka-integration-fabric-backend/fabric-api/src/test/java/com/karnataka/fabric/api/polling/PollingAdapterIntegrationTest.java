package com.karnataka.fabric.api.polling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.core.AdapterMode;
import com.karnataka.fabric.adapters.polling.PollingAdapter;
import com.karnataka.fabric.adapters.registry.DepartmentConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration test for {@link PollingAdapter}.
 *
 * <p>Uses a {@link MockWebServer} to simulate the department's polling
 * endpoint.  After one poll cycle the test verifies:</p>
 * <ol>
 *   <li>Both events are published to Kafka</li>
 *   <li>Both audit records exist in the database</li>
 *   <li>The poll cursor was persisted</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class PollingAdapterIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Spring-managed PollingAdapter — has @Value fields properly injected. */
    @Autowired
    private PollingAdapter pollingAdapter;

    @SuppressWarnings("unchecked")
    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Stub Kafka send
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Clean up from previous runs
        jdbcTemplate.update("DELETE FROM audit_records");
        jdbcTemplate.update("DELETE FROM poll_cursors");
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void pollCycle_publishesBothEventsToKafka_writesAudit_updatesCursor() throws Exception {

        // Arrange — enqueue a response with 2 records
        String responseJson = objectMapper.writeValueAsString(List.of(
                Map.of(
                        "ubid", "UBID-KA-SE-10001",
                        "serviceType", "LICENCE_RENEWAL",
                        "establishmentName", "Bengaluru Book House",
                        "addressLine1", "MG Road, Bengaluru",
                        "pincode", "560001",
                        "eventTimestamp", "2026-05-02T10:00:00Z"
                ),
                Map.of(
                        "ubid", "UBID-KA-SE-10002",
                        "serviceType", "ADDRESS_CHANGE",
                        "establishmentName", "Mysore Silk Emporium",
                        "addressLine1", "Devaraja Market, Mysuru",
                        "pincode", "570001",
                        "eventTimestamp", "2026-05-02T10:05:00Z"
                )
        ));

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        // Build a config that points at our mock server instead of real URL
        String mockUrl = mockWebServer.url("/shop-estab/api/v1/changes").toString();
        DepartmentConfig testConfig = new DepartmentConfig(
                "SHOP_ESTAB",
                "Shops & Establishments",
                AdapterMode.POLLING,
                null,
                mockUrl,
                null,
                30,
                "mappings/shop_estab.json"
        );

        // Act — trigger one poll cycle for this department
        pollingAdapter.pollDepartment(testConfig);

        // Assert 1 — Kafka: send() called exactly 2 times
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate, times(2)).send(
                topicCaptor.capture(),
                keyCaptor.capture(),
                valueCaptor.capture()
        );

        // Verify topics — all published to dept-inbound topic
        assertThat(topicCaptor.getAllValues())
                .hasSize(2)
                .allSatisfy(t -> assertThat(t).isEqualTo("dept.inbound.events"));

        // Verify keys (ubids)
        assertThat(keyCaptor.getAllValues())
                .containsExactlyInAnyOrder("UBID-KA-SE-10001", "UBID-KA-SE-10002");

        // Verify Kafka message content
        for (String value : valueCaptor.getAllValues()) {
            var json = objectMapper.readTree(value);
            assertThat(json.get("eventId").asText()).isNotBlank();
            assertThat(json.get("sourceSystemId").asText()).isEqualTo("DEPT_SHOP_ESTAB");
            assertThat(json.get("status").asText()).isEqualTo("RECEIVED");
            assertThat(json.get("checksum").asText()).isNotBlank();
            assertThat(json.get("ingestionTimestamp").asText()).isNotBlank();
        }

        // Assert 2 — Audit records: 2 rows with RECEIVED type
        List<Map<String, Object>> auditRows = jdbcTemplate.queryForList(
                "SELECT * FROM audit_records WHERE audit_event_type = 'RECEIVED'"
        );
        assertThat(auditRows).hasSize(2);

        List<Object> auditUbids = auditRows.stream()
                .map(row -> row.get("UBID"))
                .toList();
        assertThat(auditUbids).containsExactlyInAnyOrder(
                "UBID-KA-SE-10001", "UBID-KA-SE-10002"
        );

        // Assert 3 — Poll cursor persisted
        List<Map<String, Object>> cursorRows = jdbcTemplate.queryForList(
                "SELECT * FROM poll_cursors WHERE dept_id = 'SHOP_ESTAB'"
        );
        assertThat(cursorRows).hasSize(1);
        assertThat(cursorRows.getFirst().get("LAST_CURSOR").toString()).isNotBlank();
    }
}

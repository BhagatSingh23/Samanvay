package com.karnataka.fabric.api.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.core.AdapterMode;
import com.karnataka.fabric.adapters.registry.DepartmentConfig;
import com.karnataka.fabric.adapters.snapshot.SnapshotDiffAdapter;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration test for {@link SnapshotDiffAdapter}.
 *
 * <p>Verifies the snapshot-diff algorithm across three runs:</p>
 * <ol>
 *   <li>First run: all 5 records are new → 5 events emitted</li>
 *   <li>Second run: same snapshot, no changes → 0 events emitted</li>
 *   <li>Third run: 1 record modified → exactly 1 event emitted</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class SnapshotDiffAdapterIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SnapshotDiffAdapter snapshotDiffAdapter;

    @SuppressWarnings("unchecked")
    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private MockWebServer mockWebServer;

    /** Mutable snapshot data — the test modifies this between runs. */
    private final AtomicReference<List<Map<String, Object>>> snapshotData = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();

        // Use a dispatcher so we can change the response between runs
        mockWebServer.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                try {
                    String json = objectMapper.writeValueAsString(snapshotData.get());
                    return new MockResponse()
                            .setBody(json)
                            .addHeader("Content-Type", "application/json");
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        });

        mockWebServer.start();

        // Stub Kafka send
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Clean up from previous runs
        jdbcTemplate.update("DELETE FROM audit_records");
        jdbcTemplate.update("DELETE FROM snapshot_hashes");
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void snapshotDiff_firstRun5Events_secondRun0Events_thirdRunAfterModify1Event() throws Exception {

        // ── Prepare 5 snapshot records ───────────────────────────
        List<Map<String, Object>> records = buildFiveRecords();
        snapshotData.set(records);

        String mockUrl = mockWebServer.url("/revenue/api/v1/snapshot").toString();
        DepartmentConfig testConfig = new DepartmentConfig(
                "REVENUE",
                "Department of Revenue",
                AdapterMode.SNAPSHOT_DIFF,
                null,
                null,
                mockUrl,
                0,
                "mappings/revenue.json"
        );

        // ══════════════════════════════════════════════════════════
        // RUN 1: All records are new → 5 events
        // ══════════════════════════════════════════════════════════
        snapshotDiffAdapter.snapshotDepartment(testConfig);

        ArgumentCaptor<String> keyCaptor1 = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(5)).send(
                anyString(),
                keyCaptor1.capture(),
                anyString()
        );

        assertThat(keyCaptor1.getAllValues()).containsExactlyInAnyOrder(
                "UBID-KA-REV-20001",
                "UBID-KA-REV-20002",
                "UBID-KA-REV-20003",
                "UBID-KA-REV-20004",
                "UBID-KA-REV-20005"
        );

        // Verify 5 audit records
        int auditCount1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_records WHERE audit_event_type = 'RECEIVED'",
                Integer.class
        );
        assertThat(auditCount1).isEqualTo(5);

        // Verify 5 hashes stored
        int hashCount1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM snapshot_hashes WHERE dept_id = 'REVENUE'",
                Integer.class
        );
        assertThat(hashCount1).isEqualTo(5);

        // ══════════════════════════════════════════════════════════
        // RUN 2: Same snapshot, no changes → 0 new events
        // ══════════════════════════════════════════════════════════
        reset(kafkaTemplate);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        snapshotDiffAdapter.snapshotDepartment(testConfig);

        // Kafka send should NOT have been called
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

        // ══════════════════════════════════════════════════════════
        // RUN 3: Modify one record → exactly 1 event
        // ══════════════════════════════════════════════════════════
        reset(kafkaTemplate);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Modify record 3 (UBID-KA-REV-20003) — change the assessed value
        List<Map<String, Object>> modifiedRecords = buildFiveRecords();
        Map<String, Object> modified = new LinkedHashMap<>(modifiedRecords.get(2));
        modified.put("assessedValue", 9999999);
        modified.put("ownerName", "Suresh Babu (Updated)");
        modifiedRecords.set(2, modified);
        snapshotData.set(modifiedRecords);

        snapshotDiffAdapter.snapshotDepartment(testConfig);

        ArgumentCaptor<String> keyCaptor3 = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(1)).send(
                anyString(),
                keyCaptor3.capture(),
                anyString()
        );

        // Only the modified record should have been published
        assertThat(keyCaptor3.getValue()).isEqualTo("UBID-KA-REV-20003");
    }

    // ── Test data builder ────────────────────────────────────────

    private List<Map<String, Object>> buildFiveRecords() {
        List<Map<String, Object>> records = new ArrayList<>();

        records.add(orderedMap(
                "ubid", "UBID-KA-REV-20001",
                "serviceType", "PROPERTY_TAX_ASSESSMENT",
                "ownerName", "Rajesh Kumar",
                "propertyAddress", "12th Cross, Jayanagar, Bengaluru",
                "pincode", "560011",
                "assessedValue", 2500000,
                "eventTimestamp", "2026-05-02T08:00:00Z"
        ));
        records.add(orderedMap(
                "ubid", "UBID-KA-REV-20002",
                "serviceType", "MUTATION_TRANSFER",
                "ownerName", "Lakshmi Devi",
                "propertyAddress", "4th Main, Rajajinagar, Bengaluru",
                "pincode", "560010",
                "assessedValue", 3200000,
                "eventTimestamp", "2026-05-02T08:15:00Z"
        ));
        records.add(orderedMap(
                "ubid", "UBID-KA-REV-20003",
                "serviceType", "ENCUMBRANCE_CHECK",
                "ownerName", "Suresh Babu",
                "propertyAddress", "KR Puram, Bengaluru",
                "pincode", "560036",
                "assessedValue", 1800000,
                "eventTimestamp", "2026-05-02T08:30:00Z"
        ));
        records.add(orderedMap(
                "ubid", "UBID-KA-REV-20004",
                "serviceType", "PROPERTY_TAX_ASSESSMENT",
                "ownerName", "Meena Kumari",
                "propertyAddress", "Chamundi Hill Road, Mysuru",
                "pincode", "570010",
                "assessedValue", 4100000,
                "eventTimestamp", "2026-05-02T09:00:00Z"
        ));
        records.add(orderedMap(
                "ubid", "UBID-KA-REV-20005",
                "serviceType", "MUTATION_TRANSFER",
                "ownerName", "Venkatesh Prasad",
                "propertyAddress", "Hubli-Dharwad Road, Dharwad",
                "pincode", "580001",
                "assessedValue", 1500000,
                "eventTimestamp", "2026-05-02T09:15:00Z"
        ));

        return records;
    }

    private Map<String, Object> orderedMap(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}

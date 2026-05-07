package com.karnataka.fabric.core.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson round-trip tests for {@link AuditRecord}.
 */
class AuditRecordJsonTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void initMapper() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private AuditRecord sampleAudit() {
        return new AuditRecord(
                "audit-001",
                "evt-aaa",
                "UBID-KA-2024-00001",
                "SWS",
                "DEPT_REV",
                AuditEventType.DISPATCHED,
                Instant.parse("2024-07-15T14:30:00Z"),
                null,
                null,
                Map.of("addressLine1", "Old Address"),
                Map.of("addressLine1", "MG Road, Bengaluru")
        );
    }

    @Test
    void roundTrip_preservesAllFields() throws JsonProcessingException {
        AuditRecord original = sampleAudit();
        String json = mapper.writeValueAsString(original);
        AuditRecord deserialized = mapper.readValue(json, AuditRecord.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void serialise_containsAllJsonPropertyNames() throws JsonProcessingException {
        String json = mapper.writeValueAsString(sampleAudit());

        assertThat(json)
                .contains("\"auditId\"")
                .contains("\"eventId\"")
                .contains("\"ubid\"")
                .contains("\"sourceSystem\"")
                .contains("\"targetSystem\"")
                .contains("\"auditEventType\"")
                .contains("\"timestamp\"")
                .contains("\"beforeState\"")
                .contains("\"afterState\"");
    }

    @Test
    void serialise_auditEventTypeAsString() throws JsonProcessingException {
        String json = mapper.writeValueAsString(sampleAudit());
        assertThat(json).contains("\"DISPATCHED\"");
    }

    @Test
    void deserialise_fromHandcraftedJson() throws JsonProcessingException {
        String json = """
                {
                  "auditId": "a-99",
                  "eventId": "evt-z",
                  "ubid": "UBID-HC",
                  "sourceSystem": "DEPT_FIN",
                  "targetSystem": "SWS",
                  "auditEventType": "CONFLICT_DETECTED",
                  "timestamp": "2024-08-01T09:00:00Z",
                  "conflictResolutionPolicy": "LAST_WRITE_WINS",
                  "supersededByEventId": "evt-newer",
                  "beforeState": { "pin": "560001" },
                  "afterState":  { "pin": "560002" }
                }
                """;
        AuditRecord rec = mapper.readValue(json, AuditRecord.class);

        assertThat(rec.auditId()).isEqualTo("a-99");
        assertThat(rec.eventId()).isEqualTo("evt-z");
        assertThat(rec.ubid()).isEqualTo("UBID-HC");
        assertThat(rec.auditEventType()).isEqualTo(AuditEventType.CONFLICT_DETECTED);
        assertThat(rec.conflictResolutionPolicy()).isEqualTo("LAST_WRITE_WINS");
        assertThat(rec.supersededByEventId()).isEqualTo("evt-newer");
        assertThat(rec.beforeState()).containsEntry("pin", "560001");
        assertThat(rec.afterState()).containsEntry("pin", "560002");
    }

    @Test
    void roundTrip_allAuditEventTypes() throws JsonProcessingException {
        for (AuditEventType type : AuditEventType.values()) {
            AuditRecord rec = new AuditRecord(
                    "a-" + type.name(), "evt-1", "UBID-1",
                    "SRC", "TGT", type,
                    Instant.now(), null, null, null, null
            );
            String json = mapper.writeValueAsString(rec);
            AuditRecord deserialized = mapper.readValue(json, AuditRecord.class);

            assertThat(deserialized.auditEventType()).isEqualTo(type);
        }
    }

    @Test
    void roundTrip_conflictResolutionFields() throws JsonProcessingException {
        AuditRecord original = new AuditRecord(
                "audit-conflict",
                "evt-conflict",
                "UBID-CONFLICT",
                "SWS", "DEPT_REV",
                AuditEventType.CONFLICT_RESOLVED,
                Instant.now(),
                "LAST_WRITE_WINS",
                "evt-superseding",
                Map.of("field", "oldVal"),
                Map.of("field", "newVal")
        );
        String json = mapper.writeValueAsString(original);
        AuditRecord deserialized = mapper.readValue(json, AuditRecord.class);

        assertThat(deserialized).isEqualTo(original);
        assertThat(deserialized.conflictResolutionPolicy()).isEqualTo("LAST_WRITE_WINS");
        assertThat(deserialized.supersededByEventId()).isEqualTo("evt-superseding");
    }
}

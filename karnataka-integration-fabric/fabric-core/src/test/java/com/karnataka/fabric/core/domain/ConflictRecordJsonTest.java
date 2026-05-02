package com.karnataka.fabric.core.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson round-trip tests for {@link ConflictRecord}.
 */
class ConflictRecordJsonTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void initMapper() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private ConflictRecord sampleConflict() {
        return new ConflictRecord(
                "conflict-001",
                "UBID-KA-2024-00001",
                "evt-aaa",
                "evt-bbb",
                "LAST_WRITER_WINS",
                "evt-bbb",
                Instant.parse("2024-07-10T12:00:00Z"),
                "addressLine1"
        );
    }

    @Test
    void roundTrip_preservesAllFields() throws JsonProcessingException {
        ConflictRecord original = sampleConflict();
        String json = mapper.writeValueAsString(original);
        ConflictRecord deserialized = mapper.readValue(json, ConflictRecord.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void serialise_containsAllJsonPropertyNames() throws JsonProcessingException {
        String json = mapper.writeValueAsString(sampleConflict());

        assertThat(json)
                .contains("\"eventId\"")
                .contains("\"ubid\"")
                .contains("\"event1Id\"")
                .contains("\"event2Id\"")
                .contains("\"resolutionPolicy\"")
                .contains("\"winningEventId\"")
                .contains("\"resolvedAt\"")
                .contains("\"fieldInDispute\"");
    }

    @Test
    void deserialise_fromHandcraftedJson() throws JsonProcessingException {
        String json = """
                {
                  "eventId": "c-42",
                  "ubid": "UBID-MANUAL",
                  "event1Id": "e1",
                  "event2Id": "e2",
                  "resolutionPolicy": "MANUAL_REVIEW",
                  "winningEventId": null,
                  "resolvedAt": null,
                  "fieldInDispute": "pincode"
                }
                """;
        ConflictRecord rec = mapper.readValue(json, ConflictRecord.class);

        assertThat(rec.eventId()).isEqualTo("c-42");
        assertThat(rec.ubid()).isEqualTo("UBID-MANUAL");
        assertThat(rec.resolutionPolicy()).isEqualTo("MANUAL_REVIEW");
        assertThat(rec.winningEventId()).isNull();
        assertThat(rec.resolvedAt()).isNull();
        assertThat(rec.fieldInDispute()).isEqualTo("pincode");
    }

    @Test
    void roundTrip_unresolvedConflict() throws JsonProcessingException {
        ConflictRecord unresolved = new ConflictRecord(
                "conflict-unresolved",
                "UBID-HELD",
                "evt-x", "evt-y",
                "PENDING_MANUAL",
                null, null,
                "signatoryName"
        );
        String json = mapper.writeValueAsString(unresolved);
        ConflictRecord deserialized = mapper.readValue(json, ConflictRecord.class);

        assertThat(deserialized).isEqualTo(unresolved);
        assertThat(deserialized.winningEventId()).isNull();
        assertThat(deserialized.resolvedAt()).isNull();
    }
}

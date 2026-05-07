package com.karnataka.fabric.core.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson round-trip (serialise → JSON → deserialise) tests for
 * {@link CanonicalServiceRequest}.
 */
class CanonicalServiceRequestJsonTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void initMapper() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private CanonicalServiceRequest sampleRequest() {
        return new CanonicalServiceRequest(
                UUID.randomUUID().toString(),
                "UBID-KA-2024-00001",
                "SWS",
                "ADDRESS_CHANGE",
                Instant.parse("2024-06-15T10:30:00Z"),
                Instant.now(),
                Map.of("addressLine1", "MG Road, Bengaluru",
                       "pincode", "560001"),
                "sha256-abc123def456",
                PropagationStatus.RECEIVED
        );
    }

    @Test
    void roundTrip_preservesAllFields() throws JsonProcessingException {
        CanonicalServiceRequest original = sampleRequest();
        String json = mapper.writeValueAsString(original);
        CanonicalServiceRequest deserialized = mapper.readValue(json, CanonicalServiceRequest.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void serialise_containsAllJsonPropertyNames() throws JsonProcessingException {
        CanonicalServiceRequest original = sampleRequest();
        String json = mapper.writeValueAsString(original);

        assertThat(json)
                .contains("\"eventId\"")
                .contains("\"ubid\"")
                .contains("\"sourceSystemId\"")
                .contains("\"serviceType\"")
                .contains("\"eventTimestamp\"")
                .contains("\"ingestionTimestamp\"")
                .contains("\"payload\"")
                .contains("\"checksum\"")
                .contains("\"status\"");
    }

    @Test
    void serialise_statusRendersAsString() throws JsonProcessingException {
        CanonicalServiceRequest original = sampleRequest();
        String json = mapper.writeValueAsString(original);

        assertThat(json).contains("\"RECEIVED\"");
    }

    @Test
    void deserialise_fromHandcraftedJson() throws JsonProcessingException {
        String json = """
                {
                  "eventId": "evt-001",
                  "ubid": "UBID-KA-2024-99999",
                  "sourceSystemId": "DEPT_REV",
                  "serviceType": "SIGNATORY_UPDATE",
                  "eventTimestamp": "2024-07-01T08:00:00Z",
                  "ingestionTimestamp": "2024-07-01T08:00:01Z",
                  "payload": { "signatoryName": "Rajesh Kumar" },
                  "checksum": "sha256-feed",
                  "status": "PENDING"
                }
                """;
        CanonicalServiceRequest req = mapper.readValue(json, CanonicalServiceRequest.class);

        assertThat(req.eventId()).isEqualTo("evt-001");
        assertThat(req.ubid()).isEqualTo("UBID-KA-2024-99999");
        assertThat(req.sourceSystemId()).isEqualTo("DEPT_REV");
        assertThat(req.serviceType()).isEqualTo("SIGNATORY_UPDATE");
        assertThat(req.eventTimestamp()).isEqualTo(Instant.parse("2024-07-01T08:00:00Z"));
        assertThat(req.ingestionTimestamp()).isEqualTo(Instant.parse("2024-07-01T08:00:01Z"));
        assertThat(req.payload()).containsEntry("signatoryName", "Rajesh Kumar");
        assertThat(req.checksum()).isEqualTo("sha256-feed");
        assertThat(req.status()).isEqualTo(PropagationStatus.PENDING);
    }

    @Test
    void roundTrip_withNullOptionalFields() throws JsonProcessingException {
        CanonicalServiceRequest original = new CanonicalServiceRequest(
                "evt-minimal",
                "UBID-MIN",
                null, null, null, null, null, null,
                PropagationStatus.FAILED
        );
        String json = mapper.writeValueAsString(original);
        CanonicalServiceRequest deserialized = mapper.readValue(json, CanonicalServiceRequest.class);

        assertThat(deserialized.eventId()).isEqualTo("evt-minimal");
        assertThat(deserialized.ubid()).isEqualTo("UBID-MIN");
        assertThat(deserialized.status()).isEqualTo(PropagationStatus.FAILED);
        assertThat(deserialized.payload()).isNull();
    }
}

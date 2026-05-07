package com.karnataka.fabric.core.domain;

import java.time.Instant;
import java.util.Map;

public record CanonicalServiceRequest(
        String eventId,
        String ubid,
        String sourceSystemId,
        String serviceType,
        Instant eventTimestamp,
        Instant ingestionTimestamp,
        Map<String, Object> payload,
        String checksum,
        PropagationStatus status
) {}

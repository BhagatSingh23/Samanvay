package com.karnataka.fabric.core.domain;

import java.time.Instant;

public record ConflictRecord(
        String eventId,
        String ubid,
        String event1Id,
        String event2Id,
        String resolutionPolicy,
        String winningEventId,
        Instant resolvedAt,
        String fieldInDispute
) {}

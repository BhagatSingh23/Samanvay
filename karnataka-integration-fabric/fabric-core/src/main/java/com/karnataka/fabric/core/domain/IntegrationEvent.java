package com.karnataka.fabric.core.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical integration event — the fundamental unit of data exchange
 * across the Karnataka Integration Fabric.
 *
 * <p>This is a pure POJO with no Spring dependency.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationEvent {

    @Builder.Default
    private UUID eventId = UUID.randomUUID();

    @NotBlank
    private String source;

    @NotBlank
    private String eventType;

    @NotNull
    @Builder.Default
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp = Instant.now();

    private String correlationId;

    private Map<String, Object> payload;

    private Map<String, String> metadata;
}

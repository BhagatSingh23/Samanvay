package com.karnataka.fabric.core.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Declarative routing rule that maps an event type from a source
 * to one or more destination services.
 *
 * <p>Pure POJO — no Spring dependency.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingRule {

    @NotBlank
    private String ruleId;

    @NotBlank
    private String sourceEventType;

    private Set<String> destinationServiceCodes;

    private String filterExpression;

    @Builder.Default
    private boolean enabled = true;
}

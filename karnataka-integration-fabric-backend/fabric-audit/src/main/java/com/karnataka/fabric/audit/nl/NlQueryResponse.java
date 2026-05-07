package com.karnataka.fabric.audit.nl;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for natural-language audit queries.
 */
@Data
@Builder
public class NlQueryResponse {
    private String question;
    private String generatedSql;
    private String naturalSummary;
    private List<Map<String, Object>> results;
    private int rowCount;
    private Instant computedAt;
}

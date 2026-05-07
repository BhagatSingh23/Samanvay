package com.karnataka.fabric.audit.nl;

import com.karnataka.fabric.audit.JdbcAuditService;
import com.karnataka.fabric.core.domain.AuditEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Translates natural-language questions into SQL queries against the
 * audit database using the Anthropic API, executes them read-only,
 * and returns both raw results and a plain-English summary.
 */
@Service
public class NaturalLanguageQueryService {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageQueryService.class);

    private static final String SCHEMA_CONTEXT = """
            You are a read-only SQL assistant for a PostgreSQL 16 database.
            The schema is:

            audit_records(audit_id UUID, event_id UUID, ubid TEXT, source_system TEXT,
              target_system TEXT, audit_event_type TEXT, ts TIMESTAMPTZ,
              before_state JSONB, after_state JSONB)

            audit_event_type values: RECEIVED, TRANSLATED, DISPATCHED, CONFIRMED, FAILED,
              RETRY_QUEUED, DLQ_PARKED, CONFLICT_DETECTED, CONFLICT_RESOLVED, SCHEMA_DRIFT_DETECTED

            dead_letter_queue(dlq_id UUID, event_id UUID, ubid TEXT, target_system TEXT,
              failure_reason TEXT, attempt_count INT, parked_at TIMESTAMPTZ)

            conflict_records(conflict_id UUID, ubid TEXT, event1_id UUID, event2_id UUID,
              resolution_policy TEXT, winning_event_id UUID, field_in_dispute TEXT, resolved_at TIMESTAMPTZ)

            event_ledger(event_id UUID, ubid TEXT, source_system_id TEXT, service_type TEXT,
              status TEXT, payload JSONB, ingested_at TIMESTAMPTZ)

            propagation_outbox(outbox_id UUID, event_id UUID, target_system TEXT, status TEXT,
              attempt_count INT, next_attempt_at TIMESTAMPTZ, created_at TIMESTAMPTZ)

            Return ONLY a single valid PostgreSQL SELECT statement.
            Do not include any explanation, markdown formatting, semicolons, or preamble.
            Always include a LIMIT clause of at most 200 rows.
            """;

    private final JdbcTemplate jdbcTemplate;
    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final int maxResultRows;
    private final JdbcAuditService auditService;

    public NaturalLanguageQueryService(
            JdbcTemplate jdbcTemplate,
            WebClient.Builder webClientBuilder,
            @Value("${fabric.nlquery.api-key}") String apiKey,
            @Value("${fabric.nlquery.model}") String model,
            @Value("${fabric.nlquery.max-result-rows}") int maxResultRows,
            JdbcAuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.webClient = webClientBuilder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.maxResultRows = maxResultRows;
        this.auditService = auditService;
    }

    /**
     * Accepts a natural-language question, generates SQL via the Anthropic API,
     * executes it, summarises the results, and writes an audit record.
     */
    public NlQueryResponse query(String question) {
        try {
            // Step 1 — Generate SQL via Anthropic API
            String sql = generateSql(question);

            // Step 2 — Validate the SQL
            sql = validateSql(sql);

            // Step 3 — Execute the SQL
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);

            // Step 4 — Generate a natural-language summary
            String summary = generateSummary(sql, results);

            // Step 5 — Write an audit record
            writeAuditRecord(question, sql, results.size());

            // Step 6 — Build and return the response
            return NlQueryResponse.builder()
                    .question(question)
                    .generatedSql(sql)
                    .naturalSummary(summary)
                    .results(results)
                    .rowCount(results.size())
                    .computedAt(Instant.now())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("NL Query failed: " + e.getMessage(), e);
        }
    }

    // ── Private helpers ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String generateSql(String question) {
        String userMessage = SCHEMA_CONTEXT + "\n\nQuestion: " + question;

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 500,
                "messages", List.of(Map.of("role", "user", "content", userMessage))
        );

        Map<String, Object> response = webClient.post()
                .uri("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        return ((String) content.get(0).get("text")).trim();
    }

    private String validateSql(String sql) {
        if (!sql.toUpperCase().startsWith("SELECT")) {
            throw new IllegalArgumentException(
                    "Only SELECT queries are permitted. Generated: " + sql);
        }
        if (sql.contains(";")) {
            throw new IllegalArgumentException("SQL must not contain semicolons.");
        }
        if (!sql.toLowerCase().contains("limit")) {
            sql = sql + " LIMIT " + maxResultRows;
        }
        return sql;
    }

    @SuppressWarnings("unchecked")
    private String generateSummary(String sql, List<Map<String, Object>> results) {
        String summaryPrompt = "The following SQL was run: " + sql
                + "\nIt returned " + results.size() + " rows. The first 3 rows are: "
                + results.subList(0, Math.min(3, results.size())).toString()
                + "\nWrite a single plain-English sentence summarising these results for a government administrator. "
                + "Be specific about counts, department names, and dates found in the data.";

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 500,
                "messages", List.of(Map.of("role", "user", "content", summaryPrompt))
        );

        Map<String, Object> response = webClient.post()
                .uri("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        return ((String) content.get(0).get("text")).trim();
    }

    private void writeAuditRecord(String question, String sql, int rowCount) {
        try {
            String syntheticEventId = UUID.randomUUID().toString();
            auditService.recordAudit(
                    syntheticEventId,
                    null, // no UBID for NL queries
                    "DASHBOARD",
                    null,
                    AuditEventType.NL_QUERY_EXECUTED,
                    Map.of("question", question),
                    Map.of("generatedSql", sql, "rowCount", rowCount)
            );
        } catch (Exception e) {
            log.warn("Failed to write NL query audit record: {}", e.getMessage());
        }
    }
}

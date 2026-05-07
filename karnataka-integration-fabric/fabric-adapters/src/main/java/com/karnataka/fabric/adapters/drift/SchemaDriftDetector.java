package com.karnataka.fabric.adapters.drift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.mapping.MappingRepository;
import com.karnataka.fabric.adapters.mapping.MappingRuleField;
import com.karnataka.fabric.adapters.mapping.MappingRules;
import com.karnataka.fabric.adapters.mapping.SchemaMapping;
import com.karnataka.fabric.adapters.registry.DepartmentConfig;
import com.karnataka.fabric.adapters.registry.DepartmentRegistry;
import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Scheduled component that detects schema drift between the expected
 * field paths declared in {@code schema_mappings} and the actual fields
 * returned by department APIs.
 *
 * <p>Runs every 6 hours by default (configurable via
 * {@code drift.detector.cron}). For each active department config:</p>
 * <ol>
 *   <li>Fetches one sample record from the department's API
 *       (using {@code pollUrl} or {@code snapshotUrl})</li>
 *   <li>Extracts the set of JSON field paths from the response</li>
 *   <li>Compares against expected target fields from schema_mappings</li>
 *   <li>If any expected field is absent, creates a {@link DriftAlert}
 *       and publishes an audit event with type
 *       {@code SCHEMA_DRIFT_DETECTED}</li>
 * </ol>
 */
@Component
public class SchemaDriftDetector {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftDetector.class);

    private final DepartmentRegistry departmentRegistry;
    private final MappingRepository mappingRepository;
    private final DriftAlertRepository driftAlertRepository;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final AuditService auditService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${fabric.kafka.topics.audit-trail}")
    private String auditTrailTopic;

    public SchemaDriftDetector(DepartmentRegistry departmentRegistry,
                                MappingRepository mappingRepository,
                                DriftAlertRepository driftAlertRepository,
                                ObjectMapper objectMapper,
                                WebClient.Builder webClientBuilder,
                                AuditService auditService,
                                KafkaTemplate<String, String> kafkaTemplate) {
        this.departmentRegistry = departmentRegistry;
        this.mappingRepository = mappingRepository;
        this.driftAlertRepository = driftAlertRepository;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.build();
        this.auditService = auditService;
        this.kafkaTemplate = kafkaTemplate;
    }

    // ── Scheduled execution ─────────────────────────────────────

    /**
     * Runs drift detection for all active departments.
     * Default schedule: every 6 hours.
     */
    @Scheduled(cron = "${drift.detector.cron:0 0 */6 * * *}")
    public void detectDrift() {
        log.info("Starting schema drift detection cycle");
        Map<String, DepartmentConfig> configs = departmentRegistry.allConfigs();

        for (DepartmentConfig config : configs.values()) {
            try {
                detectDriftForDept(config);
            } catch (Exception e) {
                log.error("Drift detection failed for dept={}: {}",
                        config.deptId(), e.getMessage(), e);
            }
        }
        log.info("Schema drift detection cycle complete");
    }

    // ── Per-department drift detection ───────────────────────────

    /**
     * Detects drift for a single department. Package-private for testing.
     */
    public void detectDriftForDept(DepartmentConfig config) {
        String sampleUrl = getSampleUrl(config);
        if (sampleUrl == null) {
            log.debug("No sample URL for dept={}, skipping drift check", config.deptId());
            return;
        }

        // 1. Fetch one sample record from the dept API
        Map<String, Object> sampleRecord = fetchSampleRecord(sampleUrl);
        if (sampleRecord == null || sampleRecord.isEmpty()) {
            log.warn("Empty or null sample for dept={}, skipping", config.deptId());
            return;
        }

        // 2. Extract field paths from the response
        Set<String> liveFields = extractFieldPaths(sampleRecord, "");

        // 3. Load expected target fields from active mappings
        List<SchemaMapping> activeMappings = mappingRepository
                .findByDeptIdAndActiveTrue(config.deptId());

        for (SchemaMapping mapping : activeMappings) {
            MappingRules rules = parseMappingRules(mapping.getMappingRules());
            if (rules == null || rules.fields() == null) continue;

            Set<String> expectedFields = rules.fields().stream()
                    .map(MappingRuleField::targetField)
                    .collect(Collectors.toSet());

            // 4. Find missing fields
            Set<String> missingFields = expectedFields.stream()
                    .filter(f -> !liveFields.contains(f))
                    .collect(Collectors.toCollection(TreeSet::new));

            if (!missingFields.isEmpty()) {
                log.warn("Schema drift detected for dept={}, serviceType={}: missing {}",
                        config.deptId(), mapping.getServiceType(), missingFields);

                createDriftAlert(config.deptId(), missingFields);
                publishAuditEvent(config.deptId(), mapping.getServiceType(), missingFields);
            } else {
                log.debug("No drift for dept={}, serviceType={}",
                        config.deptId(), mapping.getServiceType());
            }
        }
    }

    // ── Sample URL resolution ───────────────────────────────────

    /**
     * Determines the URL to fetch a sample record from.
     * Prefers pollUrl, falls back to snapshotUrl.
     */
    private String getSampleUrl(DepartmentConfig config) {
        if (config.pollUrl() != null && !config.pollUrl().isBlank()) {
            return config.pollUrl();
        }
        if (config.snapshotUrl() != null && !config.snapshotUrl().isBlank()) {
            return config.snapshotUrl();
        }
        // For WEBHOOK-only depts, we'd need a separate sample endpoint
        return null;
    }

    // ── HTTP fetch ──────────────────────────────────────────────

    /**
     * Fetches a sample record from a department API.
     * If the response is an array, takes the first element.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> fetchSampleRecord(String url) {
        try {
            String body = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (body == null) return null;

            Object parsed = objectMapper.readValue(body, Object.class);
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> m) {
                    return (Map<String, Object>) m;
                }
            } else if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to fetch sample from {}: {}", url, e.getMessage());
            return null;
        }
    }

    // ── Field path extraction ───────────────────────────────────

    /**
     * Recursively extracts all field paths from a JSON map.
     * Produces flat keys for the top level only (to match target field names
     * in schema_mappings which are flat, not dot-notated).
     */
    Set<String> extractFieldPaths(Map<String, Object> map, String prefix) {
        Set<String> paths = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            paths.add(entry.getKey()); // Always add the flat key
            paths.add(key);            // Also add the dotted path

            if (entry.getValue() instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) nested;
                paths.addAll(extractFieldPaths(nestedMap, key));
            }
        }
        return paths;
    }

    // ── Alert persistence ───────────────────────────────────────

    private void createDriftAlert(String deptId, Set<String> missingFields) {
        try {
            String missingJson = objectMapper.writeValueAsString(missingFields);
            DriftAlert alert = new DriftAlert(deptId, missingJson);
            driftAlertRepository.save(alert);
            log.info("Created drift alert for dept={}: {}", deptId, missingFields);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise missing fields: {}", e.getMessage());
        }
    }

    // ── Audit event publishing ──────────────────────────────────

    private void publishAuditEvent(String deptId, String serviceType,
                                    Set<String> missingFields) {
        try {
            auditService.recordAudit(
                    UUID.randomUUID().toString(),
                    "SYSTEM",
                    deptId,
                    "FABRIC",
                    AuditEventType.SCHEMA_DRIFT_DETECTED,
                    Map.of("serviceType", serviceType,
                           "missingFields", missingFields),
                    null);

            // Also publish to Kafka audit trail
            Map<String, Object> auditEvent = Map.of(
                    "auditEventType", "SCHEMA_DRIFT_DETECTED",
                    "deptId", deptId,
                    "serviceType", serviceType,
                    "missingFields", missingFields,
                    "detectedAt", java.time.Instant.now().toString());

            kafkaTemplate.send(auditTrailTopic, deptId,
                    objectMapper.writeValueAsString(auditEvent));

        } catch (Exception e) {
            log.error("Failed to publish drift audit event: {}", e.getMessage());
        }
    }

    // ── JSON parsing ────────────────────────────────────────────

    private MappingRules parseMappingRules(String json) {
        try {
            return objectMapper.readValue(json, MappingRules.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse mapping_rules: {}", e.getMessage());
            return null;
        }
    }

    // ── Service for controller queries ──────────────────────────

    /**
     * Returns all unresolved drift alerts.
     */
    public List<DriftAlertResponse> getUnresolvedAlerts() {
        return driftAlertRepository.findByResolvedFalseOrderByDetectedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Returns all unresolved drift alerts for a specific department.
     */
    public List<DriftAlertResponse> getUnresolvedAlerts(String deptId) {
        return driftAlertRepository.findByDeptIdAndResolvedFalseOrderByDetectedAtDesc(deptId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private DriftAlertResponse toResponse(DriftAlert entity) {
        List<String> fields;
        try {
            fields = objectMapper.readValue(entity.getMissingFields(), List.class);
        } catch (JsonProcessingException e) {
            fields = List.of(entity.getMissingFields());
        }
        return new DriftAlertResponse(
                entity.getId(),
                entity.getDeptId(),
                fields,
                entity.getDetectedAt(),
                entity.isResolved());
    }
}

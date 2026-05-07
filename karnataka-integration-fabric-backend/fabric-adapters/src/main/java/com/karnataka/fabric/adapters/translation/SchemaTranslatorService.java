package com.karnataka.fabric.adapters.translation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.mapping.*;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import com.karnataka.fabric.core.domain.FieldTransform;
import com.karnataka.fabric.core.domain.PropagationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Translates between canonical and department-specific payloads using
 * schema mappings loaded from the {@code schema_mappings} table.
 *
 * <p>Active mappings are cached for 60 seconds via Caffeine to avoid
 * hitting the database on every translation.</p>
 *
 * <h3>Forward translation (canonical → department):</h3>
 * <ol>
 *   <li>Load active mapping for {@code (targetDeptId, serviceType)}</li>
 *   <li>For each field rule, extract the value from the canonical payload
 *       using dot-notation, apply the configured transform, and set it
 *       on the output using the target field name</li>
 *   <li>If any canonical field is missing → add a warning</li>
 * </ol>
 *
 * <h3>Reverse translation (department → canonical):</h3>
 * <ol>
 *   <li>Load active mapping for {@code (sourceDeptId, serviceType)}</li>
 *   <li>For each field rule, extract the value from the department payload
 *       using the target field name and set it on the canonical payload
 *       using the canonical field (dot-notation)</li>
 * </ol>
 */
@Service
public class SchemaTranslatorService {

    private static final Logger log = LoggerFactory.getLogger(SchemaTranslatorService.class);

    private final MappingRepository mappingRepository;
    private final ObjectMapper objectMapper;
    private final TransformEngine transformEngine;

    public SchemaTranslatorService(MappingRepository mappingRepository,
                                    ObjectMapper objectMapper,
                                    TransformEngine transformEngine) {
        this.mappingRepository = mappingRepository;
        this.objectMapper = objectMapper;
        this.transformEngine = transformEngine;
    }

    // ── Forward translation ─────────────────────────────────────

    /**
     * Translates a canonical service request payload to a
     * department-specific format.
     *
     * @param req          the canonical service request
     * @param targetDeptId the target department code (e.g. "FACTORIES")
     * @return the translation result with the transformed payload
     */
    public TranslationResult translate(CanonicalServiceRequest req, String targetDeptId) {
        if (req == null || req.serviceType() == null) {
            return TranslationResult.failure("CanonicalServiceRequest or serviceType is null");
        }

        MappingRules rules = loadActiveMapping(targetDeptId, req.serviceType());
        if (rules == null) {
            return TranslationResult.failure(
                    "No active mapping found for dept=" + targetDeptId +
                    ", serviceType=" + req.serviceType());
        }

        String mappingVersion = targetDeptId + "/" + req.serviceType() + "/v" +
                getMappingVersion(targetDeptId, req.serviceType());

        Map<String, Object> translatedPayload = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        Map<String, Object> sourcePayload = req.payload();
        if (sourcePayload == null) {
            sourcePayload = Map.of();
        }

        for (MappingRuleField rule : rules.fields()) {
            // Extract value using dot-notation path
            Object value = extractDotNotation(sourcePayload, rule.canonicalField());

            if (value == null) {
                warnings.add("Missing required field: " + rule.canonicalField());
                continue;
            }

            // Apply transform
            try {
                Object transformed = transformEngine.applyTransform(rule.transform(), value);
                translatedPayload.put(rule.targetField(), transformed);
            } catch (Exception e) {
                warnings.add("Transform " + rule.transform() + " failed on field " +
                        rule.canonicalField() + ": " + e.getMessage());
                // Still set the raw value on failure
                translatedPayload.put(rule.targetField(), value);
            }
        }

        log.debug("Translated canonical→{}: {} fields mapped, {} warnings",
                targetDeptId, translatedPayload.size(), warnings.size());

        return TranslationResult.success(translatedPayload, mappingVersion, warnings);
    }

    // ── Reverse translation ─────────────────────────────────────

    /**
     * Translates a department-specific payload back to a
     * {@link CanonicalServiceRequest}.
     *
     * <p>Reverse translation does NOT apply inverse transforms (since
     * not all transforms are reversible). The department payload values
     * are set directly onto the canonical field paths.</p>
     *
     * @param deptPayload  the department-specific payload
     * @param sourceDeptId the source department code
     * @param serviceType  the service type (e.g. "ADDRESS_CHANGE")
     * @return a new CanonicalServiceRequest with the translated payload
     */
    public CanonicalServiceRequest translateToCanonical(Map<String, Object> deptPayload,
                                                         String sourceDeptId,
                                                         String serviceType) {
        MappingRules rules = loadActiveMapping(sourceDeptId, serviceType);
        if (rules == null) {
            log.warn("No active mapping for reverse translation: dept={}, serviceType={}",
                    sourceDeptId, serviceType);
            // Return a request with the raw payload untranslated
            return new CanonicalServiceRequest(
                    UUID.randomUUID().toString(),
                    "", // UBID must be populated by caller
                    "DEPT_" + sourceDeptId,
                    serviceType,
                    null,
                    Instant.now(),
                    deptPayload != null ? deptPayload : Map.of(),
                    null,
                    PropagationStatus.RECEIVED);
        }

        Map<String, Object> canonicalPayload = new LinkedHashMap<>();

        if (deptPayload != null) {
            for (MappingRuleField rule : rules.fields()) {
                Object value = deptPayload.get(rule.targetField());
                if (value != null) {
                    setDotNotation(canonicalPayload, rule.canonicalField(), value);
                }
            }
        }

        return new CanonicalServiceRequest(
                UUID.randomUUID().toString(),
                "", // UBID must be populated by caller
                "DEPT_" + sourceDeptId,
                serviceType,
                null,
                Instant.now(),
                canonicalPayload,
                null,
                PropagationStatus.RECEIVED);
    }

    // ── Cached mapping lookup ───────────────────────────────────

    /**
     * Loads the active mapping rules for a dept + service type.
     * Cached for 60 seconds via Caffeine.
     */
    @Cacheable(value = "mappings", key = "#deptId + '/' + #serviceType")
    public MappingRules loadActiveMapping(String deptId, String serviceType) {
        log.debug("Loading active mapping from DB: dept={}, serviceType={}", deptId, serviceType);
        return mappingRepository.findByDeptIdAndServiceTypeAndActiveTrue(deptId, serviceType)
                .map(entity -> parseMappingRules(entity.getMappingRules()))
                .orElse(null);
    }

    private int getMappingVersion(String deptId, String serviceType) {
        return mappingRepository.findByDeptIdAndServiceTypeAndActiveTrue(deptId, serviceType)
                .map(SchemaMapping::getVersion)
                .orElse(0);
    }

    // ── Dot-notation helpers ────────────────────────────────────

    /**
     * Extracts a value from a nested map using dot-notation.
     * <p>Example: {@code "registeredAddress.line1"} extracts
     * {@code map.get("registeredAddress").get("line1")}.</p>
     */
    @SuppressWarnings("unchecked")
    static Object extractDotNotation(Map<String, Object> map, String dotPath) {
        if (map == null || dotPath == null) {
            return null;
        }

        String[] parts = dotPath.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (current instanceof Map<?, ?> currentMap) {
                current = currentMap.get(part);
                if (current == null) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Sets a value into a nested map using dot-notation, creating
     * intermediate maps as needed.
     * <p>Example: {@code "registeredAddress.line1"} sets
     * {@code map["registeredAddress"]["line1"] = value}.</p>
     */
    @SuppressWarnings("unchecked")
    static void setDotNotation(Map<String, Object> map, String dotPath, Object value) {
        String[] parts = dotPath.split("\\.");

        Map<String, Object> current = map;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (next instanceof Map<?, ?>) {
                current = (Map<String, Object>) next;
            } else {
                Map<String, Object> newMap = new LinkedHashMap<>();
                current.put(parts[i], newMap);
                current = newMap;
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    // ── JSON parsing ────────────────────────────────────────────

    private MappingRules parseMappingRules(String json) {
        try {
            return objectMapper.readValue(json, MappingRules.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse mapping_rules JSON: {}", e.getMessage());
            return null;
        }
    }
}

package com.karnataka.fabric.adapters.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.mapping.MappingRepository;
import com.karnataka.fabric.adapters.mapping.SchemaMapping;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import com.karnataka.fabric.core.domain.PropagationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SchemaTranslatorService}.
 *
 * <p>Tests forward translation (canonical → dept), reverse translation
 * (dept → canonical), missing field handling, and dot-notation
 * extraction/setting.</p>
 */
@ExtendWith(MockitoExtension.class)
class SchemaTranslatorServiceTest {

    @Mock
    private MappingRepository mappingRepository;

    private SchemaTranslatorService service;
    private ObjectMapper objectMapper;
    private TransformEngine transformEngine;

    // ── Sample Factories mapping JSON ────────────────────────────
    private static final String FACTORIES_MAPPING_JSON = """
            {
                "fields": [
                    {"canonicalField": "registeredAddress.line1", "targetField": "addr_line_1", "transform": "UPPERCASE"},
                    {"canonicalField": "registeredAddress.line2", "targetField": "addr_line_2", "transform": "UPPERCASE"},
                    {"canonicalField": "registeredAddress.pincode", "targetField": "postal_code", "transform": "NONE"},
                    {"canonicalField": "registeredAddress.city", "targetField": "city_name", "transform": "UPPERCASE"},
                    {"canonicalField": "registeredAddress.state", "targetField": "state_code", "transform": "UPPERCASE"},
                    {"canonicalField": "businessName", "targetField": "est_name", "transform": "NONE"},
                    {"canonicalField": "contactPerson", "targetField": "contact_person", "transform": "SPLIT_FULLNAME_TO_FIRST_LAST"}
                ]
            }
            """;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        transformEngine = new TransformEngine();
        service = new SchemaTranslatorService(mappingRepository, objectMapper, transformEngine);
    }

    /**
     * Stubs the repository to return a Factories ADDRESS_CHANGE mapping.
     */
    private void stubFactoriesMapping() {
        SchemaMapping entity = new SchemaMapping(
                "FACTORIES", "ADDRESS_CHANGE", 1, true, FACTORIES_MAPPING_JSON);
        when(mappingRepository.findByDeptIdAndServiceTypeAndActiveTrue(
                eq("FACTORIES"), eq("ADDRESS_CHANGE")))
                .thenReturn(Optional.of(entity));
    }

    /**
     * Builds a sample canonical payload with all Factories fields present.
     */
    private Map<String, Object> buildFullFactoriesPayload() {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("line1", "MG Road");
        address.put("line2", "3rd Cross");
        address.put("pincode", "560001");
        address.put("city", "Bengaluru");
        address.put("state", "Karnataka");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("registeredAddress", address);
        payload.put("businessName", "Karnataka Steel Works");
        payload.put("contactPerson", "Rajesh Kumar");
        return payload;
    }

    private CanonicalServiceRequest buildRequest(Map<String, Object> payload) {
        return new CanonicalServiceRequest(
                "evt-001", "KA-2024-001", "SWS", "ADDRESS_CHANGE",
                Instant.parse("2024-01-15T10:30:00Z"),
                Instant.now(), payload, "sha256-test",
                PropagationStatus.RECEIVED);
    }

    // ═══════════════════════════════════════════════════════════
    // Forward translation tests
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("translate(canonical → dept)")
    class ForwardTranslationTests {

        @Test
        @DisplayName("translates full Factories payload with all transforms applied")
        void fullFactoriesTranslation() {
            stubFactoriesMapping();
            Map<String, Object> payload = buildFullFactoriesPayload();
            CanonicalServiceRequest req = buildRequest(payload);

            TranslationResult result = service.translate(req, "FACTORIES");

            assertThat(result.success()).isTrue();
            assertThat(result.warnings()).isEmpty();
            assertThat(result.mappingVersion()).contains("FACTORIES/ADDRESS_CHANGE");

            Map<String, Object> translated = result.translatedPayload();
            assertThat(translated.get("addr_line_1")).isEqualTo("MG ROAD");
            assertThat(translated.get("addr_line_2")).isEqualTo("3RD CROSS");
            assertThat(translated.get("postal_code")).isEqualTo("560001");
            assertThat(translated.get("city_name")).isEqualTo("BENGALURU");
            assertThat(translated.get("state_code")).isEqualTo("KARNATAKA");
            assertThat(translated.get("est_name")).isEqualTo("Karnataka Steel Works");

            // contactPerson should be split
            @SuppressWarnings("unchecked")
            Map<String, Object> contact = (Map<String, Object>) translated.get("contact_person");
            assertThat(contact.get("firstName")).isEqualTo("Rajesh");
            assertThat(contact.get("lastName")).isEqualTo("Kumar");
        }

        @Test
        @DisplayName("translate with missing required field returns success=false + warning")
        void missingRequiredField() {
            stubFactoriesMapping();

            // Payload missing registeredAddress.line2 and contactPerson
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("line1", "MG Road");
            address.put("pincode", "560001");
            address.put("city", "Bengaluru");
            address.put("state", "Karnataka");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("registeredAddress", address);
            payload.put("businessName", "Karnataka Steel Works");
            // contactPerson intentionally missing

            CanonicalServiceRequest req = buildRequest(payload);

            TranslationResult result = service.translate(req, "FACTORIES");

            assertThat(result.success()).isFalse();
            assertThat(result.warnings()).hasSize(2);
            assertThat(result.warnings()).anyMatch(w ->
                    w.contains("registeredAddress.line2"));
            assertThat(result.warnings()).anyMatch(w ->
                    w.contains("contactPerson"));

            // Successfully translated fields should still be present
            assertThat(result.translatedPayload().get("addr_line_1")).isEqualTo("MG ROAD");
            assertThat(result.translatedPayload().get("postal_code")).isEqualTo("560001");
        }

        @Test
        @DisplayName("translate with no active mapping returns failure")
        void noActiveMapping() {
            when(mappingRepository.findByDeptIdAndServiceTypeAndActiveTrue(
                    eq("UNKNOWN_DEPT"), eq("ADDRESS_CHANGE")))
                    .thenReturn(Optional.empty());

            CanonicalServiceRequest req = buildRequest(Map.of("field", "value"));

            TranslationResult result = service.translate(req, "UNKNOWN_DEPT");

            assertThat(result.success()).isFalse();
            assertThat(result.warnings()).hasSize(1);
            assertThat(result.warnings().get(0)).contains("No active mapping");
        }

        @Test
        @DisplayName("translate with null request returns failure")
        void nullRequest() {
            TranslationResult result = service.translate(null, "FACTORIES");
            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("translate with null payload uses empty map")
        void nullPayload() {
            stubFactoriesMapping();
            CanonicalServiceRequest req = new CanonicalServiceRequest(
                    "evt-001", "KA-2024-001", "SWS", "ADDRESS_CHANGE",
                    null, null, null, null, PropagationStatus.RECEIVED);

            TranslationResult result = service.translate(req, "FACTORIES");

            assertThat(result.success()).isFalse();
            // All 7 fields should be reported as missing
            assertThat(result.warnings()).hasSize(7);
        }

        @Test
        @DisplayName("translate with empty payload reports all fields missing")
        void emptyPayload() {
            stubFactoriesMapping();
            CanonicalServiceRequest req = buildRequest(Map.of());

            TranslationResult result = service.translate(req, "FACTORIES");

            assertThat(result.success()).isFalse();
            assertThat(result.warnings()).hasSize(7);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Reverse translation tests
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("translateToCanonical(dept → canonical)")
    class ReverseTranslationTests {

        @Test
        @DisplayName("reverse translates Factories payload to canonical")
        void reverseFactories() {
            stubFactoriesMapping();

            Map<String, Object> deptPayload = new LinkedHashMap<>();
            deptPayload.put("addr_line_1", "MG ROAD");
            deptPayload.put("addr_line_2", "3RD CROSS");
            deptPayload.put("postal_code", "560001");
            deptPayload.put("city_name", "BENGALURU");
            deptPayload.put("state_code", "KARNATAKA");
            deptPayload.put("est_name", "Karnataka Steel Works");

            CanonicalServiceRequest result = service.translateToCanonical(
                    deptPayload, "FACTORIES", "ADDRESS_CHANGE");

            assertThat(result.sourceSystemId()).isEqualTo("DEPT_FACTORIES");
            assertThat(result.serviceType()).isEqualTo("ADDRESS_CHANGE");
            assertThat(result.status()).isEqualTo(PropagationStatus.RECEIVED);

            // Check nested canonical payload
            Map<String, Object> payload = result.payload();
            assertThat(payload).containsKey("registeredAddress");
            @SuppressWarnings("unchecked")
            Map<String, Object> addr = (Map<String, Object>) payload.get("registeredAddress");
            assertThat(addr.get("line1")).isEqualTo("MG ROAD");
            assertThat(addr.get("pincode")).isEqualTo("560001");
            assertThat(payload.get("businessName")).isEqualTo("Karnataka Steel Works");
        }

        @Test
        @DisplayName("reverse translate with no mapping returns raw payload")
        void noMappingReturnsRaw() {
            when(mappingRepository.findByDeptIdAndServiceTypeAndActiveTrue(
                    eq("UNKNOWN"), eq("ADDRESS_CHANGE")))
                    .thenReturn(Optional.empty());

            Map<String, Object> deptPayload = Map.of("raw_field", "raw_value");

            CanonicalServiceRequest result = service.translateToCanonical(
                    deptPayload, "UNKNOWN", "ADDRESS_CHANGE");

            assertThat(result.payload()).isEqualTo(deptPayload);
            assertThat(result.sourceSystemId()).isEqualTo("DEPT_UNKNOWN");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Dot-notation helper tests
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("dot-notation helpers")
    class DotNotationTests {

        @Test
        @DisplayName("extractDotNotation navigates nested map")
        void extractNested() {
            Map<String, Object> inner = Map.of("line1", "MG Road");
            Map<String, Object> outer = Map.of("registeredAddress", inner);

            Object result = SchemaTranslatorService.extractDotNotation(
                    outer, "registeredAddress.line1");
            assertThat(result).isEqualTo("MG Road");
        }

        @Test
        @DisplayName("extractDotNotation returns null for missing path")
        void extractMissing() {
            Map<String, Object> map = Map.of("a", Map.of("b", "value"));
            Object result = SchemaTranslatorService.extractDotNotation(map, "a.c");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("extractDotNotation handles top-level key")
        void extractTopLevel() {
            Map<String, Object> map = Map.of("businessName", "Test Corp");
            Object result = SchemaTranslatorService.extractDotNotation(
                    map, "businessName");
            assertThat(result).isEqualTo("Test Corp");
        }

        @Test
        @DisplayName("setDotNotation creates nested maps")
        void setNested() {
            Map<String, Object> map = new LinkedHashMap<>();
            SchemaTranslatorService.setDotNotation(
                    map, "registeredAddress.line1", "MG Road");

            assertThat(map).containsKey("registeredAddress");
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) map.get("registeredAddress");
            assertThat(nested.get("line1")).isEqualTo("MG Road");
        }

        @Test
        @DisplayName("setDotNotation sets top-level key")
        void setTopLevel() {
            Map<String, Object> map = new LinkedHashMap<>();
            SchemaTranslatorService.setDotNotation(map, "businessName", "Test");
            assertThat(map.get("businessName")).isEqualTo("Test");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Full end-to-end with all 7 transforms
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("E2E: all 7 Factories transforms applied correctly")
    void allSevenTransforms() {
        stubFactoriesMapping();

        Map<String, Object> payload = buildFullFactoriesPayload();
        CanonicalServiceRequest req = buildRequest(payload);

        TranslationResult result = service.translate(req, "FACTORIES");

        assertThat(result.success()).isTrue();

        Map<String, Object> t = result.translatedPayload();
        // 1. UPPERCASE on addr_line_1
        assertThat(t.get("addr_line_1")).isEqualTo("MG ROAD");
        // 2. UPPERCASE on addr_line_2
        assertThat(t.get("addr_line_2")).isEqualTo("3RD CROSS");
        // 3. NONE on postal_code
        assertThat(t.get("postal_code")).isEqualTo("560001");
        // 4. UPPERCASE on city_name
        assertThat(t.get("city_name")).isEqualTo("BENGALURU");
        // 5. UPPERCASE on state_code
        assertThat(t.get("state_code")).isEqualTo("KARNATAKA");
        // 6. NONE on est_name
        assertThat(t.get("est_name")).isEqualTo("Karnataka Steel Works");
        // 7. SPLIT_FULLNAME_TO_FIRST_LAST on contact_person
        assertThat(t.get("contact_person")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) t.get("contact_person");
        assertThat(contact.get("firstName")).isEqualTo("Rajesh");
        assertThat(contact.get("lastName")).isEqualTo("Kumar");
    }
}

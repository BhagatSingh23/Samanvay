package com.karnataka.fabric.adapters.translation;

import com.karnataka.fabric.core.domain.FieldTransform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransformEngine} — covers all 7
 * {@link FieldTransform} variants with sample Factories data.
 */
class TransformEngineTest {

    private TransformEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TransformEngine();
    }

    // ── NONE ────────────────────────────────────────────────────

    @Nested
    @DisplayName("NONE transform")
    class NoneTests {

        @Test
        @DisplayName("passes through string value unchanged")
        void passesThrough() {
            Object result = engine.applyTransform(FieldTransform.NONE, "MG Road");
            assertThat(result).isEqualTo("MG Road");
        }

        @Test
        @DisplayName("passes through numeric value unchanged")
        void passesThroughNumber() {
            Object result = engine.applyTransform(FieldTransform.NONE, 42);
            assertThat(result).isEqualTo(42);
        }

        @Test
        @DisplayName("passes through null unchanged")
        void passesThroughNull() {
            Object result = engine.applyTransform(FieldTransform.NONE, null);
            assertThat(result).isNull();
        }
    }

    // ── UPPERCASE ───────────────────────────────────────────────

    @Nested
    @DisplayName("UPPERCASE transform")
    class UppercaseTests {

        @Test
        @DisplayName("converts Factories address line to upper case")
        void factoriesAddress() {
            Object result = engine.applyTransform(FieldTransform.UPPERCASE, "MG Road, 3rd Cross");
            assertThat(result).isEqualTo("MG ROAD, 3RD CROSS");
        }

        @Test
        @DisplayName("converts city name to upper case")
        void cityName() {
            Object result = engine.applyTransform(FieldTransform.UPPERCASE, "Bengaluru");
            assertThat(result).isEqualTo("BENGALURU");
        }

        @Test
        @DisplayName("already-uppercase string stays unchanged")
        void alreadyUpper() {
            Object result = engine.applyTransform(FieldTransform.UPPERCASE, "KARNATAKA");
            assertThat(result).isEqualTo("KARNATAKA");
        }
    }

    // ── LOWERCASE ───────────────────────────────────────────────

    @Nested
    @DisplayName("LOWERCASE transform")
    class LowercaseTests {

        @Test
        @DisplayName("converts mixed-case to lower case")
        void mixedCase() {
            Object result = engine.applyTransform(FieldTransform.LOWERCASE, "Bengaluru");
            assertThat(result).isEqualTo("bengaluru");
        }

        @Test
        @DisplayName("converts Factories state code to lower case")
        void stateCode() {
            Object result = engine.applyTransform(FieldTransform.LOWERCASE, "KA");
            assertThat(result).isEqualTo("ka");
        }
    }

    // ── DATE_ISO_TO_EPOCH ───────────────────────────────────────

    @Nested
    @DisplayName("DATE_ISO_TO_EPOCH transform")
    class IsoToEpochTests {

        @Test
        @DisplayName("converts ISO-8601 instant to epoch millis")
        void convertsInstant() {
            String iso = "2024-01-15T10:30:00Z";
            Object result = engine.applyTransform(FieldTransform.DATE_ISO_TO_EPOCH, iso);
            long expected = Instant.parse(iso).toEpochMilli();
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("handles ISO with subsecond precision")
        void withMillis() {
            String iso = "2024-06-01T08:15:30.123Z";
            Object result = engine.applyTransform(FieldTransform.DATE_ISO_TO_EPOCH, iso);
            assertThat(result).isEqualTo(Instant.parse(iso).toEpochMilli());
        }

        @Test
        @DisplayName("returns original on invalid date string")
        void invalidDate() {
            Object result = engine.applyTransform(FieldTransform.DATE_ISO_TO_EPOCH, "not-a-date");
            assertThat(result).isEqualTo("not-a-date");
        }
    }

    // ── DATE_EPOCH_TO_ISO ───────────────────────────────────────

    @Nested
    @DisplayName("DATE_EPOCH_TO_ISO transform")
    class EpochToIsoTests {

        @Test
        @DisplayName("converts epoch millis (long) to ISO-8601")
        void convertsLong() {
            long epochMillis = 1705311000000L; // 2024-01-15T10:30:00Z
            Object result = engine.applyTransform(FieldTransform.DATE_EPOCH_TO_ISO, epochMillis);
            assertThat(result).isEqualTo("2024-01-15T10:30:00Z");
        }

        @Test
        @DisplayName("converts epoch millis (String) to ISO-8601")
        void convertsString() {
            Object result = engine.applyTransform(FieldTransform.DATE_EPOCH_TO_ISO, "1705311000000");
            assertThat(result).isEqualTo("2024-01-15T10:30:00Z");
        }

        @Test
        @DisplayName("returns original on non-numeric string")
        void invalidEpoch() {
            Object result = engine.applyTransform(FieldTransform.DATE_EPOCH_TO_ISO, "not-a-number");
            assertThat(result).isEqualTo("not-a-number");
        }
    }

    // ── SPLIT_FULLNAME_TO_FIRST_LAST ────────────────────────────

    @Nested
    @DisplayName("SPLIT_FULLNAME_TO_FIRST_LAST transform")
    class SplitNameTests {

        @Test
        @DisplayName("splits 'Rajesh Kumar' into firstName and lastName")
        void twoPartName() {
            Object result = engine.applyTransform(
                    FieldTransform.SPLIT_FULLNAME_TO_FIRST_LAST, "Rajesh Kumar");
            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            assertThat(map.get("firstName")).isEqualTo("Rajesh");
            assertThat(map.get("lastName")).isEqualTo("Kumar");
        }

        @Test
        @DisplayName("splits 'Amit Kumar Singh' — last name includes middle")
        void threePartName() {
            Object result = engine.applyTransform(
                    FieldTransform.SPLIT_FULLNAME_TO_FIRST_LAST, "Amit Kumar Singh");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            assertThat(map.get("firstName")).isEqualTo("Amit");
            assertThat(map.get("lastName")).isEqualTo("Kumar Singh");
        }

        @Test
        @DisplayName("single name → firstName only, lastName empty")
        void singleName() {
            Object result = engine.applyTransform(
                    FieldTransform.SPLIT_FULLNAME_TO_FIRST_LAST, "Rajesh");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            assertThat(map.get("firstName")).isEqualTo("Rajesh");
            assertThat(map.get("lastName")).isEqualTo("");
        }
    }

    // ── CONCAT_ADDRESS_LINES ────────────────────────────────────

    @Nested
    @DisplayName("CONCAT_ADDRESS_LINES transform")
    class ConcatAddressTests {

        @Test
        @DisplayName("concatenates address map into single string")
        void concatenatesMap() {
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("line1", "MG Road");
            address.put("line2", "3rd Cross");
            address.put("city", "Bengaluru");
            address.put("pincode", "560001");

            Object result = engine.applyTransform(FieldTransform.CONCAT_ADDRESS_LINES, address);
            assertThat(result).isEqualTo("MG Road, 3rd Cross, Bengaluru, 560001");
        }

        @Test
        @DisplayName("skips null values in address map")
        void skipsNulls() {
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("line1", "MG Road");
            address.put("line2", null);
            address.put("city", "Bengaluru");

            Object result = engine.applyTransform(FieldTransform.CONCAT_ADDRESS_LINES, address);
            assertThat(result).isEqualTo("MG Road, Bengaluru");
        }

        @Test
        @DisplayName("handles plain string input")
        void plainString() {
            Object result = engine.applyTransform(
                    FieldTransform.CONCAT_ADDRESS_LINES, "MG Road, Bengaluru");
            assertThat(result).isEqualTo("MG Road, Bengaluru");
        }
    }

    // ── Edge cases ──────────────────────────────────────────────

    @Test
    @DisplayName("null transform returns value unchanged")
    void nullTransform() {
        Object result = engine.applyTransform(null, "test");
        assertThat(result).isEqualTo("test");
    }

    @Test
    @DisplayName("null value returns null for any transform")
    void nullValue() {
        Object result = engine.applyTransform(FieldTransform.UPPERCASE, null);
        assertThat(result).isNull();
    }
}

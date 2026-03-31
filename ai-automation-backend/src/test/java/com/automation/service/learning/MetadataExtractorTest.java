package com.automation.service.learning;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataExtractorTest {

    @Test
    void extractDouble_integerValue_returnsDouble() {
        Map<String, Object> map = Map.of("cost", 42);
        assertThat(MetadataExtractor.extractDouble(map, "cost")).isEqualTo(42.0);
    }

    @Test
    void extractDouble_doubleValue_returnsDouble() {
        Map<String, Object> map = Map.of("cost", 3.14);
        assertThat(MetadataExtractor.extractDouble(map, "cost")).isEqualTo(3.14);
    }

    @Test
    void extractDouble_missingKey_returnsZero() {
        Map<String, Object> map = Map.of();
        assertThat(MetadataExtractor.extractDouble(map, "cost")).isEqualTo(0.0);
    }

    @Test
    void extractDouble_nonNumeric_returnsZero() {
        Map<String, Object> map = Map.of("cost", "not-a-number");
        assertThat(MetadataExtractor.extractDouble(map, "cost")).isEqualTo(0.0);
    }

    @Test
    void extractDouble_nullValue_returnsZero() {
        Map<String, Object> map = new HashMap<>();
        map.put("cost", null);
        assertThat(MetadataExtractor.extractDouble(map, "cost")).isEqualTo(0.0);
    }

    @Test
    void extractLong_integerValue_returnsLong() {
        Map<String, Object> map = Map.of("tokens", 1000);
        assertThat(MetadataExtractor.extractLong(map, "tokens")).isEqualTo(1000L);
    }

    @Test
    void extractLong_longValue_returnsLong() {
        Map<String, Object> map = Map.of("tokens", 5000000000L);
        assertThat(MetadataExtractor.extractLong(map, "tokens")).isEqualTo(5000000000L);
    }

    @Test
    void extractLong_missingKey_returnsZero() {
        Map<String, Object> map = Map.of();
        assertThat(MetadataExtractor.extractLong(map, "tokens")).isEqualTo(0L);
    }

    @Test
    void extractLong_booleanValue_returnsZero() {
        Map<String, Object> map = Map.of("tokens", true);
        assertThat(MetadataExtractor.extractLong(map, "tokens")).isEqualTo(0L);
    }
}

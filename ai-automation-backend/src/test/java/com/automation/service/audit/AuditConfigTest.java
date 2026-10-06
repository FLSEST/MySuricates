package com.automation.service.audit;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The audit agreement is understood completely or not at all. */
class AuditConfigTest {

    private static Map<String, Object> audit(Map<String, Object> block) {
        return Map.of("audit", block);
    }

    private static Map<String, Object> complete() {
        Map<String, Object> block = new HashMap<>();
        block.put("enabled", true);
        block.put("key", "mysuricates.health");
        block.put("category", "health");
        block.put("reportBlock", "report");
        return block;
    }

    @Test
    void from_readsACompleteEnabledBlock_schemaVersionDefaultsToOne() {
        AuditConfig config = AuditConfig.from(audit(complete()));

        assertThat(config).isEqualTo(new AuditConfig("mysuricates.health", "health", "report", "report", 1));
    }

    @Test
    void from_readsAnOptionalPromptBlock_defaultingToTheReportBlock() {
        Map<String, Object> block = complete();
        block.put("promptBlock", "coach");

        assertThat(AuditConfig.from(audit(block))).extracting(AuditConfig::promptBlock).isEqualTo("coach");
        assertThat(AuditConfig.from(audit(complete()))).extracting(AuditConfig::promptBlock).isEqualTo("report");
    }

    @Test
    void from_acceptsExplicitSchemaVersionOne() {
        Map<String, Object> block = complete();
        block.put("schemaVersion", 1L);

        assertThat(AuditConfig.from(audit(block))).isNotNull().extracting(AuditConfig::schemaVersion).isEqualTo(1);
    }

    @Test
    void from_rejectsAnUnknownSchemaVersion() {
        Map<String, Object> block = complete();
        block.put("schemaVersion", 2);

        assertThat(AuditConfig.from(audit(block))).isNull();
    }

    @Test
    void from_ignoresANonNumericSchemaVersion_andFallsBackToOne() {
        Map<String, Object> block = complete();
        block.put("schemaVersion", "2");

        assertThat(AuditConfig.from(audit(block))).extracting(AuditConfig::schemaVersion).isEqualTo(1);
    }

    @Test
    void from_requiresEnabledToBeExactlyTrue() {
        Map<String, Object> block = complete();
        block.put("enabled", "true");
        assertThat(AuditConfig.from(audit(block))).as("String \"true\" zaehlt nicht").isNull();

        block.remove("enabled");
        assertThat(AuditConfig.from(audit(block))).isNull();

        block.put("enabled", false);
        assertThat(AuditConfig.from(audit(block))).isNull();
    }

    @Test
    void from_rejectsAMissingRequiredField() {
        for (String field : new String[] {"key", "category", "reportBlock"}) {
            Map<String, Object> block = complete();
            block.remove(field);
            assertThat(AuditConfig.from(audit(block))).as(field).isNull();
        }
    }

    @Test
    void from_stringifiesNonStringValues() {
        Map<String, Object> block = complete();
        block.put("reportBlock", 3);

        assertThat(AuditConfig.from(audit(block))).extracting(AuditConfig::reportBlock).isEqualTo("3");
    }

    @Test
    void from_returnsNullWithoutAnAuditBlock() {
        assertThat(AuditConfig.from(null)).isNull();
        assertThat(AuditConfig.from(Map.of())).isNull();
        assertThat(AuditConfig.from(Map.of("audit", "enabled"))).isNull();
    }
}

package com.automation.service.pool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The export to the public pool must not write instance bindings.
 * Criteria: docs/workflow-pool-kuratierung.md, section "Kriterien je Eintrag".
 */
class PoolExportSanitizerTest {

    private static final String UUID_A = "11111111-2222-3333-4444-555555555555";

    @Test
    void removesProjectIdKeyWithConcreteValue() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("projectId", UUID_A);
        node.put("keep", "value");

        PoolExportSanitizer sanitizer = new PoolExportSanitizer();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) sanitizer.scrub(node);

        assertThat(result).doesNotContainKey("projectId").containsEntry("keep", "value");
        assertThat(sanitizer.removedPaths()).contains("projectId");
    }

    @Test
    void removesInstanceReferenceKeysInSnakeCase() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("project_id", "proj-42");
        node.put("workflow_id", UUID_A);
        node.put("run_id", "run-7");

        PoolExportSanitizer sanitizer = new PoolExportSanitizer();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) sanitizer.scrub(node);

        assertThat(result).isEmpty();
    }

    @Test
    void keepsInstanceReferenceKeyWhenValueIsATemplatePlaceholder() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("project_id", "{{ project_id }}");
        node.put("workflowId", "");

        PoolExportSanitizer sanitizer = new PoolExportSanitizer();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) sanitizer.scrub(node);

        assertThat(result)
                .containsEntry("project_id", "{{ project_id }}")
                .containsEntry("workflowId", "");
        assertThat(sanitizer.removedPaths()).isEmpty();
    }

    @Test
    void removesEntryWhoseValueIsExactlyAUuid() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("harmlessName", UUID_A);

        PoolExportSanitizer sanitizer = new PoolExportSanitizer();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) sanitizer.scrub(node);

        assertThat(result).isEmpty();
        assertThat(sanitizer.removedPaths()).contains("harmlessName");
    }

    @Test
    void redactsUuidEmbeddedInLongerText() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("goal", "Lies den Run " + UUID_A + " und berichte.");

        PoolExportSanitizer sanitizer = new PoolExportSanitizer();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) sanitizer.scrub(node);

        assertThat((String) result.get("goal"))
                .doesNotContain(UUID_A)
                .contains(PoolExportSanitizer.REDACTED_UUID);
        assertThat(sanitizer.removedPaths()).contains("goal");
    }

    @Test
    void recursesIntoNestedMapsAndLists() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("projectId", UUID_A);
        inner.put("command", "ls");
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "shell");
        block.put("config", inner);
        List<Object> blocks = new ArrayList<>();
        blocks.add(block);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("blocks", blocks);

        PoolExportSanitizer sanitizer = new PoolExportSanitizer();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) sanitizer.scrub(root);

        @SuppressWarnings("unchecked")
        List<Object> outBlocks = (List<Object>) result.get("blocks");
        @SuppressWarnings("unchecked")
        Map<String, Object> outConfig =
                (Map<String, Object>) ((Map<String, Object>) outBlocks.get(0)).get("config");
        assertThat(outConfig).doesNotContainKey("projectId").containsEntry("command", "ls");
        assertThat(sanitizer.removedPaths()).contains("blocks[0].config.projectId");
    }

    @Test
    void dropsListElementsThatAreExactlyAUuid() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("skillIds", List.of(UUID_A, "builtin-fact-checker"));

        PoolExportSanitizer sanitizer = new PoolExportSanitizer();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) sanitizer.scrub(node);

        @SuppressWarnings("unchecked")
        List<Object> skillIds = (List<Object>) result.get("skillIds");
        assertThat(skillIds).containsExactly("builtin-fact-checker");
        assertThat(sanitizer.removedPaths()).contains("skillIds[0]");
    }

    @Test
    void leavesCleanContentUntouched() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", Map.of("en", "Health Check"));
        node.put("tags", List.of("quality", "ci"));
        node.put("timeout_seconds", 300);

        PoolExportSanitizer sanitizer = new PoolExportSanitizer();
        Object result = sanitizer.scrub(node);

        assertThat(result).isEqualTo(node);
        assertThat(sanitizer.removedPaths()).isEmpty();
    }
}

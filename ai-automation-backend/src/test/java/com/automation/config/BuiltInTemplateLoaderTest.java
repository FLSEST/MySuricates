package com.automation.config;

import com.automation.entity.BlockDefinition;
import com.automation.entity.WorkflowTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInTemplateLoaderTest {

    private static List<WorkflowTemplate> templates;

    @BeforeAll
    static void loadTemplates() {
        BuiltInTemplateLoader loader = new BuiltInTemplateLoader(new ObjectMapper());
        templates = loader.loadAll();
    }

    @Test
    void loadAll_returnsAllTemplates() {
        assertThat(templates).hasSize(44);
    }

    @Test
    void allTemplates_haveRequiredFields() {
        for (WorkflowTemplate t : templates) {
            assertThat(t.getId()).as("id").isNotBlank();
            assertThat(t.getIcon()).as("icon for " + t.getId()).isNotBlank();
            assertThat(t.getName()).as("name for " + t.getId()).isNotNull();
            assertThat(t.getName().get("de")).as("name.de for " + t.getId()).isNotBlank();
            assertThat(t.getName().get("en")).as("name.en for " + t.getId()).isNotBlank();
            assertThat(t.getDescription()).as("description for " + t.getId()).isNotNull();
            assertThat(t.getDescription().get("de")).as("description.de for " + t.getId()).isNotBlank();
            assertThat(t.getDescription().get("en")).as("description.en for " + t.getId()).isNotBlank();
            assertThat(t.getCategory()).as("category for " + t.getId()).isNotBlank();
            assertThat(t.getBlocks()).as("blocks for " + t.getId()).isNotEmpty();
            assertThat(t.isBuiltIn()).as("builtIn for " + t.getId()).isTrue();
        }
    }

    @Test
    void allTemplates_haveUniqueIds() {
        Set<String> ids = new HashSet<>();
        for (WorkflowTemplate t : templates) {
            assertThat(ids.add(t.getId()))
                    .as("Duplicate ID: " + t.getId())
                    .isTrue();
        }
    }

    @Test
    void allTemplates_haveValidBlockTypes() {
        Set<String> validTypes = Set.of(
                "simple", "shell", "retry", "conditional", "foreach",
                "while", "switch", "agentic", "approval",
                "sub-workflow", "skill-capability", "human-input");

        for (WorkflowTemplate t : templates) {
            for (BlockDefinition block : t.getBlocks()) {
                assertThat(validTypes).as("block type '%s' in template '%s'", block.getType(), t.getId())
                        .contains(block.getType());
            }
        }
    }

    @Test
    void allTemplates_haveValidVersion() {
        for (WorkflowTemplate t : templates) {
            assertThat(t.getVersion()).as("version for " + t.getId())
                    .matches("\\d+\\.\\d+\\.\\d+");
        }
    }

    @Test
    void allTemplates_haveSequentialSortOrder() {
        List<Integer> sortOrders = templates.stream()
                .map(WorkflowTemplate::getSortOrder)
                .sorted()
                .toList();

        for (int i = 0; i < sortOrders.size(); i++) {
            assertThat(sortOrders.get(i)).as("sortOrder at position " + i).isEqualTo(i);
        }
    }
}

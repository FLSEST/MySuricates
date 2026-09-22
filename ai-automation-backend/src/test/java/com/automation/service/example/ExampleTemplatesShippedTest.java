package com.automation.service.example;

import com.automation.architecture.ShippedDefinitions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate against the shipped files, not mocks (TODO 5162bd54): every template the example project
 * names must exist, and it must keep the project's promise "no AI". The unit test mocks the
 * templates and stayed green while the example project came out empty.
 */
class ExampleTemplatesShippedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyExampleTemplateIsShipped_asABlueprintThatNeedsNoAi() throws IOException {
        Map<String, Path> filesById = new HashMap<>();
        Map<String, JsonNode> rootsById = new HashMap<>();
        for (Path file : ShippedDefinitions.templateFiles()) {
            JsonNode root = MAPPER.readTree(file.toFile());
            filesById.put(root.path("id").asText(), file);
            rootsById.put(root.path("id").asText(), root);
        }

        assertThat(ExampleProjectService.EXAMPLE_TEMPLATE_IDS).isNotEmpty();
        for (String id : ExampleProjectService.EXAMPLE_TEMPLATE_IDS) {
            assertThat(filesById).as("shipped template %s", id).containsKey(id);
            // Blueprints outside the category AI make no model call (BlueprintGateTest).
            assertThat(filesById.get(id).getParent().getFileName().toString())
                    .as("%s lies in templates/blueprints", id).isEqualTo("blueprints");
            assertThat(rootsById.get(id).path("category").asText())
                    .as("category of %s", id).isNotEqualTo("AI");
        }
    }
}

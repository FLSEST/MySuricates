package com.automation.service.example;

import com.automation.architecture.ShippedDefinitions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

    /**
     * The claims fact sheet ({@code workflow-pool/public-readiness-behauptungen.json}) counts the
     * example workflows with a grep on the declaration line (TODO 2d782949). A list wrapped over
     * several lines would still compile, and the fact sheet would quietly report too few.
     */
    @Test
    void theIdListStaysOnTheOneLineTheClaimsFactSheetCounts() throws IOException {
        Path source = Path.of("src/main/java/com/automation/service/example/ExampleProjectService.java");
        List<String> declaration = Files.readAllLines(source).stream()
                .filter(line -> line.contains("EXAMPLE_TEMPLATE_IDS = List.of("))
                .toList();

        assertThat(declaration).hasSize(1);
        assertThat(Pattern.compile("\"[^\"]*\"").matcher(declaration.get(0)).results().count())
                .as("quoted ids on the declaration line").isEqualTo(ExampleProjectService.EXAMPLE_TEMPLATE_IDS.size());
    }

    /**
     * The dashboard view of the example project is built in the frontend and shows the report
     * example as its status widget (TODO a0b414ce). Both sides name it; they must name the same
     * example, and it must be one the project gets.
     */
    @Test
    void theFrontendPicksTheSameStatusExample_andTheProjectGetsIt() throws IOException {
        Path view = Path.of("..", "ai-automation-frontend", "src", "components", "dashboard", "exampleProjectView.ts");
        var declared = Pattern.compile("EXAMPLE_STATUS_TEMPLATE_ID = '([^']+)'").matcher(Files.readString(view));

        assertThat(declared.find()).as("EXAMPLE_STATUS_TEMPLATE_ID in %s", view).isTrue();
        assertThat(declared.group(1)).isEqualTo(ExampleProjectService.STATUS_TEMPLATE_ID);
        assertThat(ExampleProjectService.EXAMPLE_TEMPLATE_IDS).contains(ExampleProjectService.STATUS_TEMPLATE_ID);
    }
}

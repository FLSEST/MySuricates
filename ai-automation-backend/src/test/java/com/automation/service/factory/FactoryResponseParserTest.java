package com.automation.service.factory;

import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Locks the model-output parsing before extraction (Audit-TODO 3b0a021c). */
class FactoryResponseParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FactoryResponseParser parser = new FactoryResponseParser(mapper);

    @Test
    void extractJsonFromResponse_plainObject_parsed() throws IOException {
        JsonNode node = parser.extractJsonFromResponse("Here you go: {\"name\":\"WF\",\"blocks\":[]} thanks");

        assertThat(node.path("name").asText()).isEqualTo("WF");
    }

    @Test
    void extractJsonFromResponse_markdownFences_stripped() throws IOException {
        JsonNode node = parser.extractJsonFromResponse("```json\n{\"name\":\"Fenced\"}\n```");

        assertThat(node.path("name").asText()).isEqualTo("Fenced");
    }

    @Test
    void extractJsonFromResponse_noObject_throwsIOException() {
        assertThatThrownBy(() -> parser.extractJsonFromResponse("no json here"))
                .isInstanceOf(IOException.class)
                .hasMessage("No valid JSON found in Claude response");
    }

    @Test
    void parseBlocks_mapsTypeConfigAndRequiredCapabilities_defaultTypeSimple() throws Exception {
        JsonNode wf = mapper.readTree("{\"blocks\":[{\"type\":\"shell\",\"config\":{\"command\":\"ls\"},\"requiredCapabilities\":[\"docker\"]},{\"config\":{\"command\":\"x\"}}]}");

        List<BlockDefinition> blocks = parser.parseBlocks(wf);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).getType()).isEqualTo("shell");
        assertThat(blocks.get(0).getConfig()).isEqualTo(Map.of("command", "ls"));
        assertThat(blocks.get(0).getRequiredCapabilities()).containsExactly("docker");
        assertThat(blocks.get(1).getType()).isEqualTo("simple");
    }

    @Test
    void parseBlocks_keepsNameAndSkillIds() throws Exception {
        // TODO d6be36f3: without the name every {{ block.<name>.output }} of a later block points nowhere.
        JsonNode wf = mapper.readTree("""
                {"blocks":[
                  {"type":"shell","name":"fetch","config":{"command":"curl x"},"skillIds":["skill-1","skill-2"]},
                  {"type":"simple","name":"summarize","config":{"command":"Fasse zusammen: {{ block.fetch.output }}"}}
                ]}""");

        List<BlockDefinition> blocks = parser.parseBlocks(wf);

        assertThat(blocks).extracting(BlockDefinition::getName).containsExactly("fetch", "summarize");
        assertThat(blocks.get(0).getSkillIds()).containsExactly("skill-1", "skill-2");
        assertThat(blocks.get(1).getSkillIds()).isNullOrEmpty();
    }

    @Test
    void parseBlocksFromArray_keepsNameAndSkillIds() {
        List<BlockDefinition> blocks = parser.parseBlocksFromArray(
                "[{\"type\":\"simple\",\"name\":\"review\",\"config\":{\"command\":\"x\"},\"skillIds\":[\"s1\"]}]");

        assertThat(blocks).singleElement().satisfies(b -> {
            assertThat(b.getName()).isEqualTo("review");
            assertThat(b.getSkillIds()).containsExactly("s1");
        });
    }

    @Test
    void parseBlocks_noBlocksArray_empty() throws Exception {
        assertThat(parser.parseBlocks(mapper.readTree("{\"blocks\":\"nope\"}"))).isEmpty();
        assertThat(parser.parseBlocks(mapper.readTree("{}"))).isEmpty();
    }

    @Test
    void parseBlocksFromArray_directArray_mapsTypeConfigAndRequiredCapabilities() {
        // TODO ba5df025: the direct-array branch used to drop requiredCapabilities.
        List<BlockDefinition> blocks = parser.parseBlocksFromArray(
                "[{\"type\":\"shell\",\"config\":{\"command\":\"ls\"},\"requiredCapabilities\":[\"docker\"]}]");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getType()).isEqualTo("shell");
        assertThat(blocks.get(0).getConfig()).isEqualTo(Map.of("command", "ls"));
        assertThat(blocks.get(0).getRequiredCapabilities()).containsExactly("docker");
    }

    @Test
    void parseBlocksFromArray_directArray_sameMappingAsParseBlocks() throws Exception {
        String block = "{\"type\":\"agentic\",\"config\":{\"goal\":\"x\"},\"requiredCapabilities\":[\"web\",\"docker\"]}";
        String bare = "{\"config\":{\"command\":\"y\"}}";

        List<BlockDefinition> fromArray = parser.parseBlocksFromArray("[" + block + "," + bare + "]");
        List<BlockDefinition> fromObject = parser.parseBlocks(mapper.readTree("{\"blocks\":[" + block + "," + bare + "]}"));

        assertThat(fromArray).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(fromArray.get(i).getType()).isEqualTo(fromObject.get(i).getType());
            assertThat(fromArray.get(i).getConfig()).isEqualTo(fromObject.get(i).getConfig());
            assertThat(fromArray.get(i).getRequiredCapabilities()).isEqualTo(fromObject.get(i).getRequiredCapabilities());
        }
        assertThat(fromArray.get(1).getType()).isEqualTo("simple");
        assertThat(fromArray.get(1).getRequiredCapabilities()).isNullOrEmpty();
    }

    @Test
    void parseBlocksFromArray_fencedArray_parsed() {
        assertThat(parser.parseBlocksFromArray("```json\n[{\"type\":\"simple\"}]\n```")).hasSize(1);
    }

    @Test
    void parseBlocksFromArray_wrappedObject_usesBlocksKey_withRequiredCapabilities() {
        List<BlockDefinition> blocks = parser.parseBlocksFromArray(
                "{\"blocks\":[{\"type\":\"agentic\",\"requiredCapabilities\":[\"web\"]}]}");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getRequiredCapabilities()).containsExactly("web");
    }

    @Test
    void parseBlocksFromArray_garbage_returnsEmpty() {
        assertThat(parser.parseBlocksFromArray("nothing to see")).isEmpty();
        assertThat(parser.parseBlocksFromArray("[not json")).isEmpty();
    }

    @Test
    void extractStepErrors_prefersErrorField_thenShortOutput() throws Exception {
        JsonNode run = mapper.readTree("{\"steps\":[{\"status\":\"COMPLETED\",\"error\":\"ignored\"},{\"status\":\"FAILED\",\"output\":\"boom\"}]}");
        assertThat(parser.extractStepErrors(run)).isEqualTo("boom");

        JsonNode withError = mapper.readTree("{\"steps\":[{\"status\":\"FAILED\",\"error\":\"exit 1\",\"output\":\"long\"}]}");
        assertThat(parser.extractStepErrors(withError)).isEqualTo("exit 1");
    }

    @Test
    void extractStepErrors_longOutputOrNoFailedStep_null() throws Exception {
        JsonNode longOut = mapper.readTree("{\"steps\":[{\"status\":\"FAILED\",\"output\":\"" + "x".repeat(500) + "\"}]}");
        assertThat(parser.extractStepErrors(longOut)).isNull();
        assertThat(parser.extractStepErrors(mapper.readTree("{\"steps\":[{\"status\":\"COMPLETED\"}]}"))).isNull();
        assertThat(parser.extractStepErrors(mapper.readTree("{}"))).isNull();
    }
}

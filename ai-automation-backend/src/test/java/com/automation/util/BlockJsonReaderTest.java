package com.automation.util;

import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared JSON → block mapping of factory and build-check correction (TODO d6be36f3). */
class BlockJsonReaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toBlock_mapsAllFiveFields() throws Exception {
        BlockDefinition block = BlockJsonReader.toBlock(mapper.readTree("""
                {"type":"agentic","name":"plan","config":{"goal":"x"},
                 "requiredCapabilities":["web"],"skillIds":["s1","s2"]}"""), mapper);

        assertThat(block.getType()).isEqualTo("agentic");
        assertThat(block.getName()).isEqualTo("plan");
        assertThat(block.getConfig()).isEqualTo(Map.of("goal", "x"));
        assertThat(block.getRequiredCapabilities()).containsExactly("web");
        assertThat(block.getSkillIds()).containsExactly("s1", "s2");
    }

    @Test
    void toBlock_defaultTypeSimple_wrongKindsLeftOut() throws Exception {
        BlockDefinition block = BlockJsonReader.toBlock(mapper.readTree("""
                {"name":{"x":1},"config":"nope","requiredCapabilities":"web","skillIds":7}"""), mapper);

        assertThat(block.getType()).isEqualTo("simple");
        assertThat(block.getName()).isNull();
        assertThat(block.getConfig()).isNullOrEmpty();
        assertThat(block.getRequiredCapabilities()).isNullOrEmpty();
        assertThat(block.getSkillIds()).isNullOrEmpty();
    }

    @Test
    void toBlocks_anyNonObjectElement_emptiesTheWholeList() throws Exception {
        assertThat(BlockJsonReader.toBlocks(mapper.readTree("[{\"type\":\"shell\"},\"oops\"]"), mapper)).isEmpty();
        assertThat(BlockJsonReader.toBlocks(mapper.readTree("{\"type\":\"shell\"}"), mapper)).isEmpty();
        assertThat(BlockJsonReader.toBlocks(null, mapper)).isEmpty();
    }

    @Test
    void parseAnswer_arrayObjectFencesAndProse() throws Exception {
        List<BlockDefinition> fenced = BlockJsonReader.parseAnswer(
                "```json\n[{\"type\":\"shell\",\"name\":\"a\"}]\n```", mapper);
        List<BlockDefinition> wrapped = BlockJsonReader.parseAnswer(
                "Hier: {\"blocks\":[{\"type\":\"simple\",\"name\":\"b\"}]} fertig", mapper);

        assertThat(fenced).extracting(BlockDefinition::getName).containsExactly("a");
        assertThat(wrapped).extracting(BlockDefinition::getName).containsExactly("b");
        assertThat(BlockJsonReader.parseAnswer("kein JSON", mapper)).isEmpty();
        assertThat(BlockJsonReader.parseAnswer("  ", mapper)).isEmpty();
        assertThat(BlockJsonReader.parseAnswer(null, mapper)).isEmpty();
    }

    @Test
    void fromMapList_mapsAllFields_skipsNonObjects_configIsMutable() {
        Object parsed = List.of(
                Map.of("type", "shell", "name", "fetch", "config", Map.of("command", "ls"),
                        "skillIds", List.of("s1"), "requiredCapabilities", List.of("docker")),
                "oops",
                Map.of("name", "bare"));

        List<BlockDefinition> blocks = BlockJsonReader.fromMapList(parsed);

        assertThat(blocks).extracting(BlockDefinition::getName).containsExactly("fetch", "bare");
        assertThat(blocks.get(0).getSkillIds()).containsExactly("s1");
        assertThat(blocks.get(0).getRequiredCapabilities()).containsExactly("docker");
        assertThat(blocks.get(1).getType()).isEqualTo("simple");
        blocks.get(0).getConfig().put("timeout_seconds", 60);
        blocks.get(1).getConfig().put("timeout_seconds", 60);
        assertThat(BlockJsonReader.fromMapList("nope")).isEmpty();
        assertThat(BlockJsonReader.fromMapList(null)).isEmpty();
    }
}

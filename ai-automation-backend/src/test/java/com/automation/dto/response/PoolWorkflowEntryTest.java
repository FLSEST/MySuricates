package com.automation.dto.response;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PoolWorkflowEntryTest {

    @Test
    void should_storeAllFields_when_constructedWithAllValues() {
        BlockDefinition block = mock(BlockDefinition.class);
        Map<String, String> name = Map.of("en", "Test Workflow", "de", "Test Workflow");
        Map<String, String> description = Map.of("en", "A description", "de", "Eine Beschreibung");
        List<String> tags = List.of("tag1", "tag2");
        List<BlockDefinition> blocks = List.of(block);
        Map<String, Object> defaultContext = Map.of("var1", "value1");
        Map<String, Object> metadata = Map.of("source", "pool");

        PoolWorkflowEntry entry = new PoolWorkflowEntry(
                "id-123",
                name,
                description,
                "icon.svg",
                "automation",
                tags,
                blocks,
                "Be careful",
                "claude-sonnet-4-6",
                "1.0.0",
                "MySuricates",
                defaultContext,
                metadata,
                "workflow.json"
        );

        assertThat(entry.id()).isEqualTo("id-123");
        assertThat(entry.name()).isEqualTo(name);
        assertThat(entry.description()).isEqualTo(description);
        assertThat(entry.icon()).isEqualTo("icon.svg");
        assertThat(entry.category()).isEqualTo("automation");
        assertThat(entry.tags()).containsExactly("tag1", "tag2");
        assertThat(entry.blocks()).containsExactly(block);
        assertThat(entry.instructions()).isEqualTo("Be careful");
        assertThat(entry.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(entry.version()).isEqualTo("1.0.0");
        assertThat(entry.author()).isEqualTo("MySuricates");
        assertThat(entry.defaultContext()).isEqualTo(defaultContext);
        assertThat(entry.metadata()).isEqualTo(metadata);
        assertThat(entry.filename()).isEqualTo("workflow.json");
    }

    @Test
    void should_acceptNullValues_when_constructedWithNulls() {
        PoolWorkflowEntry entry = new PoolWorkflowEntry(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        assertThat(entry.id()).isNull();
        assertThat(entry.name()).isNull();
        assertThat(entry.description()).isNull();
        assertThat(entry.icon()).isNull();
        assertThat(entry.category()).isNull();
        assertThat(entry.tags()).isNull();
        assertThat(entry.blocks()).isNull();
        assertThat(entry.instructions()).isNull();
        assertThat(entry.model()).isNull();
        assertThat(entry.version()).isNull();
        assertThat(entry.author()).isNull();
        assertThat(entry.defaultContext()).isNull();
        assertThat(entry.metadata()).isNull();
        assertThat(entry.filename()).isNull();
    }

    @Test
    void should_acceptEmptyCollections_when_constructedWithEmpties() {
        PoolWorkflowEntry entry = new PoolWorkflowEntry(
                "id",
                Map.of(),
                Map.of(),
                "",
                "",
                List.of(),
                List.of(),
                "",
                "",
                "",
                "",
                Map.of(),
                Map.of(),
                ""
        );

        assertThat(entry.tags()).isEmpty();
        assertThat(entry.blocks()).isEmpty();
        assertThat(entry.name()).isEmpty();
        assertThat(entry.description()).isEmpty();
        assertThat(entry.defaultContext()).isEmpty();
        assertThat(entry.metadata()).isEmpty();
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        BlockDefinition block = mock(BlockDefinition.class);
        Map<String, String> name = Map.of("en", "Workflow");
        Map<String, String> description = Map.of("en", "Desc");
        List<String> tags = List.of("a");
        List<BlockDefinition> blocks = List.of(block);
        Map<String, Object> ctx = Map.of("k", "v");
        Map<String, Object> meta = Map.of("m", "v");

        PoolWorkflowEntry a = new PoolWorkflowEntry("id", name, description, "icon", "cat", tags, blocks,
                "ins", "model", "1.0", "author", ctx, meta, "f.json");
        PoolWorkflowEntry b = new PoolWorkflowEntry("id", name, description, "icon", "cat", tags, blocks,
                "ins", "model", "1.0", "author", ctx, meta, "f.json");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_idDiffers() {
        PoolWorkflowEntry a = new PoolWorkflowEntry("id-1", null, null, null, null, null, null, null, null, null, null, null, null, null);
        PoolWorkflowEntry b = new PoolWorkflowEntry("id-2", null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_filenameDiffers() {
        PoolWorkflowEntry a = new PoolWorkflowEntry("id", null, null, null, null, null, null, null, null, null, null, null, null, "a.json");
        PoolWorkflowEntry b = new PoolWorkflowEntry("id", null, null, null, null, null, null, null, null, null, null, null, null, "b.json");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_toStringInvoked() {
        PoolWorkflowEntry entry = new PoolWorkflowEntry(
                "wf-1", Map.of("en", "Name"), Map.of("en", "Desc"), "icon", "cat",
                List.of("tag"), List.of(), "instr", "model-x", "2.0", "auth",
                Map.of(), Map.of(), "file.json"
        );

        String str = entry.toString();

        assertThat(str).contains("wf-1");
        assertThat(str).contains("icon");
        assertThat(str).contains("cat");
        assertThat(str).contains("model-x");
        assertThat(str).contains("file.json");
    }

    @Test
    void should_returnNonNullHashCode_when_allFieldsNull() {
        PoolWorkflowEntry entry = new PoolWorkflowEntry(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        // hashCode() must not throw on null fields
        assertThat(entry.hashCode()).isNotNull();
    }
}

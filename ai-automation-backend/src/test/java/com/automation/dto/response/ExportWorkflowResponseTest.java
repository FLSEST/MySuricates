package com.automation.dto.response;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportWorkflowResponseTest {

    private BlockDefinition sampleBlock() {
        return BlockDefinition.builder()
                .type("simple")
                .build();
    }

    @Test
    void should_storeAllValues_when_constructedWithFullValues() {
        Instant exportedAt = Instant.parse("2026-04-25T12:00:00Z");
        BlockDefinition block = sampleBlock();
        List<BlockDefinition> blocks = List.of(block);
        Map<String, Object> defaultContext = Map.of("env", "prod", "retries", 3);
        Map<String, Object> metadata = Map.of("author", "alex", "tags", List.of("etl"));

        ExportWorkflowResponse response = new ExportWorkflowResponse(
                "My Workflow",
                "A description",
                "rocket",
                blocks,
                "Do the thing carefully",
                "claude-sonnet-4-6",
                "1.2.3",
                defaultContext,
                metadata,
                exportedAt
        );

        assertThat(response.name()).isEqualTo("My Workflow");
        assertThat(response.description()).isEqualTo("A description");
        assertThat(response.icon()).isEqualTo("rocket");
        assertThat(response.blocks()).containsExactly(block);
        assertThat(response.instructions()).isEqualTo("Do the thing carefully");
        assertThat(response.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(response.version()).isEqualTo("1.2.3");
        assertThat(response.defaultContext()).containsEntry("env", "prod").containsEntry("retries", 3);
        assertThat(response.metadata()).containsEntry("author", "alex");
        assertThat(response.exportedAt()).isEqualTo(exportedAt);
    }

    @Test
    void should_allowNullOptionalFields_when_constructed() {
        ExportWorkflowResponse response = new ExportWorkflowResponse(
                "Minimal", null, null, null, null, null, null, null, null, null
        );

        assertThat(response.name()).isEqualTo("Minimal");
        assertThat(response.description()).isNull();
        assertThat(response.icon()).isNull();
        assertThat(response.blocks()).isNull();
        assertThat(response.instructions()).isNull();
        assertThat(response.model()).isNull();
        assertThat(response.version()).isNull();
        assertThat(response.defaultContext()).isNull();
        assertThat(response.metadata()).isNull();
        assertThat(response.exportedAt()).isNull();
    }

    @Test
    void should_allowEmptyCollections() {
        ExportWorkflowResponse response = new ExportWorkflowResponse(
                "wf", "desc", "icon", List.of(), "instr", "model", "v1",
                Map.of(), Map.of(), Instant.EPOCH
        );

        assertThat(response.blocks()).isEmpty();
        assertThat(response.defaultContext()).isEmpty();
        assertThat(response.metadata()).isEmpty();
    }

    @Test
    void should_supportMultipleBlocks() {
        BlockDefinition b1 = BlockDefinition.builder().type("simple").build();
        BlockDefinition b2 = BlockDefinition.builder().type("shell").build();
        BlockDefinition b3 = BlockDefinition.builder().type("conditional").build();

        ExportWorkflowResponse response = new ExportWorkflowResponse(
                "wf", "desc", "icon", List.of(b1, b2, b3), null, null, null, null, null, Instant.now()
        );

        assertThat(response.blocks()).hasSize(3).containsExactly(b1, b2, b3);
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        Instant exportedAt = Instant.parse("2026-04-25T12:00:00Z");
        BlockDefinition block = BlockDefinition.builder().type("simple").build();
        List<BlockDefinition> blocks = List.of(block);
        Map<String, Object> ctx = Map.of("k", "v");
        Map<String, Object> meta = Map.of("m", "x");

        ExportWorkflowResponse a = new ExportWorkflowResponse(
                "wf", "desc", "icon", blocks, "instr", "model", "v1", ctx, meta, exportedAt
        );
        ExportWorkflowResponse b = new ExportWorkflowResponse(
                "wf", "desc", "icon", blocks, "instr", "model", "v1", ctx, meta, exportedAt
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nameDiffers() {
        Instant t = Instant.parse("2026-04-25T12:00:00Z");
        ExportWorkflowResponse a = new ExportWorkflowResponse(
                "wf-A", "d", "i", List.of(), "instr", "m", "v", Map.of(), Map.of(), t
        );
        ExportWorkflowResponse b = new ExportWorkflowResponse(
                "wf-B", "d", "i", List.of(), "instr", "m", "v", Map.of(), Map.of(), t
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_versionDiffers() {
        Instant t = Instant.parse("2026-04-25T12:00:00Z");
        ExportWorkflowResponse a = new ExportWorkflowResponse(
                "wf", "d", "i", List.of(), "instr", "m", "1.0.0", Map.of(), Map.of(), t
        );
        ExportWorkflowResponse b = new ExportWorkflowResponse(
                "wf", "d", "i", List.of(), "instr", "m", "2.0.0", Map.of(), Map.of(), t
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_modelDiffers() {
        Instant t = Instant.parse("2026-04-25T12:00:00Z");
        ExportWorkflowResponse a = new ExportWorkflowResponse(
                "wf", "d", "i", List.of(), "instr", "claude-sonnet-4-6", "v", Map.of(), Map.of(), t
        );
        ExportWorkflowResponse b = new ExportWorkflowResponse(
                "wf", "d", "i", List.of(), "instr", "claude-opus-4-6", "v", Map.of(), Map.of(), t
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_blocksDiffer() {
        Instant t = Instant.parse("2026-04-25T12:00:00Z");
        BlockDefinition b1 = BlockDefinition.builder().type("simple").build();
        BlockDefinition b2 = BlockDefinition.builder().type("shell").build();

        ExportWorkflowResponse a = new ExportWorkflowResponse(
                "wf", "d", "i", List.of(b1), "instr", "m", "v", Map.of(), Map.of(), t
        );
        ExportWorkflowResponse b = new ExportWorkflowResponse(
                "wf", "d", "i", List.of(b2), "instr", "m", "v", Map.of(), Map.of(), t
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_defaultContextDiffers() {
        Instant t = Instant.parse("2026-04-25T12:00:00Z");
        ExportWorkflowResponse a = new ExportWorkflowResponse(
                "wf", "d", "i", List.of(), "instr", "m", "v", Map.of("k", "1"), Map.of(), t
        );
        ExportWorkflowResponse b = new ExportWorkflowResponse(
                "wf", "d", "i", List.of(), "instr", "m", "v", Map.of("k", "2"), Map.of(), t
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_exportedAtDiffers() {
        ExportWorkflowResponse a = new ExportWorkflowResponse(
                "wf", "d", "i", List.of(), "instr", "m", "v", Map.of(), Map.of(),
                Instant.parse("2026-04-25T12:00:00Z")
        );
        ExportWorkflowResponse b = new ExportWorkflowResponse(
                "wf", "d", "i", List.of(), "instr", "m", "v", Map.of(), Map.of(),
                Instant.parse("2026-04-26T12:00:00Z")
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_includeKeyFields_inToString() {
        Instant t = Instant.parse("2026-04-25T12:00:00Z");
        ExportWorkflowResponse response = new ExportWorkflowResponse(
                "MyWf", "desc", "rocket", List.of(), "instr", "claude-sonnet-4-6", "1.2.3",
                Map.of(), Map.of(), t
        );

        String s = response.toString();
        assertThat(s).contains("MyWf");
        assertThat(s).contains("1.2.3");
        assertThat(s).contains("claude-sonnet-4-6");
    }
}

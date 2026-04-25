package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CreateWorkflowTemplateRequestTest {

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_storeAllFields_when_constructedWithAllValues() {
            Map<String, String> name = Map.of("de", "Mein Template", "en", "My Template");
            Map<String, String> description = Map.of("de", "Beschreibung", "en", "Description");
            BlockDefinition block = new BlockDefinition();
            List<BlockDefinition> blocks = List.of(block);

            CreateWorkflowTemplateRequest request = new CreateWorkflowTemplateRequest(
                    name,
                    description,
                    "icon-name",
                    "category-name",
                    blocks,
                    "Some instructions",
                    "claude-sonnet-4-6"
            );

            assertThat(request.name()).isEqualTo(name);
            assertThat(request.description()).isEqualTo(description);
            assertThat(request.icon()).isEqualTo("icon-name");
            assertThat(request.category()).isEqualTo("category-name");
            assertThat(request.blocks()).isEqualTo(blocks);
            assertThat(request.instructions()).isEqualTo("Some instructions");
            assertThat(request.model()).isEqualTo("claude-sonnet-4-6");
        }

        @Test
        void should_allowNullOptionalFields_when_onlyRequiredFieldsAreProvided() {
            Map<String, String> name = Map.of("de", "Titel");
            List<BlockDefinition> blocks = List.of();

            CreateWorkflowTemplateRequest request = new CreateWorkflowTemplateRequest(
                    name,
                    null,
                    null,
                    null,
                    blocks,
                    null,
                    null
            );

            assertThat(request.name()).isEqualTo(name);
            assertThat(request.description()).isNull();
            assertThat(request.icon()).isNull();
            assertThat(request.category()).isNull();
            assertThat(request.blocks()).isEmpty();
            assertThat(request.instructions()).isNull();
            assertThat(request.model()).isNull();
        }

        @Test
        void should_acceptEmptyCollections_when_emptyMapsAndListsAreProvided() {
            CreateWorkflowTemplateRequest request = new CreateWorkflowTemplateRequest(
                    Map.of(),
                    Map.of(),
                    "",
                    "",
                    List.of(),
                    "",
                    ""
            );

            assertThat(request.name()).isEmpty();
            assertThat(request.description()).isEmpty();
            assertThat(request.icon()).isEmpty();
            assertThat(request.category()).isEmpty();
            assertThat(request.blocks()).isEmpty();
            assertThat(request.instructions()).isEmpty();
            assertThat(request.model()).isEmpty();
        }

        @Test
        void should_preserveMultipleBlocks_when_blocksListContainsMultipleEntries() {
            BlockDefinition block1 = new BlockDefinition();
            BlockDefinition block2 = new BlockDefinition();
            BlockDefinition block3 = new BlockDefinition();
            List<BlockDefinition> blocks = List.of(block1, block2, block3);

            CreateWorkflowTemplateRequest request = new CreateWorkflowTemplateRequest(
                    Map.of("de", "Name"),
                    null,
                    null,
                    null,
                    blocks,
                    null,
                    null
            );

            assertThat(request.blocks()).hasSize(3).containsExactly(block1, block2, block3);
        }
    }

    @Nested
    class RecordEquality {

        @Test
        void should_beEqual_when_allFieldsMatch() {
            Map<String, String> name = Map.of("de", "Titel");
            List<BlockDefinition> blocks = List.of();

            CreateWorkflowTemplateRequest a = new CreateWorkflowTemplateRequest(
                    name, Map.of("de", "Beschr"), "icon", "cat", blocks, "instr", "model");
            CreateWorkflowTemplateRequest b = new CreateWorkflowTemplateRequest(
                    name, Map.of("de", "Beschr"), "icon", "cat", blocks, "instr", "model");

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void should_notBeEqual_when_anyFieldDiffers() {
            Map<String, String> name = Map.of("de", "Titel");
            List<BlockDefinition> blocks = List.of();

            CreateWorkflowTemplateRequest base = new CreateWorkflowTemplateRequest(
                    name, null, "icon", "cat", blocks, "instr", "model");
            CreateWorkflowTemplateRequest differentIcon = new CreateWorkflowTemplateRequest(
                    name, null, "other-icon", "cat", blocks, "instr", "model");
            CreateWorkflowTemplateRequest differentModel = new CreateWorkflowTemplateRequest(
                    name, null, "icon", "cat", blocks, "instr", "other-model");

            assertThat(base).isNotEqualTo(differentIcon);
            assertThat(base).isNotEqualTo(differentModel);
        }

        @Test
        void should_includeAllFields_when_toStringIsCalled() {
            CreateWorkflowTemplateRequest request = new CreateWorkflowTemplateRequest(
                    Map.of("de", "MyName"),
                    Map.of("de", "MyDesc"),
                    "myIcon",
                    "myCategory",
                    List.of(),
                    "myInstructions",
                    "myModel"
            );

            String result = request.toString();

            assertThat(result)
                    .contains("MyName")
                    .contains("MyDesc")
                    .contains("myIcon")
                    .contains("myCategory")
                    .contains("myInstructions")
                    .contains("myModel");
        }
    }
}

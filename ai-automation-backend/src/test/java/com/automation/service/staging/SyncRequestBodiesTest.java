package com.automation.service.staging;

import com.automation.entity.BlockDefinition;
import com.automation.entity.ChainEntry;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.service.workflow.DefinitionIntegrity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyncRequestBodiesTest {

    @Nested
    class ProjectBody {

        @Test
        void should_sendRequiredFields_andLeaveOutEmptyOptionalOnes() {
            Project project = Project.builder().name("P").type(ProjectType.CODE).path("/srv/p")
                    .config(new HashMap<>()).build();

            assertThat(SyncRequestBodies.project(project)).containsExactly(
                    Map.entry("name", "P"), Map.entry("type", "CODE"), Map.entry("path", "/srv/p"));
        }

        @Test
        void should_sendSubtitleDescriptionAndConfig_whenSet() {
            Project project = Project.builder().name("P").type(ProjectType.RESEARCH).path("/p")
                    .subtitle("sub").description("desc").config(new HashMap<>(Map.of("k", "v"))).build();

            assertThat(SyncRequestBodies.project(project))
                    .containsEntry("subtitle", "sub")
                    .containsEntry("description", "desc")
                    .containsEntry("config", Map.of("k", "v"))
                    .containsEntry("type", "RESEARCH");
        }

        @Test
        void should_leaveOutANullConfig() {
            Project project = Project.builder().name("P").type(ProjectType.CODE).path("/p").config(null).build();

            assertThat(SyncRequestBodies.project(project)).doesNotContainKey("config");
        }
    }

    @Nested
    class DefinitionBody {

        @Test
        void should_alwaysSendNameDescriptionIconAndBlocks_evenWhenNull() {
            Workflow workflow = Workflow.builder().name("W").blocks(List.of()).version(null).metadata(null).build();

            Map<String, Object> body = SyncRequestBodies.definition(workflow);

            assertThat(body.keySet()).containsExactly("name", "description", "icon", "blocks");
            assertThat(body.get("description")).isNull();
        }

        @Test
        void should_sendOptionalFields_whenSet() {
            List<BlockDefinition> blocks = List.of(BlockDefinition.builder().type("shell").build());
            Workflow workflow = Workflow.builder().name("W").description("d").icon("i").blocks(blocks)
                    .instructions("be brief").model("opus").version("2.1.0")
                    .metadata(new HashMap<>(Map.of("autoResume", true))).build();

            Map<String, Object> body = SyncRequestBodies.definition(workflow);

            assertThat(body)
                    .containsEntry("blocks", blocks)
                    .containsEntry("instructions", "be brief")
                    .containsEntry("model", "opus")
                    .containsEntry("version", "2.1.0")
                    .containsEntry("metadata", Map.of("autoResume", true));
        }

        @Test
        void should_leaveOutBlankVersionAndEmptyMetadata() {
            Workflow workflow = Workflow.builder().name("W").version("  ").metadata(new HashMap<>()).build();

            assertThat(SyncRequestBodies.definition(workflow)).doesNotContainKeys("version", "metadata");
        }
    }

    @Nested
    class WorkflowImportBody {

        @Test
        void should_addProjectDefaultContextAndAMatchingIntegrityChecksum() {
            Workflow workflow = Workflow.builder().name("W").description("d")
                    .defaultContext(new HashMap<>(Map.of("branch", "main"))).build();

            Map<String, Object> body = SyncRequestBodies.workflowImport(workflow, "target-project");

            assertThat(body)
                    .containsEntry("projectId", "target-project")
                    .containsEntry("defaultContext", Map.of("branch", "main"));
            Map<String, Object> withoutChecksum = new LinkedHashMap<>(body);
            withoutChecksum.remove(DefinitionIntegrity.FIELD);
            assertThat(body.get(DefinitionIntegrity.FIELD))
                    .isEqualTo(DefinitionIntegrity.ofImportBody(withoutChecksum));
            assertThat((String) body.get(DefinitionIntegrity.FIELD)).startsWith(DefinitionIntegrity.PREFIX);
        }

        @Test
        void should_leaveOutProjectAndEmptyDefaultContext() {
            Workflow workflow = Workflow.builder().name("W").defaultContext(new HashMap<>()).build();

            Map<String, Object> body = SyncRequestBodies.workflowImport(workflow, null);

            assertThat(body).doesNotContainKeys("projectId", "defaultContext").containsKey(DefinitionIntegrity.FIELD);
        }

        @Test
        void should_changeTheChecksum_when_aCoveredFieldChanges() {
            Workflow a = Workflow.builder().name("W").description("one").build();
            Workflow b = Workflow.builder().name("W").description("two").build();

            assertThat(SyncRequestBodies.workflowImport(a, null).get(DefinitionIntegrity.FIELD))
                    .isNotEqualTo(SyncRequestBodies.workflowImport(b, null).get(DefinitionIntegrity.FIELD));
        }
    }

    @Nested
    class SkillBody {

        @Test
        void should_sendCategoryAsName_andConfigSchemaOnlyWhenSet() {
            Skill skill = Skill.builder().name("S").description("d").icon("i").category(SkillCategory.DATA)
                    .aiContext("ctx").configSchema(new HashMap<>()).build();

            assertThat(SyncRequestBodies.skill(skill)).containsExactly(
                    Map.entry("name", "S"), Map.entry("description", "d"), Map.entry("icon", "i"),
                    Map.entry("category", "DATA"), Map.entry("aiContext", "ctx"));

            skill.setConfigSchema(new HashMap<>(Map.of("type", "object")));
            assertThat(SyncRequestBodies.skill(skill)).containsEntry("configSchema", Map.of("type", "object"));

            skill.setConfigSchema(null);
            assertThat(SyncRequestBodies.skill(skill)).doesNotContainKey("configSchema");
        }
    }

    @Nested
    class ChainBodies {

        @Test
        void should_pointTheEntryAtTheTargetWorkflowId() {
            ChainEntry entry = new ChainEntry("local-id", "Build", 2, false, Map.of("x", 1));

            assertThat(SyncRequestBodies.chainEntry(entry, "target-id")).containsExactly(
                    Map.entry("workflowId", "target-id"), Map.entry("label", "Build"), Map.entry("sortOrder", 2),
                    Map.entry("stopOnFailure", false), Map.entry("inputContext", Map.of("x", 1)));
        }

        @Test
        void should_leaveOutMissingLabelAndEmptyInputContext() {
            ChainEntry entry = new ChainEntry("local-id", null, 0, true, Map.of());

            assertThat(SyncRequestBodies.chainEntry(entry, "t")).containsOnlyKeys("workflowId", "sortOrder", "stopOnFailure");
            entry.setInputContext(null);
            assertThat(SyncRequestBodies.chainEntry(entry, "t")).doesNotContainKey("inputContext");
        }

        @Test
        void should_sendChainWithEntries_andOptionalDescriptionAndIcon() {
            WorkflowChain chain = WorkflowChain.builder().name("C").build();
            chain.setStopOnFailure(false);
            List<Map<String, Object>> entries = List.of(Map.of("workflowId", "t"));

            assertThat(SyncRequestBodies.chain(chain, entries)).containsExactly(
                    Map.entry("name", "C"), Map.entry("entries", entries), Map.entry("stopOnFailure", false));

            chain.setDescription("desc");
            chain.setIcon("link");
            assertThat(SyncRequestBodies.chain(chain, entries))
                    .containsEntry("description", "desc")
                    .containsEntry("icon", "link");
        }
    }
}

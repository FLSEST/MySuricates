package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChainEntry;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BundleAssemblerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final BundleAssembler assembler = new BundleAssembler(MAPPER);

    private final Project project = Project.builder().id("p-1").name("ShopApp").type(ProjectType.CODE)
            .path("/home/u/code/shopapp").description("d").config(new HashMap<>(Map.of("k", "v"))).build();

    private static Workflow workflow(String id, String name, BlockDefinition... blocks) {
        return Workflow.builder().id(id).name(name).blocks(new ArrayList<>(List.of(blocks)))
                .metadata(new HashMap<>()).defaultContext(new HashMap<>()).activatedSkills(new ArrayList<>()).build();
    }

    private static BlockDefinition block(String type, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).config(new HashMap<>(config)).build();
    }

    private ProjectBundle assemble(List<Workflow> workflows, List<WorkflowChain> chains,
                                   List<ScheduledWorkflow> schedules, boolean chainsEnabled) {
        return assembler.assemble(new BundleAssembler.Source(project, workflows, chains, schedules,
                Map.of("s-1", "git-tools"), chainsEnabled, "0.7.8", Instant.parse("2026-09-29T10:00:00Z")));
    }

    private static Map<String, Object> blockConfig(BundleWorkflow w, int index) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) w.definition().blocks().get(index).get("config");
        return config;
    }

    @Test
    void keysAreUniqueSlugsOfTheNames() {
        ProjectBundle bundle = assemble(List.of(workflow("w-1", "Code Qualität"), workflow("w-2", "Code Qualität"),
                workflow("w-3", "Übersicht!")), List.of(), List.of(), true);

        assertThat(bundle.workflows()).extracting(BundleWorkflow::key)
                .containsExactly("code-qualitaet", "code-qualitaet-2", "uebersicht");
        assertThat(bundle.format()).isEqualTo("mysuricates-project-bundle");
        assertThat(bundle.formatVersion()).isEqualTo(1);
        assertThat(bundle.appVersion()).isEqualTo("0.7.8");
    }

    @Test
    void subWorkflowReferencesBecomeKeys_nestedAndLegacyKeyIncluded_externalOnesWarn() {
        Workflow child = workflow("w-child", "Child");
        Map<String, Object> nested = Map.of("type", "sub-workflow", "config", Map.of("workflowId", "w-child"));
        Workflow parent = workflow("w-parent", "Parent",
                block("sub-workflow", Map.of("workflow_id", "w-child")),
                block("foreach", Map.of("blocks", List.of(nested))),
                block("switch", Map.of("cases", List.of(Map.of("blocks", List.of(
                        Map.of("type", "sub-workflow", "config", Map.of("workflow_id", "w-elsewhere"))))))));

        ProjectBundle bundle = assemble(List.of(parent, child), List.of(), List.of(), true);
        BundleWorkflow p = bundle.workflows().stream().filter(w -> w.key().equals("parent")).findFirst().orElseThrow();

        assertThat(blockConfig(p, 0)).containsEntry("workflow_id", "@@workflow:child@@");
        assertThat(blockConfig(p, 1).toString()).contains("workflowId=@@workflow:child@@");
        assertThat(blockConfig(p, 2).toString()).contains("workflow_id=w-elsewhere");
        assertThat(bundle.warnings()).singleElement().asString().contains("Parent").contains("w-elsewhere");
        assertThat(parent.getBlocks().get(0).getConfig()).as("the entity stays untouched")
                .containsEntry("workflow_id", "w-child");
    }

    @Test
    void skillsTravelByName() {
        Workflow w = workflow("w-1", "A", BlockDefinition.builder().type("simple").config(new HashMap<>())
                .skillIds(new ArrayList<>(List.of("s-1", "s-gone"))).build());
        w.setActivatedSkills(new ArrayList<>(List.of(Skill.builder().id("s-2").name("docs").build())));

        ProjectBundle bundle = assemble(List.of(w), List.of(), List.of(), true);

        assertThat(bundle.workflows().get(0).definition().activatedSkillNames()).containsExactly("docs");
        assertThat(bundle.workflows().get(0).definition().blocks().get(0).get("skillIds"))
                .isEqualTo(List.of("@@skill:git-tools@@"));
        assertThat(bundle.skillNames()).containsExactly("docs", "git-tools");
        assertThat(bundle.warnings()).anySatisfy(x -> assertThat(x).contains("s-gone"));
    }

    @Test
    void noIdsSealOrPathLeaveTheInstance() throws Exception {
        Workflow w = workflow("w-secret-id", "A", block("shell", Map.of("command", "cd /home/u/code/shopapp/src")));
        w.setSealed(true);
        w.setSealedHash("hash-123");
        w.setMetadata(new HashMap<>(Map.of("workspace", Map.of("source_path", "/home/u/code/shopapp"))));

        ProjectBundle bundle = assemble(List.of(w), List.of(), List.of(), true);
        String json = MAPPER.writeValueAsString(bundle);

        assertThat(json).doesNotContain("w-secret-id", "p-1", "hash-123", "sealed", "\"id\"", "projectId");
        assertThat(MAPPER.writeValueAsString(bundle.project())).doesNotContain("/home/u", "path", "status", "container");
        assertThat(bundle.project().name()).isEqualTo("ShopApp");
        assertThat(bundle.project().type()).isEqualTo("CODE");
        assertThat(blockConfig(bundle.workflows().get(0), 0)).containsEntry("command", "cd {{ project_path }}/src");
        assertThat(bundle.workflows().get(0).definition().metadata().toString()).contains("source_path=@@path:1@@");
        assertThat(bundle.paths()).singleElement().satisfies(p -> {
            assertThat(p.original()).isEqualTo("/home/u/code/shopapp");
            assertThat(p.hint()).isEqualTo("A › metadata.workspace.source_path");
        });
    }

    @Test
    void chainsOfTheProjectAndSchedulesAreIncluded_foreignChainsAreNot() {
        Workflow a = workflow("w-a", "A", block("shell", Map.of("command", "ls /tmp/shopapp")));
        Workflow b = workflow("w-b", "B");
        WorkflowChain own = WorkflowChain.builder().id("c-1").name("Nightly").entries(List.of(
                new ChainEntry("w-a", "first", 0, true, Map.of("dir", "/tmp/shopapp/out")),
                new ChainEntry("w-b", "second", 1, false, Map.of("src", "/home/u/code/shopapp/src")))).build();
        WorkflowChain foreign = WorkflowChain.builder().id("c-2").name("Mixed").entries(List.of(
                new ChainEntry("w-a", null, 0, true, Map.of()), new ChainEntry("w-other", null, 1, true, Map.of())))
                .build();
        ScheduledWorkflow onWorkflow = ScheduledWorkflow.builder().id("s-1").workflow(a).cronExpression("0 0 6 * * *")
                .enabled(true).inputContext(Map.of()).build();
        ScheduledWorkflow onChain = ScheduledWorkflow.builder().id("s-2").targetType(ScheduledWorkflow.TargetType.CHAIN)
                .chain(own).cronExpression("0 0 2 * * *").maxRuns(3).inputContext(Map.of()).build();
        ScheduledWorkflow onForeign = ScheduledWorkflow.builder().id("s-3")
                .targetType(ScheduledWorkflow.TargetType.CHAIN).chain(foreign).cronExpression("0 0 3 * * *").build();

        ProjectBundle bundle = assemble(List.of(a, b), List.of(own, foreign), List.of(onWorkflow, onChain, onForeign), true);

        assertThat(bundle.chains()).singleElement().satisfies(c -> {
            assertThat(c.key()).isEqualTo("nightly");
            assertThat(c.entries()).extracting(e -> e.workflowKey()).containsExactly("a", "b");
            assertThat(c.entries().get(0).inputContext()).containsEntry("dir", "@@path:1@@/out");
            assertThat(c.entries().get(1).inputContext()).containsEntry("src", "@@path:2@@");
        });
        assertThat(bundle.schedules()).extracting(s -> s.target().label()).containsExactly("chain:nightly", "workflow:a");
        assertThat(bundle.schedules().get(0).maxRuns()).isEqualTo(3);
        assertThat(bundle.schedules().get(1).maxRuns()).isNull();
        assertThat(bundle.paths()).extracting(p -> p.original() + "×" + p.occurrences())
                .containsExactly("/tmp/shopapp×2", "/home/u/code/shopapp/src×1");
    }

    @Test
    void chainsModuleOff_exportsNoChainsAndWarns() {
        Workflow a = workflow("w-a", "A");
        WorkflowChain own = WorkflowChain.builder().id("c-1").name("Nightly")
                .entries(List.of(new ChainEntry("w-a", null, 0, true, Map.of()))).build();

        ProjectBundle bundle = assemble(List.of(a), List.of(own), List.of(), false);

        assertThat(bundle.chains()).isEmpty();
        assertThat(bundle.warnings()).singleElement().asString().contains("chains");
    }
}

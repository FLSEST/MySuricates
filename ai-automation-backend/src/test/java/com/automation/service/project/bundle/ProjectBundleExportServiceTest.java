package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleSchedule;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChainEntry;
import com.automation.entity.Project;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.exception.ResourceNotFoundException;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.project.ProjectService;
import com.automation.service.system.SystemIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What the export loads and hands to the assembler — repositories mocked, the assembler is real. */
class ProjectBundleExportServiceTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final WorkflowChainRepository chains = mock(WorkflowChainRepository.class);
    private final ScheduledWorkflowRepository schedules = mock(ScheduledWorkflowRepository.class);
    private final SkillRepository skills = mock(SkillRepository.class);
    private final SystemIdentityService identity = mock(SystemIdentityService.class);

    private final Project project = Project.builder().id("p-1").name("Shop Äpp").path("/home/u/shop").build();
    private final Workflow tests = workflow("w-1", "Tests");
    private final Workflow foreign = workflow("w-9", "Foreign");
    private final WorkflowChain chain = WorkflowChain.builder().id("c-1").name("All")
            .entries(new ArrayList<>(List.of(ChainEntry.builder().workflowId("w-1").build()))).build();

    private static Workflow workflow(String id, String name) {
        return Workflow.builder().id(id).name(name).blocks(new ArrayList<>()).metadata(new HashMap<>())
                .defaultContext(new HashMap<>()).activatedSkills(new ArrayList<>()).build();
    }

    private ProjectBundleExportService service(ModuleRegistry modules) {
        return new ProjectBundleExportService(projectService, workflows, chains, schedules, skills, modules, identity,
                new ObjectMapper().findAndRegisterModules());
    }

    @BeforeEach
    void setUp() {
        when(projectService.findById("p-1")).thenReturn(project);
        tests.getActivatedSkills().add(Skill.builder().id("s-1").name("git-tools").build());
        tests.getBlocks().add(BlockDefinition.builder().type("simple")
                .config(new HashMap<>(Map.of("skillIds", List.of("s-1")))).build());
        when(workflows.findByProjectId("p-1")).thenReturn(List.of(tests));
        when(chains.findAll()).thenReturn(List.of(chain));
        when(identity.getVersion()).thenReturn("0.7.9");
        when(skills.findAll()).thenReturn(List.of(Skill.builder().id("s-1").name("git-tools").build(),
                Skill.builder().id("s-1").name("duplicate-id").build()));
        when(schedules.findAllWithTargets()).thenReturn(List.of(
                ScheduledWorkflow.builder().id("s-own").workflow(tests).cronExpression("0 0 6 * * *").build(),
                ScheduledWorkflow.builder().id("s-foreign").workflow(foreign).cronExpression("0 0 7 * * *").build(),
                ScheduledWorkflow.builder().id("s-chain").targetType(ScheduledWorkflow.TargetType.CHAIN).chain(chain)
                        .cronExpression("0 0 8 * * *").build(),
                ScheduledWorkflow.builder().id("s-none").cronExpression("0 0 9 * * *").build()));
    }

    @Test
    void exportsTheProjectWithItsSchedulesChainsAndAFileName() {
        ProjectBundleExportService.Export export = service(ModuleRegistry.allEnabled()).export("p-1");

        ProjectBundle bundle = export.bundle();
        assertThat(export.fileName()).isEqualTo("shop-aepp.mysuricates-bundle.json");
        assertThat(bundle.appVersion()).isEqualTo("0.7.9");
        assertThat(bundle.exportedAt()).isNotNull();
        assertThat(bundle.project().name()).isEqualTo("Shop Äpp");
        assertThat(bundle.workflows()).singleElement().satisfies(w -> {
            assertThat(w.key()).isEqualTo("tests");
            assertThat(w.definition().activatedSkillNames()).containsExactly("git-tools");
            // two skills share an id: the first name wins
            assertThat(w.definition().blocks().get(0).get("config"))
                    .isEqualTo(Map.of("skillIds", List.of("@@skill:git-tools@@")));
        });
        assertThat(bundle.chains()).singleElement().satisfies(c -> assertThat(c.key()).isEqualTo("all"));
        assertThat(bundle.schedules()).extracting(BundleSchedule::cronExpression)
                .containsExactly("0 0 8 * * *", "0 0 6 * * *");
    }

    @Test
    void chainsModuleOff_leavesChainsAndTheirSchedulesOut() {
        ProjectBundle bundle = service(ModuleRegistry.of(Map.of(), Map.of())).export("p-1").bundle();

        assertThat(bundle.chains()).isEmpty();
        assertThat(bundle.schedules()).extracting(BundleSchedule::cronExpression).containsExactly("0 0 6 * * *");
        assertThat(bundle.warnings()).anySatisfy(w -> assertThat(w).contains("Module chains is off"));
    }

    @Test
    void unknownProjectIsANotFoundAndLoadsNothingElse() {
        when(projectService.findById("ghost")).thenThrow(new ResourceNotFoundException("Project", "ghost"));

        assertThatThrownBy(() -> service(ModuleRegistry.allEnabled()).export("ghost"))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(workflows, chains, schedules, skills);
    }

    @Test
    void fileName_isTheSlugOfTheProjectNameWithAFallback() {
        assertThat(ProjectBundleExportService.fileName("Mein Projekt!")).isEqualTo("mein-projekt.mysuricates-bundle.json");
        assertThat(ProjectBundleExportService.fileName(null)).isEqualTo("project.mysuricates-bundle.json");
        assertThat(ProjectBundleExportService.fileName("???")).isEqualTo("project.mysuricates-bundle.json");
    }
}

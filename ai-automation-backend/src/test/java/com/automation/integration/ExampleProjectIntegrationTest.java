package com.automation.integration;

import com.automation.controller.WorkflowController;
import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.Todo;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.entity.WorkflowVersion;
import com.automation.executor.ApprovalRequest;
import com.automation.integration.scenario.ScriptedAiProvider;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.repository.WorkflowVersionRepository;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.example.ExampleProjectService;
import com.automation.service.project.ContainerService;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.WorkflowExecutor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The example project against the real context (TODO d12c7560): the seeded built-in blueprints,
 * {@code PathValidator} and the version row of the gateway — none of which the unit test sees.
 *
 * <p>Every example workflow runs here once, as a fresh installation would run it (TODO 2d782949).
 * An AI call has nowhere to go: the system default provider is a {@link ScriptedAiProvider} without
 * a script, which fails every call and records it.
 */
@SpringBootTest
@ActiveProfiles("integration")
class ExampleProjectIntegrationTest {

    private static final String NO_AI = "NO_AI";
    /** {@code ExampleProjectService.EXAMPLE_TEMPLATE_IDS}, spelled out: the list is package-private there. */
    private static final List<String> EXAMPLES = List.of(
            "blueprint-approval", "blueprint-if", "blueprint-loop", "blueprint-todo", "blueprint-report");
    /** {@code ExampleProjectService.STATUS_TEMPLATE_ID}: the example the dashboard view shows. */
    private static final String STATUS_EXAMPLE = "blueprint-report";
    private static final EnumSet<RunStatus> TERMINAL = EnumSet.of(RunStatus.COMPLETED,
            RunStatus.COMPLETED_WITH_ERRORS, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.INTERRUPTED);

    @TempDir
    static Path examples;

    @DynamicPropertySource
    static void examplesDirectory(DynamicPropertyRegistry registry) {
        registry.add("automation.examples.directory", () -> examples.toString());
        registry.add("automation.ai.default-provider", () -> NO_AI);
    }

    @TestConfiguration
    static class NoAiProvider {
        @Bean
        ScriptedAiProvider noAiProvider() {
            return new ScriptedAiProvider(NO_AI);
        }
    }

    @Autowired private ExampleProjectService exampleProjectService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowVersionRepository versionRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private WorkflowExecutor workflowExecutor;
    @Autowired private RunControlService runControl;
    @Autowired private AiProviderRegistry providerRegistry;
    @Autowired private ScriptedAiProvider noAiProvider;
    @Autowired private EntityManager entityManager;
    @Autowired private WorkflowController workflowController;

    /** Shell blocks run on the host, as on an installation without Docker. */
    @MockitoBean private ContainerService containerService;

    private String projectId;

    @AfterEach
    void cleanup() {
        if (projectId == null) {
            return;
        }
        List<Workflow> workflows = workflowRepository.findByProjectId(projectId);
        for (Workflow workflow : workflows) {
            for (WorkflowRun run : runRepository.findByWorkflowIdOrderByStartedAtDesc(workflow.getId())) {
                stepResultRepository.deleteAll(stepResultRepository.findByWorkflowRunId(run.getId()));
                runRepository.delete(run);
            }
            versionRepository.deleteAll(versionRepository.findByWorkflowIdOrderByVersionNumberDesc(
                    workflow.getId(), org.springframework.data.domain.Pageable.unpaged()).getContent());
        }
        todoRepository.deleteAll(todoRepository.findByProjectIdAndStatusIn(projectId, Arrays.asList(Todo.Status.values())));
        workflowRepository.deleteAll(workflows);
        projectRepository.deleteById(projectId);
        projectId = null;
    }

    @Test
    void createsTheProjectWithTheGermanApprovalExample_andReturnsItOnTheSecondCall() {
        ExampleProjectService.Result first = exampleProjectService.ensure("de");
        projectId = first.project().getId();

        assertThat(first.created()).isTrue();
        Project project = projectRepository.findById(projectId).orElseThrow();
        assertThat(project.getName()).isEqualTo("Beispielprojekt");
        assertThat(Path.of(project.getPath())).isEqualTo(examples.resolve("example-project"));
        assertThat(examples.resolve("example-project/README.md")).isRegularFile();

        List<Workflow> workflows = workflowRepository.findByProjectId(projectId);
        assertThat(workflows).extracting(Workflow::getTemplateId)
                .containsExactlyInAnyOrderElementsOf(EXAMPLES);
        Workflow approval = workflows.stream()
                .filter(w -> "blueprint-approval".equals(w.getTemplateId()))
                .findFirst().orElseThrow();
        assertThat(approval.getName()).isEqualTo("Freigabe vor dem Handeln");
        assertThat(approval.getBlocks()).extracting(BlockDefinition::getName)
                .containsExactly("messen", "freigabe", "abschluss");
        WorkflowVersion version = versionRepository
                .findTopByWorkflowIdOrderByVersionNumberDesc(approval.getId()).orElseThrow();
        assertThat(version.getChangeSource()).isEqualTo("example-project");

        ExampleProjectService.Result second = exampleProjectService.ensure("de");

        assertThat(second.created()).isFalse();
        assertThat(second.project().getId()).isEqualTo(projectId);
        assertThat(second.workflowsAdded()).isZero();
        assertThat(workflowRepository.findByProjectId(projectId)).hasSameSizeAs(workflows);
    }

    /**
     * The dashboard view of the example project shows the report example as its status widget
     * (TODO a0b414ce), not whichever workflow comes first. The frontend finds it in {@code GET
     * /workflows} by project and template id ({@code exampleProjectView.ts}), so both have to be in
     * that list.
     */
    @Test
    void theWorkflowListNamesTheReportExampleByTemplateId_forTheStatusWidget() {
        projectId = exampleProjectService.ensure("de").project().getId();

        List<WorkflowResponse> ofTheProject = workflowController.listWorkflows().stream()
                .filter(w -> projectId.equals(w.projectId()))
                .toList();

        assertThat(ofTheProject).extracting(WorkflowResponse::templateId).containsOnlyOnce(STATUS_EXAMPLE);
    }

    /**
     * "One click, and the example workflows run without AI and without any setup" (GETTING-STARTED):
     * each one runs green, a waiting approval is answered with its first non-rejecting option, and
     * no step reaches a model — neither a provider call nor {@code aiRequest} step metadata.
     */
    @Test
    void everyExampleWorkflowRunsGreen_withoutAnAiCall() {
        assertThat(providerRegistry.getDefaultProviderId()).as("default provider of this test").isEqualTo(NO_AI);
        noAiProvider.script(List.of());
        projectId = exampleProjectService.ensure("en").project().getId();

        List<Workflow> workflows = workflowRepository.findByProjectId(projectId);
        assertThat(workflows).hasSize(EXAMPLES.size());
        for (Workflow workflow : workflows) {
            String name = workflow.getTemplateId();
            WorkflowRun run = runToTheEnd(workflowRepository.findByIdWithSkills(workflow.getId()).orElseThrow());

            assertThat(run.getStatus()).as("%s: run status (error: %s)", name, run.getError())
                    .isEqualTo(RunStatus.COMPLETED);
            List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(run.getId());
            assertThat(steps).as("%s: steps", name).hasSameSizeAs(workflow.getBlocks());
            for (StepResult step : steps) {
                Map<String, Object> metadata = step.getMetadata() == null ? Map.of() : step.getMetadata();
                assertThat(metadata).as("%s: step %d metadata", name, step.getStepIndex()).doesNotContainKey("aiRequest");
            }
        }
        assertThat(noAiProvider.calls()).as("AI calls of the example workflows").isEmpty();
        assertThat(todoRepository.findByProjectIdAndStatusIn(projectId, List.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS)))
                .as("open TODOs the examples leave behind").isEmpty();
    }

    private WorkflowRun runToTheEnd(Workflow workflow) {
        String runId = workflowExecutor.execute(workflow, Map.of(), TriggerSource.MANUAL).getId();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            ApprovalRequest pending = runControl.getPendingApproval(runId);
            if (pending != null) {
                runControl.resolveApproval(runId, pending.getOptions().stream()
                        .filter(option -> !pending.getRejectOptions().contains(option))
                        .findFirst().orElseThrow());
            }
            entityManager.clear();
            WorkflowRun run = runRepository.findById(runId).orElseThrow();
            if (TERMINAL.contains(run.getStatus())) {
                return run;
            }
            sleep();
        }
        throw new AssertionError(workflow.getTemplateId() + ": run " + runId + " did not finish in 60 s");
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}

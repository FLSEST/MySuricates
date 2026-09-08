package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest.PageContext;
import com.automation.entity.Project;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageContextRendererTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WishRepository wishRepository;
    @Mock private WorkflowRepository workflowRepository;

    private PageContextRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new PageContextRenderer(workflowRunRepository, projectRepository, wishRepository,
                workflowRepository, new ProjectFileListing());
    }

    private static StepResult step(int index, StepStatus status) {
        return StepResult.builder().stepIndex(index).status(status).output("out").build();
    }

    @Test
    void resolveRunId_prefersExplicitRunId_thenEntityIdOnRunPages() {
        assertThat(PageContextRenderer.resolveRunId(new PageContext("todos", "e", null, null, "r1", null))).isEqualTo("r1");
        assertThat(PageContextRenderer.resolveRunId(new PageContext("run", "e", null))).isEqualTo("e");
        assertThat(PageContextRenderer.resolveRunId(new PageContext("todos", "e", null))).isNull();
        assertThat(PageContextRenderer.resolveRunId(null)).isNull();
    }

    @Test
    void resolveWorkflowId_prefersExplicit_thenEntityIdOnWorkflowPages() {
        assertThat(PageContextRenderer.resolveWorkflowId(new PageContext("runs", "e", null, "wf", null, null))).isEqualTo("wf");
        assertThat(PageContextRenderer.resolveWorkflowId(new PageContext("workflows", "e", null))).isEqualTo("e");
        assertThat(PageContextRenderer.resolveWorkflowId(new PageContext("runs", "e", null))).isNull();
    }

    @Test
    void runFocused_deletedWorkflow_marksNameDeleted_andShowsDurationInSeconds() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED).workflowName("Deploy")
                .durationMs(7999L).steps(new ArrayList<>()).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.empty());

        StringBuilder sb = new StringBuilder();
        renderer.appendRunFocusedContext(sb, new PageContext("run", "r1", null, "wf-1", "r1", "/runs/r1"));

        assertThat(sb.toString()).contains("### Currently displayed run\nWorkflow: Deploy [deleted]\nStatus: COMPLETED (7s)\n");
        assertThat(sb.toString()).doesNotContain("Aktuell angezeigter Run");
    }

    @Test
    void runFocused_runError_isSmartTruncated_andStepCountsFollowEnumOrder() {
        String err = "e".repeat(1000);
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.FAILED)
                .error(Map.of("message", err))
                .steps(new ArrayList<>(List.of(step(0, StepStatus.COMPLETED), step(1, StepStatus.FAILED),
                        step(2, StepStatus.FAILED), step(3, StepStatus.COMPLETED))))
                .build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        StringBuilder sb = new StringBuilder();
        renderer.appendRunFocusedContext(sb, new PageContext("run", "r1", null));
        String out = sb.toString();

        assertThat(out).contains("Run error: ");
        assertThat(out).doesNotContain(err);
        String expectedCounts = java.util.Arrays.stream(StepStatus.values())
                .filter(s -> s == StepStatus.COMPLETED || s == StepStatus.FAILED)
                .map(s -> s + "=2")
                .collect(java.util.stream.Collectors.joining(", "));
        assertThat(out).contains("Steps: 4 (" + expectedCounts + ")\nFirst failed step: 2\n");
        assertThat(out).contains("Failed step 2 (?)\n");
        assertThat(out).contains("Other failed steps: 3\n");
        assertThat(out).contains("Full output via <fetch type=\"step_output\" id=\"r1/2\"/>");
    }

    @Test
    void runFocused_noOutputCaptured_placeholder() {
        StepResult failed = StepResult.builder().stepIndex(0).status(StepStatus.FAILED).output("  ").build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.FAILED)
                .steps(new ArrayList<>(List.of(failed))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        StringBuilder sb = new StringBuilder();
        renderer.appendRunFocusedContext(sb, new PageContext("run", "r1", null));

        assertThat(sb.toString()).contains("Failed step output:\n(no output captured)\n");
    }

    @Test
    void pageContext_nonRunPageWithRunId_usesGermanHeading_andIncludesProjectFilesLine() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING).steps(new ArrayList<>()).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));
        Project p = Project.builder().id("p1").name("Alpha").path("/nonexistent/path").build();
        Workflow wf = Workflow.builder().id("wf-1").name("A").project(p).build();
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(wf));
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        StringBuilder sb = new StringBuilder();
        renderer.appendPageContext(sb, new PageContext("workflow", "wf-1", null, "wf-1", "r1", "/workflows/wf-1"));
        String out = sb.toString();

        assertThat(out).startsWith("## Current User Context\nPage: /workflow\nPath: /workflows/wf-1\nWorkflow ID: wf-1\nRun ID: r1\nDisplayed element: ID wf-1\n");
        assertThat(out).contains("### Aktuell angezeigter Run\nWorkflow: A\nWorkflow ID: wf-1\nWorkflow project: Alpha (ID: p1)\nStatus: RUNNING\n");
        assertThat(out).contains("Active workflow: A (ID: wf-1)\nWorkflow project: Alpha (ID: p1)\nActive project: Alpha (ID: p1)\n");
        assertThat(out).endsWith("\n\n");
    }

    @Test
    void pageContext_wishPage_showsWishDetails() {
        Wish wish = Wish.builder().id("w1").originalText("Build a deploy workflow").status(WishStatus.EXECUTING)
                .iterationCount(2).maxIterations(5).build();
        when(wishRepository.findById("w1")).thenReturn(Optional.of(wish));

        StringBuilder sb = new StringBuilder();
        renderer.appendPageContext(sb, new PageContext("wishes", "w1", null));

        assertThat(sb.toString()).contains("Wish details: [EXECUTING] Build a deploy workflow, iterations: 2/5\n");
    }

    @Test
    void pageContext_projectId_showsActiveProject_andSwallowsRepositoryErrors() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(Project.builder().id("p1").name("Alpha").build()));

        StringBuilder sb = new StringBuilder();
        renderer.appendPageContext(sb, new PageContext("todos", null, "p1"));
        assertThat(sb.toString()).contains("Active project: Alpha (ID: p1)\n");

        when(projectRepository.findById("p2")).thenThrow(new RuntimeException("boom"));
        StringBuilder sb2 = new StringBuilder();
        renderer.appendPageContext(sb2, new PageContext("todos", null, "p2"));
        assertThat(sb2.toString()).doesNotContain("Active project");
    }
}

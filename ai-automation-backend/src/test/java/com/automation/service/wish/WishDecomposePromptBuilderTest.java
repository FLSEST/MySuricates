package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.*;
import com.automation.repository.*;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.workflow.AgentInstructionsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WishDecomposePromptBuilderTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowTemplateRepository templateRepository;
    @Mock private WorkflowMemoryRepository memoryRepository;
    @Mock private WorkflowPoolService workflowPoolService;
    @Mock private AgentInstructionsService agentInstructionsService;
    @Mock private SkillRepository skillRepository;
    @Mock private com.automation.service.learning.LearningService learningService;

    private final WishConfig wishConfig = new WishConfig();
    private WishDecomposePromptBuilder builder;
    private WishPromptHelper helper;

    @BeforeEach
    void setUp() {
        helper = new WishPromptHelper(skillRepository, learningService);
        builder = new WishDecomposePromptBuilder(
                workflowRepository, projectRepository, templateRepository,
                memoryRepository, workflowPoolService, agentInstructionsService, helper, wishConfig
        );
    }

    private Wish buildWish(String text) {
        Wish wish = new Wish();
        wish.setOriginalText(text);
        return wish;
    }

    private void stubDefaults() {
        lenient().when(workflowRepository.findAll()).thenReturn(List.of());
        lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
        lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
        lenient().when(skillRepository.findAll()).thenReturn(List.of());
    }

    @Nested
    class BuildPromptTests {

        @Test
        void includesWishText() {
            stubDefaults();
            Wish wish = buildWish("Deploy frontend to production");

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("## Wish")
                    .contains("Deploy frontend to production")
                    .contains("## Reusable Components");
        }

        @Test
        void withClarifications_includesSection() {
            stubDefaults();
            Wish wish = buildWish("Build app");
            wish.setMetadata(Map.of("clarifications", List.of(
                    Map.of("question", "Which framework?", "answer", "React")
            )));

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("## Clarifications from User")
                    .contains("**Q:** Which framework?")
                    .contains("**A:** React");
        }

        @Test
        void withUserFeedback_includesSection() {
            stubDefaults();
            Wish wish = buildWish("Build app");
            wish.setMetadata(Map.of("userFeedback", "Please add error handling"));

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("## User Feedback")
                    .contains("Please add error handling");
        }

        @Test
        void withEvaluations_includesLastEval() {
            stubDefaults();
            Wish wish = buildWish("Build app");
            wish.setMetadata(Map.of("evaluations", List.of(
                    Map.of("satisfied", false, "issues", List.of("Missing tests")),
                    Map.of("satisfied", false, "issues", List.of("Timeout too short"))
            )));

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("## Previous Iteration Feedback")
                    .contains("Timeout too short"); // last eval
        }

        @Test
        void noMetadata_noClarificationsOrFeedback() {
            stubDefaults();
            Wish wish = buildWish("Simple wish");

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .doesNotContain("## Clarifications")
                    .doesNotContain("## User Feedback")
                    .doesNotContain("## Previous Iteration");
        }

        @Test
        void withProject_includesProjectContext() {
            stubDefaults();
            Wish wish = buildWish("Build app");
            wish.setProjectId("proj-1");

            Project project = new Project();
            project.setId("proj-1");
            project.setName("MyProject");
            project.setPath("/home/user/project");
            project.setDescription("A cool project");
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
            when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of());

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("## Target Project")
                    .contains("**Name**: MyProject")
                    .contains("**Path**: /home/user/project")
                    .contains("**Description**: A cool project");
        }

        @Test
        void withProjectWorkflows_includesCurrentProjectSection() {
            stubDefaults();
            Wish wish = buildWish("Deploy app");
            wish.setProjectId("proj-1");

            Project project = new Project();
            project.setId("proj-1");
            project.setName("MyProject");
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));

            Workflow wf = new Workflow();
            wf.setId("wf-1");
            wf.setName("Build Pipeline");
            wf.setProject(project);
            wf.setBlocks(List.of());
            when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of(wf));

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("MyProject (current project")
                    .contains("**Build Pipeline**");
        }

        /**
         * 5fb7b1e9: the active memories of all project workflows come from one query, not from
         * one per workflow — and still appear in the prompt in workflow order.
         */
        @Test
        void projectMemories_comeFromOneBatchedQuery_inWorkflowOrder() {
            stubDefaults();
            Wish wish = buildWish("Deploy app");
            wish.setProjectId("proj-1");
            Project project = new Project();
            project.setId("proj-1");
            project.setName("MyProject");
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
            List<Workflow> workflows = new ArrayList<>();
            for (String id : List.of("wf-1", "wf-2", "wf-3")) {
                Workflow wf = new Workflow();
                wf.setId(id);
                wf.setName("Workflow " + id);
                wf.setProject(project);
                wf.setBlocks(List.of());
                workflows.add(wf);
            }
            when(workflowRepository.findByProjectId("proj-1")).thenReturn(workflows);
            // The database returns in arbitrary order — deliberately reversed here.
            when(memoryRepository.findByWorkflowIdInAndActiveTrue(List.of("wf-1", "wf-2", "wf-3"))).thenReturn(List.of(
                    WorkflowMemory.builder().workflowId("wf-3").title("Third").content("C").active(true).build(),
                    WorkflowMemory.builder().workflowId("wf-1").title("First").content("A").active(true).build()));

            String result = builder.buildPrompt(wish);

            assertThat(result).contains("## Accumulated Project Knowledge");
            assertThat(result.indexOf("### First")).isPositive().isLessThan(result.indexOf("### Third"));
            verify(memoryRepository, times(1)).findByWorkflowIdInAndActiveTrue(anyCollection());
            verify(memoryRepository, never()).findByWorkflowIdAndActiveTrue(anyString());
            // Memories, project list and execution intelligence share one query
            // (5fb7b1e9, 73036bbd: buildExecutionIntelligence used to load them a second time).
            verify(workflowRepository, times(1)).findByProjectId("proj-1");
        }

        /** Without workflows in the project, memories are not queried at all (no {@code IN ()}). */
        @Test
        void projectWithoutWorkflows_queriesNoMemories() {
            stubDefaults();
            Wish wish = buildWish("Build app");
            wish.setProjectId("proj-1");
            when(projectRepository.findById("proj-1")).thenReturn(Optional.empty());
            when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of());

            builder.buildPrompt(wish);

            verifyNoInteractions(memoryRepository);
        }

        @Test
        void withReferenceWorkflows_includesSection() {
            stubDefaults();
            Wish wish = buildWish("Deploy app");
            wish.setReferenceWorkflowIds(List.of("ref-wf-1"));

            Workflow refWf = new Workflow();
            refWf.setId("ref-wf-1");
            refWf.setName("Reference Deploy");
            refWf.setDescription("Deploy workflow");
            refWf.setBlocks(List.of());
            Project p = new Project();
            p.setName("Other");
            refWf.setProject(p);
            when(workflowRepository.findAllById(Set.of("ref-wf-1"))).thenReturn(List.of(refWf));

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("### Referenced Workflows")
                    .contains("**Reference Deploy**");
        }

        /**
         * 51ee8709: all reference workflows from one query, in selection order;
         * an unknown ID is silently dropped as before.
         */
        @Test
        void referenceWorkflows_comeFromOneQuery_inSelectionOrder() {
            stubDefaults();
            Wish wish = buildWish("Deploy app");
            wish.setReferenceWorkflowIds(List.of("ref-b", "ref-gone", "ref-a"));
            List<Workflow> found = new ArrayList<>();
            for (String id : List.of("ref-a", "ref-b")) {
                Workflow wf = new Workflow();
                wf.setId(id);
                wf.setName("Workflow " + id);
                wf.setBlocks(List.of());
                found.add(wf);
            }
            when(workflowRepository.findAllById(Set.of("ref-b", "ref-gone", "ref-a"))).thenReturn(found);

            String result = builder.buildPrompt(wish);

            assertThat(result.indexOf("**Workflow ref-b**")).isPositive().isLessThan(result.indexOf("**Workflow ref-a**"));
            assertThat(result).doesNotContain("ref-gone");
            verify(workflowRepository, times(1)).findAllById(anyCollection());
            verify(workflowRepository, never()).findById(anyString());
        }

        /** 73036bbd: the Execution Intelligence gets the project workflows already read. */
        @Test
        void executionIntelligence_usesTheProjectWorkflowsReadOnce() {
            stubDefaults();
            Wish wish = buildWish("Deploy app");
            wish.setProjectId("proj-1");
            when(projectRepository.findById("proj-1")).thenReturn(Optional.empty());
            Workflow wf = new Workflow();
            wf.setId("wf-1");
            wf.setName("Deploy WF");
            wf.setBlocks(List.of());
            when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of(wf));
            when(learningService.getWorkflowStats("wf-1")).thenReturn(Map.of(
                    "totalRuns", 4, "successRate", 0.5, "avgDurationMs", 1000.0));
            when(learningService.getExecutionInsights("wf-1")).thenReturn(Map.of("avgCostPerRunUsd", 0.01));
            when(learningService.getOptimizationSuggestions("wf-1")).thenReturn(List.of());
            when(learningService.getAgenticAnalytics("wf-1")).thenReturn(Map.of("totalAgenticRuns", 0));

            String result = builder.buildPrompt(wish);

            assertThat(result).contains("## Execution Intelligence").contains("**Deploy WF** (4 runs)");
            verify(workflowRepository, times(1)).findByProjectId("proj-1");
        }

        @Test
        void crossProjectWorkflows_scoredAndLimited() {
            Wish wish = buildWish("Deploy frontend application");
            when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            when(workflowPoolService.listAll()).thenReturn(List.of());
            when(skillRepository.findAll()).thenReturn(List.of());

            // Create 15 workflows from other projects to test top-10 limit
            List<Workflow> allWorkflows = new ArrayList<>();
            Project otherProject = new Project();
            otherProject.setId("other-proj");
            otherProject.setName("Other");
            for (int i = 0; i < 15; i++) {
                Workflow wf = new Workflow();
                wf.setId("wf-" + i);
                wf.setName("Deploy Workflow " + i);
                wf.setDescription("Deploys frontend");
                wf.setProject(otherProject);
                wf.setBlocks(List.of());
                allWorkflows.add(wf);
            }
            when(workflowRepository.findAll()).thenReturn(allWorkflows);

            String result = builder.buildPrompt(wish);

            assertThat(result).contains("### Relevant workflows from other projects");
            // Count occurrences of "Deploy Workflow" — should be max 10
            long count = result.lines()
                    .filter(l -> l.contains("**Deploy Workflow"))
                    .count();
            assertThat(count).isLessThanOrEqualTo(10);
        }
    }

    @Nested
    class BuildSystemPromptTests {

        /** The schema comes from BlockSpecs — the old text named forEach and then_blocks (TODO cf8db6f8). */
        @Test
        void containsTheBlockSpecificationsOfItsTypes() {
            when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("");

            String result = builder.buildSystemPrompt(buildWish("test"));

            assertThat(result).contains("workflow architect");
            for (String type : List.of("simple", "shell", "agentic", "conditional", "foreach", "retry", "sub-workflow")) {
                assertThat(result).contains("**Block type: " + type + "**");
            }
            assertThat(result).contains("`then_command`", "`items_source`")
                    .doesNotContain("forEach", "then_blocks", "AI overrides:");
        }

        /** The timeouts WishDecomposeService fills in, not numbers of their own. */
        @Test
        void timeoutGuidelinesNameTheConfiguredDefaults() {
            when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("");
            wishConfig.setDefaultShellTimeoutSeconds(420);
            wishConfig.setDefaultAgenticTimeoutSeconds(700);
            wishConfig.setDefaultAgenticIterationTimeoutSeconds(1500);
            wishConfig.setDefaultAgenticTotalTimeoutSeconds(4000);

            String result = builder.buildSystemPrompt(buildWish("test"));

            assertThat(result).contains("**simple** blocks: `timeout_seconds` 420.")
                    .contains("`timeout_seconds` 700 per CLI call, `iteration_timeout_seconds` 1500 per iteration, "
                            + "`total_timeout_seconds` 4000 overall");
        }

        @Test
        void containsOutputFormat() {
            when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("");

            String result = builder.buildSystemPrompt(buildWish("test"));

            assertThat(result)
                    .contains("\"analysis\"")
                    .contains("\"workflows\"")
                    .contains("\"needsClarification\"");
        }

        @Test
        void withAgentInstructions_includesPrefix() {
            when(agentInstructionsService.buildInstructionPrefix(null))
                    .thenReturn("Custom instructions here");

            String result = builder.buildSystemPrompt(buildWish("test"));

            assertThat(result).startsWith("Custom instructions here");
        }

        @Test
        void blankAgentInstructions_noPrefix() {
            when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("  ");

            String result = builder.buildSystemPrompt(buildWish("test"));

            assertThat(result).doesNotStartWith("  ");
        }
    }
}

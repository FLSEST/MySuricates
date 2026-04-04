package com.automation.service.wish;

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
    @Mock private WorkflowRunRepository workflowRunRepository;

    private WishDecomposePromptBuilder builder;
    private WishPromptHelper helper;

    @BeforeEach
    void setUp() {
        helper = new WishPromptHelper(workflowRepository, skillRepository, learningService, workflowRunRepository);
        builder = new WishDecomposePromptBuilder(
                workflowRepository, projectRepository, templateRepository,
                memoryRepository, workflowPoolService, agentInstructionsService, helper
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
            when(workflowRepository.findById("ref-wf-1")).thenReturn(Optional.of(refWf));

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("### Referenced Workflows")
                    .contains("**Reference Deploy**");
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

        @Test
        void containsBlockTypes() {
            when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("");

            String result = builder.buildSystemPrompt(buildWish("test"));

            assertThat(result)
                    .contains("workflow architect")
                    .contains("**simple**")
                    .contains("**shell**")
                    .contains("**agentic**")
                    .contains("**conditional**")
                    .contains("**forEach**")
                    .contains("**retry**")
                    .contains("**sub-workflow**");
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

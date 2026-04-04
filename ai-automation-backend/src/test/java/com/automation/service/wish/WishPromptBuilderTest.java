package com.automation.service.wish;

import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.*;
import com.automation.repository.*;
import com.automation.service.learning.LearningService;
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
class WishPromptBuilderTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private WorkflowTemplateRepository templateRepository;
    @Mock private WorkflowMemoryRepository memoryRepository;
    @Mock private WorkflowPoolService workflowPoolService;
    @Mock private AgentInstructionsService agentInstructionsService;
    @Mock private LearningService learningService;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private ChainRunRepository chainRunRepository;
    @Mock private StepResultRepository stepResultRepository;

    private WishPromptBuilder builder;

    @BeforeEach
    void setUp() {
        WishPromptHelper helper = new WishPromptHelper(
                workflowRepository, skillRepository, learningService, workflowRunRepository
        );
        WishDecomposePromptBuilder decomposeBuilder = new WishDecomposePromptBuilder(
                workflowRepository, projectRepository, templateRepository,
                memoryRepository, workflowPoolService, agentInstructionsService, helper
        );
        WishEvaluatePromptBuilder evaluateBuilder = new WishEvaluatePromptBuilder(
                workflowRunRepository, chainRunRepository, stepResultRepository, learningService
        );
        builder = new WishPromptBuilder(decomposeBuilder, evaluateBuilder, helper);
    }

    @Nested
    class ExtractKeywordsTests {

        @Test
        void nullText_returnsEmptySet() {
            assertThat(builder.extractKeywords(null)).isEmpty();
        }

        @Test
        void blankText_returnsEmptySet() {
            assertThat(builder.extractKeywords("  ")).isEmpty();
        }

        @Test
        void filtersStopWords() {
            Set<String> result = builder.extractKeywords("the quick fox and the lazy dog");
            assertThat(result).contains("quick", "fox", "lazy", "dog");
            assertThat(result).doesNotContain("the", "and");
        }

        @Test
        void splitsOnPunctuation() {
            Set<String> result = builder.extractKeywords("deploy:frontend, run tests!");
            assertThat(result).contains("deploy", "frontend", "run", "tests");
        }

        @Test
        void filtersTooShortWords() {
            Set<String> result = builder.extractKeywords("ab cd efg hij");
            assertThat(result).doesNotContain("ab", "cd");
            assertThat(result).contains("efg", "hij");
        }

        @Test
        void filtersGermanStopWords() {
            Set<String> result = builder.extractKeywords("der workflow und die pipeline");
            assertThat(result).containsExactlyInAnyOrder("workflow", "pipeline");
        }

        @Test
        void lowercasesAllKeywords() {
            Set<String> result = builder.extractKeywords("Deploy FRONTEND Build");
            assertThat(result).contains("deploy", "frontend", "build");
            assertThat(result).doesNotContain("Deploy", "FRONTEND", "Build");
        }

        @Test
        void deduplicatesKeywords() {
            Set<String> result = builder.extractKeywords("test test test");
            assertThat(result).containsExactly("test");
        }
    }

    @Nested
    class ScoreRelevanceTests {

        @Test
        void emptyKeywords_returnsZero() {
            assertThat(builder.scoreRelevance(Set.of(), "deploy", "some desc", List.of())).isZero();
        }

        @Test
        void nameMatchScoresThree() {
            int score = builder.scoreRelevance(Set.of("deploy"), "Deploy Pipeline", null, List.of());
            assertThat(score).isGreaterThanOrEqualTo(3);
        }

        @Test
        void descriptionMatchScoresTwo() {
            int score = builder.scoreRelevance(Set.of("lint"), "Checker", "run lint on code", List.of());
            assertThat(score).isGreaterThanOrEqualTo(2);
        }

        @Test
        void blockConfigMatchScoresOne() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("shell")
                    .config(Map.of("command", "npm install"))
                    .build();
            int score = builder.scoreRelevance(Set.of("npm"), "Setup", null, List.of(block));
            assertThat(score).isGreaterThanOrEqualTo(1);
        }

        @Test
        void nameAndDescriptionScoreCumulatively() {
            int score = builder.scoreRelevance(Set.of("deploy"), "deploy-pipeline", "auto deploy to prod", List.of());
            assertThat(score).isEqualTo(5); // 3 (name) + 2 (desc)
        }

        @Test
        void multipleBlocksCanEachScore() {
            BlockDefinition b1 = BlockDefinition.builder().type("shell").config(Map.of("command", "npm run deploy")).build();
            BlockDefinition b2 = BlockDefinition.builder().type("shell").config(Map.of("command", "npm run test")).build();
            int score = builder.scoreRelevance(Set.of("deploy", "test"), "x", "y", List.of(b1, b2));
            assertThat(score).isEqualTo(2); // +1 per block
        }

        @Test
        void handlesNullBlocks() {
            int score = builder.scoreRelevance(Set.of("test"), "test-wf", null, null);
            assertThat(score).isEqualTo(3); // only name match
        }

        @Test
        void handlesNullBlockConfig() {
            BlockDefinition block = BlockDefinition.builder().type("shell").config(null).build();
            int score = builder.scoreRelevance(Set.of("test"), "x", "x", List.of(block));
            assertThat(score).isEqualTo(0);
        }

        @Test
        void multipleKeywordsScoreIndependently() {
            int score = builder.scoreRelevance(Set.of("deploy", "test"), "deploy test workflow", null, List.of());
            assertThat(score).isEqualTo(6); // 3+3 both in name
        }
    }

    @Nested
    class InferModelFromBlocksTests {

        @Test
        void analysisInDescription_returnsOpus() {
            String model = builder.inferModelFromBlocks(List.of(), "Security analysis of the codebase");
            assertThat(model).isEqualTo("claude-opus-4-6");
        }

        @Test
        void auditInAgenticGoal_returnsOpus() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("agentic")
                    .config(Map.of("goal", "audit dependencies for vulnerabilities"))
                    .build();
            String model = builder.inferModelFromBlocks(List.of(block), "Check deps");
            assertThat(model).isEqualTo("claude-opus-4-6");
        }

        @Test
        void noAnalysisPattern_returnsNull() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("shell")
                    .config(Map.of("command", "npm run build"))
                    .build();
            String model = builder.inferModelFromBlocks(List.of(block), "Deploy to staging");
            assertThat(model).isNull();
        }

        @Test
        void agenticBlockWithNullConfig_returnsNull() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("agentic").config(null).build();
            String model = builder.inferModelFromBlocks(List.of(block), "Deploy app");
            assertThat(model).isNull();
        }

        @Test
        void emptyBlocksWithNullDescription_returnsNull() {
            assertThat(builder.inferModelFromBlocks(List.of(), null)).isNull();
        }

        @Test
        void shellBlockWithAnalysisInConfig_returnsNull() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("shell")
                    .config(Map.of("command", "run analysis script"))
                    .build();
            // Only agentic block goals are checked, not shell commands
            String model = builder.inferModelFromBlocks(List.of(block), "Run script");
            assertThat(model).isNull();
        }

        @Test
        void nullDescription_checksBlocksOnly() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("agentic")
                    .config(Map.of("goal", "evaluate code quality"))
                    .build();
            assertThat(builder.inferModelFromBlocks(List.of(block), null)).isEqualTo("claude-opus-4-6");
        }

        @Test
        void matchesVariousAnalysisPatterns() {
            for (String keyword : List.of("vulnerability scan", "inspect code", "quality scorecard",
                    "tech debt radar", "coverage report", "security check", "architecture assessment")) {
                assertThat(builder.inferModelFromBlocks(List.of(), keyword))
                        .as("Should match: %s", keyword)
                        .isEqualTo("claude-opus-4-6");
            }
        }

        @Test
        void descriptionCheckedBeforeBlocks() {
            // Description match returns immediately; block goal is not analysis-related
            BlockDefinition block = BlockDefinition.builder()
                    .type("agentic").config(Map.of("goal", "deploy app")).build();
            assertThat(builder.inferModelFromBlocks(List.of(block), "Security audit"))
                    .isEqualTo("claude-opus-4-6");
        }
    }

    @Nested
    class FormatBlockPipelineTests {

        @Test
        void emptyBlocks_returnsEmpty() {
            assertThat(builder.formatBlockPipeline(null)).isEqualTo("[empty]");
            assertThat(builder.formatBlockPipeline(List.of())).isEqualTo("[empty]");
        }

        @Test
        void formatsMultipleBlocks() {
            BlockDefinition shell = BlockDefinition.builder()
                    .type("shell").config(Map.of("command", "npm test")).build();
            BlockDefinition agentic = BlockDefinition.builder()
                    .type("agentic").config(Map.of("goal", "Fix bugs", "max_iterations", 5)).build();
            BlockDefinition simple = BlockDefinition.builder()
                    .type("simple").config(Map.of("command", "Summarize")).build();

            String result = builder.formatBlockPipeline(List.of(shell, agentic, simple));

            assertThat(result).contains("[shell: npm test]");
            assertThat(result).contains("[agentic: Fix bugs (5 iter)]");
            assertThat(result).contains("[simple: Summarize]");
            assertThat(result).contains("→");
        }

        @Test
        void truncatesLongCommand() {
            String longCmd = "a".repeat(50);
            BlockDefinition block = BlockDefinition.builder()
                    .type("shell").config(Map.of("command", longCmd)).build();
            String result = builder.formatBlockPipeline(List.of(block));
            assertThat(result).contains("...");
        }

        @Test
        void blockWithNoConfig_showsTypeOnly() {
            BlockDefinition block = BlockDefinition.builder().type("conditional").build();
            String result = builder.formatBlockPipeline(List.of(block));
            assertThat(result).isEqualTo("[conditional]");
        }

        @Test
        void blockWithNullType_showsQuestionMark() {
            BlockDefinition block = BlockDefinition.builder().config(Map.of("command", "test")).build();
            String result = builder.formatBlockPipeline(List.of(block));
            assertThat(result).isEqualTo("[?: test]");
        }
    }

    @Nested
    class BuildDecomposePromptTests {

        @Test
        void minimalWish_containsWishText() {
            Wish wish = createWish("w-1", null);
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("## Wish");
            assertThat(prompt).contains("Test wish text");
        }

        @Test
        void wishWithProject_containsProjectSection() {
            Wish wish = createWish("w-1", "proj-1");
            Project project = Project.builder()
                    .id("proj-1").name("MyProject").path("/home/user/project").build();
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
            lenient().when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of());
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("## Target Project");
            assertThat(prompt).contains("MyProject");
            assertThat(prompt).contains("/home/user/project");
        }

        @Test
        void wishWithClarifications_containsClarificationSection() {
            Wish wish = createWish("w-1", null);
            wish.setMetadata(new HashMap<>(Map.of(
                    "clarifications", List.of(Map.of("question", "Which DB?", "answer", "PostgreSQL"))
            )));
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("## Clarifications from User");
            assertThat(prompt).contains("Which DB?");
            assertThat(prompt).contains("PostgreSQL");
        }

        @Test
        void wishWithUserFeedback_containsFeedbackSection() {
            Wish wish = createWish("w-1", null);
            wish.setMetadata(new HashMap<>(Map.of("userFeedback", "Add error handling")));
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("## User Feedback");
            assertThat(prompt).contains("Add error handling");
        }

        @Test
        void allReposEmpty_noException() {
            Wish wish = createWish("w-1", null);
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("## Reusable Components");
            assertThat(prompt).isNotEmpty();
        }

        @Test
        void wishWithReferencedWorkflows_containsReferencedSection() {
            Wish wish = createWish("w-1", null);
            wish.setReferenceWorkflowIds(new ArrayList<>(List.of("ref-1")));
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            Project refProject = Project.builder().id("p-1").name("RefProj").build();
            Workflow refWf = new Workflow();
            refWf.setId("ref-1");
            refWf.setName("Reference WF");
            refWf.setDescription("A reference workflow");
            refWf.setBlocks(List.of(BlockDefinition.builder().type("shell").config(Map.of("command", "echo hi")).build()));
            refWf.setProject(refProject);
            when(workflowRepository.findById("ref-1")).thenReturn(Optional.of(refWf));

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("### Referenced Workflows (user-selected)");
            assertThat(prompt).contains("Reference WF");
            assertThat(prompt).contains("ref-1");
            assertThat(prompt).contains("RefProj");
        }

        @Test
        void wishWithWorkflowMemories_containsKnowledgeSection() {
            Wish wish = createWish("w-1", "proj-1");
            Project project = Project.builder().id("proj-1").name("P").build();
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));

            Workflow wf = new Workflow();
            wf.setId("wf-1");
            wf.setName("WF");
            wf.setBlocks(List.of());
            wf.setProject(project);
            when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of(wf));
            lenient().when(workflowRepository.findAll()).thenReturn(List.of(wf));
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            WorkflowMemory memory = WorkflowMemory.builder()
                    .id("mem-1").workflowId("wf-1").title("Lesson Learned")
                    .content("Always run tests first").active(true).build();
            when(memoryRepository.findByWorkflowIdAndActiveTrue("wf-1")).thenReturn(Optional.of(memory));

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("## Accumulated Project Knowledge");
            assertThat(prompt).contains("Lesson Learned");
            assertThat(prompt).contains("Always run tests first");
        }

        @Test
        void wishWithSkills_containsSkillsSection() {
            Wish wish = createWish("w-1", null);
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());

            Skill skill = new Skill();
            skill.setName("Code Review");
            skill.setDescription("Reviews code quality");
            when(skillRepository.findAll()).thenReturn(List.of(skill));

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("## Available Skills");
            assertThat(prompt).contains("Code Review");
            assertThat(prompt).contains("Reviews code quality");
        }

        @Test
        void crossProjectWorkflows_scoredByRelevance() {
            Wish wish = createWish("w-1", null);
            wish.setOriginalText("deploy application");
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            Workflow crossWf = new Workflow();
            crossWf.setId("cross-1");
            crossWf.setName("deploy-helper");
            crossWf.setDescription("auto deploy to production");
            crossWf.setBlocks(List.of());
            crossWf.setProject(Project.builder().id("other").name("Other").build());
            when(workflowRepository.findAll()).thenReturn(List.of(crossWf));

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("### Relevant workflows from other projects");
            assertThat(prompt).contains("deploy-helper");
        }

        @Test
        void wishWithEvaluations_containsPreviousIterationFeedback() {
            Wish wish = createWish("w-1", null);
            wish.setMetadata(new HashMap<>(Map.of(
                    "evaluations", List.of(
                            Map.of("satisfied", false, "issues", List.of("Missing error handling")),
                            Map.of("satisfied", false, "issues", List.of("Still no retries"))
                    )
            )));
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("## Previous Iteration Feedback");
            // Should contain last evaluation only
            assertThat(prompt).contains("Still no retries");
            assertThat(prompt).contains("The previous attempt was not satisfactory");
        }

        @Test
        void wishWithProjectDescription_containsDescription() {
            Wish wish = createWish("w-1", "proj-1");
            Project project = Project.builder()
                    .id("proj-1").name("MyProject").path("/home/user/project")
                    .description("A web application for task management").build();
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
            lenient().when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of());
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("**Description**: A web application for task management");
        }

        @Test
        void wishWithProjectWorkflows_containsCurrentProjectSection() {
            Wish wish = createWish("w-1", "proj-1");
            Project project = Project.builder().id("proj-1").name("MyProject").build();
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));

            Workflow wf = new Workflow();
            wf.setId("wf-1");
            wf.setName("Build Pipeline");
            wf.setDescription("Builds the project");
            wf.setBlocks(List.of(BlockDefinition.builder().type("shell").config(Map.of("command", "mvn package")).build()));
            wf.setProject(project);
            when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of(wf));
            lenient().when(workflowRepository.findAll()).thenReturn(List.of(wf));
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("MyProject (current project");
            assertThat(prompt).contains("Build Pipeline");
            assertThat(prompt).contains("wf-1");
        }

        @Test
        void executionIntelligence_includedWhenWorkflowsHaveRuns() {
            Wish wish = createWish("w-1", "proj-1");
            Project project = Project.builder().id("proj-1").name("P").build();
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));

            Workflow wf = new Workflow();
            wf.setId("wf-1");
            wf.setName("Test WF");
            wf.setBlocks(List.of());
            wf.setProject(project);
            when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of(wf));
            lenient().when(workflowRepository.findAll()).thenReturn(List.of(wf));
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            when(learningService.getWorkflowStats("wf-1")).thenReturn(Map.of(
                    "totalRuns", 5, "successRate", 0.8, "avgDurationMs", 30000.0
            ));
            when(learningService.getExecutionInsights("wf-1")).thenReturn(Map.of(
                    "avgCostPerRunUsd", 0.1, "totalCostUsd", 0.5, "modelsUsed", List.of("claude-sonnet-4-6")
            ));
            when(learningService.getOptimizationSuggestions("wf-1")).thenReturn(List.of());
            when(learningService.getAgenticAnalytics("wf-1")).thenReturn(Map.of("totalAgenticRuns", 0));

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("## Execution Intelligence");
            assertThat(prompt).contains("Test WF");
            assertThat(prompt).contains("80%");
        }

        @Test
        void blankUserFeedback_skipped() {
            Wish wish = createWish("w-1", null);
            wish.setMetadata(new HashMap<>(Map.of("userFeedback", "   ")));
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            String prompt = builder.buildDecomposePrompt(wish);
            assertThat(prompt).doesNotContain("## User Feedback");
        }

        @Test
        void matchingTemplates_containsTemplateSection() {
            Wish wish = createWish("w-1", null);
            wish.setOriginalText("deploy application");
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(workflowPoolService.listAll()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            WorkflowTemplate template = WorkflowTemplate.builder()
                    .name(Map.of("en", "Deploy Template"))
                    .description(Map.of("en", "Deploys to production"))
                    .blocks(List.of(BlockDefinition.builder().type("shell").config(Map.of("command", "deploy.sh")).build()))
                    .sortOrder(1)
                    .build();
            when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(template));

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("### Matching templates & pool workflows");
            assertThat(prompt).contains("Deploy Template");
            assertThat(prompt).contains("[Template]");
        }

        @Test
        void matchingPoolWorkflows_containsPoolSection() {
            Wish wish = createWish("w-1", null);
            wish.setOriginalText("deploy application");
            lenient().when(workflowRepository.findAll()).thenReturn(List.of());
            lenient().when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
            lenient().when(skillRepository.findAll()).thenReturn(List.of());

            PoolWorkflowEntry poolEntry = new PoolWorkflowEntry(
                    "pool-1", Map.of("en", "Pool Deploy"), Map.of("en", "Deploys apps"),
                    null, null, List.of(), List.of(BlockDefinition.builder().type("shell").config(Map.of("command", "deploy")).build()),
                    null, null, null, null, null, null, null
            );
            when(workflowPoolService.listAll()).thenReturn(List.of(poolEntry));

            String prompt = builder.buildDecomposePrompt(wish);

            assertThat(prompt).contains("### Matching templates & pool workflows");
            assertThat(prompt).contains("Pool Deploy");
            assertThat(prompt).contains("[Pool]");
        }
    }

    @Nested
    class BuildDecomposeSystemPromptTests {

        @Test
        void containsBlockTypeDocumentation() {
            Wish wish = createWish("w-1", null);
            when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("");

            String prompt = builder.buildDecomposeSystemPrompt(wish);

            assertThat(prompt).contains("simple");
            assertThat(prompt).contains("shell");
            assertThat(prompt).contains("agentic");
            assertThat(prompt).contains("conditional");
            assertThat(prompt).contains("workflow architect");
        }

        @Test
        void includesAgentInstructionsPrefix() {
            when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("Base instructions here");
            Wish wish = createWish("w-1", null);
            String prompt = builder.buildDecomposeSystemPrompt(wish);
            assertThat(prompt).startsWith("Base instructions here");
            assertThat(prompt).contains("---");
        }

        @Test
        void skipsBlankAgentInstructions() {
            when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("   ");
            Wish wish = createWish("w-1", null);
            String prompt = builder.buildDecomposeSystemPrompt(wish);
            assertThat(prompt).doesNotStartWith("   ");
        }

        @Test
        void includesOutputFormat() {
            when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("");
            String prompt = builder.buildDecomposeSystemPrompt(createWish("w-1", null));
            assertThat(prompt).contains("## Output Format");
            assertThat(prompt).contains("needsClarification");
            assertThat(prompt).contains("ENTIRE response must be a single JSON object");
        }

        @Test
        void includesTimeoutGuidelines() {
            when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("");
            String prompt = builder.buildDecomposeSystemPrompt(createWish("w-1", null));
            assertThat(prompt).contains("## Timeout Guidelines");
        }
    }

    @Nested
    class BuildEvaluatePromptTests {

        @Test
        void containsOriginalWishAndTask() {
            Wish wish = createWish("w-1", null);
            wish.setDecomposition(Map.of("analysis", "test analysis"));

            String prompt = builder.buildEvaluatePrompt(wish);

            assertThat(prompt).contains("## Original Wish");
            assertThat(prompt).contains("Test wish text");
            assertThat(prompt).contains("## Task");
            assertThat(prompt).contains("satisfied");
        }

        @Test
        void largeDecomposition_isTruncated() {
            Wish wish = createWish("w-1", null);
            Map<String, Object> decomposition = new HashMap<>();
            decomposition.put("analysis", "a".repeat(2000));
            decomposition.put("strategy", "b".repeat(2000));
            wish.setDecomposition(decomposition);

            String prompt = builder.buildEvaluatePrompt(wish);

            assertThat(prompt).contains("Analysis:");
            assertThat(prompt).contains("Strategy:");
        }

        @Test
        void workflowRunOutput_included() {
            Wish wish = createWish("w-1", null);
            wish.setCurrentRunId("run-1");

            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED).build();
            when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(run));

            StepResult step = StepResult.builder()
                    .stepIndex(0).output("Step output here").status(StepStatus.COMPLETED)
                    .metadata(Map.of("blockType", "shell")).build();
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of(step));
            when(learningService.getRunInsights("run-1")).thenReturn(
                    Map.of("totalCostUsd", 0.0, "totalTokens", 0L, "durationMs", 0L));

            String prompt = builder.buildEvaluatePrompt(wish);

            assertThat(prompt).contains("Run Status: COMPLETED");
            assertThat(prompt).contains("Step output here");
        }

        @Test
        void chainRunOutput_included() {
            Wish wish = createWish("w-1", null);
            wish.setGeneratedChainId("chain-1");
            wish.setCurrentRunId("chain-run-1");

            ChainRunEntry entry = ChainRunEntry.builder()
                    .workflowName("WF1").status(RunStatus.COMPLETED).workflowRunId("wf-run-1").build();
            ChainRun chainRun = ChainRun.builder()
                    .id("chain-run-1").status(RunStatus.COMPLETED).entryRuns(List.of(entry)).build();
            when(chainRunRepository.findById("chain-run-1")).thenReturn(Optional.of(chainRun));

            WorkflowRun wfRun = WorkflowRun.builder().id("wf-run-1").status(RunStatus.COMPLETED).build();
            when(workflowRunRepository.findById("wf-run-1")).thenReturn(Optional.of(wfRun));
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("wf-run-1")).thenReturn(List.of());
            when(learningService.getRunInsights("chain-run-1")).thenReturn(
                    Map.of("totalCostUsd", 0.5, "totalTokens", 1000L, "durationMs", 5000L));

            String prompt = builder.buildEvaluatePrompt(wish);

            assertThat(prompt).contains("Chain Status: COMPLETED");
            assertThat(prompt).contains("Workflow 'WF1': COMPLETED");
        }

        @Test
        void executionCost_included() {
            Wish wish = createWish("w-1", null);
            wish.setCurrentRunId("run-1");

            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED).build();
            when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(run));
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of());
            when(learningService.getRunInsights("run-1")).thenReturn(
                    Map.of("totalCostUsd", 1.5, "totalTokens", 50000L, "durationMs", 30000L));

            String prompt = builder.buildEvaluatePrompt(wish);

            assertThat(prompt).contains("## Execution Cost");
            assertThat(prompt).contains("50000");
        }

        @Test
        void verificationResults_included() {
            Wish wish = createWish("w-1", null);
            wish.setMetadata(new HashMap<>(Map.of("verificationResults", List.of(
                    Map.of("workflowId", "wf-1", "status", "PASSED")
            ))));

            String prompt = builder.buildEvaluatePrompt(wish);

            assertThat(prompt).contains("## Pre-flight Verification Results");
            assertThat(prompt).contains("wf-1");
            assertThat(prompt).contains("PASSED");
        }

        @Test
        void totalPromptTruncatedAt15000Chars() {
            Wish wish = createWish("w-1", null);
            wish.setCurrentRunId("run-1");
            // Create a very large decomposition that will push the prompt over 15000 chars
            Map<String, Object> largeDecomp = new HashMap<>();
            largeDecomp.put("analysis", "a".repeat(8000));
            largeDecomp.put("strategy", "b".repeat(8000));
            wish.setDecomposition(largeDecomp);

            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED).build();
            when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(run));
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of());
            lenient().when(learningService.getRunInsights("run-1")).thenReturn(
                    Map.of("totalCostUsd", 0.0, "totalTokens", 0L, "durationMs", 0L));

            String prompt = builder.buildEvaluatePrompt(wish);

            assertThat(prompt).contains("[... context truncated for prompt size ...]");
            assertThat(prompt).contains("## Task"); // task still appended
        }

        @Test
        void nullDecomposition_handledGracefully() {
            Wish wish = createWish("w-1", null);
            wish.setDecomposition(null);

            String prompt = builder.buildEvaluatePrompt(wish);

            assertThat(prompt).contains("## Generated Workflow Structure");
            assertThat(prompt).contains("## Task");
            // Should not throw NPE
        }

        @Test
        void runWithError_includesErrorInOutput() {
            Wish wish = createWish("w-1", null);
            wish.setCurrentRunId("run-1");

            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.FAILED)
                    .error(Map.of("message", "OutOfMemoryError: Java heap space")).build();
            when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(run));
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of());
            lenient().when(learningService.getRunInsights("run-1")).thenReturn(
                    Map.of("totalCostUsd", 0.0, "totalTokens", 0L, "durationMs", 0L));

            String prompt = builder.buildEvaluatePrompt(wish);

            assertThat(prompt).contains("Error:");
            assertThat(prompt).contains("OutOfMemoryError");
        }

        @Test
        void stepsOver8_showsTruncationSummary() {
            Wish wish = createWish("w-1", null);
            wish.setCurrentRunId("run-1");

            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED).build();
            when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(run));

            List<StepResult> steps = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                steps.add(StepResult.builder()
                        .stepIndex(i).output("Output " + i).status(StepStatus.COMPLETED)
                        .metadata(Map.of("blockType", "shell")).build());
            }
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(steps);
            when(learningService.getRunInsights("run-1")).thenReturn(
                    Map.of("totalCostUsd", 0.0, "totalTokens", 0L, "durationMs", 0L));

            String prompt = builder.buildEvaluatePrompt(wish);

            // First 4 steps (0-3) should be summarized
            assertThat(prompt).contains("[Steps 0-3:");
            assertThat(prompt).contains("4/4 succeeded");
            // Last 8 steps should have detailed output
            assertThat(prompt).contains("Output 11");
        }

        @Test
        void verificationResultsWithError_includesErrorText() {
            Wish wish = createWish("w-1", null);
            wish.setMetadata(new HashMap<>(Map.of("verificationResults", List.of(
                    Map.of("workflowId", "wf-1", "status", "FAILED", "error", "Connection timeout to API")
            ))));

            String prompt = builder.buildEvaluatePrompt(wish);

            assertThat(prompt).contains("FAILED");
            assertThat(prompt).contains("Connection timeout to API");
        }

        @Test
        void runInsightsException_handledGracefully() {
            Wish wish = createWish("w-1", null);
            wish.setCurrentRunId("run-1");

            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED).build();
            when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(run));
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of());
            when(learningService.getRunInsights("run-1")).thenThrow(new RuntimeException("DB error"));

            String prompt = builder.buildEvaluatePrompt(wish);

            // Should not throw, just skip cost section
            assertThat(prompt).doesNotContain("## Execution Cost");
            assertThat(prompt).contains("## Task");
        }
    }

    // --- Helpers ---

    private Wish createWish(String id, String projectId) {
        return Wish.builder()
                .id(id)
                .originalText("Test wish text")
                .status(WishStatus.DECOMPOSING)
                .projectId(projectId)
                .metadata(new HashMap<>())
                .generatedWorkflowIds(new ArrayList<>())
                .referenceWorkflowIds(new ArrayList<>())
                .iterationHistory(new ArrayList<>())
                .iterationCount(0)
                .maxIterations(5)
                .build();
    }
}

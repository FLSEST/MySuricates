package com.automation.service.wish;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.repository.SkillRepository;
import com.automation.service.learning.LearningService;
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
class WishPromptHelperTest {

    @Mock private SkillRepository skillRepository;
    @Mock private LearningService learningService;

    private WishPromptHelper helper;

    @BeforeEach
    void setUp() {
        helper = new WishPromptHelper(skillRepository, learningService);
    }

    // ── formatBlockPipeline ──

    @Nested
    class FormatBlockPipelineTests {

        @Test
        void nullBlocks_returnsEmpty() {
            assertThat(helper.formatBlockPipeline(null)).isEqualTo("[empty]");
        }

        @Test
        void emptyBlocks_returnsEmpty() {
            assertThat(helper.formatBlockPipeline(List.of())).isEqualTo("[empty]");
        }

        @Test
        void shellBlock_showsCommand() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("shell")
                    .config(Map.of("command", "npm run build"))
                    .build();

            String result = helper.formatBlockPipeline(List.of(block));
            assertThat(result).isEqualTo("[shell: npm run build]");
        }

        @Test
        void agenticBlock_showsGoalAndIterations() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("agentic")
                    .config(Map.of("goal", "Fix all lint errors", "max_iterations", 5))
                    .build();

            String result = helper.formatBlockPipeline(List.of(block));
            assertThat(result).isEqualTo("[agentic: Fix all lint errors (5 iter)]");
        }

        @Test
        void multipleBlocks_joinedWithArrow() {
            BlockDefinition shell = BlockDefinition.builder()
                    .type("shell").config(Map.of("command", "npm test")).build();
            BlockDefinition simple = BlockDefinition.builder()
                    .type("simple").config(Map.of("command", "Analyze results")).build();

            String result = helper.formatBlockPipeline(List.of(shell, simple));
            assertThat(result).isEqualTo("[shell: npm test] → [simple: Analyze results]");
        }

        @Test
        void blockWithNoConfig_showsTypeOnly() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("conditional").config(null).build();

            assertThat(helper.formatBlockPipeline(List.of(block))).isEqualTo("[conditional]");
        }

        @Test
        void longCommand_isTruncated() {
            String longCmd = "a".repeat(60);
            BlockDefinition block = BlockDefinition.builder()
                    .type("shell").config(Map.of("command", longCmd)).build();

            String result = helper.formatBlockPipeline(List.of(block));
            assertThat(result).contains("[shell: ");
            assertThat(result.length()).isLessThan(60);
        }
    }

    // ── extractKeywords ──

    @Nested
    class ExtractKeywordsTests {

        @Test
        void nullText_returnsEmpty() {
            assertThat(helper.extractKeywords(null)).isEmpty();
        }

        @Test
        void blankText_returnsEmpty() {
            assertThat(helper.extractKeywords("   ")).isEmpty();
        }

        @Test
        void filtersStopWords() {
            Set<String> result = helper.extractKeywords("the quick fox and the lazy dog");
            assertThat(result).contains("quick", "fox", "lazy", "dog");
            assertThat(result).doesNotContain("the", "and");
        }

        @Test
        void filtersGermanStopWords() {
            Set<String> result = helper.extractKeywords("der schnelle Fuchs und die Katze");
            assertThat(result).contains("schnelle", "fuchs", "katze");
            assertThat(result).doesNotContain("der", "und", "die");
        }

        @Test
        void filtersShortWords() {
            Set<String> result = helper.extractKeywords("go to the big house");
            assertThat(result).doesNotContain("go", "to");
            assertThat(result).contains("big", "house");
        }

        @Test
        void splitsOnPunctuation() {
            Set<String> result = helper.extractKeywords("deploy:frontend, run tests!");
            assertThat(result).contains("deploy", "frontend", "run", "tests");
        }

        @Test
        void lowercases() {
            Set<String> result = helper.extractKeywords("Deploy Frontend");
            assertThat(result).contains("deploy", "frontend");
        }
    }

    // ── scoreRelevance ──

    @Nested
    class ScoreRelevanceTests {

        @Test
        void emptyKeywords_returnsZero() {
            assertThat(helper.scoreRelevance(Set.of(), "name", "desc", List.of())).isZero();
        }

        @Test
        void nameMatch_scores3() {
            int score = helper.scoreRelevance(Set.of("deploy"), "Deploy Script", null, null);
            assertThat(score).isEqualTo(3);
        }

        @Test
        void descriptionMatch_scores2() {
            int score = helper.scoreRelevance(Set.of("deploy"), "Script", "Deploy to production", null);
            assertThat(score).isEqualTo(2);
        }

        @Test
        void blockConfigMatch_scores1() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("shell").config(Map.of("command", "npm deploy")).build();

            int score = helper.scoreRelevance(Set.of("deploy"), "Script", null, List.of(block));
            assertThat(score).isEqualTo(1);
        }

        @Test
        void combinedScoring() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("shell").config(Map.of("command", "npm deploy")).build();

            int score = helper.scoreRelevance(Set.of("deploy"), "Deploy Script", "Deploy to production", List.of(block));
            // name: 3, desc: 2, block: 1 = 6
            assertThat(score).isEqualTo(6);
        }

        @Test
        void nullNameAndDescription_noError() {
            int score = helper.scoreRelevance(Set.of("test"), null, null, null);
            assertThat(score).isZero();
        }

        @Test
        void blockWithNullConfig_skipped() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("shell").config(null).build();

            int score = helper.scoreRelevance(Set.of("test"), "name", null, List.of(block));
            assertThat(score).isZero();
        }
    }

    // ── inferModelFromBlocks ──

    @Nested
    class InferModelFromBlocksTests {

        @Test
        void analysisInDescription_returnsOpus() {
            String result = helper.inferModelFromBlocks(List.of(), "Security Analysis");
            assertThat(result).isEqualTo("opus");
        }

        @Test
        void auditInDescription_returnsOpus() {
            assertThat(helper.inferModelFromBlocks(List.of(), "Code Audit")).isEqualTo("opus");
        }

        @Test
        void reviewInDescription_returnsOpus() {
            assertThat(helper.inferModelFromBlocks(List.of(), "Architecture Review")).isEqualTo("opus");
        }

        @Test
        void noAnalysisPattern_returnsNull() {
            assertThat(helper.inferModelFromBlocks(List.of(), "Deploy frontend")).isNull();
        }

        @Test
        void analysisInAgenticGoal_returnsOpus() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("agentic")
                    .config(Map.of("goal", "Evaluate code quality"))
                    .build();

            assertThat(helper.inferModelFromBlocks(List.of(block), "Build pipeline")).isEqualTo("opus");
        }

        @Test
        void nonAgenticBlock_goalNotChecked() {
            BlockDefinition block = BlockDefinition.builder()
                    .type("simple")
                    .config(Map.of("goal", "Evaluate code quality"))
                    .build();

            assertThat(helper.inferModelFromBlocks(List.of(block), "Build pipeline")).isNull();
        }

        @Test
        void nullDescription_noError() {
            assertThat(helper.inferModelFromBlocks(List.of(), null)).isNull();
        }
    }

    // ── appendAvailableSkills ──

    @Nested
    class AppendAvailableSkillsTests {

        @Test
        void noSkills_appendsNothing() {
            when(skillRepository.findAll()).thenReturn(List.of());
            StringBuilder sb = new StringBuilder();

            helper.appendAvailableSkills(sb);

            assertThat(sb.toString()).isEmpty();
        }

        @Test
        void withSkills_appendsSection() {
            Skill skill = new Skill();
            skill.setName("Code Review");
            skill.setDescription("Reviews code for issues");
            when(skillRepository.findAll()).thenReturn(List.of(skill));
            StringBuilder sb = new StringBuilder();

            helper.appendAvailableSkills(sb);

            assertThat(sb.toString())
                    .contains("## Available Skills")
                    .contains("**Code Review**")
                    .contains("Reviews code for issues");
        }
    }

    // ── buildExecutionIntelligence ──

    @Nested
    class BuildExecutionIntelligenceTests {

        @Test
        void nullProjectId_returnsEmpty() {
            String result = helper.buildExecutionIntelligence(List.of());
            assertThat(result).isEmpty();
        }

        @Test
        void noWorkflows_returnsEmpty() {
            assertThat(helper.buildExecutionIntelligence(List.of())).isEmpty();
        }

        @Test
        void workflowWithFewRuns_skipped() {
            Workflow wf = new Workflow();
            wf.setId("wf1");
            wf.setName("Test WF");
            when(learningService.getWorkflowStats("wf1")).thenReturn(Map.of("totalRuns", 1));

            String result = helper.buildExecutionIntelligence(List.of(wf));
            assertThat(result).isEmpty();
        }

        @Test
        void workflowWithRuns_includesPerformance() {
            Workflow wf = new Workflow();
            wf.setId("wf1");
            wf.setName("Deploy WF");
            when(learningService.getWorkflowStats("wf1")).thenReturn(Map.of(
                    "totalRuns", 5, "successRate", 0.8, "avgDurationMs", 30000.0
            ));
            when(learningService.getExecutionInsights("wf1")).thenReturn(Map.of(
                    "avgCostPerRunUsd", 0.05, "totalCostUsd", 0.25, "modelsUsed", List.of("claude-sonnet-4-6")
            ));
            when(learningService.getOptimizationSuggestions("wf1")).thenReturn(List.of());
            when(learningService.getAgenticAnalytics("wf1")).thenReturn(Map.of("totalAgenticRuns", 0));

            String result = helper.buildExecutionIntelligence(List.of(wf));

            assertThat(result)
                    .contains("## Execution Intelligence")
                    .contains("### Workflow Performance")
                    .contains("Deploy WF")
                    .contains("80%")
                    .contains("### Cost Profile")
                    .contains("claude-sonnet-4-6");
        }

        @Test
        void exceptionInLearningService_returnsPartial() {
            Workflow wf = new Workflow();
            wf.setId("wf1");
            when(learningService.getWorkflowStats("wf1")).thenThrow(new RuntimeException("DB error"));

            String result = helper.buildExecutionIntelligence(List.of(wf));
            assertThat(result).isEmpty(); // try-catch returns empty
        }
    }
}

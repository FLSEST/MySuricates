package com.automation.service.wish;

import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.learning.LearningService;
import static com.automation.entity.RunStatus.COMPLETED;
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
class WishEvaluatePromptBuilderTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private ChainRunRepository chainRunRepository;
    @Mock private StepResultRepository stepResultRepository;
    @Mock private LearningService learningService;

    private WishEvaluatePromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new WishEvaluatePromptBuilder(
                workflowRunRepository, chainRunRepository, stepResultRepository, learningService
        );
    }

    private Wish buildWish(String text) {
        Wish wish = new Wish();
        wish.setOriginalText(text);
        return wish;
    }

    @Nested
    class BasicPromptTests {

        @Test
        void includesOriginalWish() {
            Wish wish = buildWish("Deploy to production");

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("## Original Wish")
                    .contains("Deploy to production")
                    .contains("## Task")
                    .contains("\"satisfied\"");
        }

        @Test
        void noDecomposition_stillBuildsPrompt() {
            Wish wish = buildWish("Simple task");
            wish.setDecomposition(null);

            String result = builder.buildPrompt(wish);

            assertThat(result).contains("## Generated Workflow Structure");
        }

        @Test
        void smallDecomposition_includesFullJson() {
            Wish wish = buildWish("Task");
            wish.setDecomposition(Map.of("analysis", "short analysis", "strategy", "simple"));

            String result = builder.buildPrompt(wish);

            assertThat(result).contains("short analysis");
        }

        @Test
        void largeDecomposition_isTruncated() {
            Wish wish = buildWish("Task");
            Map<String, Object> decomposition = new HashMap<>();
            decomposition.put("analysis", "a".repeat(3000));
            decomposition.put("strategy", "big strategy");
            decomposition.put("workflows", List.of(Map.of("name", "WF1"), Map.of("name", "WF2")));
            wish.setDecomposition(decomposition);

            String result = builder.buildPrompt(wish);

            assertThat(result).contains("Analysis:");
            assertThat(result).contains("Strategy: big strategy");
            assertThat(result).contains("Workflow: WF1");
            assertThat(result).contains("Workflow: WF2");
        }
    }

    @Nested
    class ExecutionResultsTests {

        @Test
        void noRunId_noExecutionDetails() {
            Wish wish = buildWish("Task");

            String result = builder.buildPrompt(wish);

            assertThat(result).contains("## Execution Results");
            // Should not crash, no details added
        }

        @Test
        void workflowRun_includesSteps() {
            Wish wish = buildWish("Task");
            wish.setCurrentRunId("run-1");

            WorkflowRun run = new WorkflowRun();
            run.setId("run-1");
            run.setStatus(RunStatus.COMPLETED);
            when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(run));

            StepResult step = new StepResult();
            step.setStepIndex(0);
            step.setStatus(StepStatus.COMPLETED);
            step.setOutput("All tests passed");
            step.setMetadata(Map.of("blockType", "shell"));
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1"))
                    .thenReturn(List.of(step));

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("Run Status: COMPLETED")
                    .contains("Step 0 (shell): COMPLETED")
                    .contains("All tests passed");
        }

        @Test
        void chainRun_includesEntries() {
            Wish wish = buildWish("Task");
            wish.setGeneratedChainId("chain-1");
            wish.setCurrentRunId("chain-run-1");

            ChainRun chainRun = new ChainRun();
            chainRun.setId("chain-run-1");
            chainRun.setStatus(RunStatus.COMPLETED);
            ChainRunEntry entry = new ChainRunEntry();
            entry.setWorkflowName("Build WF");
            entry.setStatus(RunStatus.COMPLETED);
            entry.setWorkflowRunId(null);
            chainRun.setEntryRuns(List.of(entry));
            when(chainRunRepository.findById("chain-run-1")).thenReturn(Optional.of(chainRun));

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("Chain Status: COMPLETED")
                    .contains("Workflow 'Build WF': COMPLETED");
        }

        @Test
        void manySteps_summarizesEarlierSteps() {
            Wish wish = buildWish("Task");
            wish.setCurrentRunId("run-1");

            WorkflowRun run = new WorkflowRun();
            run.setId("run-1");
            run.setStatus(RunStatus.COMPLETED);
            when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(run));

            List<StepResult> steps = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                StepResult s = new StepResult();
                s.setStepIndex(i);
                s.setStatus(StepStatus.COMPLETED);
                s.setOutput("Output " + i);
                s.setMetadata(Map.of("blockType", "shell"));
                steps.add(s);
            }
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(steps);

            String result = builder.buildPrompt(wish);

            // First 4 steps should be summarized
            assertThat(result).contains("[Steps 0-3:");
            // Last 8 should be detailed
            assertThat(result).contains("Step 4 (shell):");
            assertThat(result).contains("Step 11 (shell):");
        }
    }

    @Nested
    class CostContextTests {

        @Test
        void withCost_includesCostSection() {
            Wish wish = buildWish("Task");
            wish.setCurrentRunId("run-1");

            when(workflowRunRepository.findById("run-1")).thenReturn(Optional.empty());
            when(learningService.getRunInsights("run-1")).thenReturn(Map.of(
                    "totalCostUsd", 0.05, "totalTokens", 5000L, "durationMs", 30000L
            ));

            String result = builder.buildPrompt(wish);

            assertThat(result).contains("## Execution Cost");
            assertThat(result).containsPattern("\\$0[.,]0500");
            assertThat(result).contains("Tokens: 5000");
        }

        @Test
        void costException_doesNotCrash() {
            Wish wish = buildWish("Task");
            wish.setCurrentRunId("run-1");
            when(learningService.getRunInsights("run-1")).thenThrow(new RuntimeException("error"));

            String result = builder.buildPrompt(wish);
            // Should still build without cost section
            assertThat(result).contains("## Original Wish");
        }
    }

    @Nested
    class VerificationResultsTests {

        @Test
        void withVerificationResults_includesSection() {
            Wish wish = buildWish("Task");
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("verificationResults", List.of(
                    Map.of("workflowId", "wf-1", "status", "PASSED"),
                    Map.of("workflowId", "wf-2", "status", "FAILED", "error", "Timeout occurred")
            ));
            wish.setMetadata(metadata);

            String result = builder.buildPrompt(wish);

            assertThat(result)
                    .contains("## Pre-flight Verification Results")
                    .contains("Workflow wf-1: PASSED")
                    .contains("Workflow wf-2: FAILED (Timeout occurred)");
        }

        @Test
        void noVerificationResults_noSection() {
            Wish wish = buildWish("Task");

            String result = builder.buildPrompt(wish);

            assertThat(result).doesNotContain("Pre-flight Verification");
        }
    }

    @Test
    void veryLargePrompt_isTruncated() {
        Wish wish = buildWish("x".repeat(16000));

        String result = builder.buildPrompt(wish);

        assertThat(result).contains("[... context truncated");
        assertThat(result).contains("## Task");
    }
}

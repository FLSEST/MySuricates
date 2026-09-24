package com.automation.service.memory;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowMemory;
import com.automation.entity.WorkflowRun;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.ai.RunContextFiles;
import com.automation.service.ai.ShellScriptCli;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Auto-memory with a Claude CLI that fails. At a usage limit, without login or after a crash the CLI
 * prints its message and ends with an exit code ≠ 0; that message must not become the workflow's
 * memory — no memory row and no {@code project-memory.md}, which every later run of the workflow reads
 * (9cc6c615). Real listener, memory service, summarizer and host service; only the CLI is a shell
 * script, and the repositories keep what is saved in a list.
 */
class AutoMemoryCliErrorTest {

    @TempDir
    Path tempDir;

    private final List<WorkflowMemory> savedMemories = new ArrayList<>();
    private final WorkflowMemoryRepository memoryRepository = mock(WorkflowMemoryRepository.class);
    private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
    private Path projectDir;

    @BeforeEach
    void setUp() throws Exception {
        projectDir = Files.createDirectories(tempDir.resolve("project"));
        when(memoryRepository.save(any())).thenAnswer(inv -> {
            savedMemories.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(memoryRepository.findByWorkflowIdAndActiveTrue("wf-1"))
                .thenAnswer(inv -> savedMemories.stream().filter(WorkflowMemory::isActive).findFirst());
        StepResult step = StepResult.builder().stepIndex(0).input("mvn -q test").output("BUILD SUCCESS")
                .status(StepStatus.COMPLETED).metadata(new HashMap<>()).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1"))
                .thenReturn(List.of(WorkflowRun.builder().id("run-1").steps(List.of(step)).build()));
        // interval 5 reached: the run that just completed is the fifth since the last extraction
        when(runRepository.countByWorkflowIdAndStatusAndCompletedAtAfter(eq("wf-1"), eq(RunStatus.COMPLETED), any()))
                .thenReturn(5L);
    }

    @Test
    void cliEndsWithNonZeroExitCode_noMemoryRowAndNoProjectMemoryFile() throws Exception {
        runCompleted(ShellScriptCli.claudeReasoning("Claude AI usage limit reached", 1));

        assertSoftly(softly -> {
            softly.assertThat(savedMemories).as("memory rows").isEmpty();
            softly.assertThat(projectDir.resolve(RunContextFiles.WORKFLOW_MEMORY)).doesNotExist();
        });
    }

    /** Control: the same wiring with a CLI that answers stores, activates and writes the memory. */
    @Test
    void cliAnswers_memoryIsStoredActivatedAndWritten() throws Exception {
        runCompleted(ShellScriptCli.claudeReasoning("TITLE: Build\nTAGS: maven\n---\n- Tests: mvn -q test", 0));

        assertThat(savedMemories).extracting(WorkflowMemory::getContent).containsOnly("- Tests: mvn -q test");
        assertThat(savedMemories).extracting(WorkflowMemory::isActive).containsOnly(true);
        assertThat(projectDir.resolve(RunContextFiles.WORKFLOW_MEMORY)).hasContent("- Tests: mvn -q test");
    }

    private void runCompleted(HostClaudeService claude) throws Exception {
        SummarizationSetupService setup = mock(SummarizationSetupService.class);
        when(setup.getClaudeWorkDir()).thenReturn(Files.createDirectories(tempDir.resolve("summarization")));
        ConversationPreprocessor preprocessor = mock(ConversationPreprocessor.class);
        when(preprocessor.preprocess(any())).thenReturn("[step 0] mvn -q test -> BUILD SUCCESS");
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        WorkflowMemoryService memoryService = new WorkflowMemoryService(memoryRepository, runRepository,
                preprocessor, new ClaudeSummarizer(claude, setup), mock(CodexSummarizer.class),
                new ObjectMapper(), transactionManager);
        AutoMemoryExtractionListener listener = new AutoMemoryExtractionListener(memoryService,
                memoryRepository, runRepository, ModuleRegistry.allEnabled());

        listener.onWorkflowRunCompleted(new WorkflowRunCompletedEvent(this, "run-5", "wf-1", "Nightly Build",
                "proj-1", projectDir.toString(), RunStatus.COMPLETED, Map.of(),
                Map.of("autoMemoryExtraction", true), Map.of()));
    }
}

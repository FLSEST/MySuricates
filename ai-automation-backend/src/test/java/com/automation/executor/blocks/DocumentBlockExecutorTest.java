package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.service.document.DocumentExtractionException;
import com.automation.service.document.DocumentExtractionService;
import com.automation.service.document.DocumentExtractionService.ExtractionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentBlockExecutorTest {

    @Mock
    private DocumentExtractionService extractionService;

    @InjectMocks
    private DocumentBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.builder()
                .containerId("c-1")
                .workflowRunId("r-1")
                .build();
        context.setProjectPath("/project");
    }

    @Test
    void supports_documentType() {
        assertThat(executor.supports("document")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("shell")).isFalse();
    }

    @Test
    void execute_missingFilePath_throws() {
        BlockDefinition block = BlockDefinition.builder()
                .type("document").config(new HashMap<>()).build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("file_path");
    }

    @Test
    void execute_successfulExtraction_returnsCompletedStep() {
        when(extractionService.extract(eq("report.pdf"), eq("/project")))
                .thenReturn(new ExtractionResult("extracted text", "application/pdf",
                        1024L, "/project/report.pdf", "Q4 Report", 12));

        BlockDefinition block = BlockDefinition.builder()
                .type("document")
                .config(new HashMap<>(Map.of("file_path", "report.pdf")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("extracted text");
        assertThat(result.getMetadata())
                .containsEntry("contentType", "application/pdf")
                .containsEntry("sizeBytes", 1024L)
                .containsEntry("characterCount", 14)
                .containsEntry("title", "Q4 Report")
                .containsEntry("pageCount", 12);
    }

    @Test
    void execute_resolvesFilePathTemplate() {
        context.setVariable("invoice_file", "bills/may.pdf");
        when(extractionService.extract(eq("bills/may.pdf"), eq("/project")))
                .thenReturn(new ExtractionResult("text", "application/pdf",
                        100L, "/project/bills/may.pdf", null, null));

        BlockDefinition block = BlockDefinition.builder()
                .type("document")
                .config(new HashMap<>(Map.of("file_path", "{{ invoice_file }}")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_variableName_storesInContext() {
        when(extractionService.extract(eq("doc.txt"), eq("/project")))
                .thenReturn(new ExtractionResult("hello world", "text/plain",
                        11L, "/project/doc.txt", null, null));

        BlockDefinition block = BlockDefinition.builder()
                .type("document")
                .config(new HashMap<>(Map.of(
                        "file_path", "doc.txt",
                        "variable_name", "extracted"
                )))
                .build();

        executor.execute(block, context);

        assertThat(context.getVariable("extracted")).isEqualTo("hello world");
    }

    @Test
    void execute_extractionException_returnsFailedStep() {
        when(extractionService.extract(eq("broken.pdf"), eq("/project")))
                .thenThrow(new DocumentExtractionException("file not found"));

        BlockDefinition block = BlockDefinition.builder()
                .type("document")
                .config(new HashMap<>(Map.of("file_path", "broken.pdf")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("[ERROR]").contains("file not found");
        assertThat(result.getMetadata()).containsKey("error");
    }

    @Test
    void execute_emptyExtraction_failOnEmpty_returnsFailed() {
        when(extractionService.extract(eq("blank.pdf"), eq("/project")))
                .thenReturn(new ExtractionResult("", "application/pdf",
                        5000L, "/project/blank.pdf", null, 1));

        BlockDefinition block = BlockDefinition.builder()
                .type("document")
                .config(new HashMap<>(Map.of(
                        "file_path", "blank.pdf",
                        "fail_on_empty", true
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("below threshold");
    }

    @Test
    void execute_emptyExtraction_default_returnsCompletedWithEmpty() {
        when(extractionService.extract(eq("blank.pdf"), eq("/project")))
                .thenReturn(new ExtractionResult("", "application/pdf",
                        5000L, "/project/blank.pdf", null, 1));

        BlockDefinition block = BlockDefinition.builder()
                .type("document")
                .config(new HashMap<>(Map.of("file_path", "blank.pdf")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEmpty();
    }

    @Test
    void execute_minCharactersThreshold_triggersFail() {
        when(extractionService.extract(eq("short.pdf"), eq("/project")))
                .thenReturn(new ExtractionResult("hi", "application/pdf",
                        500L, "/project/short.pdf", null, 1));

        BlockDefinition block = BlockDefinition.builder()
                .type("document")
                .config(new HashMap<>(Map.of(
                        "file_path", "short.pdf",
                        "min_characters", 50,
                        "fail_on_empty", true
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("below threshold").contains("2 < 50");
    }

    @Test
    void execute_setsStartedAndCompletedTimestamps() {
        when(extractionService.extract(eq("ok.txt"), eq("/project")))
                .thenReturn(new ExtractionResult("x", "text/plain",
                        1L, "/project/ok.txt", null, null));

        BlockDefinition block = BlockDefinition.builder()
                .type("document")
                .config(new HashMap<>(Map.of("file_path", "ok.txt")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStartedAt()).isNotNull();
        assertThat(result.getCompletedAt()).isNotNull();
        assertThat(result.getDurationMs()).isNotNull();
    }
}

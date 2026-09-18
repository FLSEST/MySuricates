package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.document.DocumentExtractionException;
import com.automation.service.document.DocumentExtractionService;
import com.automation.service.document.DocumentExtractionService.ExtractionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Executes "document" blocks: extracts text from a PDF/DOCX/XLSX/HTML/TXT file
 * via Apache Tika and returns it as step output. Stage 1 of the planned
 * document-processing cascade — OCR (Tesseract) and Vision-LLM stages are
 * follow-ups.
 *
 * <p>Block config:
 * <ul>
 *   <li>{@code file_path} (required) — absolute or project-relative path to the file;
 *       supports {@code {{ variable }}} templates.</li>
 *   <li>{@code variable_name} (optional) — context variable to store extracted text in
 *       (in addition to step output). Default: none.</li>
 *   <li>{@code fail_on_empty} (optional) — if true, step FAILS when extracted text is
 *       empty/below threshold; otherwise it COMPLETES with empty output. Default: false.</li>
 *   <li>{@code min_characters} (optional) — treat extractions with fewer characters as
 *       empty (triggers {@code fail_on_empty}). Default: 1.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentBlockExecutor implements BlockExecutor {

    private final DocumentExtractionService extractionService;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        Object rawPath = config.get("file_path");
        if (rawPath == null) {
            throw new WorkflowExecutionException("Document block requires 'file_path' in config");
        }

        String filePath = context.resolveTemplate(rawPath.toString());
        boolean failOnEmpty = Boolean.TRUE.equals(config.get("fail_on_empty"));
        int minCharacters = config.get("min_characters") instanceof Number n ? n.intValue() : 1;
        String variableName = config.get("variable_name") instanceof String s && !s.isBlank() ? s : null;

        StepResult step = new StepResult();
        step.setStepIndex(context.nextStepIndex());
        step.setStartedAt(Instant.now());
        step.setInput(filePath);
        step.setStatus(StepStatus.RUNNING);

        long t0 = System.currentTimeMillis();
        try {
            ExtractionResult result = extractionService.extract(filePath, context.getProjectPath());
            String text = result.text() != null ? result.text() : "";
            boolean belowThreshold = text.length() < minCharacters;

            if (variableName != null) {
                // Extracted file content inherits the run's taint (the file_path itself can come
                // from an untrusted payload) — see ExecutionContext.setDerivedVariable
                context.setDerivedVariable(variableName, text);
            }

            step.setOutput(text);
            step.setCompletedAt(Instant.now());
            step.setDurationMs(System.currentTimeMillis() - t0);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("contentType", result.contentType());
            metadata.put("sizeBytes", result.sizeBytes());
            metadata.put("characterCount", result.characterCount());
            metadata.put("resolvedPath", result.resolvedPath());
            if (result.title() != null) metadata.put("title", result.title());
            if (result.pageCount() != null) metadata.put("pageCount", result.pageCount());
            step.setMetadata(metadata);

            if (belowThreshold && failOnEmpty) {
                step.setStatus(StepStatus.FAILED);
                step.setOutput(text + "\n\n[ERROR] Extracted text below threshold ("
                        + text.length() + " < " + minCharacters + " characters)");
            } else {
                step.setStatus(StepStatus.COMPLETED);
            }
            return step;

        } catch (DocumentExtractionException e) {
            log.warn("Document extraction failed: {}", e.getMessage());
            step.setOutput("[ERROR] " + e.getMessage());
            step.setStatus(StepStatus.FAILED);
            step.setCompletedAt(Instant.now());
            step.setDurationMs(System.currentTimeMillis() - t0);
            step.setMetadata(Map.of("error", e.getMessage()));
            return step;
        }
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("document");
    }
}

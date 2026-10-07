package com.automation.service.memory;

import com.automation.service.ai.HostClaudeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Summarizes workflow execution data using the Claude CLI on the host.
 * Instructions are loaded from CLAUDE.md in the summarization working directory
 * (set up by SummarizationSetupService).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClaudeSummarizer implements KnowledgeSummarizer {

    private final HostClaudeService hostClaudeService;
    private final SummarizationSetupService setupService;

    @Override
    public SummarizationResult summarize(String preprocessedInput) throws IOException {
        String prompt = "=== WORKFLOW EXECUTION DATA ===\n" + preprocessedInput;
        String workDir = setupService.getClaudeWorkDir().toAbsolutePath().toString();

        log.info("Sending summarization request to Claude (input length: {} chars, workDir: {})",
                preprocessedInput.length(), workDir);

        long start = System.currentTimeMillis();
        try {
            // max-turns 1: pure text generation, no tool use needed
            HostClaudeService.ReasoningResult result =
                    hostClaudeService.reasonStructured(prompt, null, workDir, 1);
            long durationMs = System.currentTimeMillis() - start;
            log.info("Claude summarization completed in {}s (response length: {} chars)",
                    durationMs / 1000, result.text().length());
            return SummarizationResult.parse(result.text());
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Claude summarization failed after {}s: {}", durationMs / 1000, e.getMessage());
            throw e;
        }
    }
}

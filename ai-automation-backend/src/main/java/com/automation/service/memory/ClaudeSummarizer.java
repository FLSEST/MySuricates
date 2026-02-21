package com.automation.service.memory;

import com.automation.service.ai.HostClaudeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

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
            return parseResponse(result.text());
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Claude summarization failed after {}s: {}", durationMs / 1000, e.getMessage());
            throw e;
        }
    }

    SummarizationResult parseResponse(String response) {
        String title = "Workflow Knowledge";
        List<String> tags = List.of();
        String content = response;

        int titleIdx = response.indexOf("TITLE:");
        int tagsIdx = response.indexOf("TAGS:");
        int separatorIdx = response.indexOf("\n---\n");

        if (titleIdx >= 0 && tagsIdx > titleIdx) {
            title = response.substring(titleIdx + 6, tagsIdx).trim();
            if (title.length() > 60) {
                title = title.substring(0, 60);
            }
        }

        if (tagsIdx >= 0 && separatorIdx > tagsIdx) {
            String tagsLine = response.substring(tagsIdx + 5, separatorIdx).trim();
            tags = Arrays.stream(tagsLine.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        if (separatorIdx >= 0 && separatorIdx + 5 < response.length()) {
            content = response.substring(separatorIdx + 5).trim();
        }

        return new SummarizationResult(title, content, tags);
    }
}

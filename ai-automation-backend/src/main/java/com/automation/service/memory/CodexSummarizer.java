package com.automation.service.memory;

import com.automation.service.ai.HostCodexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Summarizes workflow execution data using the OpenAI Codex CLI on the host.
 * Instructions are loaded from AGENTS.md in the summarization working directory
 * (set up by SummarizationSetupService).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodexSummarizer implements KnowledgeSummarizer {

    private final HostCodexService hostCodexService;
    private final SummarizationSetupService setupService;

    @Override
    public SummarizationResult summarize(String preprocessedInput) throws IOException {
        String prompt = "=== WORKFLOW EXECUTION DATA ===\n" + preprocessedInput;
        Path workDir = setupService.getCodexWorkDir();

        log.info("Sending summarization request to Codex (input length: {} chars, workDir: {})",
                preprocessedInput.length(), workDir);

        long start = System.currentTimeMillis();
        try {
            String response = hostCodexService.execute(prompt, workDir);
            long durationMs = System.currentTimeMillis() - start;
            log.info("Codex summarization completed in {}s (response length: {} chars)",
                    durationMs / 1000, response.length());
            return parseResponse(response);
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Codex summarization failed after {}s: {}", durationMs / 1000, e.getMessage());
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

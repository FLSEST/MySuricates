package com.automation.service.factory;

import com.automation.service.ai.HostCodexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Generates workflow text using the OpenAI Codex CLI on the host.
 * Merges system prompt into user prompt since Codex has no --system-prompt flag.
 * Instructions are loaded from AGENTS.md in the factory working directory.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodexWorkflowGenerator implements WorkflowTextGenerator {

    private final HostCodexService hostCodexService;
    private final FactorySetupService setupService;

    @Override
    public String generate(String prompt, String systemPrompt, int timeoutSeconds) throws IOException {
        // Merge system prompt into user prompt — Codex has no --system-prompt flag
        String mergedPrompt = systemPrompt != null
                ? systemPrompt + "\n\n---\n\n" + prompt
                : prompt;

        Path workDir = setupService.getCodexWorkDir();

        log.debug("Factory: sending prompt to Codex (prompt length: {} chars, workDir: {}, timeout: {}s)",
                mergedPrompt.length(), workDir, timeoutSeconds);

        return hostCodexService.execute(mergedPrompt, workDir, timeoutSeconds);
    }
}

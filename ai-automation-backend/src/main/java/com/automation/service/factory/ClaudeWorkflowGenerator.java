package com.automation.service.factory;

import com.automation.service.ai.HostClaudeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Generates workflow text using the Claude CLI on the host.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClaudeWorkflowGenerator implements WorkflowTextGenerator {

    private final HostClaudeService hostClaudeService;

    @Override
    public String generate(String prompt, String systemPrompt, int timeoutSeconds) throws IOException {
        log.debug("Factory: sending prompt to Claude (prompt length: {} chars, timeout: {}s)",
                prompt.length(), timeoutSeconds);

        HostClaudeService.ReasoningResult result = hostClaudeService.reasonStructured(
                prompt, systemPrompt, null, 1, timeoutSeconds);

        return result.text();
    }
}

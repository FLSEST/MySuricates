package com.automation.service.factory;

import java.io.IOException;

/**
 * Generates text responses from AI providers for workflow factory operations.
 * Prompt in, text out — used for both workflow generation and auto-fix.
 */
public interface WorkflowTextGenerator {

    String generate(String prompt, String systemPrompt, int timeoutSeconds) throws IOException;
}

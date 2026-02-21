package com.automation.service.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class AgentInstructionsService {

    private static final int MAX_INSTRUCTION_LENGTH = 10_000;

    private String baseInstructions = "";

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("agent-instructions.md");
            baseInstructions = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            log.info("Loaded agent instructions ({} chars)", baseInstructions.length());
        } catch (IOException e) {
            log.warn("Could not load agent-instructions.md: {}", e.getMessage());
        }
    }

    public String buildInstructionPrefix(String workflowInstructions) {
        StringBuilder sb = new StringBuilder();

        if (!baseInstructions.isEmpty()) {
            sb.append(baseInstructions);
        }

        String validated = validateInstructions(workflowInstructions);
        if (validated != null && !validated.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("## Workflow-Specific Instructions\n");
            sb.append(validated);
        }

        return sb.toString();
    }

    private String validateInstructions(String instructions) {
        if (instructions == null) {
            return null;
        }
        String trimmed = instructions.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_INSTRUCTION_LENGTH) {
            log.warn("Workflow instructions truncated from {} to {} chars",
                    trimmed.length(), MAX_INSTRUCTION_LENGTH);
            return trimmed.substring(0, MAX_INSTRUCTION_LENGTH) + "\n[... truncated]";
        }
        return trimmed;
    }
}

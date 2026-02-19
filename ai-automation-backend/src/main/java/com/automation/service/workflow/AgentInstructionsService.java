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

        if (workflowInstructions != null && !workflowInstructions.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("## Workflow-Specific Instructions\n");
            sb.append(workflowInstructions);
        }

        return sb.toString();
    }
}

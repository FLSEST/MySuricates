package com.automation.service.factory;

import com.automation.config.FactoryConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Picks the text generator for the factory: request provider if given, otherwise the configured
 * default. Shared by generation ({@link WorkflowFactoryService}) and auto-fix
 * ({@link FactoryVerificationService}).
 */
@Component
@RequiredArgsConstructor
public class FactoryGeneratorResolver {

    private final ClaudeWorkflowGenerator claudeGenerator;
    private final CodexWorkflowGenerator codexGenerator;
    private final FactoryConfig factoryConfig;

    public String resolveProvider(String requestProvider) {
        return (requestProvider != null && !requestProvider.isBlank())
                ? requestProvider.toUpperCase(Locale.ROOT)
                : factoryConfig.getProvider().toUpperCase(Locale.ROOT);
    }

    public WorkflowTextGenerator generatorFor(String provider) {
        return switch (provider.toUpperCase(Locale.ROOT)) {
            case "CLAUDE" -> claudeGenerator;
            case "CODEX" -> codexGenerator;
            default -> throw new IllegalArgumentException(
                    "Unknown factory provider: " + provider + ". Available: [CLAUDE, CODEX]");
        };
    }
}

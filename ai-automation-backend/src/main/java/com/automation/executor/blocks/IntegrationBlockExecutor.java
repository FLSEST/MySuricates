package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class IntegrationBlockExecutor implements BlockExecutor {

    private final IntegrationRegistry registry;
    private final IntegrationOAuthService oAuthService;
    private final ObjectMapper objectMapper;
    private final ModuleRegistry modules;

    @Override
    public Set<String> supportedTypes() {
        return Set.of("integration");
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        if (!modules.isEnabled(ModuleCatalog.INTEGRATIONS)) {
            return errorResult(context, ModuleCatalog.INTEGRATIONS.disabledMessage("Block type 'integration'"));
        }
        Map<String, Object> config = block.getConfig();
        String providerId = (String) config.get("provider");
        String action = (String) config.get("action");

        if (providerId == null || action == null) {
            return errorResult(context, "Integration block requires 'provider' and 'action' in config");
        }

        StepResult step = new StepResult();
        step.setStepIndex(context.nextStepIndex());
        step.setStartedAt(Instant.now());
        step.setInput("Integration: " + providerId + " → " + action);

        try {
            IntegrationProvider provider = registry.getProvider(providerId);
            String accessToken = oAuthService.getValidAccessToken(providerId);

            @SuppressWarnings("unchecked")
            Map<String, Object> rawParams = (Map<String, Object>) config.getOrDefault("params", Map.of());
            Map<String, Object> resolvedParams = resolveParams(rawParams, context);

            log.info("Executing integration action: {} → {} with {} params", providerId, action, resolvedParams.size());

            Object result = provider.executeAction(action, resolvedParams, accessToken);
            String output = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);

            step.setOutput(output);
            step.setStatus(StepStatus.COMPLETED);
            step.setCompletedAt(Instant.now());
            step.setDurationMs(java.time.Duration.between(step.getStartedAt(), step.getCompletedAt()).toMillis());
            step.setMetadata(Map.of(
                    "provider", providerId,
                    "action", action,
                    "executionMode", "integration"
            ));

            return step;

        } catch (Exception e) {
            log.error("Integration block failed: {} → {}", providerId, action, e);
            step.setOutput("Integration error: " + e.getMessage());
            step.setStatus(StepStatus.FAILED);
            step.setCompletedAt(Instant.now());
            step.setDurationMs(java.time.Duration.between(step.getStartedAt(), step.getCompletedAt()).toMillis());
            step.setMetadata(Map.of(
                    "provider", providerId,
                    "action", action,
                    "executionMode", "integration",
                    "error", e.getMessage()
            ));
            return step;
        }
    }

    private Map<String, Object> resolveParams(Map<String, Object> params, ExecutionContext context) {
        Map<String, Object> resolved = new HashMap<>();
        for (var entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                resolved.put(entry.getKey(), context.resolveTemplate(s));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private StepResult errorResult(ExecutionContext context, String message) {
        StepResult step = new StepResult();
        step.setStepIndex(context.nextStepIndex());
        step.setStartedAt(Instant.now());
        step.setOutput(message);
        step.setStatus(StepStatus.FAILED);
        step.setCompletedAt(Instant.now());
        step.setDurationMs(0L);
        return step;
    }
}

package com.automation.service.cli;

import com.automation.entity.*;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.StepAiMetadata;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.cli.ShellCommandService.ShellResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommandExecutorService {

    private static final String SKILLS_CONTEXT_FILE = com.automation.service.ai.RunContextFiles.SKILLS_CONTEXT;

    private final CliSessionManager sessionManager;
    private final ShellCommandService shellCommandService;
    private final ContainerShellService containerShellService;
    private final AiProviderRegistry aiProviderRegistry;

    public CliSession.CommandResult executeCommand(String containerId, String cliType, String command) {
        try {
            CliSession session = sessionManager.getOrCreateSession(containerId, cliType);
            return session.sendCommand(command);
        } catch (IOException e) {
            throw new WorkflowExecutionException("Failed to execute command in container: " + containerId, e);
        }
    }

    public String buildSkillContext(List<Skill> skills) {
        return skills.stream()
                .filter(s -> s.getAiContext() != null && !s.getAiContext().isBlank())
                .map(s -> "=== Skill: " + s.getName() + " ===\n" + s.getAiContext())
                .collect(Collectors.joining("\n\n"));
    }

    // === Host-based execution (consolidated API) ===

    /**
     * Consolidated host command execution via {@link HostExecutionRequest} builder.
     * Replaces the multiple executeCommandOnHost overloads.
     */
    public CliSession.EnrichedCommandResult executeOnHost(HostExecutionRequest request) {
        String providerId = request.providerId();
        try {
            AiProvider provider = aiProviderRegistry.resolve(providerId);
            AiProvider.ActionResult result = provider.executeAction(
                    new AiProvider.ActionRequest(request.command(), request.model(),
                            request.systemPrompt(), request.projectPath(), request.timeoutSeconds(),
                            request.priority(), true, request.allowedTools(), request.disallowedTools(),
                            request.effort(), request.userMcpServers()));
            return new CliSession.EnrichedCommandResult(result.text(), result.exitCode(), null,
                    hostCallMetadata(request, provider, result));
        } catch (IOException e) {
            throw new WorkflowExecutionException(
                    "Failed to execute command on host (project: " + request.projectPath() + ")", e);
        }
    }

    /**
     * Consolidated host step execution via {@link HostExecutionRequest} builder.
     * Replaces the multiple executeStepOnHost overloads.
     */
    public StepResult executeStepOnHost(HostExecutionRequest request) {
        if (request.block() == null) {
            throw new WorkflowExecutionException("HostExecutionRequest.block is required for step execution");
        }
        if (request.shell()) {
            return executeStepAsShell(request.projectPath(), request.block(),
                    request.stepIndex() != null ? request.stepIndex() : 0,
                    request.timeoutSeconds() != null ? request.timeoutSeconds() : 300);
        }
        return executeStepOnHostInternal(request);
    }

    private StepResult executeStepOnHostInternal(HostExecutionRequest request) {
        StepResult step = new StepResult();
        int stepIndex = request.stepIndex() != null ? request.stepIndex() : 0;
        step.setStepIndex(stepIndex);
        step.setStartedAt(Instant.now());

        String command = extractCommand(request.block());
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);

        try {
            AiProvider provider = aiProviderRegistry.resolve(request.providerId());
            AiProvider.ActionResult providerResult = provider.executeAction(
                    new AiProvider.ActionRequest(command, request.model(), request.systemPrompt(),
                            request.projectPath(), request.timeoutSeconds(), request.priority(),
                            true, request.allowedTools(), request.disallowedTools(), request.effort(), request.userMcpServers()));
            step.setOutput(providerResult.text());
            step.setStatus(providerResult.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);

            step.setMetadata(hostCallMetadata(request, provider, providerResult));
        } catch (Exception e) {
            log.error("Host step execution failed for project {}: {}", request.projectPath(), e.getMessage(), e);
            step.setOutput("Error: " + e.getMessage());
            step.setStatus(StepStatus.FAILED);
            step.setMetadata(Map.of("error", e.getMessage()));
        }

        step.setCompletedAt(Instant.now());
        step.setDurationMs(
                step.getCompletedAt().toEpochMilli() - step.getStartedAt().toEpochMilli()
        );

        return step;
    }

    /**
     * Writes aggregated skill AI context to the project's .claude/rules/skills-context.md file.
     * This file is automatically picked up by subsequent Claude CLI calls.
     * Deletes the file when no skills are provided (cleanup).
     */
    public void writeSkillContextToProject(String projectPath, List<Skill> skills) {
        if (projectPath == null || projectPath.isBlank()) {
            log.debug("No project path — skipping skill context file write");
            return;
        }

        Path contextFile = Path.of(projectPath, SKILLS_CONTEXT_FILE);

        if (skills == null || skills.isEmpty()) {
            try {
                Files.deleteIfExists(contextFile);
            } catch (IOException e) {
                log.warn("Failed to delete skill context file {}: {}", contextFile, e.getMessage());
            }
            return;
        }

        String aggregatedContext = buildSkillContext(skills);
        if (aggregatedContext.isEmpty()) {
            try {
                Files.deleteIfExists(contextFile);
            } catch (IOException e) {
                log.warn("Failed to delete skill context file {}: {}", contextFile, e.getMessage());
            }
            return;
        }

        try {
            Files.createDirectories(contextFile.getParent());
            Files.writeString(contextFile, aggregatedContext);
            log.info("Wrote skill context ({} skills) to {}", skills.size(), contextFile);
        } catch (IOException e) {
            log.warn("Failed to write skill context file to {}: {}", contextFile, e.getMessage());
        }
    }

    // === Shell-based execution (no Claude CLI) ===

    public StepResult executeStepAsShell(String projectPath, BlockDefinition block,
                                          int stepIndex, int timeoutSeconds) {
        StepResult step = new StepResult();
        step.setStepIndex(stepIndex);
        step.setStartedAt(Instant.now());

        String command = extractCommand(block);
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);

        Map<String, Object> config = block.getConfig();
        String containerImage = config.get("container_image") instanceof String s ? s : null;

        ShellResult result;
        String executionMode;
        if (containerImage != null && !containerImage.isBlank()) {
            boolean mountDockerSocket = Boolean.TRUE.equals(config.get("mount_docker_socket"));
            List<String> extraMounts = extractResolvedMounts(config.get("mounts"));
            result = containerShellService.execute(command, projectPath, timeoutSeconds,
                    containerImage, mountDockerSocket, extraMounts);
            executionMode = "container:" + containerImage;
        } else {
            result = shellCommandService.execute(command, projectPath, timeoutSeconds);
            executionMode = "shell";
        }

        step.setOutput(result.output());
        step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);
        step.setMetadata(Map.of("exitCode", result.exitCode(), "executionMode", executionMode));

        step.setCompletedAt(Instant.now());
        step.setDurationMs(result.durationMs());

        return step;
    }

    /**
     * Expects callers to have already resolved templates in mount strings via
     * context.resolveTemplate(). This method just does type-coercion and blank-filtering.
     */
    private static List<String> extractResolvedMounts(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<String> out = new java.util.ArrayList<>(list.size());
        for (Object e : list) {
            if (e == null) continue;
            String s = e.toString();
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    /**
     * Builds a fresh shell BlockDefinition from an originating block's config, preserving
     * container_image / mount_docker_socket and template-resolving the `mounts` list against
     * the given context. Used by wrapper blocks (retry, foreach, while, switch) so that the
     * inner shell execution keeps container settings instead of silently falling back to host.
     */
    public static BlockDefinition buildResolvedShellBlock(
            Map<String, Object> originalConfig, String resolvedCommand,
            com.automation.executor.ExecutionContext context) {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("command", resolvedCommand);
        Object ci = originalConfig.get("container_image");
        if (ci instanceof String s && !s.isBlank()) {
            cfg.put("container_image", s);
        }
        Object mds = originalConfig.get("mount_docker_socket");
        if (mds != null) {
            cfg.put("mount_docker_socket", mds);
        }
        Object rawMounts = originalConfig.get("mounts");
        if (rawMounts instanceof List<?> list && !list.isEmpty()) {
            List<String> resolved = new java.util.ArrayList<>(list.size());
            for (Object e : list) {
                if (e == null) continue;
                String m = e.toString();
                if (!m.isBlank()) resolved.add(context.resolveTemplate(m));
            }
            if (!resolved.isEmpty()) cfg.put("mounts", resolved);
        }
        return BlockDefinition.builder().type("shell").config(cfg).build();
    }

    public CliSession.CommandResult executeShellCommandOnHost(String projectPath, String command, int timeoutSeconds) {
        ShellResult result = shellCommandService.execute(command, projectPath, timeoutSeconds);
        return new CliSession.CommandResult(result.output(), result.exitCode());
    }

    public String prependInstructions(String command, String prefix) {
        return "INSTRUCTIONS:\n" + prefix + "\n---\nTASK:\n" + command;
    }

    /** Step metadata of one host AI call: response metadata + requestedModel/provider/aiSystemPrompt/aiRequest. */
    private Map<String, Object> hostCallMetadata(HostExecutionRequest request, AiProvider provider,
                                                 AiProvider.ActionResult result) {
        Map<String, Object> meta = new LinkedHashMap<>(
                result.metadata() != null ? result.metadata().toMap() : Map.of());
        if (request.model() != null && !request.model().isBlank()) {
            meta.put("requestedModel", request.model());
        }
        meta.put("provider", provider.getId());
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            meta.put("aiSystemPrompt", request.systemPrompt());
        }
        meta.put(StepAiMetadata.AI_REQUEST, HostRequestAiMetadata.aiRequest(request, provider));
        return meta;
    }

    private String extractCommand(BlockDefinition block) {
        Object command = block.getConfig().get("command");
        if (command == null) {
            throw new WorkflowExecutionException("Block has no 'command' in config");
        }
        return command.toString();
    }

}

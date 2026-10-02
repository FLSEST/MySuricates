package com.automation.executor.blocks;

import com.automation.config.CompressionConfig;
import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.executor.compress.CompressAiPrompt;
import com.automation.executor.compress.CompressionException;
import com.automation.executor.compress.CompressionNumbers;
import com.automation.executor.compress.CompressionOutput;
import com.automation.executor.compress.CompressionSources;
import com.automation.executor.compress.StageConfig;
import com.automation.executor.compress.TextTruncateStage;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * compress-ai — condenses one earlier block output with a single AI call that has no tools, runs in a
 * private per-call empty directory (no project CLAUDE.md, deleted again after the call), gets only the
 * source text and a literal instruction, and is bounded on input (setting) and output (max_chars,
 * deterministic cut).
 */
@Component
@RequiredArgsConstructor
public class CompressAiBlockExecutor implements BlockExecutor {

    static final int DEFAULT_MAX_CHARS = 4_000;
    private static final int HEADER_RESERVE = 300;
    private static final int MAX_AI_ERROR_CHARS = 2_000;

    private final CommandExecutorService commandExecutor;
    private final AiProviderRegistry providers;
    private final CompressionConfig compressionConfig;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig() != null ? block.getConfig() : Map.of();
        int stepIndex = context.nextStepIndex();
        Instant started = Instant.now();
        StepResult result;
        Map<String, Object> aiMetadata = Map.of();
        try {
            int maxChars = StageConfig.intValue(config, "max_chars", DEFAULT_MAX_CHARS, 500, 200_000, "compress-ai");
            String instruction = StageConfig.string(config, "instruction", "", "compress-ai");
            Integer timeout = config.get("timeout_seconds") instanceof Number n ? n.intValue() : null;
            CompressionSources.Resolved src = CompressionSources.resolve(context, config.get("source"));
            int limit = compressionConfig.getMaxAiInputChars();
            if (src.text().length() > limit) {
                throw new CompressionException("Input " + CompressionNumbers.fmt(src.text().length()) + " chars > limit "
                        + CompressionNumbers.fmt(limit) + " — put a compress block before this one");
            }
            String providerId = context.providerFor(block);
            AiProvider provider = providers.resolve(providerId);
            if (!"CLAUDE".equals(provider.getId())) {
                throw new CompressionException("Provider " + provider.getId()
                        + " cannot be called without tools — set \"provider\": \"CLAUDE\" in the block");
            }
            String prompt = CompressAiPrompt.build(instruction, Math.max(CompressionOutput.MIN_BODY_CHARS, maxChars - HEADER_RESERVE), src.text());
            Path workdir = createNeutralWorkdir();
            StepResult ai;
            try {
                ai = commandExecutor.executeStepOnHost(HostExecutionRequest.builder()
                        .projectPath(workdir.toString())
                        .block(BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", prompt))).build())
                        .stepIndex(stepIndex)
                        .systemPrompt(CompressAiPrompt.SYSTEM)
                        .model(context.modelFor(block))
                        .timeoutSeconds(timeout)
                        .providerId(providerId)
                        .effort(context.effortFor(block))
                        .allowedTools(AiProvider.NO_TOOLS)
                        .build());
            } finally {
                cleanupWorkdir(workdir);
            }
            if (ai.getMetadata() != null) aiMetadata = ai.getMetadata();
            Map<String, Object> metadata = new HashMap<>(aiMetadata);
            String input = "compress-ai " + src.label() + " (" + CompressionNumbers.fmt(src.text().length()) + " chars): "
                    + (instruction.isBlank() ? "(default summary)" : instruction);
            String answer = ai.getOutput() == null ? "" : ai.getOutput().strip();
            if (ai.getStatus() != StepStatus.COMPLETED || answer.isEmpty()) {
                metadata.put("derivedOutput", true);
                result = finished(stepIndex, started, input, aiFailureOutput(ai), StepStatus.FAILED, metadata);
            } else {
                CompressionOutput.Assembled out = CompressionOutput.assemble(src,
                        "Summary: AI (" + provider.getId() + ")", answer, maxChars);
                metadata.putAll(CompressionOutput.metadata(src, out, maxChars, null));
                result = finished(stepIndex, started, input, out.text(), StepStatus.COMPLETED, metadata);
            }
        } catch (CompressionException e) {
            result = failed(stepIndex, started, config, e.getMessage(), aiMetadata);
        } catch (RuntimeException e) {
            result = failed(stepIndex, started, config, e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage(), aiMetadata);
        }
        return result;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("compress-ai");
    }

    /** Failed call: its (tail-capped) error text; a completed call without text is a failure too. */
    private static String aiFailureOutput(StepResult ai) {
        String prefix = "AI compression failed: ";
        if (ai.getStatus() == StepStatus.COMPLETED) return prefix + "AI returned no answer";
        String output = ai.getOutput() == null || ai.getOutput().isBlank() ? "no output" : ai.getOutput();
        return prefix + TextTruncateStage.truncate(output, MAX_AI_ERROR_CHARS, TextTruncateStage.Keep.TAIL);
    }

    /** {@code error} may be null (an exception without a message) — the step must still say something readable. */
    private static StepResult failed(int stepIndex, Instant started, Map<String, Object> config, String error,
                                     Map<String, Object> aiMetadata) {
        String message = error == null || error.isBlank() ? "unknown error" : error;
        Map<String, Object> metadata = new HashMap<>(aiMetadata);
        metadata.put("derivedOutput", true);
        metadata.put("error", message);
        return finished(stepIndex, started, "compress-ai: " + config.get("source"),
                "Compression failed: " + message, StepStatus.FAILED, metadata);
    }

    private static StepResult finished(int stepIndex, Instant started, String input, String output, StepStatus status,
                                       Map<String, Object> metadata) {
        Instant completed = Instant.now();
        return StepResult.builder().stepIndex(stepIndex).startedAt(started).completedAt(completed)
                .durationMs(Duration.between(started, completed).toMillis())
                .input(input).output(output).status(status).metadata(metadata).build();
    }

    /**
     * Fresh, private directory outside any project for exactly one call, so the CLI does not load a
     * project (or a planted) CLAUDE.md / .claude/settings*.json into the prompt. Never reused across
     * calls — a shared fixed directory could be pre-seeded by an earlier run or another process.
     */
    private static Path createNeutralWorkdir() {
        try {
            return Files.createTempDirectory("mysuricates-compress-ai-");
        } catch (IOException e) {
            throw new CompressionException("Cannot create a working directory for compress-ai: " + e.getMessage());
        }
    }

    /** Best-effort recursive delete of the per-call directory — cleanup failure must never mask the step result. */
    private static void cleanupWorkdir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort — a leftover temp dir must not fail the step
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // best effort — a leftover temp dir must not fail the step
        }
    }
}

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
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * compress-ai — condenses one earlier block output with a single AI call that has no tools, runs in a
 * neutral empty directory (no project CLAUDE.md), gets only the source text and a literal instruction,
 * and is bounded on input (setting) and output (max_chars, deterministic cut).
 */
@Component
@RequiredArgsConstructor
public class CompressAiBlockExecutor implements BlockExecutor {

    static final int DEFAULT_MAX_CHARS = 4_000;
    private static final int HEADER_RESERVE = 300;

    private final CommandExecutorService commandExecutor;
    private final AiProviderRegistry providers;
    private final CompressionConfig compressionConfig;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig() != null ? block.getConfig() : Map.of();
        int stepIndex = context.nextStepIndex();
        Instant started = Instant.now();
        StepResult result;
        try {
            int maxChars = StageConfig.intValue(config, "max_chars", DEFAULT_MAX_CHARS, 500, 200_000, "compress-ai");
            String instruction = StageConfig.string(config, "instruction", "", "compress-ai");
            Integer timeout = config.get("timeout_seconds") instanceof Number n ? n.intValue() : null;
            CompressionSources.Resolved src = CompressionSources.resolve(context, config.get("source"));
            int limit = compressionConfig.getMaxAiInputChars();
            if (src.text().length() > limit) {
                throw new CompressionException("Eingabe " + CompressionNumbers.fmt(src.text().length()) + " Zeichen > Limit "
                        + CompressionNumbers.fmt(limit) + " — davor einen compress-Block einsetzen");
            }
            String providerId = context.providerFor(block);
            AiProvider provider = providers.resolve(providerId);
            if (!"CLAUDE".equals(provider.getId())) {
                throw new CompressionException("Provider " + provider.getId()
                        + " kann nicht ohne Werkzeuge aufgerufen werden — im Block \"provider\": \"CLAUDE\" setzen");
            }
            String prompt = CompressAiPrompt.build(instruction, Math.max(CompressionOutput.MIN_BODY_CHARS, maxChars - HEADER_RESERVE), src.text());
            StepResult ai = commandExecutor.executeStepOnHost(HostExecutionRequest.builder()
                    .projectPath(neutralWorkdir())
                    .block(BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", prompt))).build())
                    .stepIndex(stepIndex)
                    .systemPrompt(CompressAiPrompt.SYSTEM)
                    .model(context.modelFor(block))
                    .timeoutSeconds(timeout)
                    .providerId(providerId)
                    .effort(context.effortFor(block))
                    .allowedTools(AiProvider.NO_TOOLS)
                    .build());
            Map<String, Object> metadata = new HashMap<>(ai.getMetadata() != null ? ai.getMetadata() : Map.of());
            String input = "compress-ai " + src.label() + " (" + CompressionNumbers.fmt(src.text().length()) + " Zeichen): "
                    + (instruction.isBlank() ? "(Standard-Zusammenfassung)" : instruction);
            if (ai.getStatus() != StepStatus.COMPLETED) {
                metadata.put("derivedOutput", true);
                result = finished(stepIndex, started, input, "KI-Komprimierung fehlgeschlagen: " + ai.getOutput(), StepStatus.FAILED, metadata);
            } else {
                CompressionOutput.Assembled out = CompressionOutput.assemble(src,
                        "Zusammenfassung: KI (" + provider.getId() + ")", ai.getOutput() == null ? "" : ai.getOutput().strip(), maxChars);
                metadata.putAll(CompressionOutput.metadata(src, out, maxChars, null));
                result = finished(stepIndex, started, input, out.text(), StepStatus.COMPLETED, metadata);
            }
        } catch (CompressionException e) {
            result = finished(stepIndex, started, "compress-ai: " + config.get("source"),
                    "Komprimierung fehlgeschlagen: " + e.getMessage(), StepStatus.FAILED,
                    new HashMap<>(Map.of("derivedOutput", true, "error", e.getMessage())));
        }
        return result;
    }

    @Override
    public boolean supports(String blockType) {
        return "compress-ai".equals(blockType);
    }

    private static StepResult finished(int stepIndex, Instant started, String input, String output, StepStatus status,
                                       Map<String, Object> metadata) {
        Instant completed = Instant.now();
        return StepResult.builder().stepIndex(stepIndex).startedAt(started).completedAt(completed)
                .durationMs(Duration.between(started, completed).toMillis())
                .input(input).output(output).status(status).metadata(metadata).build();
    }

    /** Empty directory outside any project, so the CLI does not load a project CLAUDE.md into the prompt. */
    private static String neutralWorkdir() {
        try {
            return Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "mysuricates-compress-ai")).toString();
        } catch (IOException e) {
            throw new CompressionException("Arbeitsverzeichnis für compress-ai nicht anlegbar: " + e.getMessage());
        }
    }
}

package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.executor.compress.CompressionException;
import com.automation.executor.compress.CompressionNumbers;
import com.automation.executor.compress.CompressionOutput;
import com.automation.executor.compress.CompressionSources;
import com.automation.executor.compress.RegexGuard;
import com.automation.executor.compress.StageChain;
import com.automation.executor.compress.StageConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * compress — deterministic compression of one earlier block output through a stage chain. No AI, no DB,
 * no process. The original output stays untouched; this step's output is the compressed text (spec
 * docs/plans/2026-09-15-komprimierungs-bloecke-design.md).
 */
@Component
public class CompressBlockExecutor implements BlockExecutor {

    static final int DEFAULT_MAX_CHARS = 20_000;
    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig() != null ? block.getConfig() : Map.of();
        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(Instant.now());
        try {
            List<StageChain.Step> steps = StageChain.parse(config.get("stages"));
            int maxChars = StageConfig.intValue(config, "max_chars", DEFAULT_MAX_CHARS, 300, 1_000_000, "compress");
            int timeout = StageConfig.intValue(config, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, 1, 600, "compress");
            CompressionSources.Resolved src = CompressionSources.resolve(context, config.get("source"));
            StageChain.Outcome outcome = StageChain.run(steps, src.text(), RegexGuard.startingNow(Duration.ofSeconds(timeout)));
            CompressionOutput.Assembled out = CompressionOutput.assemble(src,
                    "Stages: " + String.join(" · ", outcome.notes()), outcome.text(), maxChars);
            result.setInput("compress " + src.label() + " (" + CompressionNumbers.fmt(src.text().length()) + " chars): "
                    + steps.stream().map(s -> s.stage().op()).collect(Collectors.joining(" › ")));
            result.setOutput(out.text());
            result.setStatus(StepStatus.COMPLETED);
            result.setMetadata(CompressionOutput.metadata(src, out, maxChars, outcome.stageMetadata()));
        } catch (CompressionException e) {
            fail(result, config, e.getMessage());
        } catch (RuntimeException e) {
            fail(result, config, e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.setCompletedAt(Instant.now());
        result.setDurationMs(Duration.between(result.getStartedAt(), result.getCompletedAt()).toMillis());
        return result;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("compress");
    }

    /** {@code error} may be null (an exception without a message) — the step must still say something readable. */
    private static void fail(StepResult result, Map<String, Object> config, String error) {
        String message = error == null || error.isBlank() ? "unknown error" : error;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("derivedOutput", true);
        metadata.put("error", message);
        result.setInput("compress: " + config.get("source"));
        result.setOutput("Compression failed: " + message);
        result.setStatus(StepStatus.FAILED);
        result.setMetadata(metadata);
    }
}

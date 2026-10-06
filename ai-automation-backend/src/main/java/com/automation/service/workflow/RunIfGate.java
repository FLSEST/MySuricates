package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CommandExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * {@code run_if} (TODO cc0eb3ba): a shell test before a top-level block. Exit 0 → the block runs;
 * otherwise it is skipped with {@link StepStatus#SKIPPED} and a fixed text - its executor is not called,
 * so no AI call, no approval, no writing either. Placeholders as in every shell field: foreign values
 * always quoted, with {@code shell_escape_variables} all of them. Runs in the run's working directory (in the clone
 * with isolation), at most {@value #TIMEOUT_SECONDS} s. Nested blocks do not know the key.
 */
@Component
@RequiredArgsConstructor
public class RunIfGate {

    static final String KEY = "run_if";
    static final int TIMEOUT_SECONDS = 60;

    private final CommandExecutorService commandExecutor;

    /** The result of a skipped block - empty if it should run. */
    public Optional<StepResult> skipIfUnmet(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        Object raw = config == null ? null : config.get(KEY);
        if (!(raw instanceof String condition) || condition.isBlank()) {
            return Optional.empty();
        }
        String resolved = context.resolveShellTemplate(condition, Boolean.TRUE.equals(config.get("shell_escape_variables")));
        Instant start = Instant.now();
        int exit = commandExecutor.executeShellCommandOnHost(context.getProjectPath(), resolved, TIMEOUT_SECONDS).exitCode();
        if (exit == 0) {
            return Optional.empty();
        }
        StepResult skipped = new StepResult();
        skipped.setStepIndex(context.nextStepIndex());
        skipped.setStartedAt(start);
        skipped.setInput("run_if: " + resolved);
        skipped.setOutput("Übersprungen (run_if: exit " + exit + ")");
        skipped.setStatus(StepStatus.SKIPPED);
        skipped.setCompletedAt(Instant.now());
        skipped.setDurationMs(skipped.getCompletedAt().toEpochMilli() - start.toEpochMilli());
        return Optional.of(skipped);
    }
}

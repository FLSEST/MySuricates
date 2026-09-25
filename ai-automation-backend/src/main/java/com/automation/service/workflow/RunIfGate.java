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
 * {@code run_if} (TODO cc0eb3ba): ein Shell-Test vor einem Block der obersten Ebene. Exit 0 → der Block läuft;
 * sonst wird er mit {@link StepStatus#SKIPPED} und festem Text übersprungen – sein Executor wird nicht gerufen,
 * also auch kein KI-Aufruf, keine Freigabe, kein Schreiben. Platzhalter wie in jedem Shell-Feld: fremde Werte
 * immer gequotet, mit {@code shell_escape_variables} alle. Läuft im Arbeitsverzeichnis des Laufs (bei Isolation
 * im Klon), höchstens {@value #TIMEOUT_SECONDS} s. Verschachtelte Blöcke kennen den Schlüssel nicht.
 */
@Component
@RequiredArgsConstructor
public class RunIfGate {

    static final String KEY = "run_if";
    static final int TIMEOUT_SECONDS = 60;

    private final CommandExecutorService commandExecutor;

    /** Das Ergebnis eines übersprungenen Blocks – leer, wenn er laufen soll. */
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

package com.automation.executor.blocks;

import com.automation.entity.StepResult;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.ShellCommandService.ShellResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/** Evidence paths come from preparation metadata, never from workflow input variables. */
final class WorkspaceCheckEvidence {
    private WorkspaceCheckEvidence() {}

    static void record(ExecutionContext context, StepResult step, ShellResult result) {
        if (!(context.getRunMetadata().get("workspace") instanceof Map<?, ?> workspace)) return;
        try {
            Path logs = Path.of(String.valueOf(workspace.get("logsPath")));
            Path output = logs.resolve("step-" + step.getStepIndex() + ".log");
            Files.writeString(output, result.output() + "\n", StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            Map<String, Object> metadata = new HashMap<>(step.getMetadata());
            metadata.put("logPath", output.toString());
            metadata.put("sourceCommit", workspace.get("sourceCommit"));
            metadata.put("workingDirectory", context.getProjectPath());
            step.setMetadata(metadata);
        } catch (java.io.IOException e) {
            throw new WorkflowExecutionException("Cannot retain workspace check evidence", e);
        }
    }
}

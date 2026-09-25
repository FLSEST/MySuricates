package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code run_if} (TODO cc0eb3ba). */
@ExtendWith(MockitoExtension.class)
class RunIfGateTest {

    @Mock private CommandExecutorService commandExecutor;

    private ExecutionContext context() {
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").variables(new HashMap<>()).build();
        ctx.setProjectPath("/work");
        return ctx;
    }

    private static BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("simple").config(config).build();
    }

    @Test
    void withoutRunIf_nothingIsExecuted() {
        assertThat(new RunIfGate(commandExecutor).skipIfUnmet(block(new HashMap<>()), context())).isEmpty();
        verify(commandExecutor, never()).executeShellCommandOnHost(anyString(), anyString(), anyInt());
    }

    @Test
    void exitZero_runsTheBlock() {
        when(commandExecutor.executeShellCommandOnHost(eq("/work"), eq("test -s ids.txt"), eq(60)))
                .thenReturn(new CliSession.CommandResult("", 0));

        assertThat(new RunIfGate(commandExecutor).skipIfUnmet(block(Map.of("run_if", "test -s ids.txt")), context())).isEmpty();
    }

    @Test
    void nonZero_skipsWithFixedText() {
        when(commandExecutor.executeShellCommandOnHost(eq("/work"), eq("false"), eq(60)))
                .thenReturn(new CliSession.CommandResult("", 1));

        Optional<StepResult> skipped = new RunIfGate(commandExecutor).skipIfUnmet(block(Map.of("run_if", "false")), context());

        assertThat(skipped).isPresent();
        assertThat(skipped.get().getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(skipped.get().getOutput()).isEqualTo("Übersprungen (run_if: exit 1)");
    }

    @Test
    void untrustedValue_isQuoted() {
        ExecutionContext ctx = context();
        ctx.setUntrustedVariable("x", "a; touch /tmp/pwned");
        when(commandExecutor.executeShellCommandOnHost(eq("/work"), eq("test 'a; touch /tmp/pwned' = y"), eq(60)))
                .thenReturn(new CliSession.CommandResult("", 1));

        assertThat(new RunIfGate(commandExecutor).skipIfUnmet(block(Map.of("run_if", "test {{ x }} = y")), ctx)).isPresent();
    }
}

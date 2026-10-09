package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The one inner step call of the wrapper blocks (foreach, while, conditional, retry, switch). */
@ExtendWith(MockitoExtension.class)
class InnerStepTest {

    @Mock
    private CommandExecutorService commandExecutor;

    private ExecutionContext context;
    private final StepResult done = StepResult.builder().status(StepStatus.COMPLETED).output("ok").build();

    @BeforeEach
    void setUp() {
        context = ExecutionContext.builder().workflowRunId("run-1").projectPath("/tmp/project").build();
        context.setInstructionPrefix("house rules");
        context.setProvider("CLAUDE");
        context.setModel("sonnet");
        context.setEffort("low");
        context.setVariable("target", "auth");
    }

    private static BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("foreach").config(new HashMap<>(config)).build();
    }

    @Test
    void aiStep_carriesTheResolvedPromptInstructionsBlockSettingsAndTimeout() {
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(done);
        BlockDefinition wrapper = block(Map.of("provider", "PI", "model", "anthropic/opus", "effort", "high"));

        StepResult result = new InnerStep(wrapper, false, false, 300, 42)
                .run(commandExecutor, context, "review {{ target }}");

        assertThat(result).isSameAs(done);
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(captor.capture());
        HostExecutionRequest request = captor.getValue();
        assertThat(request.projectPath()).isEqualTo("/tmp/project");
        assertThat(request.block().getType()).isEqualTo("simple");
        assertThat(request.block().getName()).isNull();
        assertThat(request.block().getConfig()).isEqualTo(Map.of("command", "review auth"));
        assertThat(request.stepIndex()).isZero();
        assertThat(request.systemPrompt()).isEqualTo("house rules");
        assertThat(request.providerId()).isEqualTo("PI");
        assertThat(request.model()).isEqualTo("anthropic/opus");
        assertThat(request.effort()).isEqualTo("high");
        assertThat(request.timeoutSeconds()).isEqualTo(42);
    }

    @Test
    void aiStep_withoutBlockSettings_fallsBackToTheWorkflowAndTheProviderTimeout() {
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(done);

        new InnerStep(block(Map.of()), false, false, 300, null).run(commandExecutor, context, "go");

        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(captor.capture());
        assertThat(captor.getValue().providerId()).isEqualTo("CLAUDE");
        assertThat(captor.getValue().model()).isEqualTo("sonnet");
        assertThat(captor.getValue().effort()).isEqualTo("low");
        assertThat(captor.getValue().timeoutSeconds()).isNull();
    }

    @Test
    void shellStep_quotesUntrustedValues_andCarriesTheContainerSettingsAndShellTimeout() {
        context.setUntrustedVariable("target", "x; id");
        when(commandExecutor.executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(77))).thenReturn(done);
        BlockDefinition wrapper = block(Map.of(
                "container_image", "alpine:3", "mount_docker_socket", true, "mounts", List.of("/data:/data")));

        new InnerStep(wrapper, true, false, 77, null).run(commandExecutor, context, "build {{ target }}");

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commandExecutor).executeStepAsShell(eq("/tmp/project"), captor.capture(), eq(0), eq(77));
        BlockDefinition shellBlock = captor.getValue();
        assertThat(shellBlock.getType()).isEqualTo("shell");
        assertThat(shellBlock.getName()).isNull();
        assertThat(shellBlock.getConfig())
                .containsEntry("command", "build 'x; id'")
                .containsEntry("container_image", "alpine:3")
                .containsEntry("mount_docker_socket", true)
                .containsEntry("mounts", List.of("/data:/data"));
    }

    @Test
    void shellStep_escapeVariables_quotesTrustedValuesToo() {
        when(commandExecutor.executeStepAsShell(any(), any(), anyInt(), anyInt())).thenReturn(done);

        new InnerStep(block(Map.of()), true, true, 60, null).run(commandExecutor, context, "build {{ target }}");

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commandExecutor).executeStepAsShell(any(), captor.capture(), anyInt(), anyInt());
        assertThat(captor.getValue().getConfig()).containsEntry("command", "build 'auth'");
    }

    @Test
    void namedStep_passesItsNameToTheShellAndTheAiStep() {
        when(commandExecutor.executeStepAsShell(any(), any(), anyInt(), anyInt())).thenReturn(done);
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(done);
        BlockDefinition caseBlock = BlockDefinition.builder().type("simple").name("deploy").config(Map.of()).build();

        new InnerStep(caseBlock, true, false, 60, null, "deploy").run(commandExecutor, context, "echo");
        new InnerStep(caseBlock, false, false, 60, null, "deploy").run(commandExecutor, context, "go");

        ArgumentCaptor<BlockDefinition> shell = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commandExecutor).executeStepAsShell(any(), shell.capture(), anyInt(), anyInt());
        assertThat(shell.getValue().getName()).isEqualTo("deploy");
        ArgumentCaptor<HostExecutionRequest> ai = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(ai.capture());
        assertThat(ai.getValue().block().getName()).isEqualTo("deploy");
    }

    @Test
    void runResolved_doesNotFillInPlaceholdersAgain() {
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(done);
        InnerStep step = new InnerStep(block(Map.of()), false, false, 60, null);

        String resolved = step.resolve(context, "review {{ target }}");
        step.runResolved(commandExecutor, context, "literal {{ target }}");

        assertThat(resolved).isEqualTo("review auth");
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(captor.capture());
        assertThat(captor.getValue().block().getConfig()).containsEntry("command", "literal {{ target }}");
    }
}

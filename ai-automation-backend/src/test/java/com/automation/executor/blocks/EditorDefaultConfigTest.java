package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.workflow.ExecutionBroadcaster;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The block editor seeds a new block with the config in
 * {@code ai-automation-frontend/src/data/blockDefaultConfig.json}. This test runs that default,
 * filled in the way a person fills it, through the real executor: a default whose keys the executor
 * does not act on runs silently wrong — the foreach default set {@code items_command} without
 * {@code items_source: "command"}, and a new foreach block ran zero items without an error
 * (TODO 2f01ab01).
 */
@ExtendWith(MockitoExtension.class)
class EditorDefaultConfigTest {

    private static final Path DEFAULTS = Path.of("..", "ai-automation-frontend", "src", "data", "blockDefaultConfig.json");
    private static final String PROJECT = "/test/project";

    @Mock private CommandExecutorService commandExecutor;
    @Mock private ExecutionBroadcaster broadcaster;
    @InjectMocks private ForEachBlockExecutor forEach;

    @Test
    void foreachDefault_filledIn_iteratesTheLinesOfItsItemsCommand() throws IOException {
        Map<String, Object> config = editorDefault("foreach");
        assertThat(config).as("the fields a person fills in").containsKeys("items_command", "command");
        config.put("items_command", "ls docs");
        config.put("command", "Summarise {{ item }}");
        lenient().when(commandExecutor.executeShellCommandOnHost(PROJECT, "ls docs", 300))
                .thenReturn(new CliSession.CommandResult("a.md\nb.md\n", 0));
        lenient().when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("ok").build());

        StepResult result = forEach.execute(BlockDefinition.builder().type("foreach").config(config).build(), context());

        assertThat(result.getInput()).as("items taken from items_command").isEqualTo("foreach item in [2 items]");
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor, times(2)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    private static Map<String, Object> editorDefault(String type) throws IOException {
        assertThat(DEFAULTS).as("editor defaults (%s)", DEFAULTS.toAbsolutePath()).exists();
        Map<String, Map<String, Object>> all = new ObjectMapper().readValue(Files.readString(DEFAULTS),
                new TypeReference<Map<String, Map<String, Object>>>() {
                });
        assertThat(all).as("editor default of '%s'", type).containsKey(type);
        return new LinkedHashMap<>(all.get(type));
    }

    private static ExecutionContext context() {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        context.setProjectPath(PROJECT);
        return context;
    }
}

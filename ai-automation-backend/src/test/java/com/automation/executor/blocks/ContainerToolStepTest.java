package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.ContainerShellService;
import com.automation.service.cli.ContainerToolRequest;
import com.automation.service.cli.ShellCommandService.ShellResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerToolStepTest {

    private static final String ONE_HIGH = "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"Trivy\"}},"
            + "\"results\":[{\"ruleId\":\"CVE-1\",\"level\":\"error\",\"message\":{\"text\":\"Package: vite\"},"
            + "\"locations\":[{\"physicalLocation\":{\"artifactLocation\":{\"uri\":\"package-lock.json\"}}}]}]}]}";

    @Mock private ContainerShellService containerShellService;
    @TempDir Path cacheRoot;

    private ContainerToolStep step;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        step = new ContainerToolStep(containerShellService, cacheRoot);
        context = ExecutionContext.builder().containerId("c-1").workflowRunId("run-1").build();
        context.setProjectPath("/test/project");
    }

    private StepResult run(Map<String, Object> config) {
        BlockDefinition block = BlockDefinition.builder().type("shell").name("trivy").config(new HashMap<>(config)).build();
        return step.execute(block, ContainerToolSpec.from(block.getConfig(), block.getName()), context);
    }

    private static Map<String, Object> sarifConfig() {
        return Map.of("container_image", "img:1", "container_args", List.of("fs", "/workspace"), "output_format", "sarif");
    }

    /** Fake container: writes {@code sarif} (if not null) into the mounted output directory and exits with {@code exit}. */
    private AtomicReference<ContainerToolRequest> containerWrites(String sarif, int exit) {
        AtomicReference<ContainerToolRequest> seen = new AtomicReference<>();
        when(containerShellService.executeTool(any(), any())).thenAnswer(invocation -> {
            ContainerToolRequest request = invocation.getArgument(0);
            seen.set(request);
            if (sarif != null) Files.writeString(request.output().hostDir().resolve("trivy.sarif"), sarif);
            return new ShellResult("tool log", exit, 42);
        });
        return seen;
    }

    @Test
    void rawMode_passesArgsWithoutShell_noOutputDir() {
        when(containerShellService.executeTool(any(), any())).thenReturn(new ShellResult("hello", 0, 5));

        StepResult result = run(Map.of("container_image", "img:1", "container_args", List.of("fs", "/workspace")));

        ArgumentCaptor<ContainerToolRequest> captor = ArgumentCaptor.forClass(ContainerToolRequest.class);
        verify(containerShellService).executeTool(captor.capture(), any());
        ContainerToolRequest request = captor.getValue();
        assertThat(request.image()).isEqualTo("img:1");
        assertThat(request.args()).containsExactly("fs", "/workspace");
        assertThat(request.projectPath()).isEqualTo("/test/project");
        assertThat(request.output()).isNull();
        assertThat(request.cache()).isNull();
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("hello");
        assertThat(result.getInput()).isEqualTo("img:1 fs /workspace");
        assertThat(result.getMetadata()).containsEntry("executionMode", "container-tool:img:1")
                .containsEntry("outputFormat", "raw").containsEntry("exitCode", 0);
    }

    @Test
    void rawMode_nonZeroExit_isFailed() {
        when(containerShellService.executeTool(any(), any())).thenReturn(new ShellResult("boom", 3, 5));

        assertThat(run(Map.of("container_image", "img:1", "container_args", List.of())).getStatus())
                .isEqualTo(StepStatus.FAILED);
    }

    @Test
    void sarifMode_validSarifWithExitOne_isCompleted() {
        containerWrites(ONE_HIGH, 1);

        StepResult result = run(sarifConfig());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("FAKT trivy_high: 1", "FAKT trivy_exit_code: 1");
        assertThat(result.getMetadata()).containsEntry("measured", true).containsEntry("findings", 1)
                .containsEntry("factPrefix", "trivy").containsEntry("outputFormat", "sarif");
    }

    @Test
    void sarifMode_noSarif_isFailedNotMeasured() {
        containerWrites(null, 0);

        StepResult result = run(sarifConfig());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("NICHT GEMESSEN: trivy_findings (keine SARIF-Datei in /out");
        assertThat(result.getMetadata()).containsEntry("measured", false);
    }

    @Test
    void sarifMode_outputDirMountedAtOutputDir_andRemovedAfterwards() {
        AtomicReference<ContainerToolRequest> seen = containerWrites(ONE_HIGH, 0);

        run(sarifConfig());

        assertThat(seen.get().output().containerPath()).isEqualTo("/out");
        assertThat(Files.exists(seen.get().output().hostDir())).isFalse();
    }

    @Test
    void outputDirRemovedEvenWhenTheRunThrows() {
        AtomicReference<Path> dir = new AtomicReference<>();
        when(containerShellService.executeTool(any(), any())).thenAnswer(invocation -> {
            dir.set(((ContainerToolRequest) invocation.getArgument(0)).output().hostDir());
            throw new IllegalStateException("docker weg");
        });

        assertThatThrownBy(() -> run(sarifConfig())).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(dir.get())).isFalse();
    }

    @Test
    void cacheDir_isPersistentPerImage() {
        when(containerShellService.executeTool(any(), any())).thenReturn(new ShellResult("", 0, 1));

        run(Map.of("container_image", "registry:5000/aquasec/trivy:0.70.0", "container_args", List.of(), "cache_dir", "/cache"));

        ArgumentCaptor<ContainerToolRequest> captor = ArgumentCaptor.forClass(ContainerToolRequest.class);
        verify(containerShellService).executeTool(captor.capture(), any());
        assertThat(captor.getValue().cache().hostDir()).isEqualTo(cacheRoot.resolve("registry_5000_aquasec_trivy"));
        assertThat(captor.getValue().cache().containerPath()).isEqualTo("/cache");
        assertThat(cacheRoot.resolve("registry_5000_aquasec_trivy")).isDirectory();
    }

    @Test
    void cacheSlug_cannotLeaveTheCacheRoot() {
        assertThat(ContainerToolStep.cacheSlug("aquasec/trivy:0.70.0")).isEqualTo("aquasec_trivy");
        assertThat(ContainerToolStep.cacheSlug("img@sha256:abc")).isEqualTo("img");
        assertThat(ContainerToolStep.cacheSlug("..")).isEqualTo("_..");
    }

    @Test
    void untrustedPlaceholder_isRefused_beforeAnyContainerStarts() {
        context.setUntrustedVariable("branch", "--config=/etc/x");

        assertThatThrownBy(() -> run(Map.of("container_image", "img:1", "container_args", List.of("fs", "{{ branch }}"))))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("untrusted");
        verify(containerShellService, never()).executeTool(any(), any());
    }

    @Test
    void platformData_isRefused() {
        context.setUntrustedPlatformVariable("open_todos", "x");

        assertThatThrownBy(() -> run(Map.of("container_image", "img:1", "container_args", List.of("{{ open_todos }}"))))
                .isInstanceOf(WorkflowExecutionException.class);
    }

    @Test
    void trustedPlaceholder_isResolved_andWorkspacePathMapped(@TempDir Path logs) {
        context.setVariable("target", "/test/project/sub");
        context.setRunMetadata("workspace", Map.of("logsPath", logs.toString(), "sourceCommit", "abc"));
        when(containerShellService.executeTool(any(), any())).thenReturn(new ShellResult("", 0, 1));

        run(Map.of("container_image", "img:1", "container_args", List.of("fs", "{{ target }}")));

        ArgumentCaptor<ContainerToolRequest> captor = ArgumentCaptor.forClass(ContainerToolRequest.class);
        verify(containerShellService).executeTool(captor.capture(), any());
        assertThat(captor.getValue().args()).containsExactly("fs", "/workspace/sub");
    }

    @Test
    void missingImage_orArgsNoList_isRefused() {
        assertThatThrownBy(() -> run(Map.of("container_args", List.of("x"))))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("container_image");
        assertThatThrownBy(() -> run(Map.of("container_image", "img", "container_args", "x y")))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("list");
    }
}

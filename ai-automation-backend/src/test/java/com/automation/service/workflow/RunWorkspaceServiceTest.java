package com.automation.service.workflow;

import com.automation.entity.Project;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RunWorkspaceServiceTest {
    @TempDir Path directory;

    @Test
    void separatesExecutionPathFromProjectIdentityAndPersistsResumeSource() throws Exception {
        Path source = directory.resolve("source");
        Files.createDirectories(source);
        git(source, "init", "-q");
        git(source, "config", "user.email", "test@example.invalid");
        git(source, "config", "user.name", "Test");
        Files.writeString(source.resolve("code"), "committed");
        git(source, "add", ".");
        git(source, "commit", "-qm", "initial");
        Files.writeString(source.resolve("code"), "uncommitted");
        var project = Project.builder().id("project").name("Example").path(source.toString()).build();
        var run = WorkflowRun.builder().id("run").build();
        var repository = mock(WorkflowRunRepository.class);
        var service = new RunWorkspaceService(repository, new ObjectMapper(), directory.resolve("runs").toString());
        var context = ExecutionContext.builder().workflowRunId("run").build();
        context.setVariable("project_path", source.toString());
        Map<String, Object> policy = Map.of("workspace", Map.of("mode", "isolated",
                "source_path", directory + "/{{ repo }}", "additional_sources", Map.of("reference", source.toString())));
        service.pinSource(run, project, policy, Map.of("repo", "source"));
        Files.writeString(source.resolve("code"), "later commit");
        git(source, "commit", "-qam", "later");
        Files.writeString(source.resolve("code"), "uncommitted");
        var executionProject = service.prepare(run, project, policy, context, false);
        assertThat(project.getPath()).isEqualTo(source.toString());
        assertThat(executionProject.getId()).isEqualTo(project.getId());
        assertThat(context.getVariable("project_path")).isEqualTo(source.toString());
        assertThat(context.getVariable("execution_path")).isEqualTo(executionProject.getPath());
        Path execution = Path.of(context.getProjectPath());
        Path additional = Path.of(String.valueOf(context.getVariable("execution_path_reference")));
        assertThat(additional).isNotEqualTo(execution);
        assertThat(Files.readString(additional.resolve("code"))).isEqualTo("committed");
        assertThat(Files.readString(execution.resolve("code"))).isEqualTo("committed");
        assertThat(Files.readString(source.resolve("code"))).isEqualTo("uncommitted");
        verify(repository, org.mockito.Mockito.times(2)).save(run);
        Files.writeString(execution.resolve("code"), "run edit");
        // A changed workflow source must not redirect an interrupted run to a fresh checkout.
        var resumed = service.prepare(run, project,
                Map.of("workspace", Map.of("mode", "isolated", "source_path", "/missing")), context, true);
        assertThat(resumed.getPath()).isEqualTo(executionProject.getPath());
        assertThat(Files.readString(execution.resolve("code"))).isEqualTo("run edit");
    }

    @Test
    void dynamicSourceRejectsPathTraversalBeforeInvokingGit() {
        var service = new RunWorkspaceService(mock(WorkflowRunRepository.class), new ObjectMapper(),
                directory.resolve("runs").toString());
        var project = Project.builder().id("project").path(directory.toString()).build();
        var run = WorkflowRun.builder().id("run").build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.pinSource(run, project,
                Map.of("workspace", Map.of("mode", "isolated", "source_path", directory + "/{{ repo }}")),
                Map.of("repo", "../outside")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one repository name");
    }

    private void git(Path source, String... args) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("git", "-C", source.toString()));
        command.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }
}

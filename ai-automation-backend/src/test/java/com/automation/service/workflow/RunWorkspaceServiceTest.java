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

    @Test
    void policy_takesWorkspaceFromFirstBlock_whenMetadataHasNone() {
        var first = com.automation.entity.BlockDefinition.builder()
                .config(Map.of("workspace", Map.of("mode", "isolated"))).build();
        var second = com.automation.entity.BlockDefinition.builder()
                .config(Map.of("workspace", Map.of("mode", "project"))).build();

        Map<String, Object> effective = RunWorkspaceService.policy(Map.of("other", 1), java.util.List.of(first, second));

        assertThat(effective).containsEntry("other", 1).containsEntry("workspace", Map.of("mode", "isolated"));
    }

    @Test
    void policy_keepsMetadataWorkspace_andToleratesNoBlocksOrBlocksWithoutWorkspace() {
        var block = com.automation.entity.BlockDefinition.builder()
                .config(Map.of("workspace", Map.of("mode", "isolated"))).build();
        Map<String, Object> own = Map.of("workspace", Map.of("mode", "project"));

        assertThat(RunWorkspaceService.policy(own, java.util.List.of(block))).isEqualTo(own);
        assertThat(RunWorkspaceService.policy(Map.of(), java.util.List.of())).isEmpty();
        assertThat(RunWorkspaceService.policy(Map.of(), java.util.List.of(new com.automation.entity.BlockDefinition())))
                .isEmpty();
    }

    @Test
    void policyForRun_prefersThePolicyPinnedAtRunStart() {
        var pinned = WorkflowRun.builder().id("run")
                .metadata(new java.util.HashMap<>(Map.of("workspacePolicy", Map.of("mode", "isolated")))).build();
        var unpinned = WorkflowRun.builder().id("run").build();
        Map<String, Object> current = Map.of("workspace", Map.of("mode", "project"), "x", 1);

        assertThat(RunWorkspaceService.policyForRun(pinned, current))
                .containsEntry("workspace", Map.of("mode", "isolated")).containsEntry("x", 1);
        assertThat(RunWorkspaceService.policyForRun(unpinned, current)).isSameAs(current);
    }

    @Test
    void enabled_onlyForIsolatedMode_andRejectsUnknownModes() {
        assertThat(RunWorkspaceService.enabled(Map.of())).isFalse();
        assertThat(RunWorkspaceService.enabled(Map.of("workspace", "isolated"))).isFalse();
        assertThat(RunWorkspaceService.enabled(Map.of("workspace", Map.of()))).isFalse();
        assertThat(RunWorkspaceService.enabled(Map.of("workspace", Map.of("mode", "project")))).isFalse();
        assertThat(RunWorkspaceService.enabled(Map.of("workspace", Map.of("mode", "isolated")))).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        RunWorkspaceService.enabled(Map.of("workspace", Map.of("mode", "sandbox"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sandbox");
    }

    @Test
    void pinSource_pinsHeadCommitAndPolicy_onlyOncePerRun() throws Exception {
        Path source = directory.resolve("repo");
        Files.createDirectories(source);
        git(source, "init", "-q");
        git(source, "config", "user.email", "test@example.invalid");
        git(source, "config", "user.name", "Test");
        Files.writeString(source.resolve("f"), "1");
        git(source, "add", ".");
        git(source, "commit", "-qm", "one");
        var repository = mock(WorkflowRunRepository.class);
        var service = new RunWorkspaceService(repository, new ObjectMapper(), directory.resolve("runs").toString());
        var project = Project.builder().id("project").path(source.toString()).build();
        var run = WorkflowRun.builder().id("run").build();
        Map<String, Object> policy = Map.of("workspace", Map.of("mode", "isolated"));

        service.pinSource(run, project, policy);
        Map<?, ?> pinned = (Map<?, ?>) run.getMetadata().get("workspaceSource");
        git(source, "commit", "-q", "--allow-empty", "-m", "two");
        service.pinSource(run, project, policy);

        assertThat(pinned.get("path")).isEqualTo(source.toString());
        assertThat(String.valueOf(pinned.get("commit"))).matches("[a-f0-9]{40,64}");
        assertThat(run.getMetadata().get("workspaceSource")).isSameAs(pinned);
        assertThat(run.getMetadata()).containsEntry("workspacePolicy", Map.of("mode", "isolated"))
                .containsEntry("workspaceAdditionalSources", Map.of());
        verify(repository, org.mockito.Mockito.times(1)).save(run);
    }

    @Test
    void pinSource_disabledPolicy_changesNothing() {
        var repository = mock(WorkflowRunRepository.class);
        var service = new RunWorkspaceService(repository, new ObjectMapper(), directory.toString());
        var run = WorkflowRun.builder().id("run").build();

        service.pinSource(run, Project.builder().id("p").path("/nowhere").build(), Map.of());

        assertThat(run.getMetadata()).doesNotContainKey("workspaceSource");
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void pinSource_nonRepositorySource_failsAsExecutionError() {
        var service = new RunWorkspaceService(mock(WorkflowRunRepository.class), new ObjectMapper(), directory.toString());
        var run = WorkflowRun.builder().id("run").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.pinSource(run,
                        Project.builder().id("p").path(directory.resolve("plain").toString()).build(),
                        Map.of("workspace", Map.of("mode", "isolated"))))
                .isInstanceOf(com.automation.exception.WorkflowExecutionException.class)
                .hasMessageContaining("Cannot pin workspace source");
    }

    @Test
    void pinSource_invalidAdditionalAlias_isRejected() throws Exception {
        Path source = directory.resolve("repo2");
        Files.createDirectories(source);
        git(source, "init", "-q");
        git(source, "-c", "user.email=t@example.invalid", "-c", "user.name=T", "commit", "-q", "--allow-empty", "-m", "x");
        var repository = mock(WorkflowRunRepository.class);
        var service = new RunWorkspaceService(repository, new ObjectMapper(), directory.toString());
        var run = WorkflowRun.builder().id("run").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.pinSource(run,
                        Project.builder().id("p").path(source.toString()).build(),
                        Map.of("workspace", Map.of("mode", "isolated",
                                "additional_sources", Map.of("../evil", source.toString())))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid repository alias");
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void prepare_withoutIsolation_returnsTheOriginalProjectUntouched() {
        var repository = mock(WorkflowRunRepository.class);
        var service = new RunWorkspaceService(repository, new ObjectMapper(), directory.toString());
        var project = Project.builder().id("p").path("/original").build();
        var context = ExecutionContext.builder().workflowRunId("run").build();

        var result = service.prepare(WorkflowRun.builder().id("run").build(), project,
                Map.of("workspace", Map.of("mode", "project")), context, false);

        assertThat(result).isSameAs(project);
        assertThat(context.getProjectPath()).isNull();
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    /**
     * 88781724: without a setting the root stays where every installation had it — not in the data
     * directory, which is {@code ~/.autotrigger} on older installations; a setting wins.
     */
    @Test
    void root_unsetKeepsTheEstablishedPlace_aSettingWins() {
        Path established = Path.of(System.getProperty("user.home"), ".mysuricates", "workspaces");
        var repository = mock(WorkflowRunRepository.class);

        assertThat(new RunWorkspaceService(repository, new ObjectMapper(), "").root()).isEqualTo(established.toString());
        assertThat(new RunWorkspaceService(repository, new ObjectMapper(), null).root()).isEqualTo(established.toString());
        assertThat(new RunWorkspaceService(repository, new ObjectMapper(), " /srv/runs ").root()).isEqualTo("/srv/runs");
        assertThat(RunWorkspaceService.defaultRoot(directory)).isEqualTo(directory.resolve(".mysuricates/workspaces"));
    }

    /**
     * 88781724: a run interrupted before the configured root changed (AUTOMATION_RUN_WORKSPACE_ROOT)
     * resumes in the checkout it started in.
     */
    @Test
    void resumeStaysUnderTheRootTheRunStartedIn_evenWhenTheConfiguredRootChanged() throws Exception {
        Path source = directory.resolve("source");
        Files.createDirectories(source);
        git(source, "init", "-q");
        git(source, "config", "user.email", "test@example.invalid");
        git(source, "config", "user.name", "Test");
        Files.writeString(source.resolve("code"), "committed");
        git(source, "add", ".");
        git(source, "commit", "-qm", "initial");
        var project = Project.builder().id("project").name("Example").path(source.toString()).build();
        var run = WorkflowRun.builder().id("run").build();
        var repository = mock(WorkflowRunRepository.class);
        Map<String, Object> policy = Map.of("workspace", Map.of("mode", "isolated",
                "additional_sources", Map.of("reference", source.toString())));
        new RunWorkspaceService(repository, new ObjectMapper(), directory.resolve("old-root").toString())
                .pinSource(run, project, policy, Map.of());
        var started = new RunWorkspaceService(repository, new ObjectMapper(), directory.resolve("old-root").toString())
                .prepare(run, project, policy, ExecutionContext.builder().workflowRunId("run").build(), false);
        Files.writeString(Path.of(started.getPath()).resolve("code"), "run edit");

        var context = ExecutionContext.builder().workflowRunId("run").build();
        var resumed = new RunWorkspaceService(repository, new ObjectMapper(), directory.resolve("new-root").toString())
                .prepare(run, project, policy, context, true);

        assertThat(resumed.getPath()).isEqualTo(started.getPath());
        assertThat(Files.readString(Path.of(resumed.getPath()).resolve("code"))).isEqualTo("run edit");
        assertThat(String.valueOf(context.getVariable("execution_path_reference")))
                .startsWith(directory.resolve("old-root").toRealPath().toString());
        assertThat(directory.resolve("new-root")).doesNotExist();
    }

    private void git(Path source, String... args) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("git", "-C", source.toString()));
        command.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }
}

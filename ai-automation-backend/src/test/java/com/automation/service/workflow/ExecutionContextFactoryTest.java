package com.automation.service.workflow;

import com.automation.entity.Project;
import com.automation.entity.RunStatus;
import com.automation.entity.Skill;
import com.automation.entity.TriggerSource;
import com.automation.entity.WorkflowRun;
import com.automation.entity.WorkflowVersion;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.memory.WorkflowMemoryService;
import com.automation.service.project.ProjectContextService;
import com.automation.service.system.SystemIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionContextFactoryTest {

    @Mock private WorkflowVersionService workflowVersionService;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private SystemIdentityService systemIdentity;
    @Mock private WorkflowContextDataService contextDataService;
    @Mock private AgentInstructionsService agentInstructionsService;
    @Mock private CommandExecutorService commandExecutorService;
    @Mock private WorkflowMemoryService workflowMemoryService;
    @Mock private ProjectContextService projectContextService;

    @InjectMocks private ExecutionContextFactory factory;

    private Project project;

    /** What the real collector reserves (pinned against the sources in PlatformVariableSourceWiringTest). */
    private static final java.util.Set<String> PLATFORM_NAMES = java.util.stream.Stream.concat(
            java.util.stream.Stream.of("previous_report", "open_todos", "request_metrics", "audit_findings",
                    "todo_details"),
            GoalContextSource.VARIABLES.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    @BeforeEach
    void setUp() {
        project = Project.builder().id("proj-1").path("/p").aiContext("ctx").build();
        lenient().when(contextDataService.platformVariableNames()).thenReturn(PLATFORM_NAMES);
        lenient().when(agentInstructionsService.buildInstructionPrefix(any())).thenReturn("");
        lenient().when(systemIdentity.getName()).thenReturn("MySuricates");
        lenient().when(systemIdentity.getVersion()).thenReturn("0.7.4");
        lenient().when(workflowVersionService.getVersionCount("wf-1")).thenReturn(0);
        lenient().when(workflowVersionService.findLatest("wf-1")).thenReturn(Optional.empty());
    }

    private ExecutionContextFactory.ContextSeed seed(Map<String, Object> input, String instructions,
                                                    String model, String provider, String effort, String resume) {
        return new ExecutionContextFactory.ContextSeed("run-1", "wf-1", "Demo", project, "docker-1",
                input, instructions, model, provider, effort, resume, null, null);
    }

    private ExecutionContextFactory.ContextSeed seed(Map<String, Object> input) {
        return seed(input, null, "", "", "", null);
    }

    @Test
    void create_seedsInputAndSystemVariables() {
        ExecutionContext ctx = factory.create(seed(Map.of("foo", "bar")));

        assertThat(ctx.getVariable("foo")).isEqualTo("bar");
        assertThat(ctx.getVariable("workflow_run_id")).isEqualTo("run-1");
        assertThat(ctx.getVariable("workflow_id")).isEqualTo("wf-1");
        assertThat(ctx.getVariable("workflow_name")).isEqualTo("Demo");
        assertThat(ctx.getVariable("project_id")).isEqualTo("proj-1");
        assertThat(ctx.getVariable("project_path")).isEqualTo("/p");
        assertThat(ctx.getProjectPath()).isEqualTo("/p");
        assertThat(ctx.getContainerId()).isEqualTo("docker-1");
        assertThat(ctx.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(String.valueOf(ctx.getVariable("run_timestamp"))).matches("\\d{8}-\\d{6}");
        assertThat(ctx.getVariable("started_at")).isNotNull();
        assertThat(ctx.getVariable("home_dir")).isNotNull();
        assertThat(ctx.getRunMetadata()).containsEntry("system", "MySuricates").containsEntry("systemVersion", "0.7.4");
    }

    @Test
    void create_userSuppliedProjectId_isOverwrittenBySystemValue() {
        ExecutionContext ctx = factory.create(seed(Map.of("project_id", "spoofed")));

        assertThat(ctx.getVariable("project_id")).isEqualTo("proj-1");
    }

    @Test
    void create_resumePreviousOutput_winsOverInputVariable() {
        ExecutionContext ctx = factory.create(seed(Map.of("previous_output", "from-input"), null, "", "", "", "from-resume"));

        assertThat(ctx.getPreviousOutput()).isEqualTo("from-resume");
    }

    @Test
    void create_previousOutputFromInput_whenNotResuming() {
        ExecutionContext ctx = factory.create(seed(Map.of("previous_output", "from-input")));

        assertThat(ctx.getPreviousOutput()).isEqualTo("from-input");
    }

    @Test
    void create_workflowChangedSinceLastRun_trueWhenVersionNewerThanLastCompletedRun() {
        WorkflowVersion v = mock(WorkflowVersion.class);
        when(v.getCreatedAt()).thenReturn(Instant.parse("2026-09-01T10:00:00Z"));
        when(workflowVersionService.getVersionCount("wf-1")).thenReturn(3);
        when(workflowVersionService.findLatest("wf-1")).thenReturn(Optional.of(v));
        WorkflowRun last = WorkflowRun.builder().completedAt(Instant.parse("2026-08-01T10:00:00Z")).build();
        when(runRepository.findTopByWorkflowIdAndStatusOrderByStartedAtDesc("wf-1", RunStatus.COMPLETED))
                .thenReturn(Optional.of(last));

        ExecutionContext ctx = factory.create(seed(Map.of()));

        assertThat(ctx.getVariable("workflow_version")).isEqualTo("3");
        assertThat(ctx.getVariable("workflow_changed_since_last_run")).isEqualTo("true");
    }

    @Test
    void create_versionOlderThanLastCompletedRun_marksUnchanged() {
        WorkflowVersion v = mock(WorkflowVersion.class);
        when(v.getCreatedAt()).thenReturn(Instant.parse("2026-07-01T10:00:00Z"));
        when(workflowVersionService.getVersionCount("wf-1")).thenReturn(2);
        when(workflowVersionService.findLatest("wf-1")).thenReturn(Optional.of(v));
        WorkflowRun last = WorkflowRun.builder().completedAt(Instant.parse("2026-08-01T10:00:00Z")).build();
        when(runRepository.findTopByWorkflowIdAndStatusOrderByStartedAtDesc("wf-1", RunStatus.COMPLETED))
                .thenReturn(Optional.of(last));

        assertThat(factory.create(seed(Map.of())).getVariable("workflow_changed_since_last_run")).isEqualTo("false");
    }

    @Test
    void create_injectsDefinitionHashAndTriggerSource() {
        ExecutionContext ctx = factory.create(new ExecutionContextFactory.ContextSeed("run-1", "wf-1", "Demo", project,
                "docker-1", Map.of("trigger_source", "SPOOFED"), null, "", "", "", null, "abc123", TriggerSource.WEBHOOK));

        assertThat(ctx.getVariable("workflow_definition_hash")).isEqualTo("abc123");
        assertThat(ctx.getVariable("trigger_source")).isEqualTo("WEBHOOK");
    }

    @Test
    void create_withoutProvenance_leavesVariablesEmpty() {
        ExecutionContext ctx = factory.create(seed(Map.of()));

        assertThat(ctx.getVariable("workflow_definition_hash")).isEqualTo("");
        assertThat(ctx.getVariable("trigger_source")).isEqualTo("");
    }

    @Test
    void create_changedSinceLastRun_comparesDefinitionHashesWhenBothKnown() {
        // version timestamp says "unchanged", but the executed definitions differ → hashes win
        WorkflowVersion v = mock(WorkflowVersion.class);
        lenient().when(v.getCreatedAt()).thenReturn(Instant.parse("2026-07-01T10:00:00Z"));
        lenient().when(workflowVersionService.findLatest("wf-1")).thenReturn(Optional.of(v));
        WorkflowRun last = WorkflowRun.builder().completedAt(Instant.parse("2026-08-01T10:00:00Z"))
                .definitionHash("old-hash").build();
        when(runRepository.findTopByWorkflowIdAndStatusOrderByStartedAtDesc("wf-1", RunStatus.COMPLETED))
                .thenReturn(Optional.of(last));

        ExecutionContext changed = factory.create(new ExecutionContextFactory.ContextSeed("run-1", "wf-1", "Demo", project,
                "docker-1", Map.of(), null, "", "", "", null, "new-hash", TriggerSource.MANUAL));
        ExecutionContext same = factory.create(new ExecutionContextFactory.ContextSeed("run-2", "wf-1", "Demo", project,
                "docker-1", Map.of(), null, "", "", "", null, "old-hash", TriggerSource.MANUAL));

        assertThat(changed.getVariable("workflow_changed_since_last_run")).isEqualTo("true");
        assertThat(same.getVariable("workflow_changed_since_last_run")).isEqualTo("false");
    }

    @Test
    void create_noVersionHistory_marksUnchanged() {
        ExecutionContext ctx = factory.create(seed(Map.of()));

        assertThat(ctx.getVariable("workflow_version")).isEqualTo("0");
        assertThat(ctx.getVariable("workflow_changed_since_last_run")).isEqualTo("false");
    }

    @Test
    void create_versionServiceThrows_fallsBackToFalse() {
        when(workflowVersionService.getVersionCount("wf-1")).thenReturn(1);
        when(workflowVersionService.findLatest("wf-1")).thenThrow(new RuntimeException("db"));

        assertThat(factory.create(seed(Map.of())).getVariable("workflow_changed_since_last_run")).isEqualTo("false");
    }

    @Test
    void create_injectsPlatformData_andBlankModelProviderEffortStayNull() {
        ExecutionContext ctx = factory.create(seed(Map.of()));

        // The short seed carries no workflow metadata: no audit workflow
        verify(contextDataService).injectPlatformData(ctx, "wf-1", "proj-1", Map.of());
        assertThat(ctx.getModel()).isNull();
        assertThat(ctx.getProvider()).isNull();
        assertThat(ctx.getEffort()).isNull();
        assertThat(ctx.getInstructionPrefix()).isNull();
    }

    /** TODO 3643beac: metadata.audit reaches the platform injection, which decides about audit_findings. */
    @Test
    void create_passesTheWorkflowMetadataToThePlatformInjection() {
        Map<String, Object> meta = Map.of("audit", Map.of("enabled", true, "key", "project.security",
                "category", "security", "reportBlock", "report"));

        ExecutionContext ctx = factory.create(new ExecutionContextFactory.ContextSeed("run-1", "wf-1", "Demo", project,
                "docker-1", Map.of(), null, "", "", "", null, null, TriggerSource.SCHEDULE, false, meta));

        verify(contextDataService).injectPlatformData(ctx, "wf-1", "proj-1", meta);
    }

    @Test
    void create_nullWorkflowMetadata_isTreatedAsNone() {
        ExecutionContextFactory.ContextSeed seed = new ExecutionContextFactory.ContextSeed("run-1", "wf-1", "Demo",
                project, "docker-1", Map.of(), null, "", "", "", null, null, null, false, null);

        assertThat(seed.workflowMeta()).isEmpty();
    }

    /**
     * TODO 3643beac: audit_findings describes the running workflow. A parent run hands every variable to
     * its sub-workflow — the parent's findings must not survive there as a trusted, unquoted variable.
     */
    @Test
    void create_handedOverAuditFindings_areDropped_soOnlyThePlatformSetsThem() {
        ExecutionContext ctx = factory.create(seed(Map.of("audit_findings", "OPEN | HIGH | x | $(id) | y",
                "foo", "bar")));

        assertThat(ctx.getVariables()).doesNotContainKey("audit_findings").containsEntry("foo", "bar");
        assertThat(ctx.resolveShellTemplate("echo {{ audit_findings }}", false))
                .as("left to the platform injection — the mock sets nothing, so the placeholder stays")
                .isEqualTo("echo {{ audit_findings }}");
    }

    /**
     * TODO 2a0695ff: no platform variable comes from an input context. For goal_status without goal_id the platform
     * sets nothing — a handed-over or passed value would otherwise stay a trusted, unquoted variable; for
     * previous_report and co. it would be overwritten, but was trusted until then.
     */
    @Test
    void create_handedOverPlatformData_isDroppedEveryVariable_soOnlyThePlatformSetsIt() {
        java.util.Map<String, Object> input = new java.util.HashMap<>();
        PLATFORM_NAMES.forEach(name -> input.put(name, "gefaelscht $(id)"));
        input.put("foo", "bar");

        ExecutionContext ctx = factory.create(seed(input));

        assertThat(ctx.getVariables()).doesNotContainKeys(PLATFORM_NAMES.toArray(String[]::new))
                .containsEntry("foo", "bar");
        assertThat(ctx.resolveShellTemplate("echo {{ goal_status }} {{ open_todos }}", false))
                .as("left to the platform injection — the mock sets nothing, so the placeholders stay")
                .isEqualTo("echo {{ goal_status }} {{ open_todos }}");
    }

    /** The reserved names are the collector's, not a list of the factory's own. */
    @Test
    void create_asksTheCollectorWhichNamesAreReserved() {
        when(contextDataService.platformVariableNames()).thenReturn(java.util.Set.of("new_platform_variable"));

        ExecutionContext ctx = factory.create(seed(Map.of("new_platform_variable", "x", "open_todos", "y")));

        assertThat(ctx.getVariables()).doesNotContainKey("new_platform_variable").containsEntry("open_todos", "y");
    }

    @Test
    void create_nonBlankInstructionsModelProviderEffort_areApplied() {
        when(agentInstructionsService.buildInstructionPrefix("be brief")).thenReturn("PREFIX");

        ExecutionContext ctx = factory.create(seed(Map.of(), "be brief", "claude-opus-4-6", "CLAUDE", "high", null));

        assertThat(ctx.getInstructionPrefix()).isEqualTo("PREFIX");
        assertThat(ctx.getModel()).isEqualTo("claude-opus-4-6");
        assertThat(ctx.getProvider()).isEqualTo("CLAUDE");
        assertThat(ctx.getEffort()).isEqualTo("high");
        assertThat(ctx.getVariable("model")).isEqualTo("claude-opus-4-6");
    }

    @Test
    void writeProjectContextFiles_delegatesToAllThreeWriters() {
        List<Skill> skills = List.of();

        factory.writeProjectContextFiles(project, "wf-1", skills);

        verify(commandExecutorService).writeSkillContextToProject("/p", skills);
        verify(workflowMemoryService).writeActiveMemoryToProject("wf-1", "/p");
        verify(projectContextService).writeProjectContextToProject("ctx", "/p");
    }


    @Test
    void create_untrustedKeys_markVariablesUntrusted() {
        ExecutionContext ctx = factory.create(seed(Map.of(
                "action", "deploy",
                "env", "test",
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, java.util.List.of("action"))));

        assertThat(ctx.isUntrusted("action")).isTrue();
        assertThat(ctx.isUntrusted("env")).isFalse();
        assertThat(ctx.resolveShellTemplate("run {{ action }} {{ env }}", false)).isEqualTo("run 'deploy' test");
    }

    @Test
    void create_untrustedSystemVariable_isOverwrittenByTrustedSystemValue() {
        ExecutionContext ctx = factory.create(seed(Map.of(
                "project_path", "/evil",
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, java.util.List.of("project_path"))));

        assertThat(ctx.getVariable("project_path")).isEqualTo("/p");
        assertThat(ctx.isUntrusted("project_path")).isFalse();
    }

    @Test
    void create_withoutUntrustedKeys_marksNothing() {
        ExecutionContext ctx = factory.create(seed(Map.of("foo", "bar")));

        assertThat(ctx.getUntrustedVariables()).isEmpty();
    }

    @Test
    void create_untrustedOutputs_taintPreviousOutputAndNamedBlockOutput() {
        ExecutionContext ctx = factory.create(seed(Map.of(
                        ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                        java.util.List.of(ExecutionContext.PREVIOUS_OUTPUT_NAME, "scan")),
                null, "", "", "", "x; id"));
        ctx.setBlockOutput("scan", "y && z");

        assertThat(ctx.hasUntrustedInput()).isTrue();
        assertThat(ctx.resolveShellTemplate("echo {{ previous_output }} {{ block.scan.output }}", false))
                .isEqualTo("echo 'x; id' 'y && z'");
    }

    @Test
    void create_untrustedOutputsWithUntrustedKeys_roundTripsAnExportedTaint() {
        ExecutionContext source = ExecutionContext.builder().workflowRunId("run-0").build();
        source.setUntrustedVariable("branch", "main; id");
        source.recordOutput("scan", "out");
        Map<String, Object> input = new java.util.HashMap<>(source.exportTaint());
        input.put("branch", "main; id");

        ExecutionContext ctx = factory.create(seed(input, null, "", "", "", "out"));

        assertThat(ctx.isUntrusted("branch")).isTrue();
        assertThat(ctx.resolveShellTemplate("echo {{ branch }} {{ previous_output }}", false))
                .isEqualTo("echo 'main; id' 'out'");
    }

    /**
     * TODO cadfc423: faerbt ein Lauf, weil Plattform-Daten in einer Shell-Kommandozeile gelandet
     * sind, ueberlebt das Flag Export und Import — auch ohne verbuchte Ausgabe dazwischen.
     */
    @Test
    void create_platformDataUsedInShell_roundTripsWithoutAnyRecordedOutput() {
        ExecutionContext source = ExecutionContext.builder().workflowRunId("run-0").build();
        source.setUntrustedPlatformVariable("previous_report", "bericht");
        source.resolveShellTemplate("echo {{ previous_report }}", false);
        Map<String, Object> input = new java.util.HashMap<>(source.exportTaint());

        ExecutionContext ctx = factory.create(seed(input, null, "", "", "", "a | b"));
        ctx.setDerivedVariable("item", "a.txt; id");

        assertThat(ctx.hasUntrustedInput()).isTrue();
        assertThat(ctx.isUntrusted("item")).isTrue();
        assertThat(ctx.resolveShellTemplate("cat {{ item }}", false)).isEqualTo("cat 'a.txt; id'");
    }

    @Test
    void create_withoutUntrustedOutputs_previousOutputStaysRaw() {
        ExecutionContext ctx = factory.create(seed(Map.of("foo", "bar"), null, "", "", "", "a | b"));

        assertThat(ctx.hasUntrustedInput()).isFalse();
        assertThat(ctx.resolveShellTemplate("echo {{ previous_output }}", false)).isEqualTo("echo a | b");
    }
}

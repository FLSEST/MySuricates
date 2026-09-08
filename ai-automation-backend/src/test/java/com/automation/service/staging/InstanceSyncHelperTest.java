package com.automation.service.staging;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncResult;
import com.automation.entity.*;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstanceSyncHelperTest {

    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private WorkflowChainRepository chainRepository;
    @Mock private InstanceHttpClient httpClient;
    @Mock private javax.sql.DataSource dataSource;

    private ObjectMapper objectMapper = new ObjectMapper();
    private InstanceSyncHelper syncHelper;

    private static final String TARGET = "http://localhost:9091/api/v1";

    @BeforeEach
    void setUp() throws Exception {
        syncHelper = new InstanceSyncHelper(
                appSettingsRepository, projectRepository, workflowRepository,
                skillRepository, chainRepository, httpClient, dataSource);
        lenient().when(chainRepository.findAll()).thenReturn(List.of());

        lenient().when(appSettingsRepository.get(anyString())).thenReturn(Optional.empty());

        // Default: pre-sync backup succeeds (stubbed globally so existing tests are unaffected).
        lenient().when(httpClient.httpPost(contains("/system/backup"), any())).thenReturn(objectMapper.readTree("{\"filename\":\"test-backup.db\",\"sizeBytes\":1024}"));
    }



    // --- Sync projects ---

    @Test
    void sync_newProject_createsOnTarget() throws Exception {
        Project proj = Project.builder().id("p1").name("NewProj").path("/new").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpPost(eq(TARGET + "/projects"), any())).thenReturn(objectMapper.readTree("{\"id\":\"tp1\"}"));

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        assertThat(result.projectsSynced()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).httpPost(eq(TARGET + "/projects"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().get("name")).isEqualTo("NewProj");
        assertThat(bodyCaptor.getValue().get("path")).isEqualTo("/new");
        assertThat(bodyCaptor.getValue().get("type")).isEqualTo("CODE");
    }

    @Test
    void sync_existingProject_skips() throws Exception {
        Project proj = Project.builder().id("p1").name("Existing").path("/existing").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[{\"name\":\"Existing\",\"path\":\"/existing\",\"id\":\"tp1\"}]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        assertThat(result.projectsSynced()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(1);
    }

    // --- Sync workflows ---

    @Test
    void sync_newWorkflow_importsOnTarget() throws Exception {
        Project proj = Project.builder().id("p1").name("Proj").path("/proj").type(ProjectType.CODE).build();
        Workflow wf = Workflow.builder().id("wf1").name("NewWF").project(proj)
                .blocks(List.of()).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[{\"name\":\"Proj\",\"path\":\"/proj\",\"id\":\"tp1\"}]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpPost(eq(TARGET + "/workflows/import"), any())).thenReturn(objectMapper.readTree("{\"id\":\"twf1\"}"));

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        assertThat(result.workflowsSynced()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> wfBodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).httpPost(eq(TARGET + "/workflows/import"), wfBodyCaptor.capture());
        assertThat(wfBodyCaptor.getValue().get("name")).isEqualTo("NewWF");
        assertThat(wfBodyCaptor.getValue().get("projectId")).isEqualTo("tp1");
    }

    @Test
    void sync_existingWorkflow_updatesOnTarget() throws Exception {
        Project proj = Project.builder().id("p1").name("Proj").path("/proj").type(ProjectType.CODE).build();
        Workflow wf = Workflow.builder().id("wf1").name("ExistingWF").project(proj)
                .description("new desc").blocks(List.of()).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[{\"name\":\"Proj\",\"path\":\"/proj\",\"id\":\"tp1\"}]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[{\"name\":\"ExistingWF\",\"projectName\":\"Proj\",\"id\":\"twf1\",\"description\":\"old desc\",\"blocks\":[]}]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpPut(contains("/workflows/twf1"), any())).thenReturn(null);

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        assertThat(result.workflowsSynced()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> updateCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).httpPut(eq(TARGET + "/workflows/twf1"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().get("name")).isEqualTo("ExistingWF");
        assertThat(updateCaptor.getValue().get("description")).isEqualTo("new desc");
    }

    // --- Sync skills ---

    @Test
    void sync_existingSkill_skips() throws Exception {
        Skill skill = Skill.builder().id("s1").name("MySkill").category(SkillCategory.CODE).builtin(false).build();
        when(projectRepository.findAll()).thenReturn(List.of());
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of(skill));

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[{\"name\":\"MySkill\"}]"));

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        assertThat(result.skillsSynced()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void sync_newSkill_importsOnTarget() throws Exception {
        Skill skill = Skill.builder().id("s1").name("NewSkill").category(SkillCategory.CODE)
                .description("desc").builtin(false).build();
        when(projectRepository.findAll()).thenReturn(List.of());
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of(skill));

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpPost(eq(TARGET + "/skills/import"), any())).thenReturn(objectMapper.readTree("{\"id\":\"ts1\"}"));

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        assertThat(result.skillsSynced()).isEqualTo(1);
    }

    // --- Selective sync ---

    @Test
    void sync_selectiveByWorkflowIds_onlySyncsSelected() throws Exception {
        Project proj = Project.builder().id("p1").name("Proj").path("/proj").type(ProjectType.CODE).build();
        Workflow wf1 = Workflow.builder().id("wf1").name("WF1").project(proj).blocks(List.of()).build();
        Workflow wf2 = Workflow.builder().id("wf2").name("WF2").project(proj).blocks(List.of()).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf1, wf2));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[{\"name\":\"Proj\",\"path\":\"/proj\",\"id\":\"tp1\"}]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpPost(eq(TARGET + "/workflows/import"), any())).thenReturn(objectMapper.readTree("{\"id\":\"twf1\"}"));

        var request = new SyncDefinitionsRequest(null, List.of("wf1"), null);
        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, request);

        // Only wf1 synced, wf2 skipped
        assertThat(result.workflowsSynced()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient, times(1)).httpPost(eq(TARGET + "/workflows/import"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().get("name")).isEqualTo("WF1");
    }

    @Test
    void sync_selectiveByProjectIds_onlySyncsWorkflowsInProject() throws Exception {
        Project proj1 = Project.builder().id("p1").name("Proj1").path("/proj1").type(ProjectType.CODE).build();
        Project proj2 = Project.builder().id("p2").name("Proj2").path("/proj2").type(ProjectType.CODE).build();
        Workflow wf1 = Workflow.builder().id("wf1").name("WF1").project(proj1).blocks(List.of()).build();
        Workflow wf2 = Workflow.builder().id("wf2").name("WF2").project(proj2).blocks(List.of()).build();
        Workflow wf3 = Workflow.builder().id("wf3").name("WF3").project(null).blocks(List.of()).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj1, proj2));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf1, wf2, wf3));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpPost(eq(TARGET + "/projects"), any())).thenReturn(objectMapper.readTree("{\"id\":\"tp1\"}"));
        when(httpClient.httpPost(eq(TARGET + "/workflows/import"), any())).thenReturn(objectMapper.readTree("{\"id\":\"twf1\"}"));

        // Only sync proj1
        var request = new SyncDefinitionsRequest(List.of("p1"), null, null);
        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, request);

        // Only wf1 (proj1) synced — wf2 (proj2) and wf3 (no project) skipped
        assertThat(result.workflowsSynced()).isEqualTo(1);
        assertThat(result.projectsSynced()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient, times(1)).httpPost(eq(TARGET + "/workflows/import"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().get("name")).isEqualTo("WF1");
    }

    // --- Error handling ---

    @Test
    void sync_targetUnreachable_collectsErrors() throws Exception {
        Project proj = Project.builder().id("p1").name("Proj").path("/proj").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        // httpGet returns null (target unreachable) — with guard, no non-backup httpPost is called
        when(httpClient.httpGet(anyString())).thenReturn(null);

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        // Should not crash, errors are collected for each entity type
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.projectsSynced()).isEqualTo(0);
        // Pre-sync backup POST is expected (best-effort rollback point) — but no sync POSTs
        verify(httpClient, never()).httpPost(not(contains("/system/backup")), any());
    }

    @Test
    void sync_httpGetReturnsNull_skipsSyncAndCollectsErrors() throws Exception {
        Project proj = Project.builder().id("p1").name("Proj").path("/proj").type(ProjectType.CODE).build();
        Workflow wf = Workflow.builder().id("wf1").name("WF1").project(proj).blocks(List.of()).build();
        Skill skill = Skill.builder().id("s1").name("Skill1").category(SkillCategory.CODE).builtin(false).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of(skill));

        // httpGet returns null for all target endpoints
        when(httpClient.httpGet(anyString())).thenReturn(null);

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        // Nothing should be synced — no sync POST calls (backup POST is expected)
        assertThat(result.projectsSynced()).isEqualTo(0);
        assertThat(result.workflowsSynced()).isEqualTo(0);
        assertThat(result.skillsSynced()).isEqualTo(0);
        assertThat(result.chainsSynced()).isEqualTo(0);
        assertThat(result.errors()).hasSize(4);
        verify(httpClient, never()).httpPost(not(contains("/system/backup")), any());
    }

    // --- API key override ---

    @Test
    void sync_withTargetApiKey_usesOverride() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of());
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(anyString())).thenReturn(objectMapper.readTree("[]"));

        syncHelper.syncDefinitionsTo(TARGET, "custom-key", null);

        InOrder inOrder = inOrder(httpClient);
        inOrder.verify(httpClient).setTargetApiKeyOverride("custom-key");
        inOrder.verify(httpClient, atLeast(3)).httpGet(anyString());
        inOrder.verify(httpClient).clearTargetApiKeyOverride();
    }

    @Test
    void sync_clearsOverride_evenWhenSyncThrows() {
        when(projectRepository.findAll()).thenThrow(new IllegalStateException("db down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> syncHelper.syncDefinitionsTo(TARGET, "custom-key", null))
                .isInstanceOf(IllegalStateException.class);

        verify(httpClient).setTargetApiKeyOverride("custom-key");
        verify(httpClient).clearTargetApiKeyOverride();
    }

    // --- markStableSynced via DataSource ---

    @Test
    void sync_existingWorkflow_callsMarkStableSyncedWithCorrectId() throws Exception {
        // P1 gap from mocking-gap-audit v2: markStableSynced() uses raw JDBC.
        // Without a proper DataSource mock chain, the call silently swallows NPE.
        // This test verifies that the prepared statement is executed with the correct workflow ID.
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        Project proj = Project.builder().id("p1").name("Proj").path("/proj").type(ProjectType.CODE).build();
        Workflow wf = Workflow.builder().id("wf1").name("ExistingWF").project(proj)
                .description("desc").blocks(List.of()).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[{\"name\":\"Proj\",\"path\":\"/proj\",\"id\":\"tp1\"}]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[{\"name\":\"ExistingWF\",\"projectName\":\"Proj\",\"id\":\"twf1\",\"description\":\"desc\",\"blocks\":[]}]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpPut(anyString(), any())).thenReturn(null);

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        assertThat(result.workflowsSynced()).isEqualTo(1);
        verify(dataSource).getConnection();
        // Verify the workflow ID is passed as the second parameter
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(2), idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo("wf1");
        verify(ps).executeUpdate();
    }

    // --- Combined projectIds + workflowIds filter ---

    @Test
    void sync_combinedProjectAndWorkflowFilter_autoIncludesWorkflowProject() throws Exception {
        // P2 gap: When both projectIds and workflowIds are set, projects of selected workflows
        // should be auto-included in selectedProjectIds, so those workflows can be synced.
        Project proj1 = Project.builder().id("p1").name("Proj1").path("/proj1").type(ProjectType.CODE).build();
        Project proj2 = Project.builder().id("p2").name("Proj2").path("/proj2").type(ProjectType.CODE).build();
        Workflow wf1 = Workflow.builder().id("wf1").name("WF1").project(proj1).blocks(List.of()).build();
        Workflow wf2 = Workflow.builder().id("wf2").name("WF2").project(proj2).blocks(List.of()).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj1, proj2));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf1, wf2));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[{\"name\":\"Proj2\",\"path\":\"/proj2\",\"id\":\"tp2\"}]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpPost(eq(TARGET + "/workflows/import"), any())).thenReturn(objectMapper.readTree("{\"id\":\"twf2\"}"));

        // Request: p1 in projectIds, wf2 (in p2) in workflowIds — p2 must be auto-included
        var request = new SyncDefinitionsRequest(List.of("p1"), List.of("wf2"), null);
        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, request);

        // wf2 is synced (p2 was auto-included); wf1 is not synced (not in workflowIds)
        assertThat(result.workflowsSynced()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient, times(1)).httpPost(eq(TARGET + "/workflows/import"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().get("name")).isEqualTo("WF2");
    }

    // --- Projectless workflow edge case ---

    @Test
    void sync_projectlessWorkflow_skippedWhenProjectIdFilterSet() throws Exception {
        // P2 gap: When selectedProjectIds is set, workflows with null project are always skipped
        // — even if they are explicitly selected via workflowIds.
        // This documents the current behavior (skip) rather than asserting it is correct.
        Project proj1 = Project.builder().id("p1").name("Proj1").path("/proj1").type(ProjectType.CODE).build();
        Workflow wf1 = Workflow.builder().id("wf1").name("WF1").project(proj1).blocks(List.of()).build();
        Workflow wfNoProject = Workflow.builder().id("wf3").name("WF3").project(null).blocks(List.of()).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj1));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf1, wfNoProject));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[{\"name\":\"Proj1\",\"path\":\"/proj1\",\"id\":\"tp1\"}]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));

        // Request: p1 in projectIds, wf3 (no project) explicitly in workflowIds
        var request = new SyncDefinitionsRequest(List.of("p1"), List.of("wf3"), null);
        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, request);

        // wf3 is skipped: selectedProjectIds filter rejects it because wf3.project == null
        assertThat(result.workflowsSynced()).isEqualTo(0);
        verify(httpClient, never()).httpPost(eq(TARGET + "/workflows/import"), any());
    }

    // --- Pre-sync backup ---

    @Test
    void sync_triggersPreSyncBackupOnTarget() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of());
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(anyString())).thenReturn(objectMapper.readTree("[]"));

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        // Backup is called with the pre-sync-definitions reason
        verify(httpClient).httpPost(eq(TARGET + "/system/backup?reason=pre-sync-definitions"), any());
        assertThat(result.preSyncBackup()).isEqualTo("test-backup.db");
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void sync_preSyncBackupFailure_doesNotBlockSync() throws Exception {
        Project proj = Project.builder().id("p1").name("NewProj").path("/new").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        // Override the global backup stub to simulate backup failure
        when(httpClient.httpPost(contains("/system/backup"), any())).thenThrow(new RuntimeException("backup-target-down"));

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpPost(eq(TARGET + "/projects"), any())).thenReturn(objectMapper.readTree("{\"id\":\"tp1\"}"));

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        // Sync still happens despite backup failure
        assertThat(result.projectsSynced()).isEqualTo(1);
        assertThat(result.preSyncBackup()).isNull();
        // Warning is recorded in errors
        assertThat(result.errors()).anyMatch(e -> e.contains("Pre-sync backup failed"));
    }

    @Test
    void sync_preSyncBackupReturnsNull_recordsWarning() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of());
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        // Backup call returns null (e.g., target rejected the request silently)
        when(httpClient.httpPost(contains("/system/backup"), any())).thenReturn(null);
        when(httpClient.httpGet(anyString())).thenReturn(objectMapper.readTree("[]"));

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, null, null);

        assertThat(result.preSyncBackup()).isNull();
        assertThat(result.errors()).anyMatch(e -> e.contains("Pre-sync backup failed"));
    }
}

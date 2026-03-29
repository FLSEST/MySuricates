package com.automation.service.staging;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncResult;
import com.automation.entity.*;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstanceSyncHelperTest {

    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private ApiKeyService apiKeyService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private InstanceSyncHelper syncHelper;

    private static final String TARGET = "http://localhost:9091/api/v1";

    @BeforeEach
    void setUp() {
        syncHelper = spy(new InstanceSyncHelper(
                appSettingsRepository, projectRepository, workflowRepository,
                skillRepository, apiKeyService, objectMapper));

        lenient().when(appSettingsRepository.get(anyString())).thenReturn(Optional.empty());
    }

    // --- Sync preview ---

    @Test
    void syncPreview_newProject_markedAsNew() throws Exception {
        Project proj = Project.builder().id("p1").name("MyProject").path("/project").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        // Target has no projects
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/projects");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/workflows");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/skills");

        SyncPreview preview = syncHelper.syncPreview(TARGET);

        assertThat(preview.projects()).hasSize(1);
        assertThat(preview.projects().get(0).action()).isEqualTo("NEW");
    }

    @Test
    void syncPreview_existingProject_markedAsSkip() throws Exception {
        Project proj = Project.builder().id("p1").name("MyProject").path("/project").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        doReturn(objectMapper.readTree("[{\"name\":\"MyProject\",\"path\":\"/project\",\"id\":\"tp1\"}]"))
                .when(syncHelper).httpGet(TARGET + "/projects");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/workflows");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/skills");

        SyncPreview preview = syncHelper.syncPreview(TARGET);

        assertThat(preview.projects().get(0).action()).isEqualTo("SKIP");
    }

    // --- Sync projects ---

    @Test
    void sync_newProject_createsOnTarget() throws Exception {
        Project proj = Project.builder().id("p1").name("NewProj").path("/new").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/projects");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/workflows");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/skills");
        doReturn(objectMapper.readTree("{\"id\":\"tp1\"}")).when(syncHelper).httpPost(eq(TARGET + "/projects"), any());

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET);

        assertThat(result.projectsSynced()).isEqualTo(1);
        verify(syncHelper).httpPost(eq(TARGET + "/projects"), any());
    }

    @Test
    void sync_existingProject_skips() throws Exception {
        Project proj = Project.builder().id("p1").name("Existing").path("/existing").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        doReturn(objectMapper.readTree("[{\"name\":\"Existing\",\"path\":\"/existing\",\"id\":\"tp1\"}]"))
                .when(syncHelper).httpGet(TARGET + "/projects");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/workflows");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/skills");

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET);

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

        doReturn(objectMapper.readTree("[{\"name\":\"Proj\",\"path\":\"/proj\",\"id\":\"tp1\"}]"))
                .when(syncHelper).httpGet(TARGET + "/projects");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/workflows");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/skills");
        doReturn(objectMapper.readTree("{\"id\":\"twf1\"}")).when(syncHelper).httpPost(eq(TARGET + "/workflows/import"), any());

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET);

        assertThat(result.workflowsSynced()).isEqualTo(1);
        verify(syncHelper).httpPost(eq(TARGET + "/workflows/import"), any());
    }

    @Test
    void sync_existingWorkflow_updatesOnTarget() throws Exception {
        Project proj = Project.builder().id("p1").name("Proj").path("/proj").type(ProjectType.CODE).build();
        Workflow wf = Workflow.builder().id("wf1").name("ExistingWF").project(proj)
                .description("new desc").blocks(List.of()).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        doReturn(objectMapper.readTree("[{\"name\":\"Proj\",\"path\":\"/proj\",\"id\":\"tp1\"}]"))
                .when(syncHelper).httpGet(TARGET + "/projects");
        doReturn(objectMapper.readTree("[{\"name\":\"ExistingWF\",\"projectName\":\"Proj\",\"id\":\"twf1\",\"description\":\"old desc\",\"blocks\":[]}]"))
                .when(syncHelper).httpGet(TARGET + "/workflows");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/skills");
        doReturn(null).when(syncHelper).httpPut(contains("/workflows/twf1"), any());

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET);

        assertThat(result.workflowsSynced()).isEqualTo(1);
        verify(syncHelper).httpPut(eq(TARGET + "/workflows/twf1"), any());
    }

    // --- Sync skills ---

    @Test
    void sync_existingSkill_skips() throws Exception {
        Skill skill = Skill.builder().id("s1").name("MySkill").category(SkillCategory.CODE).builtin(false).build();
        when(projectRepository.findAll()).thenReturn(List.of());
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of(skill));

        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/projects");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/workflows");
        doReturn(objectMapper.readTree("[{\"name\":\"MySkill\"}]")).when(syncHelper).httpGet(TARGET + "/skills");

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET);

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

        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/projects");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/workflows");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/skills");
        doReturn(objectMapper.readTree("{\"id\":\"ts1\"}")).when(syncHelper).httpPost(eq(TARGET + "/skills/import"), any());

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET);

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

        doReturn(objectMapper.readTree("[{\"name\":\"Proj\",\"path\":\"/proj\",\"id\":\"tp1\"}]"))
                .when(syncHelper).httpGet(TARGET + "/projects");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/workflows");
        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(TARGET + "/skills");
        doReturn(objectMapper.readTree("{\"id\":\"twf1\"}")).when(syncHelper).httpPost(eq(TARGET + "/workflows/import"), any());

        var request = new SyncDefinitionsRequest(null, List.of("wf1"), null);
        SyncResult result = syncHelper.syncDefinitionsTo(TARGET, request);

        // Only wf1 synced, wf2 skipped
        assertThat(result.workflowsSynced()).isEqualTo(1);
        verify(syncHelper, times(1)).httpPost(eq(TARGET + "/workflows/import"), any());
    }

    // --- Error handling ---

    @Test
    void sync_targetUnreachable_collectsErrors() throws Exception {
        Project proj = Project.builder().id("p1").name("Proj").path("/proj").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        // httpGet returns null (target unreachable), httpPost also fails
        doReturn(null).when(syncHelper).httpGet(anyString());
        doThrow(new RuntimeException("Connection refused")).when(syncHelper).httpPost(anyString(), any());

        SyncResult result = syncHelper.syncDefinitionsTo(TARGET);

        // Should not crash, errors are collected
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().get(0)).contains("Connection refused");
    }

    // --- API key override ---

    @Test
    void sync_withTargetApiKey_usesOverride() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of());
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        doReturn(objectMapper.readTree("[]")).when(syncHelper).httpGet(anyString());

        syncHelper.syncDefinitionsTo(TARGET, "custom-key", null);

        // The override key is set via ThreadLocal — verify httpGet was called (key is in header, not directly testable without real HTTP)
        verify(syncHelper, atLeast(3)).httpGet(anyString());
    }
}

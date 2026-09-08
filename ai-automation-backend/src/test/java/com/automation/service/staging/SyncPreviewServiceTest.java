package com.automation.service.staging;

import com.automation.dto.response.SyncPreview;
import com.automation.entity.*;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The two preview tests moved from {@code InstanceSyncHelperTest}, plus diff and override cases (Audit-TODO 189ad41b). */
@ExtendWith(MockitoExtension.class)
class SyncPreviewServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private WorkflowChainRepository chainRepository;
    @Mock private InstanceHttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SyncPreviewService previewService;

    private static final String TARGET = "http://localhost:9091/api/v1";

    @BeforeEach
    void setUp() {
        previewService = new SyncPreviewService(projectRepository, workflowRepository, skillRepository,
                chainRepository, objectMapper, httpClient);
        lenient().when(chainRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void syncPreview_newProject_markedAsNew() throws Exception {
        Project proj = Project.builder().id("p1").name("MyProject").path("/project").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        // Target has no projects
        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));

        SyncPreview preview = previewService.syncPreview(TARGET);

        assertThat(preview.projects()).hasSize(1);
        assertThat(preview.projects().get(0).action()).isEqualTo("NEW");
    }

    @Test
    void syncPreview_existingProject_markedAsSkip() throws Exception {
        Project proj = Project.builder().id("p1").name("MyProject").path("/project").type(ProjectType.CODE).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());

        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[{\"name\":\"MyProject\",\"path\":\"/project\",\"id\":\"tp1\"}]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));

        SyncPreview preview = previewService.syncPreview(TARGET);

        assertThat(preview.projects().get(0).action()).isEqualTo("SKIP");
    }

    @Test
    void syncPreview_existingWorkflow_reportsFieldDiff_andUpdateAction() throws Exception {
        Project proj = Project.builder().id("p1").name("MyProject").path("/project").type(ProjectType.CODE).build();
        Workflow wf = Workflow.builder().id("wf1").name("WF").description("new desc").model("opus").project(proj)
                .blocks(new java.util.ArrayList<>(List.of(BlockDefinition.builder().type("shell").build()))).build();
        when(projectRepository.findAll()).thenReturn(List.of(proj));
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());
        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree(
                "[{\"id\":\"t1\",\"name\":\"WF\",\"projectName\":\"MyProject\",\"description\":\"old desc\",\"model\":\"sonnet\",\"blocks\":[]}]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));

        SyncPreview preview = previewService.syncPreview(TARGET);

        var item = preview.workflows().get(0);
        assertThat(item.action()).isEqualTo("UPDATE");
        assertThat(item.diff().changedFields()).containsExactly("description", "model", "blocks");
        assertThat(item.diff().modelBefore()).isEqualTo("sonnet");
        assertThat(item.diff().modelAfter()).isEqualTo("opus");
        assertThat(item.diff().blocksAdded()).isEqualTo(1);
        assertThat(item.diff().blocksRemoved()).isZero();
    }

    @Test
    void syncPreview_identicalWorkflow_isSkip_chainsAlwaysNewOrUpdate() throws Exception {
        Project proj = Project.builder().id("p1").name("MyProject").path("/project").type(ProjectType.CODE).build();
        Workflow wf = Workflow.builder().id("wf1").name("WF").project(proj).blocks(new java.util.ArrayList<>()).build();
        when(projectRepository.findAll()).thenReturn(List.of());
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of(wf));
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());
        when(chainRepository.findAll()).thenReturn(List.of(
                WorkflowChain.builder().id("c1").name("Known").build(),
                WorkflowChain.builder().id("c2").name("Fresh").build()));
        when(httpClient.httpGet(TARGET + "/projects")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/workflows")).thenReturn(objectMapper.readTree(
                "[{\"id\":\"t1\",\"name\":\"WF\",\"projectName\":\"MyProject\",\"description\":\"\",\"model\":\"\",\"instructions\":\"\",\"blocks\":[]}]"));
        when(httpClient.httpGet(TARGET + "/skills")).thenReturn(objectMapper.readTree("[]"));
        when(httpClient.httpGet(TARGET + "/chains")).thenReturn(objectMapper.readTree("[{\"name\":\"Known\"}]"));

        SyncPreview preview = previewService.syncPreview(TARGET);

        assertThat(preview.workflows().get(0).action()).isEqualTo("SKIP");
        assertThat(preview.chains()).extracting(i -> i.action()).containsExactly("UPDATE", "NEW");
    }

    @Test
    void syncPreview_withApiKey_setsAndClearsOverrideAroundTheCalls() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of());
        when(workflowRepository.findAllReadOnly()).thenReturn(List.of());
        when(skillRepository.findByBuiltin(false)).thenReturn(List.of());
        when(httpClient.httpGet(anyString())).thenReturn(objectMapper.readTree("[]"));

        previewService.syncPreview(TARGET, "stable-key");

        InOrder inOrder = inOrder(httpClient);
        inOrder.verify(httpClient).setTargetApiKeyOverride("stable-key");
        inOrder.verify(httpClient, atLeast(3)).httpGet(anyString());
        inOrder.verify(httpClient).clearTargetApiKeyOverride();
    }
}

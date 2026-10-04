package com.automation.service.staging;

import com.automation.config.StagingConfig;
import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncResult;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.repository.WorkflowRepository;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TestingServiceTest {

    @Spy
    private StagingConfig stagingConfig = new StagingConfig();

    @Mock
    private InstanceSyncHelper instanceSyncHelper;

    @Mock
    private InstanceHttpClient instanceHttpClient;

    @Mock
    private SyncPreviewService syncPreviewService;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private DockerContainerManager dockerContainerManager;

    @Mock
    private StagingComposeCommand stagingComposeCommand;

    @InjectMocks
    private TestingService testingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- reportTestResult ---

    @Test
    void reportTestResult_matchingWorkflow_setsStatusAndTimestamp() {
        Project project = Project.builder().id("p-1").name("MyProject").build();
        Workflow workflow = Workflow.builder()
                .id("wf-1").name("Test WF").project(project).build();
        when(workflowRepository.findAll()).thenReturn(List.of(workflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        testingService.reportTestResult("Test WF", "MyProject", "PASSED");

        assertThat(workflow.getTestStatus()).isEqualTo("PASSED");
        assertThat(workflow.getLastTestedAt()).isNotNull();
        verify(workflowRepository).save(workflow);
    }

    @Test
    void reportTestResult_noMatchingWorkflow_doesNotSave() {
        when(workflowRepository.findAll()).thenReturn(List.of());

        testingService.reportTestResult("Missing", "Project", "PASSED");

        verify(workflowRepository, never()).save(any());
    }

    @Test
    void reportTestResult_matchesOnNullProject() {
        Workflow workflow = Workflow.builder()
                .id("wf-1").name("No Project WF").project(null).build();
        when(workflowRepository.findAll()).thenReturn(List.of(workflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        testingService.reportTestResult("No Project WF", null, "FAILED");

        assertThat(workflow.getTestStatus()).isEqualTo("FAILED");
        verify(workflowRepository).save(workflow);
    }

    @Test
    void reportTestResult_wrongProjectName_doesNotMatch() {
        Project project = Project.builder().id("p-1").name("ProjectA").build();
        Workflow workflow = Workflow.builder()
                .id("wf-1").name("Test WF").project(project).build();
        when(workflowRepository.findAll()).thenReturn(List.of(workflow));

        testingService.reportTestResult("Test WF", "ProjectB", "PASSED");

        verify(workflowRepository, never()).save(any());
    }

    @Test
    void reportTestResult_updatesCorrectWorkflowAmongMultiple() {
        Project projA = Project.builder().id("p-1").name("ProjectA").build();
        Project projB = Project.builder().id("p-2").name("ProjectB").build();
        Workflow wf1 = Workflow.builder().id("wf-1").name("Deploy").project(projA).build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("Deploy").project(projB).build();
        when(workflowRepository.findAll()).thenReturn(List.of(wf1, wf2));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        testingService.reportTestResult("Deploy", "ProjectB", "PASSED");

        assertThat(wf2.getTestStatus()).isEqualTo("PASSED");
        assertThat(wf1.getTestStatus()).isNotEqualTo("PASSED");
        verify(workflowRepository).save(wf2);
    }

    @Test
    void reportTestResult_overwritesPreviousStatus() {
        Workflow workflow = Workflow.builder()
                .id("wf-1").name("Test WF").project(null).testStatus("FAILED").build();
        when(workflowRepository.findAll()).thenReturn(List.of(workflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        testingService.reportTestResult("Test WF", null, "PASSED");

        assertThat(workflow.getTestStatus()).isEqualTo("PASSED");
    }

    // --- deriveTestBaseUrl ---

    @Test
    void deriveTestBaseUrl_extractsBaseFromHealthUrl() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");

        String baseUrl = testingService.deriveTestBaseUrl();

        assertThat(baseUrl).isEqualTo("http://localhost:8090/api/v1");
    }

    @Test
    void deriveTestBaseUrl_fallsBackToDefault_whenNoApiV1InUrl() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/health");

        String baseUrl = testingService.deriveTestBaseUrl();

        assertThat(baseUrl).isEqualTo("http://localhost:8090/api/v1");
    }

    // --- syncDefinitions ---

    @Test
    void syncDefinitions_delegatesToSyncHelper() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        // Make the test spy return true for isHealthy — use a real TestingService with spied methods
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();
        doReturn("test-api-key").when(spied).readContainerApiKey(anyString());

        SyncResult expected = new SyncResult(2, 3, 1, 0, List.of());
        when(instanceSyncHelper.syncDefinitionsTo(eq("http://localhost:8090/api/v1"), eq("test-api-key"), any()))
                .thenReturn(expected);

        SyncResult result = spied.syncDefinitions();

        assertThat(result.projectsSynced()).isEqualTo(2);
        assertThat(result.workflowsSynced()).isEqualTo(3);
        assertThat(result.skillsSynced()).isEqualTo(1);
    }

    @Test
    void syncDefinitions_missingKeyFile_returnsTheClearMessage() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();
        when(dockerContainerManager.readApiKey(anyString(), any())).thenThrow(new com.automation.exception.LocalizedStateException(
                com.automation.exception.ErrorMessage.CONTAINER_API_KEY_MISSING,
                java.util.Map.of("container", "mysuricates-test", "file", "/home/user/.autotrigger/api-key")));

        SyncResult result = spied.syncDefinitions();

        assertThat(result.errors()).singleElement().asString()
                .contains("/home/user/.autotrigger/api-key").contains("test-promote");
        verify(instanceSyncHelper, never()).syncDefinitionsTo(anyString(), anyString(), any());
    }

    /** Test started from Dev gets Dev's key as AUTOMATION_API_KEY and writes no key file — the sync passes it along. */
    @Test
    void syncDefinitions_passesTheOwnKeyAsFallbackForAContainerWithoutKeyFile() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();
        when(apiKeyService.getEffectiveApiKey()).thenReturn("dev-key");
        when(dockerContainerManager.readApiKey("mysuricates-test", "dev-key")).thenReturn("dev-key");

        spied.syncDefinitions();

        verify(instanceSyncHelper).syncDefinitionsTo(eq("http://localhost:8090/api/v1"), eq("dev-key"), any());
    }

    @Test
    void syncDefinitions_throwsIfNotHealthy() {
        TestingService spied = spy(testingService);
        doReturn(false).when(spied).isHealthy();

        assertThatThrownBy(spied::syncDefinitions)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running or not healthy");
    }

    // --- executeWorkflowOnTest ---

    @Test
    void executeWorkflowOnTest_findsAndStartsWorkflow() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        // Workflows list on test instance
        ArrayNode workflows = objectMapper.createArrayNode();
        ObjectNode wf = objectMapper.createObjectNode();
        wf.put("id", "test-wf-1");
        wf.put("name", "My Workflow");
        wf.put("projectName", "Project A");
        workflows.add(wf);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(workflows);

        // Execute response
        ObjectNode execResult = objectMapper.createObjectNode();
        execResult.put("id", "run-123");
        when(instanceHttpClient.httpPost(eq("http://localhost:8090/api/v1/workflows/test-wf-1/execute"), any()))
                .thenReturn(execResult);

        String runId = spied.executeWorkflowOnTest("My Workflow", "Project A", Map.of("key", "val"));

        assertThat(runId).isEqualTo("run-123");
    }

    @Test
    void executeWorkflowOnTest_workflowNotFound_throws() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        ArrayNode workflows = objectMapper.createArrayNode();
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(workflows);

        assertThatThrownBy(() -> spied.executeWorkflowOnTest("Missing", "Project", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found on Test instance");
    }

    @Test
    void executeWorkflowOnTest_notHealthy_throws() {
        TestingService spied = spy(testingService);
        doReturn(false).when(spied).isHealthy();

        assertThatThrownBy(() -> spied.executeWorkflowOnTest("WF", "Proj", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running or not healthy");
    }

    // --- pollTestRun ---

    @Test
    void pollTestRun_completedImmediately_returnsRun() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");

        ObjectNode completedRun = objectMapper.createObjectNode();
        completedRun.put("status", "COMPLETED");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/executions/run-1"))
                .thenReturn(completedRun);

        JsonNode result = testingService.pollTestRun("run-1", 10, 1000);

        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void pollTestRun_failedStatus_returnsRun() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");

        ObjectNode failedRun = objectMapper.createObjectNode();
        failedRun.put("status", "FAILED");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/executions/run-1"))
                .thenReturn(failedRun);

        JsonNode result = testingService.pollTestRun("run-1", 10, 1000);

        assertThat(result.path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void pollTestRun_cancelledStatus_returnsRun() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");

        ObjectNode cancelledRun = objectMapper.createObjectNode();
        cancelledRun.put("status", "CANCELLED");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/executions/run-1"))
                .thenReturn(cancelledRun);

        JsonNode result = testingService.pollTestRun("run-1", 10, 1000);

        assertThat(result.path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void pollTestRun_timeout_throwsException() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");

        ObjectNode runningRun = objectMapper.createObjectNode();
        runningRun.put("status", "RUNNING");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/executions/run-1"))
                .thenReturn(runningRun);

        assertThatThrownBy(() -> testingService.pollTestRun("run-1", 1, 1000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not complete within");
    }

    @Test
    void pollTestRun_subSecondInterval_doesNotInfiniteLoop() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");

        ObjectNode runningRun = objectMapper.createObjectNode();
        runningRun.put("status", "RUNNING");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/executions/run-1"))
                .thenReturn(runningRun);

        // With intervalMs=200, maxWait=1 — must timeout, NOT infinite loop
        assertThatThrownBy(() -> testingService.pollTestRun("run-1", 1, 200))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not complete within");
    }

    @Test
    void pollTestRun_becomesCompletedAfterPolling_returns() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");

        ObjectNode runningRun = objectMapper.createObjectNode();
        runningRun.put("status", "RUNNING");
        ObjectNode completedRun = objectMapper.createObjectNode();
        completedRun.put("status", "COMPLETED");

        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/executions/run-1"))
                .thenReturn(runningRun)
                .thenReturn(runningRun)
                .thenReturn(completedRun);

        JsonNode result = testingService.pollTestRun("run-1", 10, 1000);

        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
    }

    // --- pullChanges ---

    @Test
    void pullChanges_updatesInstructionsAndModel() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        // Dev workflow
        Workflow devWorkflow = Workflow.builder()
                .id("wf-1").name("Test WF").project(null)
                .instructions("old instructions").model("claude-sonnet-4-6").build();
        when(workflowRepository.findAll()).thenReturn(List.of(devWorkflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Test workflow list
        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "test-wf-1");
        tw.put("name", "Test WF");
        tw.put("projectName", "");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        // Full test workflow details
        ObjectNode fullTw = objectMapper.createObjectNode();
        fullTw.put("instructions", "new instructions from test");
        fullTw.put("model", "claude-opus-4-6");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/test-wf-1")).thenReturn(fullTw);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(1);
        assertThat(devWorkflow.getInstructions()).isEqualTo("new instructions from test");
        assertThat(devWorkflow.getModel()).isEqualTo("claude-opus-4-6");
    }

    @Test
    void pullChanges_noChanges_doesNotSave() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Workflow devWorkflow = Workflow.builder()
                .id("wf-1").name("Test WF").project(null)
                .instructions("same").model("claude-sonnet-4-6").build();
        when(workflowRepository.findAll()).thenReturn(List.of(devWorkflow));

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "test-wf-1");
        tw.put("name", "Test WF");
        tw.put("projectName", "");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        ObjectNode fullTw = objectMapper.createObjectNode();
        fullTw.put("instructions", "same");
        fullTw.put("model", "claude-sonnet-4-6");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/test-wf-1")).thenReturn(fullTw);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(0);
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void pullChanges_notHealthy_throws() {
        TestingService spied = spy(testingService);
        doReturn(false).when(spied).isHealthy();

        assertThatThrownBy(spied::pullChanges)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running or not healthy");
    }

    // --- getStatus ---

    @Test
    void getStatus_notRunning_returnsNotRunningNotHealthy() {
        TestingService spied = spy(testingService);
        doReturn(false).when(spied).isContainerRunning();

        var status = spied.getStatus();

        assertThat(status.containerRunning()).isFalse();
        assertThat(status.healthy()).isFalse();
        assertThat(status.testVersion()).isNull();
    }

    @Test
    void getStatus_runningAndHealthy_includesVersionInfo() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isContainerRunning();
        doReturn(true).when(spied).isHealthy();

        ObjectNode info = objectMapper.createObjectNode();
        info.put("version", "0.3.0");
        info.put("buildTimestamp", "2025-01-15T10:00:00Z");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/system/info")).thenReturn(info);

        var status = spied.getStatus();

        assertThat(status.containerRunning()).isTrue();
        assertThat(status.healthy()).isTrue();
        assertThat(status.testVersion()).isEqualTo("0.3.0");
        assertThat(status.testBuildTimestamp()).isEqualTo("2025-01-15T10:00:00Z");
    }

    // --- pullChanges: project-based matching (mirrors reportTestResult matching logic) ---

    @Test
    void pullChanges_matchesByProjectName() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Project project = Project.builder().id("p-1").name("MyProject").build();
        Workflow devWorkflow = Workflow.builder()
                .id("wf-1").name("Deploy").project(project)
                .instructions("old").model("claude-sonnet-4-6").build();
        when(workflowRepository.findAll()).thenReturn(List.of(devWorkflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "test-wf-1");
        tw.put("name", "Deploy");
        tw.put("projectName", "MyProject");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        ObjectNode fullTw = objectMapper.createObjectNode();
        fullTw.put("instructions", "updated");
        fullTw.put("model", "claude-sonnet-4-6");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/test-wf-1")).thenReturn(fullTw);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(1);
        assertThat(devWorkflow.getInstructions()).isEqualTo("updated");
    }

    @Test
    void pullChanges_wrongProject_doesNotMatch() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Project project = Project.builder().id("p-1").name("ProjectA").build();
        Workflow devWorkflow = Workflow.builder()
                .id("wf-1").name("Deploy").project(project)
                .instructions("old").model("old-model").build();
        when(workflowRepository.findAll()).thenReturn(List.of(devWorkflow));

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "test-wf-1");
        tw.put("name", "Deploy");
        tw.put("projectName", "ProjectB");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(0);
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void pullChanges_onlyInstructionsChanged_updatesAndCounts() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Workflow devWorkflow = Workflow.builder()
                .id("wf-1").name("WF").project(null)
                .instructions("old instructions").model("same-model").build();
        when(workflowRepository.findAll()).thenReturn(List.of(devWorkflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "t-1");
        tw.put("name", "WF");
        tw.put("projectName", "");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        ObjectNode fullTw = objectMapper.createObjectNode();
        fullTw.put("instructions", "new instructions");
        fullTw.put("model", "same-model");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/t-1")).thenReturn(fullTw);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(1);
        assertThat(devWorkflow.getInstructions()).isEqualTo("new instructions");
        assertThat(devWorkflow.getModel()).isEqualTo("same-model");
    }

    @Test
    void pullChanges_onlyModelChanged_updatesAndCounts() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Workflow devWorkflow = Workflow.builder()
                .id("wf-1").name("WF").project(null)
                .instructions("same").model("old-model").build();
        when(workflowRepository.findAll()).thenReturn(List.of(devWorkflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "t-1");
        tw.put("name", "WF");
        tw.put("projectName", "");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        ObjectNode fullTw = objectMapper.createObjectNode();
        fullTw.put("instructions", "same");
        fullTw.put("model", "new-model");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/t-1")).thenReturn(fullTw);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(1);
        assertThat(devWorkflow.getModel()).isEqualTo("new-model");
    }

    @Test
    void pullChanges_fullWorkflowFetchReturnsNull_skipsWithoutError() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Workflow devWorkflow = Workflow.builder()
                .id("wf-1").name("WF").project(null).build();
        when(workflowRepository.findAll()).thenReturn(List.of(devWorkflow));

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "t-1");
        tw.put("name", "WF");
        tw.put("projectName", "");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/t-1")).thenReturn(null);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(0);
        assertThat(result.errors()).isEmpty();
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void pullChanges_multipleWorkflows_countsOnlyChanged() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Workflow wf1 = Workflow.builder().id("wf-1").name("WF-A").project(null)
                .instructions("old-A").model("m1").build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("WF-B").project(null)
                .instructions("same-B").model("m2").build();
        when(workflowRepository.findAll()).thenReturn(List.of(wf1, wf2));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw1 = objectMapper.createObjectNode();
        tw1.put("id", "t-1"); tw1.put("name", "WF-A"); tw1.put("projectName", "");
        ObjectNode tw2 = objectMapper.createObjectNode();
        tw2.put("id", "t-2"); tw2.put("name", "WF-B"); tw2.put("projectName", "");
        testWorkflows.add(tw1);
        testWorkflows.add(tw2);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        ObjectNode full1 = objectMapper.createObjectNode();
        full1.put("instructions", "new-A"); full1.put("model", "m1");
        ObjectNode full2 = objectMapper.createObjectNode();
        full2.put("instructions", "same-B"); full2.put("model", "m2");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/t-1")).thenReturn(full1);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/t-2")).thenReturn(full2);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(1);
        assertThat(wf1.getInstructions()).isEqualTo("new-A");
        assertThat(wf2.getInstructions()).isEqualTo("same-B");
        verify(workflowRepository, times(1)).save(any());
    }

    @Test
    void pullChanges_errorInOneWorkflow_collectsErrorAndContinues() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Workflow wf1 = Workflow.builder().id("wf-1").name("WF-Good").project(null)
                .instructions("old").model("m").build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("WF-Bad").project(null)
                .instructions("old").model("m").build();
        when(workflowRepository.findAll()).thenReturn(List.of(wf1, wf2));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw1 = objectMapper.createObjectNode();
        tw1.put("id", "t-1"); tw1.put("name", "WF-Good"); tw1.put("projectName", "");
        ObjectNode tw2 = objectMapper.createObjectNode();
        tw2.put("id", "t-2"); tw2.put("name", "WF-Bad"); tw2.put("projectName", "");
        testWorkflows.add(tw1);
        testWorkflows.add(tw2);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        ObjectNode full1 = objectMapper.createObjectNode();
        full1.put("instructions", "new"); full1.put("model", "m");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/t-1")).thenReturn(full1);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/t-2"))
                .thenThrow(new RuntimeException("Network error"));

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("WF-Bad");
    }

    @Test
    void pullChanges_testWorkflowNotInDev_skippedSilently() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        when(workflowRepository.findAll()).thenReturn(List.of());

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "t-1"); tw.put("name", "OnlyOnTest"); tw.put("projectName", "");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(0);
        assertThat(result.errors()).isEmpty();
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void pullChanges_nullWorkflowList_returnsError() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(null);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(0);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("Could not fetch workflows");
    }

    @Test
    void pullChanges_nullInstructions_handledCorrectly() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Workflow devWorkflow = Workflow.builder()
                .id("wf-1").name("WF").project(null)
                .instructions("has instructions").model(null).build();
        when(workflowRepository.findAll()).thenReturn(List.of(devWorkflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "t-1"); tw.put("name", "WF"); tw.put("projectName", "");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        // Test workflow has no instructions field → asText(null) returns null
        ObjectNode fullTw = objectMapper.createObjectNode();
        fullTw.putNull("instructions");
        fullTw.putNull("model");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/t-1")).thenReturn(fullTw);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(1);
        assertThat(devWorkflow.getInstructions()).isNull();
    }

    // --- syncPreview ---

    @Test
    void syncPreview_notHealthy_throws() {
        TestingService spied = spy(testingService);
        doReturn(false).when(spied).isHealthy();

        assertThatThrownBy(spied::syncPreview)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running or not healthy");
    }

    // === Additional tests to secure refactoring targets ===

    // --- pollTestRun: default overload delegates with magic number defaults (600s, 2000ms) ---

    @Test
    void pollTestRun_defaultOverload_completesImmediately() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");

        ObjectNode completedRun = objectMapper.createObjectNode();
        completedRun.put("status", "COMPLETED");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/executions/run-1"))
                .thenReturn(completedRun);

        // Uses the no-arg overload which internally calls pollTestRun(runId, 600, 2000)
        JsonNode result = testingService.pollTestRun("run-1");

        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        verify(instanceHttpClient).httpGet("http://localhost:8090/api/v1/executions/run-1");
    }

    @Test
    void pollTestRun_httpGetReturnsNull_continuesPolling() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");

        ObjectNode completedRun = objectMapper.createObjectNode();
        completedRun.put("status", "COMPLETED");

        // First call returns null, second returns completed
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/executions/run-1"))
                .thenReturn(null)
                .thenReturn(completedRun);

        JsonNode result = testingService.pollTestRun("run-1", 10, 1000);

        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        verify(instanceHttpClient, times(2)).httpGet("http://localhost:8090/api/v1/executions/run-1");
    }

    @Test
    void pollTestRun_unknownStatus_continuesPolling() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");

        ObjectNode pendingRun = objectMapper.createObjectNode();
        pendingRun.put("status", "PENDING");
        ObjectNode completedRun = objectMapper.createObjectNode();
        completedRun.put("status", "COMPLETED");

        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/executions/run-1"))
                .thenReturn(pendingRun)
                .thenReturn(completedRun);

        JsonNode result = testingService.pollTestRun("run-1", 10, 1000);

        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
    }

    // --- getStatus: running but not healthy ---

    @Test
    void getStatus_runningButNotHealthy_returnsRunningNotHealthyNoVersion() {
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isContainerRunning();
        doReturn(false).when(spied).isHealthy();

        var status = spied.getStatus();

        assertThat(status.containerRunning()).isTrue();
        assertThat(status.healthy()).isFalse();
        assertThat(status.testVersion()).isNull();
        assertThat(status.testBuildTimestamp()).isNull();
    }

    @Test
    void getStatus_healthyButInfoFetchThrows_returnsNullVersions() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isContainerRunning();
        doReturn(true).when(spied).isHealthy();

        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/system/info"))
                .thenThrow(new RuntimeException("Connection refused"));

        var status = spied.getStatus();

        assertThat(status.containerRunning()).isTrue();
        assertThat(status.healthy()).isTrue();
        assertThat(status.testVersion()).isNull();
        assertThat(status.testBuildTimestamp()).isNull();
    }

    @Test
    void getStatus_healthyButInfoReturnsNull_returnsNullVersions() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isContainerRunning();
        doReturn(true).when(spied).isHealthy();

        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/system/info")).thenReturn(null);

        var status = spied.getStatus();

        assertThat(status.containerRunning()).isTrue();
        assertThat(status.healthy()).isTrue();
        assertThat(status.testVersion()).isNull();
        assertThat(status.testBuildTimestamp()).isNull();
    }

    // --- Workflow search logic consistency (refactoring 3: duplicated search in reportTestResult + pullChanges + executeWorkflowOnTest) ---

    @Test
    void reportTestResult_emptyProjectName_matchesNullProjectWorkflow() {
        // Ensures the matching logic: project==null → "" equals ""
        Workflow workflow = Workflow.builder()
                .id("wf-1").name("WF").project(null).build();
        when(workflowRepository.findAll()).thenReturn(List.of(workflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        testingService.reportTestResult("WF", "", "PASSED");

        assertThat(workflow.getTestStatus()).isEqualTo("PASSED");
        verify(workflowRepository).save(workflow);
    }

    @Test
    void reportTestResult_firstMatchWins_whenMultipleSameNameNoProject() {
        Workflow wf1 = Workflow.builder().id("wf-1").name("WF").project(null).build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("WF").project(null).build();
        when(workflowRepository.findAll()).thenReturn(List.of(wf1, wf2));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        testingService.reportTestResult("WF", null, "PASSED");

        assertThat(wf1.getTestStatus()).isEqualTo("PASSED");
        assertThat(wf2.getTestStatus()).isNotEqualTo("PASSED");
        verify(workflowRepository, times(1)).save(wf1);
    }

    @Test
    void executeWorkflowOnTest_nullProjectName_matchesEmptyProjectOnTest() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        ArrayNode workflows = objectMapper.createArrayNode();
        ObjectNode wf = objectMapper.createObjectNode();
        wf.put("id", "t-1");
        wf.put("name", "Deploy");
        wf.put("projectName", "");
        workflows.add(wf);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(workflows);

        ObjectNode execResult = objectMapper.createObjectNode();
        execResult.put("id", "run-99");
        when(instanceHttpClient.httpPost(eq("http://localhost:8090/api/v1/workflows/t-1/execute"), any()))
                .thenReturn(execResult);

        String runId = spied.executeWorkflowOnTest("Deploy", null, Map.of());

        assertThat(runId).isEqualTo("run-99");
    }

    @Test
    void executeWorkflowOnTest_nullContext_sendsEmptyBody() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        ArrayNode workflows = objectMapper.createArrayNode();
        ObjectNode wf = objectMapper.createObjectNode();
        wf.put("id", "t-1");
        wf.put("name", "WF");
        wf.put("projectName", "");
        workflows.add(wf);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(workflows);

        ObjectNode execResult = objectMapper.createObjectNode();
        execResult.put("id", "run-42");
        when(instanceHttpClient.httpPost(eq("http://localhost:8090/api/v1/workflows/t-1/execute"), eq(Map.of())))
                .thenReturn(execResult);

        String runId = spied.executeWorkflowOnTest("WF", null, null);

        assertThat(runId).isEqualTo("run-42");
    }

    @Test
    void executeWorkflowOnTest_multipleWorkflows_matchesCorrectByNameAndProject() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        ArrayNode workflows = objectMapper.createArrayNode();
        ObjectNode wf1 = objectMapper.createObjectNode();
        wf1.put("id", "t-1"); wf1.put("name", "Deploy"); wf1.put("projectName", "ProjectA");
        ObjectNode wf2 = objectMapper.createObjectNode();
        wf2.put("id", "t-2"); wf2.put("name", "Deploy"); wf2.put("projectName", "ProjectB");
        workflows.add(wf1);
        workflows.add(wf2);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(workflows);

        ObjectNode execResult = objectMapper.createObjectNode();
        execResult.put("id", "run-B");
        when(instanceHttpClient.httpPost(eq("http://localhost:8090/api/v1/workflows/t-2/execute"), any()))
                .thenReturn(execResult);

        String runId = spied.executeWorkflowOnTest("Deploy", "ProjectB", Map.of());

        assertThat(runId).isEqualTo("run-B");
        verify(instanceHttpClient).httpPost(eq("http://localhost:8090/api/v1/workflows/t-2/execute"), any());
        verify(instanceHttpClient, never()).httpPost(eq("http://localhost:8090/api/v1/workflows/t-1/execute"), any());
    }

    @Test
    void executeWorkflowOnTest_nullWorkflowList_throws() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(null);

        assertThatThrownBy(() -> spied.executeWorkflowOnTest("WF", null, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not fetch workflows");
    }

    @Test
    void executeWorkflowOnTest_executeReturnsNull_throws() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        ArrayNode workflows = objectMapper.createArrayNode();
        ObjectNode wf = objectMapper.createObjectNode();
        wf.put("id", "t-1"); wf.put("name", "WF"); wf.put("projectName", "");
        workflows.add(wf);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(workflows);
        when(instanceHttpClient.httpPost(anyString(), any())).thenReturn(null);

        assertThatThrownBy(() -> spied.executeWorkflowOnTest("WF", null, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to start workflow");
    }

    // --- pullChanges: null project on dev side matches empty string on test side ---

    @Test
    void pullChanges_devWorkflowNullProject_matchesEmptyProjectNameOnTest() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Workflow devWorkflow = Workflow.builder()
                .id("wf-1").name("WF").project(null)
                .instructions("old").model("m").build();
        when(workflowRepository.findAll()).thenReturn(List.of(devWorkflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "t-1"); tw.put("name", "WF"); tw.put("projectName", "");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        ObjectNode fullTw = objectMapper.createObjectNode();
        fullTw.put("instructions", "updated"); fullTw.put("model", "m");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/t-1")).thenReturn(fullTw);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(1);
        assertThat(devWorkflow.getInstructions()).isEqualTo("updated");
    }

    @Test
    void pullChanges_sameNameDifferentProjects_updatesCorrectOne() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();

        Project projA = Project.builder().id("p-1").name("Alpha").build();
        Project projB = Project.builder().id("p-2").name("Beta").build();
        Workflow wfA = Workflow.builder().id("wf-1").name("Deploy").project(projA)
                .instructions("old-A").model("m").build();
        Workflow wfB = Workflow.builder().id("wf-2").name("Deploy").project(projB)
                .instructions("old-B").model("m").build();
        when(workflowRepository.findAll()).thenReturn(List.of(wfA, wfB));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Only test has the Beta project workflow with changes
        ArrayNode testWorkflows = objectMapper.createArrayNode();
        ObjectNode tw = objectMapper.createObjectNode();
        tw.put("id", "t-1"); tw.put("name", "Deploy"); tw.put("projectName", "Beta");
        testWorkflows.add(tw);
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows")).thenReturn(testWorkflows);

        ObjectNode fullTw = objectMapper.createObjectNode();
        fullTw.put("instructions", "new-Beta"); fullTw.put("model", "m");
        when(instanceHttpClient.httpGet("http://localhost:8090/api/v1/workflows/t-1")).thenReturn(fullTw);

        SyncResult result = spied.pullChanges();

        assertThat(result.workflowsSynced()).isEqualTo(1);
        assertThat(wfB.getInstructions()).isEqualTo("new-Beta");
        assertThat(wfA.getInstructions()).isEqualTo("old-A"); // unchanged
        verify(workflowRepository, times(1)).save(wfB);
    }

    // --- deriveTestBaseUrl: edge case for magic URL construction ---

    @Test
    void deriveTestBaseUrl_customPort_extractsCorrectly() {
        stagingConfig.setTestHealthUrl("http://myhost:9999/api/v1/system/health");

        String baseUrl = testingService.deriveTestBaseUrl();

        assertThat(baseUrl).isEqualTo("http://myhost:9999/api/v1");
    }

    // --- syncDefinitions with request parameter ---

    @Test
    void syncDefinitions_withRequest_delegatesRequestToSyncHelper() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();
        doReturn("test-api-key").when(spied).readContainerApiKey(anyString());

        SyncDefinitionsRequest request = new SyncDefinitionsRequest(List.of("p-1"), List.of("wf-1"), List.of());
        SyncResult expected = new SyncResult(1, 2, 0, 0, List.of());
        when(instanceSyncHelper.syncDefinitionsTo(eq("http://localhost:8090/api/v1"), eq("test-api-key"), eq(request)))
                .thenReturn(expected);

        SyncResult result = spied.syncDefinitions(request);

        assertThat(result.projectsSynced()).isEqualTo(1);
        assertThat(result.workflowsSynced()).isEqualTo(2);
        verify(instanceSyncHelper).syncDefinitionsTo("http://localhost:8090/api/v1", "test-api-key", request);
    }

    @Test
    void syncDefinitions_noArgOverload_passesNullRequest() {
        stagingConfig.setTestHealthUrl("http://localhost:8090/api/v1/system/health");
        TestingService spied = spy(testingService);
        doReturn(true).when(spied).isHealthy();
        doReturn("test-api-key").when(spied).readContainerApiKey(anyString());

        SyncResult expected = new SyncResult(0, 0, 0, 0, List.of());
        when(instanceSyncHelper.syncDefinitionsTo(eq("http://localhost:8090/api/v1"), eq("test-api-key"), isNull()))
                .thenReturn(expected);

        SyncResult result = spied.syncDefinitions();

        verify(instanceSyncHelper).syncDefinitionsTo("http://localhost:8090/api/v1", "test-api-key", null);
    }
}

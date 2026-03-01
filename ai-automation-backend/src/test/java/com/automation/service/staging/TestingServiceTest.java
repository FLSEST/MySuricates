package com.automation.service.staging;

import com.automation.config.StagingConfig;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TestingServiceTest {

    @Spy
    private StagingConfig stagingConfig = new StagingConfig();

    @Mock
    private InstanceSyncHelper instanceSyncHelper;

    @Mock
    private WorkflowRepository workflowRepository;

    @InjectMocks
    private TestingService testingService;

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
}

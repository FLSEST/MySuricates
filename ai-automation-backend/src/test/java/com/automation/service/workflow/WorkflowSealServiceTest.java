package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSealServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private ActivityLogService activityLog;

    private final WorkflowDefinitionHasher hasher = new WorkflowDefinitionHasher();
    private WorkflowSealService service;
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        service = new WorkflowSealService(workflowRepository, hasher, activityLog);
        workflow = Workflow.builder().id("wf-1").name("Deploy").blocks(new ArrayList<>()).instructions("go")
                .activatedSkills(new ArrayList<>()).build();
    }

    @Test
    void sealPinsTheCurrentHashAndLogsASecurityEvent() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        Workflow sealed = service.seal("wf-1");

        assertThat(sealed.isSealed()).isTrue();
        assertThat(sealed.getSealedHash()).isEqualTo(hasher.hash(workflow));
        assertThat(sealed.getSealedAt()).isNotNull();
        verify(activityLog).log(LogLevel.WARN, LogCategory.SECURITY, "Workflow sealed: Deploy", "WorkflowSealService",
                "wf-1", Map.of("sealedHash", sealed.getSealedHash()), "wf-1");
    }

    @Test
    void sealingTwiceKeepsTheOriginalSeal() {
        workflow.setSealed(true);
        workflow.setSealedHash("original");
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));

        assertThat(service.seal("wf-1").getSealedHash()).isEqualTo("original");
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void unsealClearsTheSealAndLogsThePreviousHash() {
        workflow.setSealed(true);
        workflow.setSealedHash("abc");
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        Workflow unsealed = service.unseal("wf-1");

        assertThat(unsealed.isSealed()).isFalse();
        assertThat(unsealed.getSealedHash()).isNull();
        verify(activityLog).log(eq(LogLevel.WARN), eq(LogCategory.SECURITY), anyString(), anyString(), eq("wf-1"),
                eq(Map.of("previousSealedHash", "abc")), eq("wf-1"));
    }

    @Test
    void unknownWorkflowIsNotFound() {
        when(workflowRepository.findByIdWithSkills("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.seal("nope")).isInstanceOf(ResourceNotFoundException.class);
    }
}

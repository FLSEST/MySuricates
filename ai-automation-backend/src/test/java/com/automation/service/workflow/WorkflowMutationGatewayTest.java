package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowVersion;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowMutationGatewayTest {

    @Mock private WorkflowVersionService versions;
    @Mock private ActivityLogService activityLog;

    private final WorkflowDefinitionHasher hasher = new WorkflowDefinitionHasher();
    private WorkflowMutationGateway gateway;
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        gateway = new WorkflowMutationGateway(versions, hasher, activityLog);
        workflow = Workflow.builder().id("wf-1").name("Deploy")
                .blocks(new ArrayList<>(List.of(BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of("command", "make"))).build())))
                .instructions("old").activatedSkills(new ArrayList<>())
                .build();
    }

    @Test
    void createWritesTheFirstVersionWithItsSource() {
        when(versions.saveVersion(eq("wf-1"), any(), anyString(), eq(ChangeSource.FACTORY), any())).thenReturn(1);

        gateway.record(workflow, null, ChangeSource.FACTORY, null);

        verify(versions).saveVersion("wf-1", WorkflowSnapshot.from(workflow), hasher.hash(workflow), ChangeSource.FACTORY, null);
        verify(versions, never()).findLatest(any());
        Map<String, Object> meta = loggedMetadata();
        assertThat(meta).containsEntry("changeSource", "factory").containsEntry("versionNumber", 1)
                .containsEntry("newHash", hasher.hash(workflow)).doesNotContainKey("previousHash");
    }

    @Test
    void labelOnlyChangesWriteNothing() {
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        workflow.setName("Renamed");

        gateway.record(workflow, before, ChangeSource.USER, null);

        verifyNoInteractions(versions, activityLog);
    }

    @Test
    void definitionChangeWritesPostStateWhenPreStateIsKnown() {
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        String beforeHash = hasher.hash(before);
        when(versions.findLatest("wf-1")).thenReturn(Optional.of(WorkflowVersion.builder().definitionHash(beforeHash).build()));
        workflow.setInstructions("new");
        when(versions.saveVersion(eq("wf-1"), any(), anyString(), eq(ChangeSource.ASSISTANT), eq("tool call"))).thenReturn(5);

        gateway.record(workflow, before, ChangeSource.ASSISTANT, "tool call");

        verify(versions, never()).saveVersion(any(), any(), any(), eq(ChangeSource.UNTRACKED), any());
        verify(versions).saveVersion("wf-1", WorkflowSnapshot.from(workflow), hasher.hash(workflow), ChangeSource.ASSISTANT, "tool call");
        Map<String, Object> meta = loggedMetadata();
        assertThat(meta).containsEntry("previousHash", beforeHash).containsEntry("versionNumber", 5)
                .containsEntry("changedFields", List.of("instructions"));
    }

    @Test
    void driftBeforeTheChangeIsRecordedAsUntrackedFirst() {
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        // newest row describes some older state — e.g. a raw DB edit or a legacy pre-state row
        when(versions.findLatest("wf-1")).thenReturn(Optional.of(WorkflowVersion.builder().definitionHash("something-else").build()));
        workflow.setModel("opus");

        gateway.record(workflow, before, ChangeSource.USER, null);

        InOrder order = inOrder(versions);
        order.verify(versions).saveVersion("wf-1", before, hasher.hash(before), ChangeSource.UNTRACKED,
                "State before this change had no version");
        order.verify(versions).saveVersion(eq("wf-1"), eq(WorkflowSnapshot.from(workflow)), anyString(), eq(ChangeSource.USER), any());
    }

    @Test
    void firstTrackedChangeOfAWorkflowWithoutVersionsAlsoKeepsThePreState() {
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        when(versions.findLatest("wf-1")).thenReturn(Optional.empty());
        workflow.setEffort("high");

        gateway.record(workflow, before, ChangeSource.USER, null);

        verify(versions).saveVersion(eq("wf-1"), eq(before), anyString(), eq(ChangeSource.UNTRACKED), any());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loggedMetadata() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(activityLog).log(eq(LogLevel.INFO), eq(LogCategory.SYSTEM), anyString(), eq("WorkflowMutationGateway"),
                eq("wf-1"), captor.capture(), eq("wf-1"));
        return captor.getValue();
    }
}

package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowVersion;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.BlockTypeRegistry;
import com.automation.executor.ExecutionContext;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private final BlockTypeValidator blockTypes = new BlockTypeValidator(new BlockTypeRegistry(List.of(
            executorFor("shell"), executorFor("simple"), executorFor("switch"))));
    private WorkflowMutationGateway gateway;
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        gateway = new WorkflowMutationGateway(versions, hasher, activityLog, blockTypes);
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

    /**
     * The version label ({@code 4.4.0} from the pool) is a label like the name: no version row, the
     * definition hash stays, and a sealed workflow accepts it (TODO a3b89355).
     */
    @Test
    void versionLabelIsNoDefinitionChange_evenWhenSealed() {
        workflow.setSealed(true);
        workflow.setVersion("3.0.0");
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        String hashBefore = hasher.hash(workflow);
        workflow.setVersion("4.4.0");

        gateway.record(workflow, before, ChangeSource.POOL_INSTALL, "Pool install: health");

        verifyNoInteractions(versions, activityLog);
        assertThat(hasher.hash(workflow)).isEqualTo(hashBefore);
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

    @Test
    void sealedWorkflowRejectsDefinitionChangesButAllowsLabels() {
        workflow.setSealed(true);
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        workflow.setName("Renamed");
        gateway.record(workflow, before, ChangeSource.USER, null); // label only: fine

        workflow.setInstructions("sneaky");
        assertThatThrownBy(() -> gateway.record(workflow, before, ChangeSource.ASSISTANT, null))
                .isInstanceOf(HardeningException.class)
                .hasMessageContaining("sealed").hasMessageContaining("instructions")
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.WORKFLOW_SEALED))
                .satisfies(e -> assertThat(((HardeningException) e).getParams()).containsOnlyKeys("name"));
        verifyNoInteractions(versions);
    }

    @Test
    void unknownBlockTypeIsRefusedOnCreate_namingTypeAndAllowedTypes() {
        workflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("frobnicate").name("weird").config(new HashMap<>()).build())));

        assertThatThrownBy(() -> gateway.record(workflow, null, ChangeSource.USER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frobnicate")
                .hasMessageContaining("weird")
                .hasMessageContaining("shell, simple, switch");
        verifyNoInteractions(versions, activityLog);
    }

    @Test
    void unknownBlockTypeIsRefusedOnUpdate() {
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        workflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("shel").config(new HashMap<>()).build())));

        assertThatThrownBy(() -> gateway.record(workflow, before, ChangeSource.POOL_INSTALL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shel");
        verifyNoInteractions(versions, activityLog);
    }

    @Test
    void unknownBlockTypeNestedInASwitchCaseIsRefused() {
        Map<String, Object> config = new HashMap<>(Map.of("cases", List.of(Map.of(
                "value", "a", "blocks", List.of(Map.of("type", "document", "name", "nested"))))));
        workflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("switch").name("route").config(config).build())));

        assertThatThrownBy(() -> gateway.record(workflow, null, ChangeSource.IMPORT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document").hasMessageContaining("nested");
        verifyNoInteractions(versions, activityLog);
    }

    /** Restoring an old version goes through the gateway too: a definition we can no longer run stays gone. */
    @Test
    void restoringAVersionWithAnUnknownTypeIsRefused() {
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        workflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("frobnicate").name("legacy").config(new HashMap<>()).build())));

        assertThatThrownBy(() -> gateway.record(workflow, before, ChangeSource.RESTORE, "restore v3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frobnicate");
        verifyNoInteractions(versions, activityLog);
    }

    @Test
    void blockWithoutATypeIsRefused() {
        workflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().name("typeless").config(new HashMap<>()).build())));

        assertThatThrownBy(() -> gateway.record(workflow, null, ChangeSource.USER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typeless");
        verifyNoInteractions(versions, activityLog);
    }

    @Test
    void labelOnlyChangeOfALegacyWorkflowWithUnknownTypeStillPasses() {
        workflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("frobnicate").config(new HashMap<>()).build())));
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        workflow.setName("Renamed");

        gateway.record(workflow, before, ChangeSource.USER, null);

        verifyNoInteractions(versions, activityLog);
    }

    @Test
    void requireUnsealedBlocksDeleteOfSealedWorkflows() {
        gateway.requireUnsealed(workflow, "deleting it"); // unsealed: no-op
        workflow.setSealed(true);

        assertThatThrownBy(() -> gateway.requireUnsealed(workflow, "deleting it"))
                .isInstanceOf(HardeningException.class).hasMessageContaining("deleting it");
    }

    private static BlockExecutor executorFor(String type) {
        return new BlockExecutor() {
            @Override
            public com.automation.entity.StepResult execute(BlockDefinition block, ExecutionContext context) {
                return null;
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of(type);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loggedMetadata() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(activityLog).log(eq(LogLevel.INFO), eq(LogCategory.SYSTEM), anyString(), eq("WorkflowMutationGateway"),
                eq("wf-1"), captor.capture(), eq("wf-1"));
        return captor.getValue();
    }
}

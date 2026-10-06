package com.automation.service.audit;

import com.automation.dto.projection.ChainRunOriginRow;
import com.automation.dto.projection.RunOriginRow;
import com.automation.entity.TriggerSource;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunProjectionRepository;
import com.automation.repository.WorkflowVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Origin of the audit reports, directly at the block (TODO b37a6646). The cases via the report page
 * are in {@link AuditCenterServiceTest}; here the rules that do not appear there: which runs
 * do not inherit the trigger of their chain and how the queries are bundled.
 */
@ExtendWith(MockitoExtension.class)
class AuditReportOriginsTest {

    @Mock private WorkflowRunProjectionRepository runProjections;
    @Mock private ChainRunRepository chainRunRepository;
    @Mock private WorkflowVersionRepository versionRepository;

    private AuditReportOrigins origins;

    @BeforeEach
    void setUp() {
        origins = new AuditReportOrigins(runProjections, chainRunRepository, versionRepository);
    }

    /** Duplicate and missing run IDs of a page result in exactly one query; a deleted run is missing from the map. */
    @Test
    void byRunId_asksOnceWithDistinctIds_andLeavesAGoneRunOut() {
        when(runProjections.findOriginRows(List.of("run-1", "run-gone"))).thenReturn(List.of(
                new RunOriginRow("run-1", TriggerSource.MANUAL, null, null, null, null)));

        Map<String, AuditReportOrigins.Origin> result =
                origins.byRunId(Arrays.asList("run-1", null, "run-gone", "run-1"));

        assertThat(result).containsOnlyKeys("run-1");
        assertThat(result.get("run-1").triggerSource()).isEqualTo(TriggerSource.MANUAL);
        verify(runProjections).findOriginRows(any());
        verifyNoMoreInteractions(runProjections);
        verifyNoInteractions(chainRunRepository, versionRepository);
    }

    @Test
    void byRunId_withoutAnyRunId_asksNothing() {
        assertThat(origins.byRunId(Arrays.asList(null, null))).isEmpty();
        assertThat(origins.byRunId(List.of())).isEmpty();
        verifyNoInteractions(runProjections, chainRunRepository, versionRepository);
    }

    /**
     * A resumed chain entry keeps the chain's trigger in its context, but was resumed by a
     * person: RESUME, without a chain (the column is only set by a CHAIN start). The version number
     * comes from the native query as an arbitrary number.
     */
    @Test
    void resumedChainEntry_saysResume_notTheChainStarter() {
        when(runProjections.findOriginRows(List.of("run-1"))).thenReturn(List.of(
                new RunOriginRow("run-1", TriggerSource.RESUME, null, null, "v-1", "SCHEDULE")));
        when(versionRepository.findVersionNumbers(Set.of("v-1"))).thenReturn(List.<Object[]>of(new Object[]{"v-1", 3L}));

        AuditReportOrigins.Origin origin = origins.byRunId(List.of("run-1")).get("run-1");

        assertThat(origin).isEqualTo(new AuditReportOrigins.Origin(TriggerSource.RESUME, null, null, null, null, 3));
        verifyNoInteractions(chainRunRepository);
    }

    /** Without a reference to the parent run its trigger would be read as its own: SUB_WORKFLOW stays SUB_WORKFLOW. */
    @Test
    void subWorkflowRun_staysSubWorkflow_evenWithAReadableParent() {
        when(runProjections.findOriginRows(List.of("run-1"))).thenReturn(List.of(
                new RunOriginRow("run-1", TriggerSource.SUB_WORKFLOW, null, null, null, "MANUAL")));

        AuditReportOrigins.Origin origin = origins.byRunId(List.of("run-1")).get("run-1");

        assertThat(origin).isEqualTo(new AuditReportOrigins.Origin(TriggerSource.SUB_WORKFLOW, null, null, null, null, null));
        verifyNoInteractions(chainRunRepository, versionRepository);
    }

    /** A directly scheduled run names its own schedule, a chain entry that of the chain. */
    @Test
    void scheduleId_isTheRunsOwn_orForAChainEntryTheChains() {
        when(runProjections.findOriginRows(List.of("run-direct", "run-chain"))).thenReturn(List.of(
                new RunOriginRow("run-direct", TriggerSource.SCHEDULE, "sched-direct", null, null, null),
                new RunOriginRow("run-chain", TriggerSource.CHAIN, "sched-ignored", "cr-1", null, "SCHEDULE")));
        when(chainRunRepository.findOriginRows(Set.of("cr-1")))
                .thenReturn(List.of(new ChainRunOriginRow("cr-1", "chain-1", "Audit-Runde", "sched-chain")));

        Map<String, AuditReportOrigins.Origin> result = origins.byRunId(List.of("run-direct", "run-chain"));

        assertThat(result.get("run-direct").scheduleId()).isEqualTo("sched-direct");
        assertThat(result.get("run-chain")).isEqualTo(new AuditReportOrigins.Origin(
                TriggerSource.SCHEDULE, "sched-chain", "cr-1", "chain-1", "Audit-Runde", null));
    }
}

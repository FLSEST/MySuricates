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
 * Herkunft der Audit-Reports, direkt am Baustein (TODO b37a6646). Die Faelle ueber die Report-Seite
 * stehen in {@link AuditCenterServiceTest}; hier die Regeln, die dort nicht vorkommen: welche Laeufe
 * den Ausloeser ihrer Kette nicht erben und wie die Abfragen gebuendelt werden.
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

    /** Doppelte und fehlende Run-IDs einer Seite ergeben genau eine Abfrage; ein geloeschter Lauf fehlt in der Map. */
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
     * Ein fortgesetzter Ketteneintrag behaelt im Kontext den Ausloeser der Kette, wurde aber von einer
     * Person fortgesetzt: RESUME, ohne Kette (die Spalte setzt nur ein CHAIN-Start). Die Versionsnummer
     * kommt aus der nativen Abfrage als beliebige Zahl.
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

    /** Ohne Verweis auf den Eltern-Lauf wuerde dessen Ausloeser als eigener gelesen: SUB_WORKFLOW bleibt SUB_WORKFLOW. */
    @Test
    void subWorkflowRun_staysSubWorkflow_evenWithAReadableParent() {
        when(runProjections.findOriginRows(List.of("run-1"))).thenReturn(List.of(
                new RunOriginRow("run-1", TriggerSource.SUB_WORKFLOW, null, null, null, "MANUAL")));

        AuditReportOrigins.Origin origin = origins.byRunId(List.of("run-1")).get("run-1");

        assertThat(origin).isEqualTo(new AuditReportOrigins.Origin(TriggerSource.SUB_WORKFLOW, null, null, null, null, null));
        verifyNoInteractions(chainRunRepository, versionRepository);
    }

    /** Ein direkt geplanter Lauf nennt seinen eigenen Zeitplan, ein Ketteneintrag den der Kette. */
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

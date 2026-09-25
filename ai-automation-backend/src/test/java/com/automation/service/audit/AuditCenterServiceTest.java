package com.automation.service.audit;

import com.automation.dto.projection.AuditFindingCountRow;
import com.automation.dto.projection.AuditReportSummaryRow;
import com.automation.dto.response.PageResponse;
import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.GateStatus;
import com.automation.entity.TriggerSource;
import com.automation.dto.projection.ChainRunOriginRow;
import com.automation.dto.projection.RunOriginRow;
import com.automation.entity.Todo;
import com.automation.event.TodoCreatedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.audit.AuditCenterService.AuditDetail;
import com.automation.service.audit.AuditCenterService.AuditFindingResponse;
import com.automation.service.audit.AuditCenterService.AuditReportResponse;
import com.automation.service.audit.AuditCenterService.AuditSummary;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.repository.WorkflowVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Das Audit Center: Uebersicht, Historie, Findings und ihre TODO-Verknuepfungen. Loesen einer
 * Verknuepfung laesst keinen Verweis zurueck.
 */
@ExtendWith(MockitoExtension.class)
class AuditCenterServiceTest {

    private static final String FINDING_ID = "f-1";
    private static final String TODO_ID = "t-1";

    @Mock private AuditReportRepository reportRepository;
    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingOccurrenceRepository occurrenceRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ChainRunRepository chainRunRepository;
    @Mock private WorkflowVersionRepository versionRepository;

    private AuditCenterService service;

    @BeforeEach
    void setUp() {
        ManagedTodoWriter managedTodoWriter =
                new ManagedTodoWriter(findingRepository, findingTodoRepository, todoRepository, eventPublisher);
        service = new AuditCenterService(reportRepository, findingRepository, occurrenceRepository,
                new AuditAcceptancePolicy(),
                new ManagedTodoReconciler(findingRepository, findingTodoRepository, todoRepository),
                new AuditFindingTodoLinker(findingRepository, findingTodoRepository, todoRepository, managedTodoWriter),
                new AuditReportOrigins(runRepository, chainRunRepository, versionRepository));
    }

    @Test
    void unlinkTodo_clearsTheReferenceBackToTheFinding() {
        AuditFinding finding = finding();
        Todo todo = Todo.builder().id(TODO_ID).title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId("p-1")
                .referenceType("audit_finding").referenceId(FINDING_ID).build();
        AuditFindingTodo link = AuditFindingTodo.builder().findingId(FINDING_ID).todoId(TODO_ID).managed(false).build();
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding));
        when(findingTodoRepository.findByFindingIdAndTodoId(FINDING_ID, TODO_ID)).thenReturn(Optional.of(link));
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        service.unlinkTodo(FINDING_ID, TODO_ID);

        verify(findingTodoRepository).delete(link);
        assertThat(todo.getReferenceId()).as("Deep-Link /reports?finding=… darf nicht stehen bleiben").isNull();
        assertThat(todo.getReferenceType()).isNull();
        verify(todoRepository).save(todo);
    }

    @Test
    void unlinkTodo_leavesAReferenceToAnotherFindingAlone() {
        AuditFinding finding = finding();
        Todo todo = Todo.builder().id(TODO_ID).title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId("p-1")
                .referenceType("audit_finding").referenceId("f-other").build();
        AuditFindingTodo link = AuditFindingTodo.builder().findingId(FINDING_ID).todoId(TODO_ID).managed(false).build();
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding));
        when(findingTodoRepository.findByFindingIdAndTodoId(FINDING_ID, TODO_ID)).thenReturn(Optional.of(link));
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        service.unlinkTodo(FINDING_ID, TODO_ID);

        assertThat(todo.getReferenceId()).isEqualTo("f-other");
    }

    // --- listAudits ---

    @Test
    void listAudits_summarisesTheLatestValidReportPerAudit_withTrendAndCounts() {
        Instant now = Instant.now();
        AuditReport invalidNewest = report("r-3", "p-1", "a.health", GateStatus.RED, AuditIngestionStatus.INVALID, now);
        AuditReport latest = report("r-2", "p-1", "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID, now.minusSeconds(60));
        AuditReport previous = report("r-1", "p-1", "a.health", GateStatus.YELLOW, AuditIngestionStatus.VALID, now.minusSeconds(120));
        invalidNewest.setIngestionError("kein Envelope");
        when(reportRepository.findAllSummaryRows()).thenReturn(rows(invalidNewest, latest, previous));
        when(findingRepository.countByScopeStatusAndSeverity()).thenReturn(List.of(
                new AuditFindingCountRow("p-1", "a.health", AuditFindingStatus.OPEN, AuditSeverity.HIGH, 1),
                new AuditFindingCountRow("p-1", "a.health", AuditFindingStatus.RESOLVED, AuditSeverity.LOW, 1),
                new AuditFindingCountRow("p-2", "a.health", AuditFindingStatus.OPEN, AuditSeverity.LOW, 7)));

        List<AuditSummary> result = service.listAudits(null, null, null);

        assertThat(result).hasSize(1);
        AuditSummary summary = result.getFirst();
        assertThat(summary.latestReportId()).isEqualTo("r-2");
        assertThat(summary.gateStatus()).isEqualTo(GateStatus.GREEN);
        assertThat(summary.trend()).isEqualTo("IMPROVED");
        assertThat(summary.latestAttemptStatus()).as("der letzte Versuch war ungueltig").isEqualTo(AuditIngestionStatus.INVALID);
        assertThat(summary.latestAttemptError()).isEqualTo("kein Envelope");
        assertThat(summary.stale()).isFalse();
        assertThat(summary.findingsByStatus()).containsExactlyInAnyOrderEntriesOf(Map.of("OPEN", 1L, "RESOLVED", 1L));
        assertThat(summary.openFindingsBySeverity()).as("nur nicht-terminale Findings")
                .containsExactlyInAnyOrderEntriesOf(Map.of("HIGH", 1L));
    }

    @Test
    void listAudits_skipsAuditsWithoutValidReport_andFiltersByProjectCategoryAndGate() {
        Instant now = Instant.now();
        AuditReport onlyInvalid = report("r-x", "p-1", "a.broken", null, AuditIngestionStatus.INVALID, now);
        AuditReport otherProject = report("r-o", "p-2", "a.health", GateStatus.RED, AuditIngestionStatus.VALID, now);
        AuditReport red = report("r-r", "p-1", "a.security", GateStatus.RED, AuditIngestionStatus.VALID, now);
        AuditReport green = report("r-g", "p-1", "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID, now);
        AuditReport otherCategory = report("r-c", "p-1", "a.docs", GateStatus.RED, AuditIngestionStatus.VALID, now);
        otherCategory.setCategory("docs");
        when(reportRepository.findAllSummaryRows())
                .thenReturn(rows(onlyInvalid, otherProject, red, green, otherCategory));
        when(findingRepository.countByScopeStatusAndSeverity()).thenReturn(List.of());

        List<AuditSummary> result = service.listAudits("p-1", "health", GateStatus.RED);

        assertThat(result).extracting(AuditSummary::auditKey).containsExactly("a.security");
        assertThat(result.getFirst().trend()).as("ohne Vorgaenger").isEqualTo("NEW");
    }

    @Test
    void listAudits_marksAuditsOlderThanThirtyDaysStale_andDetectsRegression() {
        Instant old = Instant.now().minus(Duration.ofDays(31));
        when(reportRepository.findAllSummaryRows()).thenReturn(rows(
                report("r-2", "p-1", "a.health", GateStatus.RED, AuditIngestionStatus.VALID, old),
                report("r-1", "p-1", "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID, old.minusSeconds(60))));
        when(findingRepository.countByScopeStatusAndSeverity()).thenReturn(List.of());

        AuditSummary summary = service.listAudits(null, null, null).getFirst();

        assertThat(summary.stale()).isTrue();
        assertThat(summary.trend()).isEqualTo("REGRESSED");
    }

    /** Die Uebersicht laedt weder Report-Markdown noch Findings je Audit (TODO 3d7e3987). */
    @Test
    void listAudits_usesOneReportProjectionAndOneFindingCount_forAnyNumberOfAudits() {
        Instant now = Instant.now();
        when(reportRepository.findAllSummaryRows()).thenReturn(rows(
                report("r-1", "p-1", "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID, now),
                report("r-2", "p-1", "a.security", GateStatus.RED, AuditIngestionStatus.VALID, now),
                report("r-3", "p-2", "a.health", GateStatus.YELLOW, AuditIngestionStatus.VALID, now)));
        when(findingRepository.countByScopeStatusAndSeverity()).thenReturn(List.of());

        assertThat(service.listAudits(null, null, null)).hasSize(3);

        verify(reportRepository).findAllSummaryRows();
        verify(findingRepository).countByScopeStatusAndSeverity();
        verifyNoMoreInteractions(reportRepository, findingRepository);
    }

    // --- getAudit ---

    @Test
    void getAudit_combinesSummaryAndLatestReport_withUnchangedTrend() {
        Instant now = Instant.now();
        AuditReport latest = report("r-2", "p-1", "a.health", GateStatus.YELLOW, AuditIngestionStatus.VALID, now);
        AuditReport previous = report("r-1", "p-1", "a.health", GateStatus.YELLOW, AuditIngestionStatus.VALID, now.minusSeconds(60));
        when(reportRepository.findTopByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
                "p-1", "a.health", AuditIngestionStatus.VALID)).thenReturn(Optional.of(latest));
        when(reportRepository.findByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
                "p-1", "a.health", AuditIngestionStatus.VALID, PageRequest.of(0, 2)))
                .thenReturn(new PageImpl<>(List.of(latest, previous)));
        when(reportRepository.findTopByProjectIdAndAuditKeyOrderByGeneratedAtDesc("p-1", "a.health"))
                .thenReturn(Optional.empty());
        when(findingRepository.findByProjectIdAndAuditKey("p-1", "a.health")).thenReturn(List.of());
        when(occurrenceRepository.countByReportIds(List.of("r-2"))).thenReturn(countRows("r-2", 2));

        AuditDetail detail = service.getAudit("p-1", "a.health");

        assertThat(detail.summary().trend()).isEqualTo("UNCHANGED");
        assertThat(detail.summary().latestAttemptStatus()).as("ohne Versuch faellt es auf den Report zurueck")
                .isEqualTo(AuditIngestionStatus.VALID);
        assertThat(detail.latestReport().id()).isEqualTo("r-2");
        assertThat(detail.latestReport().findingCount()).isEqualTo(2);
    }

    @Test
    void getAudit_withoutValidReport_isNotFound() {
        when(reportRepository.findTopByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
                "p-1", "a.none", AuditIngestionStatus.VALID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAudit("p-1", "a.none")).isInstanceOf(ResourceNotFoundException.class);
    }

    // --- Reports ---

    @Test
    void listReports_mapsThePageIncludingInvalidAttempts() {
        AuditReport invalid = report("r-2", "p-1", "a.health", null, AuditIngestionStatus.INVALID, Instant.now());
        invalid.setIngestionError("Schema kaputt");
        when(reportRepository.findByProjectIdAndAuditKeyOrderByGeneratedAtDesc("p-1", "a.health", PageRequest.of(1, 1)))
                .thenReturn(new PageImpl<>(List.of(invalid), PageRequest.of(1, 1), 3));

        PageResponse<AuditReportResponse> page = service.listReports("p-1", "a.health", 1, 1);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.content()).singleElement().satisfies(r -> {
            assertThat(r.ingestionStatus()).isEqualTo(AuditIngestionStatus.INVALID);
            assertThat(r.ingestionError()).isEqualTo("Schema kaputt");
            assertThat(r.findingCount()).isZero();
        });
    }

    @Test
    void getReport_returnsTheReportOr404() {
        when(reportRepository.findById("r-1")).thenReturn(Optional.of(
                report("r-1", "p-1", "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID, Instant.now())));
        when(occurrenceRepository.countByReportIds(List.of("r-1"))).thenReturn(countRows("r-1", 1));
        when(reportRepository.findById("r-missing")).thenReturn(Optional.empty());

        assertThat(service.getReport("r-1").findingCount()).isEqualTo(1);
        assertThatThrownBy(() -> service.getReport("r-missing")).isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Herkunft je Report (TODO a3b89355): Ketteneintrag mit Kette und Zeitplan der Kette, direkter Lauf,
     * Kettenlauf von vor V89, geloeschter Lauf — fuer die ganze Seite je eine Abfrage, nicht je Report.
     */
    @Test
    void listReports_resolvesTheOriginOfEveryReport_withOneQueryPerKind() {
        Instant now = Instant.now();
        AuditReport viaChain = report("r-chain", "p-1", "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID, now);
        AuditReport direct = report("r-direct", "p-1", "a.health", GateStatus.YELLOW, AuditIngestionStatus.VALID, now.minusSeconds(60));
        AuditReport oldChain = report("r-old", "p-1", "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID, now.minusSeconds(120));
        AuditReport runGone = report("r-gone", "p-1", "a.health", null, AuditIngestionStatus.INVALID, now.minusSeconds(180));
        when(reportRepository.findByProjectIdAndAuditKeyOrderByGeneratedAtDesc("p-1", "a.health", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(viaChain, direct, oldChain, runGone), PageRequest.of(0, 20), 4));
        when(occurrenceRepository.countByReportIds(List.of("r-chain", "r-direct", "r-old", "r-gone")))
                .thenReturn(countRows("r-chain", 3, "r-direct", 1));
        when(runRepository.findOriginRows(List.of("run-r-chain", "run-r-direct", "run-r-old", "run-r-gone"))).thenReturn(List.of(
                new RunOriginRow("run-r-chain", TriggerSource.CHAIN, null, "cr-1", "v-7", "SCHEDULE"),
                new RunOriginRow("run-r-direct", TriggerSource.SCHEDULE, "sched-direct", null, "v-8", null),
                new RunOriginRow("run-r-old", TriggerSource.CHAIN, null, null, null, "MANUAL")));
        when(chainRunRepository.findOriginRows(Set.of("cr-1")))
                .thenReturn(List.of(new ChainRunOriginRow("cr-1", "chain-1", "Wochen-Audits", "sched-chain")));
        when(versionRepository.findVersionNumbers(Set.of("v-7", "v-8")))
                .thenReturn(List.of(new Object[]{"v-7", 7}, new Object[]{"v-8", 8}));

        List<AuditReportResponse> reports = service.listReports("p-1", "a.health", 0, 20).content();

        assertThat(reports).extracting(AuditReportResponse::id).containsExactly("r-chain", "r-direct", "r-old", "r-gone");
        assertThat(reports.get(0)).satisfies(r -> {
            assertThat(r.triggerSource()).as("der Ausloeser der Kette, nicht CHAIN").isEqualTo(TriggerSource.SCHEDULE);
            assertThat(r.scheduleId()).as("der Zeitplan der Kette").isEqualTo("sched-chain");
            assertThat(r.chainRunId()).isEqualTo("cr-1");
            assertThat(r.chainId()).isEqualTo("chain-1");
            assertThat(r.chainName()).isEqualTo("Wochen-Audits");
            assertThat(r.definitionVersionNumber()).isEqualTo(7);
            assertThat(r.findingCount()).isEqualTo(3);
        });
        assertThat(reports.get(1)).satisfies(r -> {
            assertThat(r.triggerSource()).isEqualTo(TriggerSource.SCHEDULE);
            assertThat(r.scheduleId()).isEqualTo("sched-direct");
            assertThat(r.chainRunId()).isNull();
            assertThat(r.chainName()).isNull();
            assertThat(r.definitionVersionNumber()).isEqualTo(8);
            assertThat(r.findingCount()).isEqualTo(1);
        });
        assertThat(reports.get(2)).as("Kettenlauf von vor V89: nur CHAIN, nichts Erfundenes").satisfies(r -> {
            assertThat(r.triggerSource()).isEqualTo(TriggerSource.CHAIN);
            assertThat(r.scheduleId()).isNull();
            assertThat(r.chainRunId()).isNull();
            assertThat(r.chainId()).isNull();
            assertThat(r.chainName()).isNull();
            assertThat(r.definitionVersionNumber()).isNull();
            assertThat(r.findingCount()).isZero();
        });
        assertThat(reports.get(3)).as("Lauf geloescht").satisfies(r -> {
            assertThat(r.triggerSource()).isNull();
            assertThat(r.scheduleId()).isNull();
            assertThat(r.chainRunId()).isNull();
            assertThat(r.definitionVersionNumber()).isNull();
        });
        verify(occurrenceRepository).countByReportIds(any());
        verify(runRepository).findOriginRows(any());
        verify(chainRunRepository).findOriginRows(any());
        verify(versionRepository).findVersionNumbers(any());
        verifyNoMoreInteractions(occurrenceRepository, runRepository, chainRunRepository, versionRepository);
    }

    /** Die Kette ist geloescht (samt Chain-Runs): der Lauf behaelt seinen chainRunId, Name und Zeitplan fehlen. */
    @Test
    void getReport_chainGone_keepsTheChainRunIdAndTheStarterButNamesNothing() {
        when(reportRepository.findById("r-1")).thenReturn(Optional.of(
                report("r-1", "p-1", "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID, Instant.now())));
        when(runRepository.findOriginRows(List.of("run-r-1"))).thenReturn(List.of(new RunOriginRow(
                "run-r-1", TriggerSource.CHAIN, null, "cr-gone", null, "WISH")));
        when(chainRunRepository.findOriginRows(Set.of("cr-gone"))).thenReturn(List.of());

        AuditReportResponse report = service.getReport("r-1");

        assertThat(report.triggerSource()).isEqualTo(TriggerSource.WISH);
        assertThat(report.chainRunId()).isEqualTo("cr-gone");
        assertThat(report.chainId()).isNull();
        assertThat(report.chainName()).isNull();
        assertThat(report.scheduleId()).isNull();
        verify(versionRepository, never()).findVersionNumbers(any());
    }

    /**
     * Kein lesbarer Eltern-Ausloeser — der Kontext war kein gueltiges JSON oder nannte keinen bekannten
     * Ausloeser: der Ketteneintrag bleibt CHAIN, samt Kette und deren Zeitplan.
     */
    @Test
    void getReport_unreadableParentSource_fallsBackToChain() {
        when(reportRepository.findById("r-1")).thenReturn(Optional.of(
                report("r-1", "p-1", "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID, Instant.now())));
        when(runRepository.findOriginRows(List.of("run-r-1"))).thenReturn(List.of(
                new RunOriginRow("run-r-1", TriggerSource.CHAIN, null, "cr-1", null, null)));
        when(chainRunRepository.findOriginRows(Set.of("cr-1")))
                .thenReturn(List.of(new ChainRunOriginRow("cr-1", "chain-1", "Wochen-Audits", "sched-chain")));

        AuditReportResponse report = service.getReport("r-1");

        assertThat(report.triggerSource()).isEqualTo(TriggerSource.CHAIN);
        assertThat(report.chainName()).isEqualTo("Wochen-Audits");
        assertThat(report.scheduleId()).isEqualTo("sched-chain");
    }

    /** Ohne Ketten und Versionen auf der Seite fragt die Aufloesung dort gar nicht erst nach. */
    @Test
    void listReports_withoutChainsOrVersions_asksNeitherChainRunsNorVersions() {
        AuditReport manual = report("r-1", "p-1", "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID, Instant.now());
        when(reportRepository.findByProjectIdAndAuditKeyOrderByGeneratedAtDesc("p-1", "a.health", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(manual), PageRequest.of(0, 20), 1));
        when(runRepository.findOriginRows(List.of("run-r-1"))).thenReturn(List.of(
                new RunOriginRow("run-r-1", TriggerSource.MANUAL, null, null, null, null)));

        AuditReportResponse report = service.listReports("p-1", "a.health", 0, 20).content().getFirst();

        assertThat(report.triggerSource()).isEqualTo(TriggerSource.MANUAL);
        assertThat(report.definitionVersionNumber()).isNull();
        verifyNoInteractions(chainRunRepository, versionRepository);
    }

    @Test
    void listReports_emptyPage_asksNothingElse() {
        when(reportRepository.findByProjectIdAndAuditKeyOrderByGeneratedAtDesc("p-1", "a.none", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(service.listReports("p-1", "a.none", 0, 20).content()).isEmpty();
        verifyNoInteractions(occurrenceRepository, runRepository, chainRunRepository, versionRepository);
    }

    // --- Findings ---

    @Test
    void getFinding_includesOnlyLinksWhoseTodoStillExists() {
        AuditFinding finding = finding();
        finding.setTags("[\"security\",\"shell\"]");
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding));
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(
                AuditFindingTodo.builder().findingId(FINDING_ID).todoId(TODO_ID).managed(true).build(),
                AuditFindingTodo.builder().findingId(FINDING_ID).todoId("t-gone").managed(false).build()));
        when(todoRepository.findAllById(List.of(TODO_ID, "t-gone"))).thenReturn(List.of(todo("p-1")));

        AuditFindingResponse response = service.getFinding(FINDING_ID);

        assertThat(response.tags()).containsExactly("security", "shell");
        assertThat(response.todos()).singleElement().satisfies(link -> {
            assertThat(link.id()).isEqualTo(TODO_ID);
            assertThat(link.managed()).isTrue();
            assertThat(link.status()).isEqualTo(Todo.Status.OPEN);
        });
    }

    @Test
    void getFinding_unknownId_isNotFound() {
        when(findingRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFinding("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFindings_ordersInTheQuery_andAttachesLinksInOneBatch() {
        AuditFinding finding = finding();
        when(findingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(finding), PageRequest.of(0, 50), 1));
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());
        when(todoRepository.findAllById(List.of())).thenReturn(List.of());

        PageResponse<AuditFindingResponse> page = service.listFindings(0, 50, "p-1", null, AuditFindingStatus.OPEN, null);

        assertThat(page.content()).extracting(AuditFindingResponse::id).containsExactly(FINDING_ID);
        assertThat(page.content().getFirst().todos()).isEmpty();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(findingRepository).findAll(any(Specification.class), pageable.capture());
        // Nicht per Sort.by("severity"): das sortiert den Enum-Text alphabetisch (LOW vor MEDIUM).
        // Die Rangfolge steht in der Specification; belegt gegen SQLite in
        // AuditFindingSeverityOrderIntegrationTest (TODO f4ddc836).
        assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
    }

    @Test
    void updateFinding_terminalStatusSetsResolvedAt_andReconcilesTheManagedTodo() {
        AuditFinding finding = finding();
        Todo todo = todo("p-1");
        AuditFindingTodo link = AuditFindingTodo.builder().findingId(FINDING_ID).todoId(TODO_ID).managed(true).build();
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding));
        when(findingRepository.save(finding)).thenReturn(finding);
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(link));
        when(findingTodoRepository.findByTodoId(TODO_ID)).thenReturn(List.of(link));
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        AuditFindingResponse response = service.updateFinding(FINDING_ID, AuditFindingStatus.DISMISSED, "Fehlalarm");

        assertThat(response.status()).isEqualTo(AuditFindingStatus.DISMISSED);
        assertThat(response.resolvedAt()).isNotNull();
        assertThat(response.dispositionNote()).isEqualTo("Fehlalarm");
        assertThat(response.dispositionEvidence()).as("Snapshot nur bei ACCEPTED_RISK").isNull();
        assertThat(todo.getStatus()).as("verwaltetes TODO folgt dem terminalen Finding").isEqualTo(Todo.Status.DONE);
    }

    @Test
    void updateFinding_acceptedRiskSnapshotsEvidence_reopeningClearsResolvedAt() {
        AuditFinding finding = finding();
        finding.setEvidence("Big.java: 320 LOC");
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding));
        when(findingRepository.save(finding)).thenReturn(finding);
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of());
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        AuditFindingResponse accepted = service.updateFinding(FINDING_ID, AuditFindingStatus.ACCEPTED_RISK, null);
        assertThat(accepted.dispositionEvidence()).isEqualTo("Big.java: 320 LOC");
        assertThat(accepted.dispositionAt()).isNotNull().isEqualTo(accepted.resolvedAt());

        AuditFindingResponse reopened = service.updateFinding(FINDING_ID, AuditFindingStatus.OPEN, null);
        assertThat(reopened.resolvedAt()).isNull();
    }

    @Test
    void updateFinding_noteOnly_keepsTheStatus() {
        AuditFinding finding = finding();
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding));
        when(findingRepository.save(finding)).thenReturn(finding);
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of());
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        AuditFindingResponse response = service.updateFinding(FINDING_ID, null, "nur eine Notiz");

        assertThat(response.status()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(response.resolvedAt()).isNull();
        assertThat(response.dispositionNote()).isEqualTo("nur eine Notiz");
    }

    // --- TODO-Verknuepfungen ---

    @Test
    void createManagedTodo_createsTodoLinkAndEvent() {
        AuditFinding finding = finding();
        finding.setDescription(null);
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding));
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of());
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> {
            Todo t = inv.getArgument(0);
            t.setId(TODO_ID);
            return t;
        });
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        service.createManagedTodo(FINDING_ID);

        ArgumentCaptor<Todo> saved = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(saved.capture());
        Todo todo = saved.getValue();
        assertThat(todo.getPriority()).isEqualTo(Todo.Priority.HIGH);
        assertThat(todo.getWorkflowRunId()).as("letzter Lauf des Findings").isEqualTo("run-1");
        assertThat(todo.getReferenceType()).isEqualTo(ManagedTodoWriter.REFERENCE_TYPE);
        assertThat(todo.getReferenceId()).isEqualTo(FINDING_ID);
        assertThat(todo.getSourceAgent()).isEqualTo(ManagedTodoWriter.SOURCE_AGENT);
        assertThat(todo.getDescription()).startsWith("Audit finding requires remediation.")
                .contains("[Evidence run](/runs/run-1)");
        ArgumentCaptor<AuditFindingTodo> link = ArgumentCaptor.forClass(AuditFindingTodo.class);
        verify(findingTodoRepository).save(link.capture());
        assertThat(link.getValue().isManaged()).isTrue();
        verify(eventPublisher).publishEvent(any(TodoCreatedEvent.class));
    }

    @Test
    void createManagedTodo_isIdempotentWhenAManagedTodoExists() {
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding()));
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(
                AuditFindingTodo.builder().findingId(FINDING_ID).todoId(TODO_ID).managed(true).build()));
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        service.createManagedTodo(FINDING_ID);

        verify(todoRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void linkTodo_createsAnUnmanagedLinkOnlyOnce() {
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding()));
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo("p-1")));
        when(findingTodoRepository.findByFindingIdAndTodoId(FINDING_ID, TODO_ID)).thenReturn(Optional.empty());
        when(findingTodoRepository.save(any(AuditFindingTodo.class))).thenAnswer(inv -> inv.getArgument(0));
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        service.linkTodo(FINDING_ID, TODO_ID);

        ArgumentCaptor<AuditFindingTodo> link = ArgumentCaptor.forClass(AuditFindingTodo.class);
        verify(findingTodoRepository).save(link.capture());
        assertThat(link.getValue().isManaged()).isFalse();
        assertThat(link.getValue().getTodoId()).isEqualTo(TODO_ID);
    }

    @Test
    void linkTodo_existingLinkIsNotDuplicated() {
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding()));
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo("p-1")));
        when(findingTodoRepository.findByFindingIdAndTodoId(FINDING_ID, TODO_ID)).thenReturn(Optional.of(
                AuditFindingTodo.builder().findingId(FINDING_ID).todoId(TODO_ID).managed(true).build()));
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        service.linkTodo(FINDING_ID, TODO_ID);

        verify(findingTodoRepository, never()).save(any());
    }

    @Test
    void linkTodo_rejectsATodoFromAnotherProject_andAMissingTodo() {
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding()));
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo("p-other")));
        when(todoRepository.findById("t-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.linkTodo(FINDING_ID, TODO_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same project");
        assertThatThrownBy(() -> service.linkTodo(FINDING_ID, "t-missing"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(findingTodoRepository, never()).save(any());
    }

    @Test
    void markAwaitingVerification_movesOnlyOpenFindingsOfManagedLinks() {
        AuditFinding open = finding();
        AuditFinding resolved = finding();
        resolved.setId("f-resolved");
        resolved.setStatus(AuditFindingStatus.RESOLVED);
        when(findingTodoRepository.findByTodoId(TODO_ID)).thenReturn(List.of(
                AuditFindingTodo.builder().findingId(FINDING_ID).todoId(TODO_ID).managed(true).build(),
                AuditFindingTodo.builder().findingId("f-resolved").todoId(TODO_ID).managed(true).build(),
                AuditFindingTodo.builder().findingId("f-manual").todoId(TODO_ID).managed(false).build()));
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(open));
        when(findingRepository.findById("f-resolved")).thenReturn(Optional.of(resolved));

        service.markAwaitingVerification(TODO_ID);

        assertThat(open.getStatus()).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        assertThat(resolved.getStatus()).isEqualTo(AuditFindingStatus.RESOLVED);
        verify(findingRepository).save(open);
        verify(findingRepository, never()).save(resolved);
        verify(findingRepository, never()).findById("f-manual");
    }

    /** {@code [reportId, count]}-Zeilen wie aus {@code countByReportIds}: abwechselnd Id und Anzahl. */
    private static List<Object[]> countRows(Object... idsAndCounts) {
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < idsAndCounts.length; i += 2) {
            rows.add(new Object[]{idsAndCounts[i], ((Integer) idsAndCounts[i + 1]).longValue()});
        }
        return rows;
    }

    private static List<AuditReportSummaryRow> rows(AuditReport... reports) {
        return java.util.Arrays.stream(reports).map(r -> new AuditReportSummaryRow(r.getId(), r.getProjectId(),
                r.getWorkflowId(), r.getWorkflowRunId(), r.getAuditKey(), r.getCategory(), r.getTitle(),
                r.getGateStatus(), r.getGateDescription(), r.getIngestionStatus(), r.getIngestionError(),
                r.getGeneratedAt())).toList();
    }

    private static AuditReport report(String id, String projectId, String auditKey, GateStatus gate,
                                      AuditIngestionStatus status, Instant generatedAt) {
        return AuditReport.builder().id(id).projectId(projectId).workflowId("wf-1").workflowRunId("run-" + id)
                .auditKey(auditKey).category("health").title("Health").gateStatus(gate)
                .ingestionStatus(status).generatedAt(generatedAt).build();
    }

    private static Todo todo(String projectId) {
        return Todo.builder().id(TODO_ID).title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId(projectId).build();
    }

    private static AuditFinding finding() {
        return AuditFinding.builder().id(FINDING_ID).projectId("p-1").auditKey("mysuricates.security")
                .fingerprint("fp").ruleKey("shell.injection").subject("ContainerShellService")
                .severity(AuditSeverity.HIGH).title("Shell-Aufruf ohne Escaping")
                .status(AuditFindingStatus.OPEN)
                .firstSeenReportId("rep-1").lastSeenReportId("rep-1")
                .firstSeenRunId("run-1").lastSeenRunId("run-1")
                .firstSeenAt(Instant.parse("2026-09-05T10:30:00Z")).lastSeenAt(Instant.parse("2026-09-12T10:30:00Z"))
                .build();
    }
}

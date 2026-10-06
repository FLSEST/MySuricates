package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingOccurrence;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.AuditSeverity;
import com.automation.entity.Todo;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Zusammenfuehren zweier Findings derselben Identitaet: nichts geht verloren, Dispositionen gewinnen. */
@ExtendWith(MockitoExtension.class)
class AuditFindingMergerTest {

    private static final Instant T0 = Instant.parse("2026-09-05T10:30:00Z");
    private static final Instant T1 = Instant.parse("2026-09-12T10:30:00Z");
    private static final Instant T2 = Instant.parse("2026-09-12T19:16:00Z");

    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private AuditFindingOccurrenceRepository occurrenceRepository;
    @Mock private TodoRepository todoRepository;

    private AuditFindingMerger merger() {
        return new AuditFindingMerger(findingTodoRepository, occurrenceRepository, todoRepository);
    }

    private void noLinks() {
        when(findingTodoRepository.findByFindingId(anyString())).thenReturn(List.of());
        when(occurrenceRepository.findByFindingId(anyString())).thenReturn(List.of());
    }

    @Test
    void merge_sumsCountersAndTakesTheEarliestFirstSeenAndTheLatestAttributes() {
        noLinks();
        AuditFinding target = finding("target", AuditFindingStatus.OPEN, T1, T1, "rep-t", "run-t");
        target.setOccurrenceCount(3);
        target.setRegressionCount(1);
        AuditFinding duplicate = finding("dup", AuditFindingStatus.OPEN, T0, T2, "rep-d", "run-d");
        duplicate.setOccurrenceCount(2);
        duplicate.setRegressionCount(2);
        duplicate.setSubject("ContainerShellService.execute");
        duplicate.setSeverity(AuditSeverity.CRITICAL);
        duplicate.setTitle("neuer Titel");
        duplicate.setEvidence("neu");

        merger().merge(target, duplicate);

        assertThat(target.getOccurrenceCount()).isEqualTo(5);
        assertThat(target.getRegressionCount()).isEqualTo(3);
        assertThat(target.getFirstSeenAt()).isEqualTo(T0);
        assertThat(target.getFirstSeenReportId()).isEqualTo("rep-d");
        assertThat(target.getFirstSeenRunId()).isEqualTo("run-d");
        assertThat(target.getLastSeenAt()).isEqualTo(T2);
        assertThat(target.getLastSeenRunId()).isEqualTo("run-d");
        assertThat(target.getSubject()).isEqualTo("ContainerShellService.execute");
        assertThat(target.getSeverity()).isEqualTo(AuditSeverity.CRITICAL);
        assertThat(target.getTitle()).isEqualTo("neuer Titel");
        assertThat(target.getEvidence()).isEqualTo("neu");
    }

    @Test
    void merge_keepsTheTargetAttributesWhenTheDuplicateIsOlder() {
        noLinks();
        AuditFinding target = finding("target", AuditFindingStatus.OPEN, T0, T2, "rep-t", "run-t");
        AuditFinding duplicate = finding("dup", AuditFindingStatus.OPEN, T1, T1, "rep-d", "run-d");
        duplicate.setTitle("alter Titel");

        merger().merge(target, duplicate);

        assertThat(target.getFirstSeenRunId()).isEqualTo("run-t");
        assertThat(target.getLastSeenRunId()).isEqualTo("run-t");
        assertThat(target.getTitle()).isEqualTo("Shell-Aufruf ohne Escaping");
    }

    @Test
    void merge_humanDispositionOnTheDuplicateWins_includingItsSnapshot() {
        noLinks();
        AuditFinding target = finding("target", AuditFindingStatus.RESOLVED, T0, T1, "rep-t", "run-t");
        target.setResolvedAt(T1);
        AuditFinding duplicate = finding("dup", AuditFindingStatus.ACCEPTED_RISK, T2, T2, "rep-d", "run-d");
        duplicate.setDispositionNote("bewusst akzeptiert");
        duplicate.setDispositionEvidence("320 LOC");
        duplicate.setDispositionAt(T2);
        duplicate.setResolvedAt(T2);

        merger().merge(target, duplicate);

        assertThat(target.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(target.getDispositionNote()).isEqualTo("bewusst akzeptiert");
        assertThat(target.getDispositionEvidence()).isEqualTo("320 LOC");
        assertThat(target.getDispositionAt()).isEqualTo(T2);
        assertThat(target.getResolvedAt()).isEqualTo(T2);
    }

    @Test
    void merge_humanDispositionOnTheTargetIsNeverOverwritten() {
        noLinks();
        AuditFinding target = finding("target", AuditFindingStatus.DISMISSED, T0, T1, "rep-t", "run-t");
        target.setDispositionNote("Fehlalarm");
        target.setResolvedAt(T1);
        AuditFinding duplicate = finding("dup", AuditFindingStatus.OPEN, T2, T2, "rep-d", "run-d");

        merger().merge(target, duplicate);

        assertThat(target.getStatus()).isEqualTo(AuditFindingStatus.DISMISSED);
        assertThat(target.getDispositionNote()).isEqualTo("Fehlalarm");
        assertThat(target.getResolvedAt()).isEqualTo(T1);
    }

    @Test
    void merge_anOpenDuplicatePullsAResolvedTargetBackOpen() {
        noLinks();
        AuditFinding target = finding("target", AuditFindingStatus.RESOLVED, T0, T1, "rep-t", "run-t");
        target.setResolvedAt(T1);

        merger().merge(target, finding("dup", AuditFindingStatus.OPEN, T2, T2, "rep-d", "run-d"));

        assertThat(target.getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(target.getResolvedAt()).as("kein Behebungsdatum an einem offenen Finding").isNull();
    }

    @Test
    void merge_anAwaitingDuplicateMovesAResolvedTargetToAwaitingVerification() {
        noLinks();
        AuditFinding target = finding("target", AuditFindingStatus.RESOLVED, T0, T1, "rep-t", "run-t");
        target.setResolvedAt(T1);

        merger().merge(target, finding("dup", AuditFindingStatus.AWAITING_VERIFICATION, T2, T2, "rep-d", "run-d"));

        assertThat(target.getStatus()).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        assertThat(target.getResolvedAt()).isNull();
    }

    @Test
    void merge_twoResolvedFindingsStayResolved() {
        noLinks();
        AuditFinding target = finding("target", AuditFindingStatus.RESOLVED, T0, T1, "rep-t", "run-t");
        target.setResolvedAt(T1);

        merger().merge(target, finding("dup", AuditFindingStatus.RESOLVED, T2, T2, "rep-d", "run-d"));

        assertThat(target.getStatus()).isEqualTo(AuditFindingStatus.RESOLVED);
        assertThat(target.getResolvedAt()).isEqualTo(T1);
    }

    @Test
    void merge_relinksTodos_repointsTheDeepLink_andPromotesAnExistingLinkToManaged() {
        AuditFinding target = finding("target", AuditFindingStatus.OPEN, T0, T1, "rep-t", "run-t");
        AuditFinding duplicate = finding("dup", AuditFindingStatus.OPEN, T1, T1, "rep-d", "run-d");
        AuditFindingTodo moving = AuditFindingTodo.builder().findingId("dup").todoId("t-move").managed(false).build();
        AuditFindingTodo colliding = AuditFindingTodo.builder().findingId("dup").todoId("t-both").managed(true).manual(true).build();
        AuditFindingTodo targetLink = AuditFindingTodo.builder().findingId("target").todoId("t-both").managed(false).build();
        Todo managedTodo = Todo.builder().id("t-both").referenceType(ManagedTodoWriter.REFERENCE_TYPE).referenceId("dup").build();
        Todo foreignTodo = Todo.builder().id("t-move").referenceType("something-else").referenceId("dup").build();
        when(findingTodoRepository.findByFindingId("dup")).thenReturn(List.of(moving, colliding));
        when(findingTodoRepository.findByFindingId("target")).thenReturn(List.of(targetLink));
        when(todoRepository.findAllById(List.of("t-move", "t-both"))).thenReturn(List.of(foreignTodo, managedTodo));
        when(occurrenceRepository.findByFindingId("dup")).thenReturn(List.of());

        merger().merge(target, duplicate);

        assertThat(moving.getFindingId()).isEqualTo("target");
        verify(findingTodoRepository).deleteAll(List.of(colliding));
        verify(findingTodoRepository, never()).delete(any());
        // d2500abd: Ziel-Verknuepfungen und TODOs je eine Abfrage, keine je Verknuepfung.
        verify(findingTodoRepository, never()).findByFindingIdAndTodoId(any(), any());
        verify(todoRepository, never()).findById(any());
        assertThat(targetLink.isManaged()).as("verwaltete Dublette macht die Ziel-Verknuepfung verwaltet").isTrue();
        assertThat(targetLink.isManual()).as("a TODO created by hand stays manual on the target (f2ce6c50)").isTrue();
        assertThat(managedTodo.getReferenceId()).isEqualTo("target");
        assertThat(foreignTodo.getReferenceId()).as("fremde Referenztypen bleiben unberuehrt").isEqualTo("dup");
    }

    @Test
    void merge_movesOccurrences_andDropsThoseForAReportTheTargetAlreadyHas() {
        AuditFinding target = finding("target", AuditFindingStatus.OPEN, T0, T1, "rep-t", "run-t");
        AuditFinding duplicate = finding("dup", AuditFindingStatus.OPEN, T1, T1, "rep-d", "run-d");
        AuditFindingOccurrence fresh = AuditFindingOccurrence.builder().findingId("dup").reportId("rep-d").build();
        AuditFindingOccurrence clash = AuditFindingOccurrence.builder().findingId("dup").reportId("rep-t").build();
        when(findingTodoRepository.findByFindingId("dup")).thenReturn(List.of());
        when(occurrenceRepository.findByFindingId("dup")).thenReturn(List.of(fresh, clash));
        when(occurrenceRepository.findReportIdsByFindingId("target")).thenReturn(List.of("rep-t", "rep-older"));

        merger().merge(target, duplicate);

        assertThat(fresh.getFindingId()).isEqualTo("target");
        assertThat(clash.getFindingId()).isEqualTo("dup");
        verify(occurrenceRepository).deleteAll(List.of(clash));
        verify(occurrenceRepository, never()).delete(any());
        // d2500abd: die Reports des Ziels aus einer Abfrage, kein exists je Vorkommen.
        verify(occurrenceRepository, never()).existsByFindingIdAndReportId(any(), any());
    }

    private static AuditFinding finding(String id, AuditFindingStatus status, Instant firstSeen, Instant lastSeen,
                                        String reportId, String runId) {
        return AuditFinding.builder().id(id).projectId("p-1").auditKey("test.audit").fingerprint("fp-" + id)
                .ruleKey("shell.injection").subject("ContainerShellService").severity(AuditSeverity.HIGH)
                .title("Shell-Aufruf ohne Escaping").status(status)
                .firstSeenReportId(reportId).lastSeenReportId(reportId).firstSeenRunId(runId).lastSeenRunId(runId)
                .firstSeenAt(firstSeen).lastSeenAt(lastSeen).build();
    }
}

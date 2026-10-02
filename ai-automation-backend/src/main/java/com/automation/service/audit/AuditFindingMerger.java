package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingOccurrence;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.Todo;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fuehrt zwei Findings derselben Identitaet zusammen: Zaehler, Anzeigeattribute, Status samt
 * Disposition, TODO-Verknuepfungen und Vorkommen wandern auf das Ziel. Das Loeschen der Dublette
 * bleibt beim Aufrufer.
 *
 * <p>Aus {@link AuditFingerprintMigrationJob} herausgeloest, damit der Job nur noch Scopes,
 * Gruppierung und Marker verantwortet (Groessen-Ratchet). Reine DB-Arbeit in der Transaktion des
 * Aufrufers.
 */
@Component
@RequiredArgsConstructor
public class AuditFindingMerger {

    private final AuditFindingTodoRepository findingTodoRepository;
    private final AuditFindingOccurrenceRepository occurrenceRepository;
    private final TodoRepository todoRepository;

    /** Uebernimmt alles aus {@code duplicate} in {@code target}; {@code duplicate} ist danach leer. */
    public void merge(AuditFinding target, AuditFinding duplicate) {
        mergeAttributes(target, duplicate);
        relinkTodos(target, duplicate);
        relinkOccurrences(target, duplicate);
    }

    private static void mergeAttributes(AuditFinding target, AuditFinding duplicate) {
        target.setOccurrenceCount(target.getOccurrenceCount() + duplicate.getOccurrenceCount());
        target.setRegressionCount(target.getRegressionCount() + duplicate.getRegressionCount());
        if (isBefore(duplicate.getFirstSeenAt(), target.getFirstSeenAt())) {
            target.setFirstSeenAt(duplicate.getFirstSeenAt());
            target.setFirstSeenReportId(duplicate.getFirstSeenReportId());
            target.setFirstSeenRunId(duplicate.getFirstSeenRunId());
        }
        if (isBefore(target.getLastSeenAt(), duplicate.getLastSeenAt())) {
            target.setLastSeenAt(duplicate.getLastSeenAt());
            target.setLastSeenReportId(duplicate.getLastSeenReportId());
            target.setLastSeenRunId(duplicate.getLastSeenRunId());
            target.setSubject(duplicate.getSubject());
            target.setSeverity(duplicate.getSeverity());
            target.setTitle(duplicate.getTitle());
            target.setDescription(duplicate.getDescription());
            target.setEvidence(duplicate.getEvidence());
            target.setTags(duplicate.getTags());
        }
        applyStatusAndDisposition(target, duplicate);
    }

    /**
     * Status und Disposition wandern immer <em>zusammen</em>: eine menschliche Disposition
     * ({@code ACCEPTED_RISK}, {@code DISMISSED}) schlaegt, egal auf welcher Seite sie steht — beim
     * wahrscheinlichsten Drift-Verlauf ist das aeltere Finding auto-resolved und der juengere
     * Wiedergaenger der akzeptierte. Genau diese Wiedergaenger haben die Akzeptanzen bisher
     * unterlaufen; wiedereroeffnet wird ein akzeptiertes Risiko allein ueber
     * {@link AuditAcceptancePolicy} (belegte Verschlechterung). Ohne Disposition im Spiel zieht ein
     * offener oder wiedervorgelegter Wiedergaenger das Ziel aus {@code RESOLVED} heraus — dann darf
     * kein Behebungsdatum stehen bleiben.
     */
    private static void applyStatusAndDisposition(AuditFinding target, AuditFinding duplicate) {
        if (isDisposed(target.getStatus())) {
            return;
        }
        if (isDisposed(duplicate.getStatus())) {
            target.setStatus(duplicate.getStatus());
            target.setDispositionNote(duplicate.getDispositionNote());
            target.setDispositionEvidence(duplicate.getDispositionEvidence());
            target.setDispositionAt(duplicate.getDispositionAt());
            target.setResolvedAt(duplicate.getResolvedAt());
            return;
        }
        if (target.getStatus() == AuditFindingStatus.OPEN || duplicate.getStatus() == AuditFindingStatus.OPEN) {
            target.setStatus(AuditFindingStatus.OPEN);
            target.setResolvedAt(null);
        } else if (duplicate.getStatus() == AuditFindingStatus.AWAITING_VERIFICATION) {
            target.setStatus(AuditFindingStatus.AWAITING_VERIFICATION);
            target.setResolvedAt(null);
        }
    }

    private static boolean isDisposed(AuditFindingStatus status) {
        return status == AuditFindingStatus.ACCEPTED_RISK || status == AuditFindingStatus.DISMISSED;
    }

    /**
     * Verknuepfungen der Dublette wandern aufs Ziel; hat das Ziel dasselbe TODO schon, faellt die
     * Dubletten-Verknuepfung weg (eine verwaltete macht die Ziel-Verknuepfung verwaltet). Die
     * Ziel-Verknuepfungen und die TODOs kommen aus je einer Abfrage statt einer je Verknuepfung,
     * geloescht wird gesammelt (d2500abd).
     */
    private void relinkTodos(AuditFinding target, AuditFinding duplicate) {
        List<AuditFindingTodo> links = findingTodoRepository.findByFindingId(duplicate.getId());
        if (links.isEmpty()) return;
        Map<String, AuditFindingTodo> targetLinks = findingTodoRepository.findByFindingId(target.getId()).stream()
                .collect(Collectors.toMap(AuditFindingTodo::getTodoId, Function.identity(), (first, second) -> first));
        Map<String, Todo> todos = todoRepository.findAllById(links.stream().map(AuditFindingTodo::getTodoId).distinct().toList())
                .stream().collect(Collectors.toMap(Todo::getId, Function.identity()));
        List<AuditFindingTodo> dropped = new ArrayList<>();
        for (AuditFindingTodo link : links) {
            repointTodoReference(target, duplicate, todos.get(link.getTodoId()));
            AuditFindingTodo existing = targetLinks.get(link.getTodoId());
            if (existing != null) {
                if (link.isManaged()) {
                    existing.setManaged(true);
                }
                dropped.add(link);
            } else {
                link.setFindingId(target.getId());
            }
        }
        if (!dropped.isEmpty()) findingTodoRepository.deleteAll(dropped);
    }

    /** Sonst zeigen der Deep-Link {@code /reports?finding=…} und {@code todo_reference_id} ins Leere. */
    private static void repointTodoReference(AuditFinding target, AuditFinding duplicate, Todo todo) {
        if (todo != null && ManagedTodoWriter.REFERENCE_TYPE.equals(todo.getReferenceType())
                && duplicate.getId().equals(todo.getReferenceId())) {
            todo.setReferenceId(target.getId());
        }
    }

    /** Ein Vorkommen je Report: was das Ziel schon hat, faellt weg; die Report-IDs des Ziels kommen aus einer Abfrage. */
    private void relinkOccurrences(AuditFinding target, AuditFinding duplicate) {
        List<AuditFindingOccurrence> occurrences = occurrenceRepository.findByFindingId(duplicate.getId());
        if (occurrences.isEmpty()) return;
        Set<String> targetReports = new HashSet<>(occurrenceRepository.findReportIdsByFindingId(target.getId()));
        List<AuditFindingOccurrence> dropped = new ArrayList<>();
        for (AuditFindingOccurrence occurrence : occurrences) {
            if (targetReports.contains(occurrence.getReportId())) {
                dropped.add(occurrence);
            } else {
                occurrence.setFindingId(target.getId());
            }
        }
        if (!dropped.isEmpty()) occurrenceRepository.deleteAll(dropped);
    }

    private static boolean isBefore(Instant candidate, Instant reference) {
        return candidate != null && (reference == null || candidate.isBefore(reference));
    }
}

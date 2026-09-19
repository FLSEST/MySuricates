package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingOccurrence;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

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

    private void relinkTodos(AuditFinding target, AuditFinding duplicate) {
        for (AuditFindingTodo link : findingTodoRepository.findByFindingId(duplicate.getId())) {
            repointTodoReference(target, duplicate, link.getTodoId());
            Optional<AuditFindingTodo> existing =
                    findingTodoRepository.findByFindingIdAndTodoId(target.getId(), link.getTodoId());
            if (existing.isPresent()) {
                if (link.isManaged()) {
                    existing.get().setManaged(true);
                }
                findingTodoRepository.delete(link);
            } else {
                link.setFindingId(target.getId());
            }
        }
    }

    /** Sonst zeigen der Deep-Link {@code /reports?finding=…} und {@code todo_reference_id} ins Leere. */
    private void repointTodoReference(AuditFinding target, AuditFinding duplicate, String todoId) {
        todoRepository.findById(todoId).ifPresent(todo -> {
            if (ManagedTodoWriter.REFERENCE_TYPE.equals(todo.getReferenceType())
                    && duplicate.getId().equals(todo.getReferenceId())) {
                todo.setReferenceId(target.getId());
            }
        });
    }

    private void relinkOccurrences(AuditFinding target, AuditFinding duplicate) {
        for (AuditFindingOccurrence occurrence : occurrenceRepository.findByFindingId(duplicate.getId())) {
            if (occurrenceRepository.existsByFindingIdAndReportId(target.getId(), occurrence.getReportId())) {
                occurrenceRepository.delete(occurrence);
            } else {
                occurrence.setFindingId(target.getId());
            }
        }
    }

    private static boolean isBefore(Instant candidate, Instant reference) {
        return candidate != null && (reference == null || candidate.isBefore(reference));
    }
}

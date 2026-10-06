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
  * Merges two findings of the same identity: counters, display attributes, status including
  * disposition, TODO links and occurrences move to the target. Deleting the duplicate
  * stays with the caller.
 *
  * <p>Split out of {@link AuditFingerprintMigrationJob} so the job is only responsible for scopes,
  * grouping and markers (size ratchet). Pure DB work in the caller's
  * transaction.
 */
@Component
@RequiredArgsConstructor
public class AuditFindingMerger {

    private final AuditFindingTodoRepository findingTodoRepository;
    private final AuditFindingOccurrenceRepository occurrenceRepository;
    private final TodoRepository todoRepository;

    /** Takes everything from {@code duplicate} into {@code target}; {@code duplicate} is empty afterwards. */
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
     * Status and disposition always move <em>together</em>: a human disposition
     * ({@code ACCEPTED_RISK}, {@code DISMISSED}) wins, no matter which side it is on — in the
     * most likely drift scenario the older finding is auto-resolved and the younger
     * revenant is the accepted one. These very revenants have so far undermined the acceptances;
     * an accepted risk is reopened solely via
     * {@link AuditAcceptancePolicy} (documented worsening). Without a disposition in play, an
     * open or resubmitted revenant pulls the target out of {@code RESOLVED} — then
     * no fix date may remain.
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
     * Links of the duplicate move to the target; if the target already has the same TODO, the
     * duplicate's link is dropped (a managed one makes the target's link managed). The
     * target's links and the TODOs come from one query each instead of one per link,
     * deletion is done collectively (d2500abd).
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
                    // A TODO created by hand stays manual, whichever side carried it (f2ce6c50)
                    existing.setManual(existing.isManual() || link.isManual());
                    existing.setManaged(true);
                }
                dropped.add(link);
            } else {
                link.setFindingId(target.getId());
            }
        }
        if (!dropped.isEmpty()) findingTodoRepository.deleteAll(dropped);
    }

    /** Otherwise the deep link {@code /reports?finding=…} and {@code todo_reference_id} point nowhere. */
    private static void repointTodoReference(AuditFinding target, AuditFinding duplicate, Todo todo) {
        if (todo != null && ManagedTodoWriter.REFERENCE_TYPE.equals(todo.getReferenceType())
                && duplicate.getId().equals(todo.getReferenceId())) {
            todo.setReferenceId(target.getId());
        }
    }

    /** One occurrence per report: what the target already has is dropped; the target's report IDs come from one query. */
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

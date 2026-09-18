package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingOccurrence;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Einmaliger Aufraeumlauf beim Start: rechnet die Fingerprints aller Audit-Findings auf das
 * normalisierte subject um ({@link AuditSubjectNormalizer}) und fuehrt die Wiedergaenger zusammen,
 * die vorher durch subject-Drift als eigene Findings entstanden sind (Audit-TODO 0e19648d).
 *
 * <p>Ziel einer Gruppe ist immer das aelteste Finding ({@code createdAt}); es erbt die Zaehler, die
 * juengsten Anzeigeattribute, TODO-Verknuepfungen und Vorkommen, die Dubletten werden geloescht.
 * Reine DB-Arbeit in kurzen Transaktionen je (Projekt, Audit) — passend zu pool-size 1, ohne
 * langsame Operationen. Der Lauf ist idempotent: ist nichts mehr zu tun, schreibt er nichts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditFingerprintMigrationJob {

    private static final Comparator<AuditFinding> OLDEST_FIRST = Comparator
            .comparing(AuditFinding::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(AuditFinding::getFirstSeenAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(AuditFinding::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final AuditFindingRepository findingRepository;
    private final AuditFindingTodoRepository findingTodoRepository;
    private final AuditFindingOccurrenceRepository occurrenceRepository;
    private final TransactionTemplate transactionTemplate;
    private final ModuleRegistry modules;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        if (!modules.isEnabled(ModuleCatalog.AUDIT)) {
            return;
        }
        List<Scope> scopes = transactionTemplate.execute(status -> findingRepository.findAll().stream()
                .map(finding -> new Scope(finding.getProjectId(), finding.getAuditKey())).distinct().toList());
        if (scopes == null || scopes.isEmpty()) {
            return;
        }
        int merged = 0;
        int rehashed = 0;
        for (Scope scope : scopes) {
            int[] counts = transactionTemplate.execute(status -> migrateScope(scope));
            if (counts != null) {
                merged += counts[0];
                rehashed += counts[1];
            }
        }
        if (merged > 0 || rehashed > 0) {
            log.info("Audit fingerprints migrated: {} duplicate findings merged, {} findings re-hashed",
                    merged, rehashed);
        }
    }

    /** @return {@code [zusammengefuehrte Dubletten, neu gehashte Findings]} */
    private int[] migrateScope(Scope scope) {
        Map<String, List<AuditFinding>> groups = new LinkedHashMap<>();
        for (AuditFinding finding : findingRepository.findByProjectIdAndAuditKey(scope.projectId(), scope.auditKey())) {
            groups.computeIfAbsent(newFingerprint(finding), key -> new ArrayList<>()).add(finding);
        }
        Map<String, AuditFinding> survivors = new LinkedHashMap<>();
        int merged = 0;
        for (Map.Entry<String, List<AuditFinding>> group : groups.entrySet()) {
            List<AuditFinding> sorted = new ArrayList<>(group.getValue());
            sorted.sort(OLDEST_FIRST);
            AuditFinding target = sorted.get(0);
            for (AuditFinding duplicate : sorted.subList(1, sorted.size())) {
                mergeInto(target, duplicate);
                relinkTodos(target, duplicate);
                relinkOccurrences(target, duplicate);
                findingRepository.delete(duplicate);
                merged++;
            }
            survivors.put(group.getKey(), target);
        }
        // Erst die Dubletten wirklich loeschen: uq(project_id, audit_key, fingerprint) wuerde sonst
        // brechen, weil Hibernate Updates vor Deletes schreibt.
        findingRepository.flush();
        int rehashed = 0;
        for (Map.Entry<String, AuditFinding> survivor : survivors.entrySet()) {
            if (!survivor.getKey().equals(survivor.getValue().getFingerprint())) {
                survivor.getValue().setFingerprint(survivor.getKey());
                rehashed++;
            }
        }
        findingRepository.flush();
        return new int[]{merged, rehashed};
    }

    private void mergeInto(AuditFinding target, AuditFinding duplicate) {
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
        if (target.getDispositionNote() == null && duplicate.getDispositionNote() != null) {
            target.setDispositionNote(duplicate.getDispositionNote());
            target.setDispositionEvidence(duplicate.getDispositionEvidence());
            target.setDispositionAt(duplicate.getDispositionAt());
        }
        target.setStatus(mergedStatus(target.getStatus(), duplicate.getStatus()));
        if (target.getStatus() == AuditFindingStatus.OPEN) {
            target.setResolvedAt(null);
        }
    }

    /**
     * Ein offener oder wiedervorgelegter Wiedergaenger zieht das Ziel aus {@code RESOLVED} heraus —
     * aber niemals aus einer menschlichen Disposition ({@code ACCEPTED_RISK}, {@code DISMISSED}).
     * Genau diese Wiedergaenger haben die Akzeptanzen bisher unterlaufen; wiedereroeffnet wird ein
     * akzeptiertes Risiko allein ueber {@link AuditAcceptancePolicy} (belegte Verschlechterung).
     */
    private static AuditFindingStatus mergedStatus(AuditFindingStatus target, AuditFindingStatus duplicate) {
        if (target == AuditFindingStatus.ACCEPTED_RISK || target == AuditFindingStatus.DISMISSED) {
            return target;
        }
        if (target == AuditFindingStatus.OPEN || duplicate == AuditFindingStatus.OPEN) {
            return AuditFindingStatus.OPEN;
        }
        if (target == AuditFindingStatus.AWAITING_VERIFICATION || duplicate == AuditFindingStatus.AWAITING_VERIFICATION) {
            return AuditFindingStatus.AWAITING_VERIFICATION;
        }
        return target;
    }

    private void relinkTodos(AuditFinding target, AuditFinding duplicate) {
        for (AuditFindingTodo link : findingTodoRepository.findByFindingId(duplicate.getId())) {
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

    private void relinkOccurrences(AuditFinding target, AuditFinding duplicate) {
        for (AuditFindingOccurrence occurrence : occurrenceRepository.findByFindingId(duplicate.getId())) {
            if (occurrenceRepository.existsByFindingIdAndReportId(target.getId(), occurrence.getReportId())) {
                occurrenceRepository.delete(occurrence);
            } else {
                occurrence.setFindingId(target.getId());
            }
        }
    }

    private static String newFingerprint(AuditFinding finding) {
        return AuditIngestionService.fingerprint(finding.getProjectId(), finding.getAuditKey(),
                finding.getRuleKey(), finding.getSubject());
    }

    private static boolean isBefore(Instant candidate, Instant reference) {
        return candidate != null && (reference == null || candidate.isBefore(reference));
    }

    private record Scope(String projectId, String auditKey) {
    }
}

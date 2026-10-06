package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.modules.ModuleStateChangedEvent;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.AuditFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
  * One-time cleanup run at startup: recomputes the fingerprints of all audit findings to the
  * normalized subject ({@link AuditSubjectNormalizer}) and merges the revenants
  * that previously arose as separate findings through subject drift (audit TODO 0e19648d).
 *
  * <p>The target of a group is always the oldest finding ({@code createdAt}); it inherits the counters, the
  * youngest display attributes, TODO links and occurrences, the duplicates are deleted.
  * Pure DB work in short transactions per (project, audit) — suited to pool-size 1, without
  * slow operations. The run is idempotent: if there is nothing left to do, it writes nothing.
 *
  * <p>It still runs only once: after a complete run the marker
  * {@link #MARKER_KEY} in {@code app_settings} records the job version, otherwise every
  * backend start would scan all findings (audit TODO 763ff931). If the AUDIT module was off at startup,
  * {@link #onModuleStateChanged} catches the run up when it is switched on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditFingerprintMigrationJob {

    /** Marker in {@code app_settings}: up to which job version has cleanup already been done? */
    static final String MARKER_KEY = "audit-fingerprint-migration.version";
    /**
     * Increase when a new cleanup step is added — then the job runs once more.
     * v2: also normalizes the ruleKey via {@link AuditRuleKeyCatalog} (audit TODO 8fc9a401).
     * v3: {@link AuditSubjectNormalizer} leaves {@code <art>:<wert>} intact (TODO 94fda6ef) — the
     * fingerprints of such findings are recomputed.
     * v4: {@code ¦} in rule or subject counts like {@code |} in the fingerprint (TODO 90c4f7db) — a
     * finding already stored with {@code ¦} gets its new fingerprint.
     * v5: rules with a fixed subject in the catalog ({@link AuditRuleKeyCatalog#fixedSubject}, TODO 802f740a) —
     * findings with a deviating subject pass to the canonical finding including their triage.
     * v6: class name and Java path are the same identity, frontend modules uniformly carry
     * {@code frontend/src/…} ({@link AuditSubjectNormalizer}), metric rules of the performance audit and
     * health check have the FAKT name as fixed subject (TODO 87a1dac0) — findings that previously stood
     * separately under {@code AssistantService} and {@code com/automation/service/AssistantService.java}
     * are merged including their triage.
     */
    static final int VERSION = 6;

    private static final Comparator<AuditFinding> OLDEST_FIRST = Comparator
            .comparing(AuditFinding::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(AuditFinding::getFirstSeenAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(AuditFinding::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final AuditFindingRepository findingRepository;
    private final AuditRuleKeyCatalog ruleKeyCatalog;
    private final AuditFindingMerger merger;
    private final ManagedTodoReconciler managedTodoReconciler;
    private final AppSettingsRepository appSettings;
    private final TransactionTemplate transactionTemplate;
    private final ModuleRegistry modules;

    @EventListener(ApplicationReadyEvent.class)
    public void migrateOnStartup() {
        migrateIfNeeded();
    }

    /**
     * If the AUDIT module was off at startup, the run is caught up here — otherwise it would only run at the
     * next restart.
     */
    @EventListener
    public void onModuleStateChanged(ModuleStateChangedEvent event) {
        if (event.getModule() == ModuleCatalog.AUDIT && event.isEnabled()) {
            migrateIfNeeded();
        }
    }

    /** Runs only while the marker is missing: a successful run writes it. */
    private void migrateIfNeeded() {
        if (!modules.isEnabled(ModuleCatalog.AUDIT) || isMigrated()) {
            return;
        }
        if (migrate()) {
            markMigrated();
        }
    }

    /**
     * Runs the cleanup over all scopes — without a marker check, so that it stays repeatable even after
     * a data correction (it is idempotent).
     *
     * @return {@code true} if every scope ran through (only then may the marker drop)
     */
    public boolean migrate() {
        if (!modules.isEnabled(ModuleCatalog.AUDIT)) {
            return false;
        }
        List<Scope> scopes;
        try {
            scopes = transactionTemplate.execute(status -> findingRepository.findDistinctScopes().stream()
                    .map(row -> new Scope((String) row[0], (String) row[1])).toList());
        } catch (Exception e) {
            log.error("Audit fingerprint migration could not read its scopes, skipping the run: {}", e.toString(), e);
            return false;
        }
        if (scopes == null || scopes.isEmpty()) {
            return true;
        }
        boolean complete = true;
        int merged = 0;
        int rehashed = 0;
        for (Scope scope : scopes) {
            // A broken scope (constraint, SQLITE_BUSY) must block neither the others nor the
            // backend start — the job is idempotent and catches it up next time.
            try {
                int[] counts = transactionTemplate.execute(status -> migrateScope(scope));
                if (counts != null) {
                    merged += counts[0];
                    rehashed += counts[1];
                }
            } catch (Exception e) {
                complete = false;
                log.error("Audit fingerprint migration failed for project {} / audit {}, left unchanged: {}",
                        scope.projectId(), scope.auditKey(), e.toString(), e);
            }
        }
        if (merged > 0 || rehashed > 0) {
            log.info("Audit fingerprints migrated: {} duplicate findings merged, {} findings re-hashed",
                    merged, rehashed);
        }
        return complete;
    }

    /** @return true if the marker already proves the run of this version (or a newer one) */
    private boolean isMigrated() {
        try {
            return appSettings.get(MARKER_KEY).map(String::trim).filter(v -> !v.isEmpty())
                    .map(v -> parseVersion(v) >= VERSION).orElse(false);
        } catch (Exception e) {
            // Better to scan once too often than to guess the marker: the run is idempotent.
            log.warn("Audit fingerprint migration could not read its marker, running anyway: {}", e.toString());
            return false;
        }
    }

    private void markMigrated() {
        try {
            appSettings.set(MARKER_KEY, String.valueOf(VERSION));
        } catch (Exception e) {
            log.warn("Audit fingerprint migration ran but its marker could not be stored: {}", e.toString());
        }
    }

    private static int parseVersion(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** @return {@code [merged duplicates, newly hashed findings]} */
    private int[] migrateScope(Scope scope) {
        // The normalized key is only computed at first: it is only written to the
        // surviving record. A duplicate is deleted right afterwards — its UPDATE
        // would be discarded and the log line would claim a change that nobody ever sees.
        Map<AuditFinding, String> normalizedKeys = new IdentityHashMap<>();
        Map<String, List<AuditFinding>> groups = new LinkedHashMap<>();
        for (AuditFinding finding : findingRepository.findByProjectIdAndAuditKey(scope.projectId(), scope.auditKey())) {
            String normalized = normalizedRuleKey(finding);
            normalizedKeys.put(finding, normalized);
            groups.computeIfAbsent(newFingerprint(finding, normalized, subjectOf(finding, normalized)),
                    key -> new ArrayList<>()).add(finding);
        }
        Map<String, AuditFinding> survivors = new LinkedHashMap<>();
        List<AuditFinding> mergeTargets = new ArrayList<>();
        int merged = 0;
        for (Map.Entry<String, List<AuditFinding>> group : groups.entrySet()) {
            List<AuditFinding> sorted = new ArrayList<>(group.getValue());
            sorted.sort(OLDEST_FIRST);
            AuditFinding target = sorted.get(0);
            if (sorted.size() > 1) {
                mergeTargets.add(target);
            }
            String targetSubject = target.getSubject();
            for (AuditFinding duplicate : sorted.subList(1, sorted.size())) {
                // Deleting is irreversible — both raw subjects remain traceable in the log.
                log.info("Audit fingerprint merge: finding {} (subject \"{}\") into {} (subject \"{}\") — "
                                + "ruleKey {}, project {}, audit {}",
                        duplicate.getId(), duplicate.getSubject(), target.getId(), targetSubject,
                        normalizedKeys.get(target), target.getProjectId(), target.getAuditKey());
                merger.merge(target, duplicate);
                findingRepository.delete(duplicate);
                merged++;
            }
            survivors.put(group.getKey(), target);
        }
        // Only now really delete the duplicates: uq(project_id, audit_key, fingerprint) would otherwise
        // break, because Hibernate writes updates before deletes.
        findingRepository.flush();
        // Two-phase: if two survivors swap their fingerprints, uq(...) would break at the first
        // UPDATE — and the job would fail again at every start. First a unique placeholder per
        // record, flush, then the target.
        List<Map.Entry<String, AuditFinding>> rehashing = new ArrayList<>();
        for (Map.Entry<String, AuditFinding> survivor : survivors.entrySet()) {
            applyRuleKey(survivor.getValue(), normalizedKeys.get(survivor.getValue()));
            applyFixedSubject(survivor.getValue(), normalizedKeys.get(survivor.getValue()));
            if (!survivor.getKey().equals(survivor.getValue().getFingerprint())) {
                survivor.getValue().setFingerprint("migrating:" + survivor.getValue().getId());
                rehashing.add(survivor);
            }
        }
        findingRepository.flush();
        rehashing.forEach(survivor -> survivor.getValue().setFingerprint(survivor.getKey()));
        int rehashed = rehashing.size();
        findingRepository.flush();
        closeManagedTodosOfTerminalTargets(mergeTargets);
        return new int[]{merged, rehashed};
    }

    /**
     * If a merged target is terminal (accepted risk, dismissed, resolved), the duplicate's
     * re-attached managed TODO otherwise stays permanently open in the fix loop: the
     * absence loop of {@code AuditSnapshotApplier} never touches terminal findings
     * again. The same rule as for the disposition change in the Audit Center.
     */
    private void closeManagedTodosOfTerminalTargets(List<AuditFinding> mergeTargets) {
        for (AuditFinding target : mergeTargets) {
            if (ManagedTodoReconciler.isTerminal(target)) {
                managedTodoReconciler.reconcileFinding(target.getId());
            }
        }
    }

    /**
     * Pulls the spelling of the ruleKey to the catalog entry, as the ingestion has done since the
     * ruleKey catalog (audit TODO 8fc9a401). Without this step the next ingest does not find a
     * drifted legacy finding again: a second record would arise, while the old one
     * falls to RESOLVED via the absence loop — including first sighting, counters and any
     * acceptance.
     *
     * @return the spelling to use (unchanged if the catalog says nothing about it)
     */
    private String normalizedRuleKey(AuditFinding finding) {
        String normalized = ruleKeyCatalog.resolve(finding.getAuditKey(), finding.getRuleKey()).ruleKey();
        return normalized != null ? normalized : finding.getRuleKey();
    }

    /** Writes the pulled spelling — only on the surviving record, see migrateScope. */
    private void applyRuleKey(AuditFinding finding, String normalized) {
        if (normalized == null || normalized.equals(finding.getRuleKey())) {
            return;
        }
        log.info("Audit ruleKey normalized: finding {} '{}' → '{}' (project {}, audit {})",
                finding.getId(), finding.getRuleKey(), normalized, finding.getProjectId(), finding.getAuditKey());
        finding.setRuleKey(normalized);
    }

    /**
     * The subject under which the ingestion keeps the finding today: the rule's fixed value, otherwise the
     * stored one. This way findings with a deviating subject of a fixed rule land in the same group as the
     * canonical one, and the merge takes their triage along (follow-up finding to 802f740a).
     */
    private String subjectOf(AuditFinding finding, String ruleKey) {
        String fixed = ruleKeyCatalog.fixedSubject(finding.getAuditKey(), ruleKey);
        return fixed != null ? fixed : finding.getSubject();
    }

    /** Writes the fixed value — only on the surviving record, the merge otherwise sets the youngest. */
    private void applyFixedSubject(AuditFinding finding, String ruleKey) {
        String fixed = ruleKeyCatalog.fixedSubject(finding.getAuditKey(), ruleKey);
        if (fixed == null || fixed.equals(finding.getSubject())) {
            return;
        }
        log.info("Audit subject fixed: finding {} '{}' → '{}' (rule {}, project {}, audit {})",
                finding.getId(), finding.getSubject(), fixed, ruleKey, finding.getProjectId(), finding.getAuditKey());
        finding.setSubject(fixed);
    }

    /** Expects the ruleKey pulled via {@link #normalizedRuleKey} and the subject from {@link #subjectOf}. */
    private static String newFingerprint(AuditFinding finding, String ruleKey, String subject) {
        return AuditSnapshotApplier.fingerprint(finding.getProjectId(), finding.getAuditKey(), ruleKey, subject);
    }

    private record Scope(String projectId, String auditKey) {
    }
}

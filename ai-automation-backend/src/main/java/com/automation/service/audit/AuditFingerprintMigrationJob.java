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
 * Einmaliger Aufraeumlauf beim Start: rechnet die Fingerprints aller Audit-Findings auf das
 * normalisierte subject um ({@link AuditSubjectNormalizer}) und fuehrt die Wiedergaenger zusammen,
 * die vorher durch subject-Drift als eigene Findings entstanden sind (Audit-TODO 0e19648d).
 *
 * <p>Ziel einer Gruppe ist immer das aelteste Finding ({@code createdAt}); es erbt die Zaehler, die
 * juengsten Anzeigeattribute, TODO-Verknuepfungen und Vorkommen, die Dubletten werden geloescht.
 * Reine DB-Arbeit in kurzen Transaktionen je (Projekt, Audit) — passend zu pool-size 1, ohne
 * langsame Operationen. Der Lauf ist idempotent: ist nichts mehr zu tun, schreibt er nichts.
 *
 * <p>Er laeuft trotzdem nur einmal: nach einem vollstaendigen Lauf haelt der Marker
 * {@link #MARKER_KEY} in {@code app_settings} die Job-Version fest, sonst scannte jeder
 * Backend-Start alle Findings (Audit-TODO 763ff931). War das AUDIT-Modul beim Start aus, holt
 * {@link #onModuleStateChanged} den Lauf beim Einschalten nach.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditFingerprintMigrationJob {

    /** Marker in {@code app_settings}: bis zu welcher Job-Version wurde schon aufgeraeumt? */
    static final String MARKER_KEY = "audit-fingerprint-migration.version";
    /**
     * Erhoehen, wenn ein neuer Aufraeumschritt dazukommt — dann laeuft der Job noch einmal.
     * v2: normalisiert auch den ruleKey ueber {@link AuditRuleKeyCatalog} (Audit-TODO 8fc9a401).
     * v3: {@link AuditSubjectNormalizer} laesst {@code <art>:<wert>} ganz (TODO 94fda6ef) — die
     * Fingerprints solcher Findings werden neu berechnet.
     */
    static final int VERSION = 3;

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
     * War das AUDIT-Modul beim Start aus, holt der Lauf hier nach — sonst liefe er erst beim
     * naechsten Neustart.
     */
    @EventListener
    public void onModuleStateChanged(ModuleStateChangedEvent event) {
        if (event.getModule() == ModuleCatalog.AUDIT && event.isEnabled()) {
            migrateIfNeeded();
        }
    }

    /** Laeuft nur, solange der Marker fehlt: ein erfolgreicher Lauf schreibt ihn. */
    private void migrateIfNeeded() {
        if (!modules.isEnabled(ModuleCatalog.AUDIT) || isMigrated()) {
            return;
        }
        if (migrate()) {
            markMigrated();
        }
    }

    /**
     * Fuehrt den Aufraeumlauf ueber alle Scopes aus — ohne Marker-Pruefung, damit er auch nach
     * einer Datenkorrektur wiederholbar bleibt (er ist idempotent).
     *
     * @return {@code true}, wenn jeder Scope durchgelaufen ist (nur dann darf der Marker fallen)
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
            // Ein kaputter Scope (Constraint, SQLITE_BUSY) darf weder die uebrigen noch den
            // Backend-Start blockieren — der Job ist idempotent und holt ihn beim naechsten Mal nach.
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

    /** @return true, wenn der Marker den Lauf dieser Version (oder eine neuere) schon belegt */
    private boolean isMigrated() {
        try {
            return appSettings.get(MARKER_KEY).map(String::trim).filter(v -> !v.isEmpty())
                    .map(v -> parseVersion(v) >= VERSION).orElse(false);
        } catch (Exception e) {
            // Lieber einmal zu viel scannen als den Marker raten: der Lauf ist idempotent.
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

    /** @return {@code [zusammengefuehrte Dubletten, neu gehashte Findings]} */
    private int[] migrateScope(Scope scope) {
        // Der normalisierte Key wird zuerst nur berechnet: geschrieben wird er erst am
        // ueberlebenden Datensatz. Eine Dublette wird gleich danach geloescht — ihr UPDATE
        // waere verworfen und die Log-Zeile behauptete eine Aenderung, die niemand je sieht.
        Map<AuditFinding, String> normalizedKeys = new IdentityHashMap<>();
        Map<String, List<AuditFinding>> groups = new LinkedHashMap<>();
        for (AuditFinding finding : findingRepository.findByProjectIdAndAuditKey(scope.projectId(), scope.auditKey())) {
            String normalized = normalizedRuleKey(finding);
            normalizedKeys.put(finding, normalized);
            groups.computeIfAbsent(newFingerprint(finding, normalized), key -> new ArrayList<>()).add(finding);
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
                // Das Loeschen ist nicht umkehrbar — beide Roh-Subjects bleiben im Log nachvollziehbar.
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
        // Erst die Dubletten wirklich loeschen: uq(project_id, audit_key, fingerprint) wuerde sonst
        // brechen, weil Hibernate Updates vor Deletes schreibt.
        findingRepository.flush();
        int rehashed = 0;
        for (Map.Entry<String, AuditFinding> survivor : survivors.entrySet()) {
            applyRuleKey(survivor.getValue(), normalizedKeys.get(survivor.getValue()));
            if (!survivor.getKey().equals(survivor.getValue().getFingerprint())) {
                survivor.getValue().setFingerprint(survivor.getKey());
                rehashed++;
            }
        }
        findingRepository.flush();
        closeManagedTodosOfTerminalTargets(mergeTargets);
        return new int[]{merged, rehashed};
    }

    /**
     * Ist ein zusammengefuehrtes Ziel terminal (akzeptiertes Risiko, verworfen, aufgeloest), haengt
     * das umgehaengte managed TODO der Dublette sonst dauerhaft offen im Fix-Loop: die
     * Abwesenheits-Schleife von {@code AuditSnapshotApplier} fasst terminale Findings nie wieder
     * an. Dieselbe Regel wie beim Dispositionswechsel im Audit Center.
     */
    private void closeManagedTodosOfTerminalTargets(List<AuditFinding> mergeTargets) {
        for (AuditFinding target : mergeTargets) {
            if (ManagedTodoReconciler.isTerminal(target)) {
                managedTodoReconciler.reconcileFinding(target.getId());
            }
        }
    }

    /**
     * Zieht die Schreibweise des ruleKeys auf den Katalogeintrag, wie es die Ingestion seit dem
     * ruleKey-Katalog tut (Audit-TODO 8fc9a401). Ohne diesen Schritt findet der naechste Ingest ein
     * gedriftetes Altfinding nicht wieder: es entstuende ein zweiter Datensatz, waehrend der alte
     * ueber die Abwesenheits-Schleife auf RESOLVED faellt — samt Erstsichtung, Zaehlern und einer
     * eventuellen Akzeptanz.
     *
     * @return die zu verwendende Schreibweise (unveraendert, wenn der Katalog nichts dazu sagt)
     */
    private String normalizedRuleKey(AuditFinding finding) {
        String normalized = ruleKeyCatalog.resolve(finding.getAuditKey(), finding.getRuleKey()).ruleKey();
        return normalized != null ? normalized : finding.getRuleKey();
    }

    /** Schreibt die gezogene Schreibweise — nur am ueberlebenden Datensatz, siehe migrateScope. */
    private void applyRuleKey(AuditFinding finding, String normalized) {
        if (normalized == null || normalized.equals(finding.getRuleKey())) {
            return;
        }
        log.info("Audit ruleKey normalized: finding {} '{}' → '{}' (project {}, audit {})",
                finding.getId(), finding.getRuleKey(), normalized, finding.getProjectId(), finding.getAuditKey());
        finding.setRuleKey(normalized);
    }

    /** Erwartet den ueber {@link #normalizedRuleKey} gezogenen ruleKey. */
    private static String newFingerprint(AuditFinding finding, String ruleKey) {
        return AuditSnapshotApplier.fingerprint(finding.getProjectId(), finding.getAuditKey(),
                ruleKey, finding.getSubject());
    }

    private record Scope(String projectId, String auditKey) {
    }
}

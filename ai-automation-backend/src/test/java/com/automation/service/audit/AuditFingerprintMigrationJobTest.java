package com.automation.service.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.automation.entity.*;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der einmalige Merge-Job fuehrt Wiedergaenger zusammen, die vor der subject-Normalisierung als
 * eigene Findings entstanden sind (Audit-TODO 0e19648d) — und ist beim zweiten Lauf ein No-op.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditFingerprintMigrationJobTest {

    /**
     * Erfundener auditKey ohne Katalogeintrag. Hier geht es um die Fingerprint-Migration, nicht um
     * das ruleKey-Vokabular; mit einem echten Schluessel reisst jedes ergaenzte Vokabular diese
     * Tests mit (seit TODO a17e2d19 haben alle sechs ausgelieferten Audits einen Katalog -- vorher
     * stand hier mysuricates.security).
     */
    private static final String AUDIT_KEY = "test.audit-ohne-katalog";
    private static final String RULE_KEY = "shell.injection";
    /** Das einzige Audit mit ruleKey-Katalog (siehe {@code audit/rule-keys.json}). */
    private static final String COVERAGE_AUDIT_KEY = "mysuricates.test-coverage";
    private static final String CANONICAL_RULE_KEY = "test-coverage.untested-class";
    private static final Instant T0 = Instant.parse("2026-09-05T10:30:00Z");
    private static final Instant T1 = Instant.parse("2026-09-12T10:30:00Z");
    private static final Instant T2 = Instant.parse("2026-09-12T19:16:00Z");

    @Autowired private AuditFingerprintMigrationJob job;
    @Autowired private AuditRuleKeyCatalog ruleKeyCatalog;
    @Autowired private AuditFindingRepository findingRepository;
    @Autowired private AuditFindingTodoRepository findingTodoRepository;
    @Autowired private AuditFindingOccurrenceRepository occurrenceRepository;
    @Autowired private AuditReportRepository reportRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EntityManager entityManager;

    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();
    private Level previousLogLevel;
    private String projectId;
    private String reportOld;
    private String reportNew;

    @BeforeEach
    void setUp() {
        projectId = projectRepository.save(Project.builder()
                .name("Audit Merge Test").type(ProjectType.CODE)
                .path("/tmp/audit-merge-test").status(ProjectStatus.RUNNING).build()).getId();
        reportOld = saveReport("run-old", T1);
        reportNew = saveReport("run-new", T2);
    }

    @Test
    void mergesDriftedTwinsOntoTheOldestFindingAndIsIdempotent() {
        AuditFinding older = saveFinding("ContainerShellService", AuditFindingStatus.RESOLVED, 3, T0, T1, reportOld, "run-old");
        AuditFinding newer = saveFinding("ContainerShellService.execute", AuditFindingStatus.OPEN, 1, T2, T2, reportNew, "run-new");
        AuditFinding untouched = saveFinding("application.yml:key-store-password", AuditFindingStatus.OPEN, 2, T1, T1, reportOld, "run-old");
        saveOccurrence(older.getId(), reportOld);
        saveOccurrence(newer.getId(), reportNew);
        saveOccurrence(newer.getId(), reportOld); // Kollision: das Ziel hat diesen Report schon
        String todoId = todoRepository.save(Todo.builder().title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId(projectId)
                .source(Todo.Source.WORKFLOW).sourceAgent("audit-center")
                .referenceType("audit_finding").referenceId(newer.getId()).build()).getId();
        findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(newer.getId()).todoId(todoId).managed(true).build());
        ListAppender<ILoggingEvent> log = attachLogAppender();

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        String messages = String.join("\n", log.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(messages).as("irreversibles Loeschen wird protokolliert")
                .contains(newer.getId()).contains(older.getId())
                .contains("ContainerShellService.execute").contains("ContainerShellService")
                .contains(RULE_KEY);

        List<AuditFinding> remaining = findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY);
        assertThat(remaining).extracting(AuditFinding::getId)
                .containsExactlyInAnyOrder(older.getId(), untouched.getId());
        AuditFinding merged = findingRepository.findById(older.getId()).orElseThrow();
        assertThat(merged.getOccurrenceCount()).isEqualTo(4);
        assertThat(merged.getLastSeenAt()).isEqualTo(T2);
        assertThat(merged.getLastSeenReportId()).isEqualTo(reportNew);
        assertThat(merged.getSubject()).as("neuester subject gewinnt").isEqualTo("ContainerShellService.execute");
        assertThat(merged.getStatus()).as("ein offener Wiedergaenger haelt das Ziel offen")
                .isEqualTo(AuditFindingStatus.OPEN);
        assertThat(merged.getResolvedAt()).isNull();
        assertThat(merged.getFingerprint())
                .isEqualTo(AuditIngestionService.fingerprint(projectId, AUDIT_KEY, RULE_KEY, "ContainerShellService"));
        assertThat(findingRepository.findById(untouched.getId()).orElseThrow().getFingerprint())
                .as("Einzelgaenger werden nur neu gehasht, nicht veraendert")
                .isEqualTo(AuditIngestionService.fingerprint(projectId, AUDIT_KEY, RULE_KEY, "application.yml"));
        assertThat(findingTodoRepository.findByTodoId(todoId)).extracting(AuditFindingTodo::getFindingId)
                .containsExactly(older.getId());
        assertThat(todoRepository.findById(todoId)).get().extracting(Todo::getStatus).isEqualTo(Todo.Status.OPEN);
        assertThat(todoRepository.findById(todoId)).get()
                .as("Deep-Link /reports?finding=… darf nicht ins Leere zeigen")
                .extracting(Todo::getReferenceId).isEqualTo(older.getId());
        assertThat(occurrenceRepository.findByFindingId(older.getId()))
                .extracting(AuditFindingOccurrence::getReportId)
                .containsExactlyInAnyOrder(reportOld, reportNew);

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY)).hasSize(2);
        AuditFinding afterSecondRun = findingRepository.findById(older.getId()).orElseThrow();
        assertThat(afterSecondRun.getOccurrenceCount()).as("zweiter Lauf aendert nichts").isEqualTo(4);
        assertThat(afterSecondRun.getFingerprint()).isEqualTo(merged.getFingerprint());
        assertThat(findingTodoRepository.findByTodoId(todoId)).hasSize(1);
    }

    /**
     * Ein akzeptiertes Risiko ist eine menschliche Entscheidung — ein Wiedergaenger darf sie beim
     * Zusammenfuehren nicht aufheben. Wiedereroeffnung nur ueber {@link AuditAcceptancePolicy}.
     */
    @Test
    void acceptedRiskTargetStaysAcceptedWhenTheTwinIsOpen() {
        AuditFinding accepted = saveFinding("ContainerShellService", AuditFindingStatus.ACCEPTED_RISK, 3, T0, T1, reportOld, "run-old");
        accepted.setDispositionNote("Bewusst so, Aufrufer sind validiert");
        accepted.setDispositionEvidence("ContainerShellService: 3 Fundstellen");
        accepted.setDispositionAt(T1);
        accepted.setResolvedAt(T1);
        AuditFinding twin = saveFinding("ContainerShellService.execute", AuditFindingStatus.OPEN, 1, T2, T2, reportNew, "run-new");
        String todoId = todoRepository.save(Todo.builder().title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId(projectId)
                .source(Todo.Source.WORKFLOW).sourceAgent("audit-center")
                .referenceType("audit_finding").referenceId(twin.getId()).build()).getId();
        findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(twin.getId()).todoId(todoId).managed(true).build());

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY))
                .extracting(AuditFinding::getId).containsExactly(accepted.getId());
        AuditFinding merged = findingRepository.findById(accepted.getId()).orElseThrow();
        assertThat(merged.getStatus()).as("Akzeptanz bleibt, der Wiedergaenger hebt sie nicht auf")
                .isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(merged.getDispositionNote()).isEqualTo("Bewusst so, Aufrufer sind validiert");
        assertThat(merged.getDispositionEvidence()).isEqualTo("ContainerShellService: 3 Fundstellen");
        assertThat(merged.getDispositionAt()).isEqualTo(T1);
        assertThat(merged.getResolvedAt()).isEqualTo(T1);
        assertThat(merged.getOccurrenceCount()).isEqualTo(4);
        assertThat(merged.getLastSeenAt()).isEqualTo(T2);
        assertThat(findingTodoRepository.findByTodoId(todoId)).extracting(AuditFindingTodo::getFindingId)
                .containsExactly(accepted.getId());
        assertThat(todoRepository.findById(todoId)).get()
                .as("das verwaltete TODO der Dublette haengt sonst dauerhaft offen im Fix-Loop")
                .extracting(Todo::getStatus).isEqualTo(Todo.Status.DONE);
    }

    /**
     * Gegenprobe zum Spiegelfall: erbt das Ziel die Akzeptanz des Wiedergaengers, muss auch dessen
     * umgehaengtes managed TODO geschlossen werden — die Absence-Schleife von
     * {@code AuditIngestionService} fasst terminale Findings nie wieder an.
     */
    @Test
    void closesTheManagedTodoWhenTheMergedTargetInheritsATerminalDisposition() {
        AuditFinding resolved = saveFinding("ContainerShellService", AuditFindingStatus.RESOLVED, 3, T0, T1, reportOld, "run-old");
        AuditFinding acceptedTwin = saveFinding("ContainerShellService.execute", AuditFindingStatus.ACCEPTED_RISK, 1, T2, T2, reportNew, "run-new");
        acceptedTwin.setDispositionNote("Bewusst so, Aufrufer sind validiert");
        acceptedTwin.setDispositionAt(T2);
        acceptedTwin.setResolvedAt(T2);
        findingRepository.saveAndFlush(acceptedTwin);
        String todoId = todoRepository.save(Todo.builder().title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId(projectId)
                .source(Todo.Source.WORKFLOW).sourceAgent("audit-center")
                .referenceType("audit_finding").referenceId(acceptedTwin.getId()).build()).getId();
        findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(acceptedTwin.getId()).todoId(todoId).managed(true).build());

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findById(resolved.getId()).orElseThrow().getStatus())
                .isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(findingTodoRepository.findByTodoId(todoId)).extracting(AuditFindingTodo::getFindingId)
                .as("der Link zeigt auf das zusammengefuehrte Ziel").containsExactly(resolved.getId());
        assertThat(todoRepository.findById(todoId)).get()
                .extracting(Todo::getStatus).isEqualTo(Todo.Status.DONE);
        assertThat(todoRepository.findById(todoId)).get()
                .as("Verweis auf das akzeptierte Finding bleibt erhalten")
                .extracting(Todo::getReferenceId).isEqualTo(resolved.getId());
    }

    /**
     * Ein nicht-terminales Ziel schliesst nichts: das managed TODO der Dublette bleibt offen und
     * haengt am Ziel — sonst verschwaende der Merge einen echten Fix-Auftrag.
     */
    @Test
    void keepsTheManagedTodoOpenWhenTheMergedTargetIsNotTerminal() {
        AuditFinding older = saveFinding("ContainerShellService", AuditFindingStatus.RESOLVED, 3, T0, T1, reportOld, "run-old");
        AuditFinding newer = saveFinding("ContainerShellService.execute", AuditFindingStatus.OPEN, 1, T2, T2, reportNew, "run-new");
        String todoId = todoRepository.save(Todo.builder().title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId(projectId)
                .source(Todo.Source.WORKFLOW).sourceAgent("audit-center")
                .referenceType("audit_finding").referenceId(newer.getId()).build()).getId();
        findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(newer.getId()).todoId(todoId).managed(true).build());

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findById(older.getId()).orElseThrow().getStatus())
                .isEqualTo(AuditFindingStatus.OPEN);
        assertThat(todoRepository.findById(todoId)).get()
                .extracting(Todo::getStatus).isEqualTo(Todo.Status.OPEN);
    }

    /**
     * Ein zur Wiedervorlage stehender Wiedergaenger zieht das aufgeloeste Ziel zurueck in
     * {@code AWAITING_VERIFICATION} — dann darf kein Loesungszeitpunkt stehen bleiben, sonst zeigt
     * das Audit Center ein offenes Finding mit Behebungsdatum.
     */
    @Test
    void clearsResolvedAtWhenTheTargetFallsBackToAwaitingVerification() {
        AuditFinding resolved = saveFinding("ContainerShellService", AuditFindingStatus.RESOLVED, 3, T0, T1, reportOld, "run-old");
        AuditFinding twin = saveFinding("ContainerShellService.execute", AuditFindingStatus.AWAITING_VERIFICATION, 1, T2, T2, reportNew, "run-new");
        assertThat(twin.getResolvedAt()).isNull();

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        AuditFinding merged = findingRepository.findById(resolved.getId()).orElseThrow();
        assertThat(merged.getStatus()).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        assertThat(merged.getResolvedAt()).as("kein Behebungsdatum an einem wieder offenen Finding").isNull();
    }

    /**
     * Spiegelfall und wahrscheinlichster Drift-Verlauf: das aeltere Finding wurde auto-resolved,
     * der juengere Wiedergaenger wurde akzeptiert. Die Disposition schlaegt, egal auf welcher Seite
     * sie steht — sonst liefe der naechste Ingest ueber RESOLVED → OPEN samt neuem managed TODO.
     */
    @Test
    void resolvedTargetInheritsTheAcceptedRiskOfItsTwin() {
        AuditFinding resolved = saveFinding("ContainerShellService", AuditFindingStatus.RESOLVED, 3, T0, T1, reportOld, "run-old");
        AuditFinding acceptedTwin = saveFinding("ContainerShellService.execute", AuditFindingStatus.ACCEPTED_RISK, 1, T2, T2, reportNew, "run-new");
        acceptedTwin.setDispositionNote("Bewusst so, Aufrufer sind validiert");
        acceptedTwin.setDispositionEvidence("ContainerShellService.execute: 3 Fundstellen");
        acceptedTwin.setDispositionAt(T2);
        acceptedTwin.setResolvedAt(T2);
        findingRepository.saveAndFlush(acceptedTwin);

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY))
                .extracting(AuditFinding::getId).containsExactly(resolved.getId());
        AuditFinding merged = findingRepository.findById(resolved.getId()).orElseThrow();
        assertThat(merged.getStatus()).as("die Disposition des Wiedergaengers schlaegt RESOLVED")
                .isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(merged.getDispositionNote()).isEqualTo("Bewusst so, Aufrufer sind validiert");
        assertThat(merged.getDispositionEvidence()).isEqualTo("ContainerShellService.execute: 3 Fundstellen");
        assertThat(merged.getDispositionAt()).isEqualTo(T2);
        assertThat(merged.getResolvedAt()).as("Status und Disposition laufen nicht auseinander").isEqualTo(T2);
        assertThat(merged.getOccurrenceCount()).isEqualTo(4);
    }

    /**
     * Seit dem ruleKey-Katalog (Audit-TODO 8fc9a401) bildet die Ingestion den Fingerprint aus dem
     * <em>normalisierten</em> ruleKey. Altbestand mit gedrifteter Schreibweise wuerde sonst beim
     * naechsten Ingest nicht mehr gefunden — neues Finding, altes ueber die Abwesenheits-Schleife
     * auf RESOLVED, und eine Akzeptanz bliebe am toten Datensatz haengen. Der Aufraeumlauf zieht
     * den Key deshalb mit nach.
     */
    @Test
    void normalizesDriftedRuleKeysAndMergesTheTwinsTheyCreated() {
        String subject = "service/workflow/RunAdmission.java";
        AuditFinding canonical = saveCoverageFinding(CANONICAL_RULE_KEY, subject,
                AuditFindingStatus.RESOLVED, T0, T1);
        AuditFinding drifted = saveCoverageFinding("Test-Coverage.Untested_Class", subject,
                AuditFindingStatus.ACCEPTED_RISK, T2, T2);
        drifted.setDispositionNote("Bewusst ungetestet — Fassade ohne Logik");
        drifted.setDispositionEvidence("RunAdmission.java: 0 Tests");
        drifted.setDispositionAt(T2);
        drifted.setResolvedAt(T2);
        findingRepository.saveAndFlush(drifted);

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        List<AuditFinding> remaining = findingRepository.findByProjectIdAndAuditKey(projectId, COVERAGE_AUDIT_KEY);
        assertThat(remaining).as("kein zweiter Datensatz fuer dieselbe Regel")
                .extracting(AuditFinding::getId).containsExactly(canonical.getId());
        AuditFinding merged = findingRepository.findById(canonical.getId()).orElseThrow();
        assertThat(merged.getRuleKey()).as("der Katalog-Key gewinnt").isEqualTo(CANONICAL_RULE_KEY);
        assertThat(merged.getFingerprint()).as("genau der Fingerprint, den der naechste Ingest bildet")
                .isEqualTo(AuditIngestionService.fingerprint(projectId, COVERAGE_AUDIT_KEY, CANONICAL_RULE_KEY, subject));
        assertThat(merged.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(merged.getDispositionNote()).isEqualTo("Bewusst ungetestet — Fassade ohne Logik");
        assertThat(merged.getDispositionAt()).isEqualTo(T2);
        assertThat(merged.getOccurrenceCount()).isEqualTo(4);
    }

    /**
     * Der haeufigste Realfall: ein <em>einzelnes</em> gedriftetes Altfinding ohne Zwilling. Hier
     * muss die neue Schreibweise wirklich in der DB landen — im Zwillingsfall traegt der
     * ueberlebende Datensatz den kanonischen Key ohnehin, dort beweist nichts die Schreibung.
     */
    /**
     * v3 (94fda6ef): Ein {@code <art>:<wert>}-Finding trug bisher den Fingerprint von {@code <art>};
     * nach der Migration hat es genau den, den der naechste Ingest bildet — sonst entstuende ein
     * Wiedergaenger und das Altfinding fiele auf RESOLVED.
     */
    @Test
    void rehashesAKindPrefixedSubjectOntoItsFullIdentity() {
        String subject = "package:com.automation.controller";
        AuditFinding kindPrefixed = saveFinding(subject, AuditFindingStatus.OPEN, 1, T1, T1, reportOld, "run-old");

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        AuditFinding stored = findingRepository.findById(kindPrefixed.getId()).orElseThrow();
        assertThat(stored.getFingerprint())
                .isEqualTo(AuditIngestionService.fingerprint(projectId, AUDIT_KEY, RULE_KEY, subject))
                .isNotEqualTo(AuditIngestionService.fingerprint(projectId, AUDIT_KEY, RULE_KEY, "package"));
    }

    /**
     * v4 (90c4f7db): {@code audit_findings} zeigt ein {@code |} im subject als {@code ¦}. Ein Finding, das schon
     * mit {@code ¦} gespeichert ist, und sein Zwilling mit {@code |} sind derselbe Befund.
     */
    @Test
    void mergesAFindingStoredWithTheMaskedSeparatorOntoItsPipeTwin() {
        AuditFinding older = saveFinding("scripts/install.sh | sh", AuditFindingStatus.ACCEPTED_RISK, 2, T0, T1,
                reportOld, "run-old");
        AuditFinding masked = saveFinding("scripts/install.sh ¦ sh", AuditFindingStatus.OPEN, 1, T2, T2,
                reportNew, "run-new");

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY)).extracting(AuditFinding::getId)
                .containsExactly(older.getId());
        assertThat(findingRepository.findById(masked.getId())).isEmpty();
        assertThat(findingRepository.findById(older.getId()).orElseThrow().getFingerprint())
                .isEqualTo(AuditIngestionService.fingerprint(projectId, AUDIT_KEY, RULE_KEY, "scripts/install.sh | sh"))
                .isEqualTo(AuditIngestionService.fingerprint(projectId, AUDIT_KEY, RULE_KEY, "scripts/install.sh ¦ sh"));
    }

    @Test
    void writesTheCatalogueKeyAndFingerprintOfALoneDriftedFinding() {
        String subject = "service/workflow/RunAdmission.java";
        AuditFinding drifted = saveCoverageFinding("Test-Coverage.Untested_Class", subject,
                AuditFindingStatus.OPEN, T1, T1);

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        AuditFinding stored = findingRepository.findById(drifted.getId()).orElseThrow();
        assertThat(stored.getRuleKey()).as("der katalogisierte Key steht in der DB")
                .isEqualTo(CANONICAL_RULE_KEY);
        assertThat(stored.getFingerprint()).as("genau der Fingerprint, den der naechste Ingest bildet")
                .isEqualTo(AuditIngestionService.fingerprint(projectId, COVERAGE_AUDIT_KEY,
                        CANONICAL_RULE_KEY, subject));
    }

    /**
     * Gegenprobe mit einem Key, den die Normalisierung veraendern <em>wuerde</em>: nur der
     * fehlende Katalogeintrag haelt ihn fest. Mit einem bereits kanonischen Key bewiese der Test
     * nichts — er bliebe auch gruen, wenn normalize() bedingungslos liefe.
     */
    @Test
    void leavesADriftedRuleKeyOfAnUncataloguedAuditAlone() {
        String driftedKey = "Shell_Injection";
        assertThat(AuditRuleKeyCatalog.normalize(driftedKey))
                .as("die Normalisierung wuerde diesen Key veraendern")
                .isNotEqualTo(driftedKey);
        assertThat(ruleKeyCatalog.isChecked(AUDIT_KEY))
                .as("dieses Audit hat keinen Katalogeintrag").isFalse();
        AuditFinding finding = saveFinding(driftedKey, "ContainerShellService",
                AuditFindingStatus.OPEN, 1, T1, T1, reportOld, "run-old");

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findById(finding.getId()).orElseThrow().getRuleKey())
                .isEqualTo(driftedKey);
    }

    /**
     * Ein Finding, das gleich danach als Dublette geloescht wird, braucht die neue Schreibweise
     * nicht: das UPDATE waere verworfen und die Log-Zeile behauptete eine Aenderung, die niemand
     * je sieht. Gezogen wird der Key erst am ueberlebenden Datensatz.
     */
    @Test
    void doesNotRewriteTheRuleKeyOfAFindingItDeletesAsADuplicate() {
        String subject = "service/workflow/RunAdmission.java";
        saveCoverageFinding(CANONICAL_RULE_KEY, subject, AuditFindingStatus.OPEN, T0, T1);
        AuditFinding drifted = saveCoverageFinding("Test-Coverage.Untested_Class", subject,
                AuditFindingStatus.OPEN, T2, T2);
        ListAppender<ILoggingEvent> log = attachLogAppender();

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        String messages = String.join("\n", log.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(messages).as("der Appender hat den Lauf ueberhaupt mitgeschrieben: der Merge steht drin")
                .contains("Audit fingerprint merge: finding " + drifted.getId())
                .contains("ruleKey " + CANONICAL_RULE_KEY);
        assertThat(messages).as("keine Normalisierungs-Meldung fuer einen geloeschten Datensatz")
                .doesNotContain("ruleKey normalized: finding " + drifted.getId());
        assertThat(findingRepository.findById(drifted.getId())).isEmpty();
    }

    /** Ein Audit ohne Katalogeintrag behaelt seine Keys — dort vergibt der Prompt sie frei. */
    @Test
    void leavesRuleKeysOfAnUncataloguedAuditAlone() {
        AuditFinding finding = saveFinding("ContainerShellService", AuditFindingStatus.OPEN, 1, T1, T1, reportOld, "run-old");

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findById(finding.getId()).orElseThrow().getRuleKey()).isEqualTo(RULE_KEY);
    }

    private AuditFinding saveCoverageFinding(String ruleKey, String subject, AuditFindingStatus status,
                                             Instant firstSeenAt, Instant lastSeenAt) {
        return findingRepository.saveAndFlush(AuditFinding.builder()
                .projectId(projectId).auditKey(COVERAGE_AUDIT_KEY)
                .fingerprint(legacyFingerprint(COVERAGE_AUDIT_KEY, ruleKey, subject))
                .ruleKey(ruleKey).subject(subject).severity(AuditSeverity.MEDIUM)
                .title("Klasse ohne Test").evidence(subject + ": 0 Tests").status(status)
                .resolvedAt(status == AuditFindingStatus.RESOLVED ? lastSeenAt : null)
                .firstSeenReportId(reportOld).lastSeenReportId(reportNew)
                .firstSeenRunId("run-old").lastSeenRunId("run-new")
                .firstSeenAt(firstSeenAt).lastSeenAt(lastSeenAt).occurrenceCount(2).build());
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuditFingerprintMigrationJob.class);
        previousLogLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        appenders.add(appender);
        return appender;
    }

    @AfterEach
    void detachLogAppenders() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuditFingerprintMigrationJob.class);
        appenders.forEach(logger::detachAppender);
        appenders.clear();
        logger.setLevel(previousLogLevel);
    }

    private String saveReport(String runId, Instant generatedAt) {
        return reportRepository.save(AuditReport.builder()
                .projectId(projectId).workflowId("wf-1").workflowRunId(runId).auditKey(AUDIT_KEY)
                .category("security").title("Security Audit").ingestionStatus(AuditIngestionStatus.VALID)
                .generatedAt(generatedAt).build()).getId();
    }

    private AuditFinding saveFinding(String subject, AuditFindingStatus status, int occurrences,
                                     Instant firstSeenAt, Instant lastSeenAt, String reportId, String runId) {
        return saveFinding(RULE_KEY, subject, status, occurrences, firstSeenAt, lastSeenAt, reportId, runId);
    }

    private AuditFinding saveFinding(String ruleKey, String subject, AuditFindingStatus status, int occurrences,
                                     Instant firstSeenAt, Instant lastSeenAt, String reportId, String runId) {
        AuditFinding saved = findingRepository.saveAndFlush(AuditFinding.builder()
                .projectId(projectId).auditKey(AUDIT_KEY)
                .fingerprint(legacyFingerprint(AUDIT_KEY, ruleKey, subject))
                .ruleKey(ruleKey).subject(subject).severity(AuditSeverity.HIGH)
                .title("Shell-Aufruf ohne Escaping").evidence(subject + ": 3 Fundstellen").status(status)
                .resolvedAt(status == AuditFindingStatus.RESOLVED ? lastSeenAt : null)
                .firstSeenReportId(reportId).lastSeenReportId(reportId)
                .firstSeenRunId(runId).lastSeenRunId(runId)
                .firstSeenAt(firstSeenAt).lastSeenAt(lastSeenAt).occurrenceCount(occurrences).build());
        return saved;
    }

    private void saveOccurrence(String findingId, String reportId) {
        occurrenceRepository.saveAndFlush(AuditFindingOccurrence.builder()
                .findingId(findingId).reportId(reportId).severity(AuditSeverity.HIGH)
                .title("Shell-Aufruf ohne Escaping").observedAt(T1).build());
    }

    /** Fingerprint vor der Normalisierung: SHA-256 ueber rohen ruleKey und rohes subject. */
    private String legacyFingerprint(String subject) {
        return legacyFingerprint(AUDIT_KEY, RULE_KEY, subject);
    }

    private String legacyFingerprint(String auditKey, String ruleKey, String subject) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(
                    String.join("\n", projectId, auditKey, ruleKey, subject)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

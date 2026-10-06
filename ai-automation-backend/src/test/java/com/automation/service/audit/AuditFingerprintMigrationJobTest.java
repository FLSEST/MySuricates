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
 * The one-off merge job merges recurrences that arose as separate findings before the subject
 * normalization (audit TODO 0e19648d) — and is a no-op on the second run.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditFingerprintMigrationJobTest {

    /**
     * Made-up auditKey without a catalog entry. This is about the fingerprint migration, not about
     * the ruleKey vocabulary; with a real key every added vocabulary entry drags these
     * tests along (since TODO a17e2d19 all six shipped audits have a catalog -- before that
     * this was mysuricates.security).
     */
    private static final String AUDIT_KEY = "test.audit-ohne-katalog";
    private static final String RULE_KEY = "shell.injection";
    /** The only audit with a ruleKey catalog (see {@code audit/rule-keys.json}). */
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
        saveOccurrence(newer.getId(), reportOld); // collision: the target already has this report
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
     * An accepted risk is a human decision — a recurrence must not revoke it when
     * merging. Reopening only via {@link AuditAcceptancePolicy}.
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
     * Counter-check to the mirror case: if the target inherits the recurrence's acceptance, its
     * re-attached managed TODO must be closed as well — the absence loop of
     * {@code AuditIngestionService} never touches terminal findings again.
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
     * A non-terminal target closes nothing: the duplicate's managed TODO stays open and
     * stays attached to the target — otherwise the merge would make a real fix task vanish.
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
     * A recurrence due for resubmission pulls the resolved target back into
     * {@code AWAITING_VERIFICATION} — then no resolution timestamp may remain, otherwise the
     * Audit Center shows an open finding with a fix date.
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
     * Mirror case and most likely drift path: the older finding was auto-resolved,
     * the younger recurrence was accepted. The disposition wins, whichever side
     * it is on — otherwise the next ingest would go RESOLVED → OPEN including a new managed TODO.
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
     * Since the ruleKey catalog (audit TODO 8fc9a401) the ingestion forms the fingerprint from the
     * <em>normalized</em> ruleKey. Legacy data with a drifted spelling would otherwise no longer be found at the
     * next ingest — new finding, the old one set to RESOLVED via the absence loop,
     * and an acceptance would stay stuck on the dead record. The cleanup run therefore
     * carries the key along.
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
     * The most common real case: a <em>single</em> drifted legacy finding without a twin. Here
     * the new spelling must really land in the DB — in the twin case the
     * surviving record carries the canonical key anyway, so nothing there proves the spelling.
     */
    /**
     * v3 (94fda6ef): A {@code <art>:<wert>} finding used to carry the fingerprint of {@code <art>};
     * after the migration it has exactly the one the next ingest forms — otherwise a
     * recurrence would arise and the legacy finding would fall to RESOLVED.
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
     * v4 (90c4f7db): {@code audit_findings} shows a {@code |} in the subject as {@code ¦}. A finding already stored
     * with {@code ¦} and its twin with {@code |} are the same finding.
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
     * Counter-check with a key that the normalization <em>would</em> change: only the
     * missing catalog entry holds it in place. With an already canonical key the test would prove
     * nothing — it would also stay green if normalize() ran unconditionally.
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
     * A finding that is deleted as a duplicate right afterwards does not need the new spelling:
     * the UPDATE would be discarded and the log line would claim a change that nobody
     * ever sees. The key is only pulled on the surviving record.
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

    /** An audit without a catalog entry keeps its keys — there the prompt assigns them freely. */
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

    // --- v6 (87a1dac0): class name = path, metric rules with FAKT names ----------------------

    /**
     * The same finding once stood under the class name (accepted) and once under the path (open, with a
     * managed TODO). The cleanup run merges both; the acceptance wins, the TODO closes.
     */
    @Test
    void mergesClassNameAndPathOfTheSameClass_keepingTheAcceptedRisk() {
        AuditFinding accepted = saveFinding("AssistantService", AuditFindingStatus.ACCEPTED_RISK, 2, T0, T1, reportOld, "run-old");
        accepted.setDispositionNote("Fassade, Aufteilung geplant");
        accepted.setDispositionEvidence("AssistantService: 420 Zeilen");
        accepted.setDispositionAt(T1);
        accepted.setResolvedAt(T1);
        findingRepository.saveAndFlush(accepted);
        AuditFinding path = saveFinding("com/automation/service/AssistantService.java", AuditFindingStatus.OPEN, 1,
                T2, T2, reportNew, "run-new");
        String todoId = todoRepository.save(Todo.builder().title("AssistantService aufteilen")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId(projectId)
                .source(Todo.Source.WORKFLOW).sourceAgent("audit-center")
                .referenceType("audit_finding").referenceId(path.getId()).build()).getId();
        findingTodoRepository.save(AuditFindingTodo.builder().findingId(path.getId()).todoId(todoId).managed(true).build());

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY))
                .extracting(AuditFinding::getId).containsExactly(accepted.getId());
        AuditFinding merged = findingRepository.findById(accepted.getId()).orElseThrow();
        assertThat(merged.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(merged.getDispositionNote()).isEqualTo("Fassade, Aufteilung geplant");
        assertThat(merged.getDispositionEvidence()).isEqualTo("AssistantService: 420 Zeilen");
        assertThat(merged.getOccurrenceCount()).isEqualTo(3);
        assertThat(merged.getSubject()).as("neuester subject gewinnt")
                .isEqualTo("com/automation/service/AssistantService.java");
        assertThat(merged.getFingerprint())
                .isEqualTo(AuditSnapshotApplier.fingerprint(projectId, AUDIT_KEY, RULE_KEY, "AssistantService"));
        assertThat(todoRepository.findById(todoId)).get().extracting(Todo::getStatus).isEqualTo(Todo.Status.DONE);

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        AuditFinding again = findingRepository.findById(accepted.getId()).orElseThrow();
        assertThat(again.getFingerprint()).as("zweiter Lauf aendert nichts").isEqualTo(merged.getFingerprint());
        assertThat(again.getOccurrenceCount()).isEqualTo(3);
        assertThat(again.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
    }

    /** Same-named class in backend and frontend: the cleanup run does not merge them. */
    @Test
    void keepsTheBackendClassAndTheFrontendModuleOfTheSameNameApart() {
        AuditFinding backend = saveFinding("com/automation/entity/ActivityLog.java", AuditFindingStatus.ACCEPTED_RISK,
                1, T0, T1, reportOld, "run-old");
        AuditFinding frontend = saveFinding("frontend/src/pages/ActivityLog.tsx", AuditFindingStatus.OPEN,
                1, T2, T2, reportNew, "run-new");

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY))
                .extracting(AuditFinding::getId).containsExactlyInAnyOrder(backend.getId(), frontend.getId());
        assertThat(findingRepository.findById(frontend.getId()).orElseThrow().getStatus())
                .isEqualTo(AuditFindingStatus.OPEN);
    }

    /** Metric rule: drifted subjects ("Datenbank", "DB-Groesse") are moved over to the FAKT name. */
    @Test
    void mergesDriftedSubjectsOfAMetricRuleOntoTheFactName_keepingTheAcceptedRisk() {
        String performance = "mysuricates.performance";
        AuditFinding first = saveScopedFinding(performance, "perf.db.size", "Datenbank", AuditFindingStatus.RESOLVED, T0, T0);
        AuditFinding accepted = saveScopedFinding(performance, "perf.db.size", "DB-Groesse",
                AuditFindingStatus.ACCEPTED_RISK, T1, T1);
        accepted.setDispositionNote("Wachstum erwartet, Retention greift");
        accepted.setDispositionAt(T1);
        findingRepository.saveAndFlush(accepted);
        AuditFinding latest = saveScopedFinding(performance, "perf.db.size", "database_bytes", AuditFindingStatus.OPEN, T2, T2);

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, performance))
                .extracting(AuditFinding::getId).containsExactly(first.getId());
        AuditFinding merged = findingRepository.findById(first.getId()).orElseThrow();
        assertThat(merged.getSubject()).isEqualTo("database_bytes");
        assertThat(merged.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(merged.getDispositionNote()).isEqualTo("Wachstum erwartet, Retention greift");
        assertThat(merged.getFingerprint()).isEqualTo(
                AuditSnapshotApplier.fingerprint(projectId, performance, "perf.db.size", "database_bytes"));
        assertThat(findingRepository.findById(latest.getId())).isEmpty();
    }

    private AuditFinding saveScopedFinding(String auditKey, String ruleKey, String subject, AuditFindingStatus status,
                                           Instant firstSeenAt, Instant lastSeenAt) {
        return findingRepository.saveAndFlush(AuditFinding.builder()
                .projectId(projectId).auditKey(auditKey)
                .fingerprint(legacyFingerprint(auditKey, ruleKey, subject))
                .ruleKey(ruleKey).subject(subject).severity(AuditSeverity.MEDIUM)
                .title("Befund").evidence("FAKT database_bytes: 1000").status(status)
                .resolvedAt(status == AuditFindingStatus.OPEN ? null : lastSeenAt)
                .firstSeenReportId(reportOld).lastSeenReportId(reportNew)
                .firstSeenRunId("run-old").lastSeenRunId("run-new")
                .firstSeenAt(firstSeenAt).lastSeenAt(lastSeenAt).occurrenceCount(1).build());
    }

    // --- Follow-up finding to 802f740a: fixed subjects per ruleKey -------------------------------------

    private static final String PUBLIC_READINESS = "project.public-readiness";

    private AuditFinding savePublicReadinessFinding(String ruleKey, String subject, AuditFindingStatus status,
                                                    Instant firstSeenAt, Instant lastSeenAt) {
        return findingRepository.saveAndFlush(AuditFinding.builder()
                .projectId(projectId).auditKey(PUBLIC_READINESS)
                .fingerprint(legacyFingerprint(PUBLIC_READINESS, ruleKey, subject))
                .ruleKey(ruleKey).subject(subject).severity(AuditSeverity.MEDIUM)
                .title("Befund").evidence(subject + ": 3 Treffer").status(status)
                .resolvedAt(status == AuditFindingStatus.OPEN ? null : lastSeenAt)
                .firstSeenReportId(reportOld).lastSeenReportId(reportNew)
                .firstSeenRunId("run-old").lastSeenRunId("run-new")
                .firstSeenAt(firstSeenAt).lastSeenAt(lastSeenAt).occurrenceCount(1).build());
    }

    /**
     * A recurrence with a deviating subject of a rule with a fixed value carries the disposition; since the
     * ingestion enforces the fixed value, the next run would no longer find it. The cleanup run merges
     * it into the canonical finding — the disposition including its note goes along.
     */
    @Test
    void mergesDriftedSubjectsOfAFixedRuleOntoTheCanonicalFinding_keepingTheirDisposition() {
        AuditFinding canonical = savePublicReadinessFinding("public.secrets.keyword-hits", "repository",
                AuditFindingStatus.OPEN, T0, T1);
        AuditFinding drifted = savePublicReadinessFinding("public.secrets.keyword-hits",
                "scripts/public-readiness/secrets.sh", AuditFindingStatus.ACCEPTED_RISK, T2, T2);
        drifted.setDispositionNote("Testschluessel, bewusst eingecheckt");
        drifted.setDispositionAt(T2);
        findingRepository.saveAndFlush(drifted);

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, PUBLIC_READINESS))
                .extracting(AuditFinding::getId).containsExactly(canonical.getId());
        AuditFinding merged = findingRepository.findById(canonical.getId()).orElseThrow();
        assertThat(merged.getSubject()).isEqualTo("repository");
        assertThat(merged.getFingerprint()).isEqualTo(AuditSnapshotApplier.fingerprint(projectId, PUBLIC_READINESS,
                "public.secrets.keyword-hits", "repository"));
        assertThat(merged.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(merged.getDispositionNote()).isEqualTo("Testschluessel, bewusst eingecheckt");

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        AuditFinding again = findingRepository.findById(canonical.getId()).orElseThrow();
        assertThat(again.getFingerprint()).as("zweiter Lauf aendert nichts").isEqualTo(merged.getFingerprint());
        assertThat(again.getOccurrenceCount()).isEqualTo(merged.getOccurrenceCount());
        assertThat(again.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
    }

    /** Without a canonical twin the deviating finding itself becomes canonical — disposition stays. */
    @Test
    void movesALoneDriftedFindingOfAFixedRuleOntoTheFixedSubject() {
        AuditFinding drifted = savePublicReadinessFinding("public.deadcode.markers", "ai-automation-backend",
                AuditFindingStatus.DISMISSED, T1, T1);
        AuditFinding pathRule = savePublicReadinessFinding("public.privacy.private-terms", "docs/notes.md",
                AuditFindingStatus.OPEN, T1, T1);

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        AuditFinding stored = findingRepository.findById(drifted.getId()).orElseThrow();
        assertThat(stored.getSubject()).isEqualTo("repository");
        assertThat(stored.getStatus()).isEqualTo(AuditFindingStatus.DISMISSED);
        assertThat(stored.getFingerprint()).isEqualTo(AuditSnapshotApplier.fingerprint(projectId, PUBLIC_READINESS,
                "public.deadcode.markers", "repository"));
        assertThat(findingRepository.findById(pathRule.getId()).orElseThrow().getSubject())
                .as("Pfadregel: kein fester Wert").isEqualTo("docs/notes.md");
    }

    /**
     * Review of 802f740a: two findings of the same fixed rule with an old subject — one of them dispositioned —
     * and the canonical one. All three become one, the disposition including its note stays, no matter in which
     * order the merge hits the recurrences.
     */
    @Test
    void mergesTwoDriftedSubjectsAndTheCanonicalOne_keepingTheAcceptance() {
        AuditFinding canonical = savePublicReadinessFinding("public.deadcode.markers", "repository",
                AuditFindingStatus.OPEN, T0, T0);
        AuditFinding accepted = savePublicReadinessFinding("public.deadcode.markers", "ai-automation-backend",
                AuditFindingStatus.ACCEPTED_RISK, T1, T1);
        accepted.setDispositionNote("Marker sind Absicht");
        accepted.setDispositionAt(T1);
        findingRepository.saveAndFlush(accepted);
        savePublicReadinessFinding("public.deadcode.markers", "ai-automation-frontend", AuditFindingStatus.OPEN, T2, T2);

        assertThat(job.migrate()).isTrue();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, PUBLIC_READINESS))
                .extracting(AuditFinding::getId).containsExactly(canonical.getId());
        AuditFinding merged = findingRepository.findById(canonical.getId()).orElseThrow();
        assertThat(merged.getSubject()).isEqualTo("repository");
        assertThat(merged.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(merged.getDispositionNote()).isEqualTo("Marker sind Absicht");
    }

    /**
     * Review of 802f740a: if two survivors swap their fingerprints, uq(project_id, audit_key,
     * fingerprint) would break on the first write — and the job would fail again at every start. It therefore
     * writes in two phases (first a placeholder, then the target).
     */
    @Test
    void twoSurvivorsSwappingTheirFingerprints_doNotBreakTheUniqueConstraint() {
        String fpA = AuditSnapshotApplier.fingerprint(projectId, PUBLIC_READINESS, "public.privacy.private-terms", "docs/a.md");
        String fpB = AuditSnapshotApplier.fingerprint(projectId, PUBLIC_READINESS, "public.privacy.private-terms", "docs/b.md");
        AuditFinding a = savePublicReadinessFinding("public.privacy.private-terms", "docs/a.md", AuditFindingStatus.OPEN, T1, T1);
        AuditFinding b = savePublicReadinessFinding("public.privacy.private-terms", "docs/b.md", AuditFindingStatus.OPEN, T1, T1);
        a.setFingerprint("fp-tmp");
        findingRepository.saveAndFlush(a);
        b.setFingerprint(fpA);
        findingRepository.saveAndFlush(b);
        a.setFingerprint(fpB);
        findingRepository.saveAndFlush(a);

        assertThat(job.migrate()).as("der Scope laeuft durch").isTrue();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findById(a.getId()).orElseThrow().getFingerprint()).isEqualTo(fpA);
        assertThat(findingRepository.findById(b.getId()).orElseThrow().getFingerprint()).isEqualTo(fpB);
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

    /** Fingerprint before normalization: SHA-256 over raw ruleKey and raw subject. */
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

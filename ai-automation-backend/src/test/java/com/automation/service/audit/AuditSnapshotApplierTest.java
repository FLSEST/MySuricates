package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingOccurrence;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.audit.AuditResultParser.ParsedFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link AuditSnapshotApplier} direkt: was aus einem Report gespeichert wird und woran ein Befund haengt. */
@ExtendWith(MockitoExtension.class)
class AuditSnapshotApplierTest {

    private static final String PROJECT = "proj-1";
    /** Ohne Katalogeintrag: jeder ruleKey wird genommen, wie er kommt. */
    private static final String AUDIT_KEY = "test.audit-ohne-katalog";
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingOccurrenceRepository occurrenceRepository;
    @Mock private ActivityLogService activityLog;
    @Mock private ManagedTodoWriter managedTodoWriter;

    private AuditSnapshotApplier applier;

    @BeforeEach
    void setUp() {
        applier = new AuditSnapshotApplier(findingRepository, occurrenceRepository, new AuditAcceptancePolicy(),
                new AuditRuleKeyCatalog(new ObjectMapper()), activityLog, managedTodoWriter);
        lenient().when(findingRepository.saveAll(any())).thenAnswer(inv -> {
            List<AuditFinding> saved = new ArrayList<>();
            int next = 0;
            for (AuditFinding finding : inv.<Iterable<AuditFinding>>getArgument(0)) {
                if (finding.getId() == null) finding.setId("f-new" + (next++ == 0 ? "" : "-" + next));
                saved.add(finding);
            }
            return saved;
        });
    }

    /** Alle Findings, die {@code saveAll} bekam, in Aufrufreihenfolge. */
    @SuppressWarnings("unchecked")
    private List<AuditFinding> savedFindings() {
        ArgumentCaptor<Iterable<AuditFinding>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(findingRepository, atLeastOnce()).saveAll(captor.capture());
        List<AuditFinding> all = new ArrayList<>();
        captor.getAllValues().forEach(batch -> batch.forEach(all::add));
        return all;
    }

    /** Alle Vorkommen, die {@code saveAll} bekam. */
    @SuppressWarnings("unchecked")
    private List<AuditFindingOccurrence> savedOccurrences() {
        ArgumentCaptor<Iterable<AuditFindingOccurrence>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(occurrenceRepository, atLeastOnce()).saveAll(captor.capture());
        List<AuditFindingOccurrence> all = new ArrayList<>();
        captor.getAllValues().forEach(batch -> batch.forEach(all::add));
        return all;
    }

    private void apply(ParsedFinding... findings) {
        applyTo(AUDIT_KEY, findings);
    }

    private void applyTo(String auditKey, ParsedFinding... findings) {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(this, "run-1", "wf-1", "Audit", PROJECT,
                "/tmp/p", RunStatus.COMPLETED, Map.of(), Map.of(), null);
        AuditReport report = AuditReport.builder().id("rep-1").projectId(PROJECT).auditKey(auditKey)
                .generatedAt(NOW).build();
        applier.apply(event, report, List.of(findings), Set.of());
    }

    // --- TODO 90c4f7db: was audit_findings mit ¦ zeigt, wird mit | gespeichert -------------------

    /** Ein Prompt fuehrt den Befund so weiter, wie der Block ihn zeigt – das Audit Center zeigt ihn wie zuvor. */
    @Test
    void apply_echoedMaskedFields_areStoredWithThePipe_onTheSameFinding() {
        AuditFinding existing = AuditFinding.builder().id("f-1").projectId(PROJECT).auditKey(AUDIT_KEY)
                .fingerprint(AuditSnapshotApplier.fingerprint(PROJECT, AUDIT_KEY, "shell.pipe|curl", "scripts/a.sh | sh"))
                .ruleKey("shell.pipe|curl").subject("scripts/a.sh | sh").title("curl | sh im Installer")
                .severity(AuditSeverity.MEDIUM).status(AuditFindingStatus.OPEN).occurrenceCount(1).build();
        when(findingRepository.findByProjectIdAndAuditKey(PROJECT, AUDIT_KEY)).thenReturn(List.of(existing));

        apply(new ParsedFinding("shell.pipe¦curl", "scripts/a.sh ¦ sh", AuditSeverity.MEDIUM,
                "curl ¦ sh im Installer", null, null, List.of()));

        assertThat(existing.getSubject()).isEqualTo("scripts/a.sh | sh");
        assertThat(existing.getTitle()).isEqualTo("curl | sh im Installer");
        assertThat(existing.getOccurrenceCount()).as("derselbe Befund, kein Wiedergaenger").isEqualTo(2);
        assertThat(savedOccurrences()).singleElement()
                .extracting(AuditFindingOccurrence::getTitle).isEqualTo("curl | sh im Installer");
    }

    // --- Fingerprint mit festen Werten: ohne ¦ wie vor 90c4f7db --------------------------------
    //
    // Die Werte sind unabhaengig berechnet: SHA-256 ueber "projectId\nauditKey\nruleKey\nsubject" (subject hier ohne
    // Normalisierungsbedarf). Aendert ein Umbau die Identitaet eines Befunds ohne ¦, wird jeder gespeicherte Befund
    // beim naechsten Report zum Wiedergaenger – das soll hier rot werden, nicht erst im Audit Center.

    @Test
    void fingerprint_ofAFindingWithoutTheMaskedSeparator_isPinned() {
        assertThat(AuditSnapshotApplier.fingerprint("proj-1", "mysuricates.security", "shell.injection",
                "ContainerShellService"))
                .isEqualTo("19e1ea30058711efd257e0c5d1b1a8ce7df1ebe9b276fe5353bddee31d2a1f4b");
    }

    @Test
    void fingerprint_withTheMaskedSeparator_isTheOneWithThePipe() {
        String expected = "864a46001860cb1f009da8c6b3ad37bf4d9ecf6803a02f3f47f5bb0c34df8963";

        assertThat(AuditSnapshotApplier.fingerprint("proj-1", "test.audit-ohne-katalog", "shell.pipe|curl",
                "scripts/a.sh | sh")).isEqualTo(expected);
        assertThat(AuditSnapshotApplier.fingerprint("proj-1", "test.audit-ohne-katalog", "shell.pipe¦curl",
                "scripts/a.sh ¦ sh")).isEqualTo(expected);
    }

    @Test
    void apply_maskedFieldsOfANewFinding_areStoredWithThePipe() {
        apply(new ParsedFinding("shell.pipe¦curl", "b¦c", AuditSeverity.LOW, "x¦y", null, null, List.of()));

        AuditFinding saved = savedFindings().getFirst();
        assertThat(saved).extracting(AuditFinding::getRuleKey, AuditFinding::getSubject, AuditFinding::getTitle)
                .containsExactly("shell.pipe|curl", "b|c", "x|y");
        assertThat(saved.getFingerprint())
                .isEqualTo(AuditSnapshotApplier.fingerprint(PROJECT, AUDIT_KEY, "shell.pipe|curl", "b|c"));
    }

    // --- TODO 3deaee34: Bestand einmal laden, gesammelt speichern ---------------------------------

    private AuditFinding known(String id, String ruleKey, String subject, AuditFindingStatus status) {
        return AuditFinding.builder().id(id).projectId(PROJECT).auditKey(AUDIT_KEY)
                .fingerprint(AuditSnapshotApplier.fingerprint(PROJECT, AUDIT_KEY, ruleKey, subject))
                .ruleKey(ruleKey).subject(subject).title(subject).severity(AuditSeverity.LOW)
                .status(status).occurrenceCount(1).build();
    }

    private static ParsedFinding low(String ruleKey, String subject) {
        return new ParsedFinding(ruleKey, subject, AuditSeverity.LOW, subject, null, null, List.of());
    }

    /**
     * Drei Befunde: einer bekannt, zwei neu, ein bekannter fehlt. Der Bestand kommt aus einer Abfrage, die
     * Vorkommen des Reports aus einer, gespeichert wird je Art einmal — vorher je Befund eine Suche, ein
     * exists und zwei save.
     */
    @Test
    void apply_loadsTheStockOnce_andSavesFindingsAndOccurrencesInBatches() {
        AuditFinding seen = known("f-seen", "rule.a", "A.java", AuditFindingStatus.RESOLVED);
        AuditFinding gone = known("f-gone", "rule.a", "Gone.java", AuditFindingStatus.OPEN);
        AuditFinding accepted = known("f-acc", "rule.a", "Acc.java", AuditFindingStatus.ACCEPTED_RISK);
        when(findingRepository.findByProjectIdAndAuditKey(PROJECT, AUDIT_KEY)).thenReturn(List.of(seen, gone, accepted));

        apply(low("rule.a", "A.java"), low("rule.a", "B.java"), low("rule.b", "C.java"));

        assertThat(seen.getStatus()).as("wieder da").isEqualTo(AuditFindingStatus.OPEN);
        assertThat(seen.getRegressionCount()).isEqualTo(1);
        assertThat(seen.getOccurrenceCount()).isEqualTo(2);
        assertThat(gone.getStatus()).as("fehlt im Snapshot").isEqualTo(AuditFindingStatus.RESOLVED);
        assertThat(gone.getResolvedAt()).isEqualTo(NOW);
        assertThat(accepted.getStatus()).as("Einordnung bleibt bei Abwesenheit").isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(savedFindings()).extracting(AuditFinding::getSubject)
                .containsExactly("A.java", "B.java", "C.java", "Gone.java");
        assertThat(savedOccurrences()).extracting(AuditFindingOccurrence::getTitle)
                .containsExactly("A.java", "B.java", "C.java");
        verify(findingRepository, times(2)).saveAll(any());
        verify(findingRepository, times(1)).findByProjectIdAndAuditKey(PROJECT, AUDIT_KEY);
        verify(findingRepository, never()).findByProjectIdAndAuditKeyAndFingerprint(any(), any(), any());
        verify(findingRepository, never()).save(any());
        verify(occurrenceRepository, times(1)).findFindingIdsByReportId("rep-1");
        verify(occurrenceRepository, times(1)).saveAll(any());
        verify(occurrenceRepository, never()).existsByFindingIdAndReportId(any(), any());
        verify(occurrenceRepository, never()).save(any());
        verify(managedTodoWriter).reconcile(argThat(touched -> touched.size() == 4));
    }

    /** Ein Vorkommen je Finding und Report: was der Report schon hat, wird nicht noch einmal angelegt. */
    @Test
    void apply_skipsAnOccurrenceTheReportAlreadyHas() {
        AuditFinding seen = known("f-seen", "rule.a", "A.java", AuditFindingStatus.OPEN);
        when(findingRepository.findByProjectIdAndAuditKey(PROJECT, AUDIT_KEY)).thenReturn(List.of(seen));
        when(occurrenceRepository.findFindingIdsByReportId("rep-1")).thenReturn(List.of("f-seen"));

        apply(low("rule.a", "A.java"), low("rule.a", "B.java"));

        assertThat(savedOccurrences()).extracting(AuditFindingOccurrence::getFindingId).containsExactly("f-new");
    }

    /** Ein leerer Snapshot speichert keine Findings, loest aber den Bestand auf. */
    @Test
    void apply_emptySnapshot_onlyResolvesTheStock() {
        AuditFinding gone = known("f-gone", "rule.a", "Gone.java", AuditFindingStatus.AWAITING_VERIFICATION);
        when(findingRepository.findByProjectIdAndAuditKey(PROJECT, AUDIT_KEY)).thenReturn(List.of(gone));

        apply();

        assertThat(gone.getStatus()).isEqualTo(AuditFindingStatus.RESOLVED);
        assertThat(savedFindings()).containsExactly(gone);
        verify(occurrenceRepository, never()).saveAll(any());
        verify(occurrenceRepository, never()).findFindingIdsByReportId(any());
    }

    // --- TODO 802f740a: festes subject je ruleKey aus dem Katalog ---------------------------------

    private static final String PUBLIC_READINESS = "project.public-readiness";

    /** Das Modell weicht vom vorgegebenen subject ab — gespeichert und identifiziert wird der feste Wert. */
    @Test
    void apply_aDriftedSubjectOfARuleWithAFixedSubject_isReplacedByTheCatalogValue() {
        AuditFinding existing = AuditFinding.builder().id("f-keywords").projectId(PROJECT).auditKey(PUBLIC_READINESS)
                .fingerprint(AuditSnapshotApplier.fingerprint(PROJECT, PUBLIC_READINESS, "public.secrets.keyword-hits",
                        "repository"))
                .ruleKey("public.secrets.keyword-hits").subject("repository").title("Treffer")
                .severity(AuditSeverity.MEDIUM).status(AuditFindingStatus.ACCEPTED_RISK).occurrenceCount(3).build();
        when(findingRepository.findByProjectIdAndAuditKey(PROJECT, PUBLIC_READINESS)).thenReturn(List.of(existing));

        applyTo(PUBLIC_READINESS, new ParsedFinding("public.secrets.keyword-hits", "scripts/public-readiness/secrets.sh",
                AuditSeverity.MEDIUM, "Treffer", null, null, List.of()));

        assertThat(savedFindings()).singleElement().isSameAs(existing);
        assertThat(existing.getSubject()).isEqualTo("repository");
        assertThat(existing.getStatus()).as("die Einordnung bleibt, kein Wiedergaenger").isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(existing.getOccurrenceCount()).isEqualTo(4);
    }

    /**
     * Wer sich an den Prompt hielt, behaelt seine Identitaet: der Fingerprint des vorgegebenen subjects ist
     * derselbe wie vor dem Durchsetzen (hier fest gepinnt).
     */
    @Test
    void apply_aPromptCompliantSubject_keepsItsFingerprint() {
        applyTo(PUBLIC_READINESS, new ParsedFinding("public.docs.missing-license", "LICENSE", AuditSeverity.HIGH,
                "Keine Lizenz", null, null, List.of()));

        AuditFinding saved = savedFindings().getFirst();
        assertThat(saved.getSubject()).isEqualTo("LICENSE");
        assertThat(saved.getFingerprint()).isEqualTo(AuditSnapshotApplier.fingerprint(PROJECT, PUBLIC_READINESS,
                "public.docs.missing-license", "LICENSE"));
    }

    /** Dateibezogene Regeln haben keinen festen Wert: dort bleibt das subject des Modells. */
    @Test
    void apply_aPathRule_keepsTheSubjectOfTheModel() {
        applyTo(PUBLIC_READINESS, new ParsedFinding("public.privacy.private-terms", "docs/notes.md", AuditSeverity.LOW,
                "Privater Begriff", null, null, List.of()));

        assertThat(savedFindings().getFirst().getSubject()).isEqualTo("docs/notes.md");
    }

    /** Zwei abweichende subjects derselben festen Regel sind ein Befund; gemeldet wird, was das Modell schickte. */
    @Test
    void apply_twoSubjectsOfAFixedRule_collapseIntoOneFinding_andTheRawSubjectIsReported() {
        applyTo(PUBLIC_READINESS,
                new ParsedFinding("public.deadcode.markers", "ai-automation-backend", AuditSeverity.LOW, "Marker", null, null, List.of()),
                new ParsedFinding("public.deadcode.markers", "ai-automation-frontend", AuditSeverity.LOW, "Marker", null, null, List.of()));

        assertThat(savedFindings()).singleElement().extracting(AuditFinding::getSubject).isEqualTo("repository");
        verify(activityLog).warn(any(), org.mockito.ArgumentMatchers.contains("ai-automation-frontend"), any(),
                any(), any());
    }
}

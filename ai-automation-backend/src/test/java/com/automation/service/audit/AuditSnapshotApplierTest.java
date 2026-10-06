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
import com.automation.repository.AuditReportRepository;
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

/** {@link AuditSnapshotApplier} directly: what is stored from a report and what a finding hangs on. */
@ExtendWith(MockitoExtension.class)
class AuditSnapshotApplierTest {

    private static final String PROJECT = "proj-1";
    /** Without a catalog entry: every ruleKey is taken as it comes. */
    private static final String AUDIT_KEY = "test.audit-ohne-katalog";
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingOccurrenceRepository occurrenceRepository;
    @Mock private ActivityLogService activityLog;
    @Mock private ManagedTodoWriter managedTodoWriter;
    @Mock private AuditReportRepository reportRepository;

    private final AuditRuleKeyCatalog catalog = new AuditRuleKeyCatalog(new ObjectMapper());

    private AuditSnapshotApplier applier;

    @BeforeEach
    void setUp() {
        applier = new AuditSnapshotApplier(findingRepository, occurrenceRepository, new AuditAcceptancePolicy(),
                catalog, activityLog, managedTodoWriter, new AuditDispositionLifecycle(catalog, reportRepository));
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

    /** All findings that {@code saveAll} received, in call order. */
    @SuppressWarnings("unchecked")
    private List<AuditFinding> savedFindings() {
        ArgumentCaptor<Iterable<AuditFinding>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(findingRepository, atLeastOnce()).saveAll(captor.capture());
        List<AuditFinding> all = new ArrayList<>();
        captor.getAllValues().forEach(batch -> batch.forEach(all::add));
        return all;
    }

    /** All occurrences that {@code saveAll} received. */
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

    // --- TODO 90c4f7db: what audit_findings shows with ¦ is stored with | -------------------

    /** A prompt carries the finding on the way the block shows it – the Audit Center shows it as before. */
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

    // --- Fingerprint with fixed values: without ¦ as before 90c4f7db --------------------------------
    //
    // The values are computed independently: SHA-256 over "projectId\nauditKey\nruleKey\nsubject" (subject here without
    // need for normalization). If a refactoring changes the identity of a finding without ¦, every stored finding
    // becomes a recurrence at the next report – that should turn red here, not only in the Audit Center.

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

    // --- TODO 3deaee34: load existing data once, save in bulk ---------------------------------

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
     * Three findings: one known, two new, one known missing. The existing data comes from one query, the
     * occurrences of the report from one, and saving happens once per kind — before, per finding a search, an
     * exists and two saves.
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

    /** One occurrence per finding and report: what the report already has is not created again. */
    @Test
    void apply_skipsAnOccurrenceTheReportAlreadyHas() {
        AuditFinding seen = known("f-seen", "rule.a", "A.java", AuditFindingStatus.OPEN);
        when(findingRepository.findByProjectIdAndAuditKey(PROJECT, AUDIT_KEY)).thenReturn(List.of(seen));
        when(occurrenceRepository.findFindingIdsByReportId("rep-1")).thenReturn(List.of("f-seen"));

        apply(low("rule.a", "A.java"), low("rule.a", "B.java"));

        assertThat(savedOccurrences()).extracting(AuditFindingOccurrence::getFindingId).containsExactly("f-new");
    }

    /** An empty snapshot stores no findings, but resolves the existing data. */
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

    // --- TODO 802f740a: fixed subject per ruleKey from the catalog ---------------------------------

    private static final String PUBLIC_READINESS = "project.public-readiness";

    /** The model deviates from the prescribed subject — the fixed value is stored and identified. */
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
     * Whoever stuck to the prompt keeps their identity: the fingerprint of the prescribed subject is
     * the same as before the enforcement (pinned here).
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

    /** File-related rules have no fixed value: there the model's subject stays. */
    @Test
    void apply_aPathRule_keepsTheSubjectOfTheModel() {
        applyTo(PUBLIC_READINESS, new ParsedFinding("public.privacy.private-terms", "docs/notes.md", AuditSeverity.LOW,
                "Privater Begriff", null, null, List.of()));

        assertThat(savedFindings().getFirst().getSubject()).isEqualTo("docs/notes.md");
    }

    /** Two deviating subjects of the same fixed rule are one finding; what the model sent is reported. */
    @Test
    void apply_twoSubjectsOfAFixedRule_collapseIntoOneFinding_andTheRawSubjectIsReported() {
        applyTo(PUBLIC_READINESS,
                new ParsedFinding("public.deadcode.markers", "ai-automation-backend", AuditSeverity.LOW, "Marker", null, null, List.of()),
                new ParsedFinding("public.deadcode.markers", "ai-automation-frontend", AuditSeverity.LOW, "Marker", null, null, List.of()));

        assertThat(savedFindings()).singleElement().extracting(AuditFinding::getSubject).isEqualTo("repository");
        verify(activityLog).warn(any(), org.mockito.ArgumentMatchers.contains("ai-automation-frontend"), any(),
                any(), any());
    }

    /** 87a1dac0: ActivityLog exists as a Java class and as a frontend page — two findings, no collision. */
    @Test
    void apply_backendClassAndFrontendModuleOfTheSameName_areTwoFindings() {
        apply(low("rule.a", "com/automation/entity/ActivityLog.java"), low("rule.a", "frontend/src/pages/ActivityLog.tsx"));

        assertThat(savedFindings()).extracting(AuditFinding::getSubject)
                .containsExactly("com/automation/entity/ActivityLog.java", "frontend/src/pages/ActivityLog.tsx");
        verify(activityLog, never()).warn(any(), any(), any(), any(), any());
    }
}

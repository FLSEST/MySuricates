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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
        lenient().when(findingRepository.save(any())).thenAnswer(inv -> {
            AuditFinding finding = inv.getArgument(0);
            if (finding.getId() == null) finding.setId("f-new");
            return finding;
        });
    }

    private void apply(ParsedFinding... findings) {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(this, "run-1", "wf-1", "Audit", PROJECT,
                "/tmp/p", RunStatus.COMPLETED, Map.of(), Map.of(), null);
        AuditReport report = AuditReport.builder().id("rep-1").projectId(PROJECT).auditKey(AUDIT_KEY)
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
        when(findingRepository.findByProjectIdAndAuditKeyAndFingerprint(PROJECT, AUDIT_KEY, existing.getFingerprint()))
                .thenReturn(Optional.of(existing));

        apply(new ParsedFinding("shell.pipe¦curl", "scripts/a.sh ¦ sh", AuditSeverity.MEDIUM,
                "curl ¦ sh im Installer", null, null, List.of()));

        assertThat(existing.getSubject()).isEqualTo("scripts/a.sh | sh");
        assertThat(existing.getTitle()).isEqualTo("curl | sh im Installer");
        assertThat(existing.getOccurrenceCount()).as("derselbe Befund, kein Wiedergaenger").isEqualTo(2);
        ArgumentCaptor<AuditFindingOccurrence> occurrence = ArgumentCaptor.forClass(AuditFindingOccurrence.class);
        verify(occurrenceRepository).save(occurrence.capture());
        assertThat(occurrence.getValue().getTitle()).isEqualTo("curl | sh im Installer");
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

        ArgumentCaptor<AuditFinding> saved = ArgumentCaptor.forClass(AuditFinding.class);
        verify(findingRepository).save(saved.capture());
        assertThat(saved.getValue()).extracting(AuditFinding::getRuleKey, AuditFinding::getSubject, AuditFinding::getTitle)
                .containsExactly("shell.pipe|curl", "b|c", "x|y");
        assertThat(saved.getValue().getFingerprint())
                .isEqualTo(AuditSnapshotApplier.fingerprint(PROJECT, AUDIT_KEY, "shell.pipe|curl", "b|c"));
    }
}

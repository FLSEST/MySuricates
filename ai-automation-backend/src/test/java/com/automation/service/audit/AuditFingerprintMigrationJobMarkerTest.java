package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditSeverity;
import com.automation.event.ModuleStateChangedEvent;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Der Aufraeumlauf ist einmalig: ein Marker in {@code app_settings} haelt fest, dass er
 * durchgelaufen ist — sonst scannte jeder Backend-Start alle Findings (Audit-TODO 763ff931).
 * Wird das AUDIT-Modul erst zur Laufzeit eingeschaltet, holt er den Lauf am Modul-Event nach.
 */
@ExtendWith(MockitoExtension.class)
class AuditFingerprintMigrationJobMarkerTest {

    private static final String MARKER = "audit-fingerprint-migration.version";

    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private AuditFindingOccurrenceRepository occurrenceRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private AppSettingsRepository appSettings;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ModuleRegistry modules;

    private AuditFingerprintMigrationJob job;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        job = new AuditFingerprintMigrationJob(findingRepository,
                new AuditFindingMerger(findingTodoRepository, occurrenceRepository, todoRepository),
                new ManagedTodoReconciler(findingRepository, findingTodoRepository, todoRepository),
                appSettings, new TransactionTemplate(transactionManager), modules);
        lenient().when(modules.isEnabled(ModuleCatalog.AUDIT)).thenReturn(true);
    }

    @Test
    void anExistingMarkerSkipsTheScanEntirely() {
        when(appSettings.get(MARKER)).thenReturn(Optional.of("1"));

        job.migrateOnStartup();

        verifyNoInteractions(findingRepository);
        verify(appSettings, never()).set(anyString(), anyString());
    }

    @Test
    void aMissingMarkerRunsTheScanAndRecordsTheVersion() {
        when(appSettings.get(MARKER)).thenReturn(Optional.empty());
        when(findingRepository.findDistinctScopes()).thenReturn(List.<Object[]>of(new Object[]{"p-1", "audit-a"}));
        when(findingRepository.findByProjectIdAndAuditKey("p-1", "audit-a"))
                .thenReturn(List.of(finding("ContainerShellService")));

        job.migrateOnStartup();

        verify(findingRepository).findDistinctScopes();
        verify(appSettings).set(MARKER, "1");
    }

    @Test
    void aFailedScopeLeavesTheMarkerUnset() {
        when(appSettings.get(MARKER)).thenReturn(Optional.empty());
        when(findingRepository.findDistinctScopes()).thenReturn(List.<Object[]>of(new Object[]{"p-1", "audit-a"}));
        when(findingRepository.findByProjectIdAndAuditKey("p-1", "audit-a"))
                .thenThrow(new DataIntegrityViolationException("constraint boom"));

        job.migrateOnStartup();

        verify(appSettings, never()).set(anyString(), anyString());
    }

    @Test
    void switchingTheAuditModuleOnAtRuntimeRunsTheScan() {
        when(appSettings.get(MARKER)).thenReturn(Optional.empty());
        when(findingRepository.findDistinctScopes()).thenReturn(List.of());

        job.onModuleStateChanged(new ModuleStateChangedEvent(this, ModuleCatalog.AUDIT, true));

        verify(findingRepository).findDistinctScopes();
    }

    @Test
    void anotherModuleOrASwitchOffDoesNothing() {
        job.onModuleStateChanged(new ModuleStateChangedEvent(this, ModuleCatalog.WEBHOOKS, true));
        job.onModuleStateChanged(new ModuleStateChangedEvent(this, ModuleCatalog.AUDIT, false));

        verifyNoInteractions(findingRepository, appSettings);
    }

    private static AuditFinding finding(String subject) {
        Instant seen = Instant.parse("2026-09-12T10:30:00Z");
        return AuditFinding.builder().id("f-" + subject).projectId("p-1").auditKey("audit-a")
                .fingerprint("legacy-" + subject).ruleKey("shell.injection").subject(subject)
                .severity(AuditSeverity.HIGH).title("Shell-Aufruf ohne Escaping")
                .status(AuditFindingStatus.OPEN)
                .firstSeenReportId("rep-1").lastSeenReportId("rep-1").firstSeenRunId("run-1").lastSeenRunId("run-1")
                .firstSeenAt(seen).lastSeenAt(seen).occurrenceCount(1).build();
    }
}

package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditSeverity;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The startup job must never prevent the backend from starting: a broken scope (constraint,
 * SQLITE_BUSY) is logged, the remaining scopes still run.
 */
@ExtendWith(MockitoExtension.class)
class AuditFingerprintMigrationJobResilienceTest {

    private static final String RULE_KEY = "shell.injection";

    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private AuditFindingOccurrenceRepository occurrenceRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private com.automation.repository.AppSettingsRepository appSettings;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ModuleRegistry modules;

    private AuditFingerprintMigrationJob job;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        job = new AuditFingerprintMigrationJob(findingRepository,
                new AuditRuleKeyCatalog(new com.fasterxml.jackson.databind.ObjectMapper()),
                new AuditFindingMerger(findingTodoRepository, occurrenceRepository, todoRepository),
                new ManagedTodoReconciler(findingRepository, findingTodoRepository, todoRepository),
                appSettings, new TransactionTemplate(transactionManager), modules);
        when(modules.isEnabled(ModuleCatalog.AUDIT)).thenReturn(true);
    }

    @Test
    void aFailingScopeIsLoggedAndDoesNotStopTheOthers() {
        when(findingRepository.findDistinctScopes()).thenReturn(List.of(
                new Object[]{"p-broken", "audit-a"}, new Object[]{"p-healthy", "audit-a"}));
        when(findingRepository.findByProjectIdAndAuditKey("p-broken", "audit-a"))
                .thenThrow(new DataIntegrityViolationException("constraint boom"));
        AuditFinding healthy = finding("p-healthy", "ContainerShellService.execute");
        when(findingRepository.findByProjectIdAndAuditKey("p-healthy", "audit-a")).thenReturn(List.of(healthy));

        assertThatCode(job::migrate).doesNotThrowAnyException();

        assertThat(healthy.getFingerprint()).as("der gesunde Scope wird trotzdem migriert")
                .isEqualTo(AuditIngestionService.fingerprint("p-healthy", "audit-a", RULE_KEY, "ContainerShellService"));
    }

    @Test
    void emptyTableChangesNothing() {
        when(findingRepository.findDistinctScopes()).thenReturn(List.of());

        job.migrate();

        verify(findingRepository, never()).findByProjectIdAndAuditKey(anyString(), anyString());
        verify(findingRepository, never()).delete(any(AuditFinding.class));
        verify(findingRepository, never()).flush();
        verifyNoInteractions(findingTodoRepository, occurrenceRepository, todoRepository);
    }

    private AuditFinding finding(String projectId, String subject) {
        return AuditFinding.builder().id("f-" + subject.hashCode()).projectId(projectId).auditKey("audit-a")
                .fingerprint("legacy").ruleKey(RULE_KEY).subject(subject).severity(AuditSeverity.HIGH)
                .title("Shell-Aufruf ohne Escaping").status(AuditFindingStatus.OPEN)
                .firstSeenReportId("rep-1").lastSeenReportId("rep-1").firstSeenRunId("run-1").lastSeenRunId("run-1")
                .firstSeenAt(Instant.parse("2026-09-12T10:30:00Z")).lastSeenAt(Instant.parse("2026-09-12T10:30:00Z"))
                .occurrenceCount(1).build();
    }
}

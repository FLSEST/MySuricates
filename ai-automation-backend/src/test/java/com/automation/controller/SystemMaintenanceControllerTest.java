package com.automation.controller;

import com.automation.exception.GlobalExceptionHandler;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.repair.AutoRepairService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.system.DatabaseBackupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemMaintenanceController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemMaintenanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AutoRepairService autoRepairService;
    @MockitoBean
    private DatabaseBackupService databaseBackupService;
    @MockitoBean
    private ActivityLogService activityLogService;
    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    @TempDir
    Path tmp;

    private Path backupFile(String name, String content) throws Exception {
        return Files.writeString(tmp.resolve(name), content);
    }

    @Test
    void repairFailedIstAcceptedMitAnzahlUndForceDefaultFalse() throws Exception {
        when(autoRepairService.repairAllFailed(false)).thenReturn(3);

        mockMvc.perform(post("/api/v1/system/repair-failed"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("repair_started"))
                .andExpect(jsonPath("$.workflowsQueued").value(3))
                .andExpect(jsonPath("$.force").value(false));
    }

    @Test
    void repairFailedReichtForceDurch() throws Exception {
        when(autoRepairService.repairAllFailed(true)).thenReturn(0);

        mockMvc.perform(post("/api/v1/system/repair-failed").param("force", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.force").value(true));
        verify(autoRepairService).repairAllFailed(true);
    }

    @Test
    void repairFailedHaengtAmFactoryModulVacuumNicht() throws Exception {
        RequiresModule repair = SystemMaintenanceController.class
                .getMethod("repairAllFailed", boolean.class).getAnnotation(RequiresModule.class);
        assertThat(repair.value()).isEqualTo(ModuleCatalog.FACTORY);
        assertThat(SystemMaintenanceController.class.getAnnotation(RequiresModule.class)).isNull();
        assertThat(SystemMaintenanceController.class.getMethod("vacuumDatabase")
                .getAnnotation(RequiresModule.class)).isNull();
    }

    @Test
    void vacuumErfolgreichIst200() throws Exception {
        when(databaseBackupService.fullVacuum()).thenReturn(Map.of("status", "ok", "freedBytes", 1024));

        mockMvc.perform(post("/api/v1/system/vacuum"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.freedBytes").value(1024));
    }

    @Test
    void vacuumFehlschlagIst500MitErgebnisImBody() throws Exception {
        when(databaseBackupService.fullVacuum()).thenReturn(Map.of("status", "failed", "error", "locked"));

        mockMvc.perform(post("/api/v1/system/vacuum"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("locked"));
    }

    @Test
    void backupDownloadLiefertDateiAlsAnhang() throws Exception {
        Path file = backupFile("mysuricates-backup-2026-01-02_03-04-05.db", "SQLITE-BYTES");
        when(databaseBackupService.createBackup()).thenReturn(file);

        mockMvc.perform(get("/api/v1/system/backup"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"mysuricates-backup-2026-01-02_03-04-05.db\""))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 12))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().string("SQLITE-BYTES"));
    }

    @Test
    void backupAnlegenRotiertDanachUndMeldetMetadaten() throws Exception {
        Path file = backupFile("mysuricates-backup-2026-01-02_03-04-05.db", "12345");
        when(databaseBackupService.createBackup("vor-update")).thenReturn(file);

        mockMvc.perform(post("/api/v1/system/backup").param("reason", "vor-update"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("mysuricates-backup-2026-01-02_03-04-05.db"))
                .andExpect(jsonPath("$.sizeBytes").value(5))
                .andExpect(jsonPath("$.createdAt").exists());

        var order = inOrder(databaseBackupService);
        order.verify(databaseBackupService).createBackup("vor-update");
        order.verify(databaseBackupService).rotateBackups();
    }

    @Test
    void backupAnlegenOhneGrundReichtNullDurch() throws Exception {
        Path file = backupFile("b.db", "x");
        when(databaseBackupService.createBackup(null)).thenReturn(file);

        mockMvc.perform(post("/api/v1/system/backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("b.db"));
        verify(databaseBackupService, never()).createBackup();
    }

    @Test
    void backupListe() throws Exception {
        when(databaseBackupService.listBackups()).thenReturn(List.of(
                new DatabaseBackupService.BackupInfo("a.db", 10, Instant.parse("2026-01-01T00:00:00Z"))));

        mockMvc.perform(get("/api/v1/system/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").value("a.db"))
                .andExpect(jsonPath("$[0].sizeBytes").value(10));
    }

    @Test
    void einzelnesBackupHerunterladen() throws Exception {
        Path file = backupFile("a.db", "inhalt");
        when(databaseBackupService.getBackupFile("a.db")).thenReturn(file);

        mockMvc.perform(get("/api/v1/system/backups/a.db"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"a.db\""))
                .andExpect(content().string("inhalt"));
    }

    @Test
    void unbekanntesOderUnzulaessigesBackupIst404() throws Exception {
        when(databaseBackupService.getBackupFile("fehlt.db")).thenReturn(null);

        mockMvc.perform(get("/api/v1/system/backups/fehlt.db"))
                .andExpect(status().isNotFound());
    }
}

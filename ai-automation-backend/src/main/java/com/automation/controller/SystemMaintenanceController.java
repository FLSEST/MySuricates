package com.automation.controller;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.repair.AutoRepairService;
import com.automation.service.system.DatabaseBackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Wartung unter /api/v1/system: Auto-Repair, VACUUM, Datenbank-Backups. */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemMaintenanceController {

    private final AutoRepairService autoRepairService;
    private final DatabaseBackupService databaseBackupService;

    @PostMapping("/repair-failed")
    @RequiresModule(ModuleCatalog.FACTORY)
    public ResponseEntity<Map<String, Object>> repairAllFailed(
            @RequestParam(defaultValue = "false") boolean force) {
        int queued = autoRepairService.repairAllFailed(force);
        return ResponseEntity.accepted().body(Map.of(
                "status", "repair_started",
                "workflowsQueued", queued,
                "force", force));
    }

    @PostMapping("/vacuum")
    public ResponseEntity<Map<String, Object>> vacuumDatabase() {
        Map<String, Object> result = databaseBackupService.fullVacuum();
        if ("failed".equals(result.get("status"))) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/backup")
    public ResponseEntity<Resource> backupDatabase() throws IOException {
        Path backupPath = databaseBackupService.createBackup();
        String filename = backupPath.getFileName().toString();

        InputStreamResource resource = new InputStreamResource(new FileInputStream(backupPath.toFile()));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(backupPath))
                .body(resource);
    }

    public record BackupMetadata(String filename, long sizeBytes, Instant createdAt) {}

    @PostMapping("/backup")
    public ResponseEntity<BackupMetadata> createBackup(
            @RequestParam(required = false) String reason) throws IOException {
        Path backupPath = databaseBackupService.createBackup(reason);
        databaseBackupService.rotateBackups();
        return ResponseEntity.ok(new BackupMetadata(
                backupPath.getFileName().toString(),
                Files.size(backupPath),
                Instant.now()
        ));
    }

    @GetMapping("/backups")
    public List<DatabaseBackupService.BackupInfo> listBackups() throws IOException {
        return databaseBackupService.listBackups();
    }

    @GetMapping("/backups/{filename}")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String filename) throws IOException {
        Path file = databaseBackupService.getBackupFile(filename);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }

        InputStreamResource resource = new InputStreamResource(new FileInputStream(file.toFile()));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .body(resource);
    }
}

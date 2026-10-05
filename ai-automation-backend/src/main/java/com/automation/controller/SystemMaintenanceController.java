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

    /**
     * Queues a repair for every workflow with a failed run and answers 202 with the number queued.
     * Without {@code force=true} only workflows with auto-repair enabled; sealed workflows are always skipped.
     */
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

    /** Runs a full SQLite VACUUM and reports its duration; a failed VACUUM is 500. */
    @PostMapping("/vacuum")
    public ResponseEntity<Map<String, Object>> vacuumDatabase() {
        Map<String, Object> result = databaseBackupService.fullVacuum();
        if ("failed".equals(result.get("status"))) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** Creates a backup of the database now and returns it as a file download. */
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

    /**
     * Creates a backup of the database and returns its file name, size and time.
     * An optional {@code reason} goes into the activity log; old backups beyond the retain count are deleted.
     */
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

    /** Lists the backup files with name, size and creation time, newest first. */
    @GetMapping("/backups")
    public List<DatabaseBackupService.BackupInfo> listBackups() throws IOException {
        return databaseBackupService.listBackups();
    }

    /** Downloads one backup file; an unknown name or one with a path in it is 404. */
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

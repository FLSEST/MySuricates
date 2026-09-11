package com.automation.service.system;

import com.automation.config.BackupConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.service.activitylog.ActivityLogService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseBackupService {

    private final DataSource dataSource;
    private final BackupConfig backupConfig;
    private final ActivityLogService activityLogService;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @PostConstruct
    void init() throws IOException {
        Path dir = Path.of(backupConfig.getDirectory());
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("Created backup directory: {}", dir);
        }

        if (backupConfig.isEnabled()) {
            try {
                Path backup = createBackup();
                rotateBackups();
                log.info("Startup backup created: {}", backup.getFileName());
                activityLogService.info(LogCategory.SYSTEM, "Startup backup created: " + backup.getFileName(), "DatabaseBackupService");
            } catch (Exception e) {
                log.warn("Startup backup failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Creates a WAL-safe backup of the SQLite database.
     *
     * @return the path to the created backup file
     */
    public Path createBackup() throws IOException {
        Path dbPath = resolveDbPath();
        if (!Files.exists(dbPath)) {
            throw new IOException("Database file not found: " + dbPath);
        }

        // Flush WAL to main database file before copying
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(FULL)");
        } catch (Exception e) {
            log.warn("WAL checkpoint before backup failed: {}", e.getMessage());
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String filename = "mysuricates-backup-" + timestamp + ".db";
        Path backupDir = Path.of(backupConfig.getDirectory());
        Path backupPath = backupDir.resolve(filename);

        Files.copy(dbPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

        // Also copy WAL and SHM if they exist (for consistency)
        Path walPath = Path.of(dbPath + "-wal");
        Path shmPath = Path.of(dbPath + "-shm");
        if (Files.exists(walPath)) {
            Files.copy(walPath, Path.of(backupPath + "-wal"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(shmPath)) {
            Files.copy(shmPath, Path.of(backupPath + "-shm"), StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Database backup created: {} ({})", filename, formatSize(Files.size(backupPath)));
        return backupPath;
    }

    /**
     * Creates a backup and logs the given reason to the activity log.
     * Used for pre-sync / pre-seed rollback points so operators can identify
     * why a backup was created when browsing the backup list.
     */
    public Path createBackup(String reason) throws IOException {
        Path backup = createBackup();
        if (reason != null && !reason.isBlank()) {
            activityLogService.info(
                    LogCategory.SYSTEM,
                    "Backup created: " + backup.getFileName() + " (reason: " + reason + ")",
                    "DatabaseBackupService"
            );
        }
        return backup;
    }

    /**
     * Deletes oldest backups exceeding the retain count.
     */
    public int rotateBackups() throws IOException {
        Path backupDir = Path.of(backupConfig.getDirectory());
        List<Path> backups = listBackupFiles(backupDir);

        int deleted = 0;
        if (backups.size() > backupConfig.getRetainCount()) {
            // Sort oldest first
            backups.sort(Comparator.comparing(p -> {
                try {
                    return Files.readAttributes(p, BasicFileAttributes.class).creationTime().toInstant();
                } catch (IOException e) {
                    return Instant.EPOCH;
                }
            }));

            int toDelete = backups.size() - backupConfig.getRetainCount();
            for (int i = 0; i < toDelete; i++) {
                Path backup = backups.get(i);
                Files.deleteIfExists(backup);
                Files.deleteIfExists(Path.of(backup + "-wal"));
                Files.deleteIfExists(Path.of(backup + "-shm"));
                deleted++;
                log.debug("Rotated old backup: {}", backup.getFileName());
            }
        }
        return deleted;
    }

    /**
     * Lists all backup files with metadata.
     */
    public List<BackupInfo> listBackups() throws IOException {
        Path backupDir = Path.of(backupConfig.getDirectory());
        if (!Files.exists(backupDir)) {
            return List.of();
        }

        List<Path> files = listBackupFiles(backupDir);
        // Sort newest first
        files.sort(Comparator.<Path, Instant>comparing(p -> {
            try {
                return Files.readAttributes(p, BasicFileAttributes.class).creationTime().toInstant();
            } catch (IOException e) {
                return Instant.EPOCH;
            }
        }).reversed());

        List<BackupInfo> result = new ArrayList<>();
        for (Path file : files) {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            result.add(new BackupInfo(
                    file.getFileName().toString(),
                    Files.size(file),
                    attrs.creationTime().toInstant()
            ));
        }
        return result;
    }

    /**
     * Returns the path to a specific backup file, or null if not found.
     */
    public Path getBackupFile(String filename) {
        // Prevent path traversal
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return null;
        }
        Path file = Path.of(backupConfig.getDirectory()).resolve(filename);
        return Files.exists(file) ? file : null;
    }

    /**
     * {@code PRAGMA auto_vacuum} only takes effect on an empty database or after a full VACUUM; the
     * Hikari init-sql sets INCREMENTAL, but a database created before that setting silently stays
     * at NONE and {@link #scheduledIncrementalVacuum()} becomes a no-op. Surface that at startup so
     * a one-off {@code POST /api/v1/system/vacuum} can convert the file.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void checkAutoVacuumMode() {
        // Read the pragma and release the connection BEFORE logging: pool-size=1, and the
        // ActivityLog write is itself transactional — holding the connection here deadlocks.
        int mode;
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement();
             var rs = stmt.executeQuery("PRAGMA auto_vacuum")) {
            mode = rs.next() ? rs.getInt(1) : -1;
        } catch (Exception e) {
            log.debug("auto_vacuum check skipped: {}", e.getMessage());
            return;
        }
        if (mode == 2) {
            log.info("SQLite auto_vacuum = INCREMENTAL (nightly incremental_vacuum active)");
            return;
        }
        String text = mode == 1 ? "FULL" : mode == 0 ? "NONE" : "unknown(" + mode + ")";
        activityLogService.warn(LogCategory.SYSTEM,
                "SQLite auto_vacuum ist " + text + " statt INCREMENTAL — das nächtliche incremental_vacuum "
                        + "gibt keinen Platz frei. Einmal POST /api/v1/system/vacuum ausführen (volles VACUUM "
                        + "übernimmt das Pragma).",
                "DatabaseBackupService");
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledIncrementalVacuum() {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA incremental_vacuum(1000)");
            log.info("Incremental vacuum completed (up to 1000 pages reclaimed)");
        } catch (Exception e) {
            log.warn("Incremental vacuum failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> fullVacuum() {
        long startTime = System.currentTimeMillis();
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("VACUUM");
            long durationMs = System.currentTimeMillis() - startTime;
            log.info("Full VACUUM completed in {} ms", durationMs);
            return Map.of("status", "completed", "durationMs", durationMs);
        } catch (Exception e) {
            log.error("VACUUM failed: {}", e.getMessage(), e);
            return Map.of("status", "failed",
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        }
    }

    @Scheduled(cron = "${automation.backup.cron:0 0 2 * * *}")
    public void scheduledBackup() {
        if (!backupConfig.isEnabled()) {
            return;
        }

        try {
            Path backup = createBackup();
            int rotated = rotateBackups();
            String msg = "Scheduled backup created: " + backup.getFileName();
            if (rotated > 0) {
                msg += " (" + rotated + " old backup(s) rotated)";
            }
            activityLogService.info(LogCategory.SYSTEM, msg, "DatabaseBackupService");
        } catch (Exception e) {
            log.error("Scheduled backup failed", e);
            activityLogService.error(LogCategory.SYSTEM, "Scheduled backup failed: " + e.getMessage(), "DatabaseBackupService");
        }
    }

    public boolean isEnabled() {
        return backupConfig.isEnabled();
    }

    Path resolveDbPath() {
        String url = datasourceUrl;
        if (url.startsWith("jdbc:sqlite:")) {
            url = url.substring("jdbc:sqlite:".length());
        }
        int queryIdx = url.indexOf('?');
        if (queryIdx > 0) {
            url = url.substring(0, queryIdx);
        }
        return Path.of(url).toAbsolutePath().normalize();
    }

    private List<Path> listBackupFiles(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.exists(directory)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.db")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    files.add(entry);
                }
            }
        }
        return files;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    public record BackupInfo(String filename, long sizeBytes, Instant createdAt) {}
}

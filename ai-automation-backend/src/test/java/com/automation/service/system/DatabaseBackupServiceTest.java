package com.automation.service.system;

import com.automation.config.BackupConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseBackupServiceTest {

    @TempDir
    Path tempDir;

    private Path dbFile;
    private Path backupDir;
    private DataSource dataSource;
    private ActivityLogService activityLogService;
    private BackupConfig backupConfig;
    private AppSettingsRepository appSettingsRepository;
    private DatabaseBackupService service;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tempDir.resolve("test.db");
        Files.writeString(dbFile, "SQLite test database content");

        backupDir = tempDir.resolve("backups");
        Files.createDirectories(backupDir);

        dataSource = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);

        activityLogService = mock(ActivityLogService.class);

        backupConfig = new BackupConfig();
        backupConfig.setEnabled(true);
        backupConfig.setRetainCount(3);
        backupConfig.setDirectory(backupDir.toString());

        appSettingsRepository = mock(AppSettingsRepository.class);
        service = new DatabaseBackupService(dataSource, backupConfig, activityLogService,
                new SystemSettingsService(appSettingsRepository, backupConfig, mock(ApplicationEventPublisher.class)));
        // Inject datasource URL via reflection
        var field = DatabaseBackupService.class.getDeclaredField("datasourceUrl");
        field.setAccessible(true);
        field.set(service, "jdbc:sqlite:" + dbFile.toAbsolutePath() + "?journal_mode=WAL");
    }

    @Test
    void createBackup_createsFileInBackupDirectory() throws IOException {
        Path backup = service.createBackup();

        assertThat(backup).exists();
        assertThat(backup.getParent()).isEqualTo(backupDir);
        assertThat(backup.getFileName().toString()).startsWith("mysuricates-backup-").endsWith(".db");
        assertThat(Files.readString(backup)).isEqualTo("SQLite test database content");
    }

    @Test
    void createBackup_copiesWalAndShmIfPresent() throws IOException {
        Files.writeString(Path.of(dbFile + "-wal"), "wal data");
        Files.writeString(Path.of(dbFile + "-shm"), "shm data");

        Path backup = service.createBackup();

        assertThat(Path.of(backup + "-wal")).exists();
        assertThat(Path.of(backup + "-shm")).exists();
    }

    @Test
    void createBackup_throwsIfDbFileMissing() {
        dbFile.toFile().delete();

        assertThatThrownBy(() -> service.createBackup())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Database file not found");
    }

    @Test
    void rotateBackups_deletesOldestWhenExceedingRetainCount() throws Exception {
        // Create 5 backup files (retain is 3)
        for (int i = 0; i < 5; i++) {
            Path f = backupDir.resolve("mysuricates-backup-2025-01-0" + (i + 1) + "_02-00-00.db");
            Files.writeString(f, "backup " + i);
            // Slight delay to differentiate creation times
            Thread.sleep(50);
        }

        int deleted = service.rotateBackups();

        assertThat(deleted).isEqualTo(2);
        List<DatabaseBackupService.BackupInfo> remaining = service.listBackups();
        assertThat(remaining).hasSize(3);
    }

    /**
     * 205ebd8f: the stored backup-retain-count counts, read at rotation time — after a restart too,
     * where it used to fall back to automation.backup.retain-count (here 3) until the next change.
     */
    @Test
    void rotateBackups_keepsTheStoredRetainCount_notTheConfiguredOne() throws Exception {
        when(appSettingsRepository.get("backup-retain-count")).thenReturn(Optional.of("4"));
        for (int i = 0; i < 6; i++) {
            Files.writeString(backupDir.resolve("mysuricates-backup-2025-01-0" + (i + 1) + "_02-00-00.db"), "backup " + i);
            Thread.sleep(50);
        }

        int deleted = service.rotateBackups();

        assertThat(deleted).isEqualTo(2);
        assertThat(service.listBackups()).hasSize(4);
    }

    @Test
    void rotateBackups_deletesAssociatedWalAndShm() throws Exception {
        for (int i = 0; i < 4; i++) {
            Path f = backupDir.resolve("mysuricates-backup-2025-01-0" + (i + 1) + "_02-00-00.db");
            Files.writeString(f, "data");
            Files.writeString(Path.of(f + "-wal"), "wal");
            Files.writeString(Path.of(f + "-shm"), "shm");
            Thread.sleep(50);
        }

        service.rotateBackups();

        // 1 should have been deleted (4 - retainCount 3 = 1)
        long dbCount = Files.list(backupDir).filter(p -> p.toString().endsWith(".db")).count();
        assertThat(dbCount).isEqualTo(3);
    }

    @Test
    void rotateBackups_ignoresFilesOutsideOwnNamingScheme() throws Exception {
        // Foreign .db files in the backup directory (manual copies, other instances, other
        // naming schemes) must never be rotated away — only our own timestamped backups count.
        Files.writeString(backupDir.resolve("manual-copy.db"), "manual");
        Files.writeString(backupDir.resolve("mysuricates-backup-before-migration.db"), "manual");
        Files.writeString(backupDir.resolve("mysuricates.db"), "manual");
        Thread.sleep(50);
        for (int i = 0; i < 5; i++) {
            Files.writeString(backupDir.resolve("mysuricates-backup-2025-02-0" + (i + 1) + "_02-00-00.db"), "own " + i);
            Thread.sleep(50);
        }

        int deleted = service.rotateBackups();

        // 5 own backups, retain 3 → exactly the 2 oldest OWN files go
        assertThat(deleted).isEqualTo(2);
        assertThat(backupDir.resolve("manual-copy.db")).exists();
        assertThat(backupDir.resolve("mysuricates-backup-before-migration.db")).exists();
        assertThat(backupDir.resolve("mysuricates.db")).exists();
        assertThat(backupDir.resolve("mysuricates-backup-2025-02-01_02-00-00.db")).doesNotExist();
        assertThat(backupDir.resolve("mysuricates-backup-2025-02-02_02-00-00.db")).doesNotExist();
        assertThat(backupDir.resolve("mysuricates-backup-2025-02-03_02-00-00.db")).exists();
        // Foreign files stay visible in the listing (operators must still see them)
        assertThat(service.listBackups()).hasSize(6);
    }

    @Test
    void rotateBackups_foreignFilesDoNotCountTowardsRetainLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            Files.writeString(backupDir.resolve("foreign-" + i + ".db"), "x");
        }
        Files.writeString(backupDir.resolve("mysuricates-backup-2025-03-01_02-00-00.db"), "own");

        int deleted = service.rotateBackups();

        assertThat(deleted).isZero();
        assertThat(Files.list(backupDir).count()).isEqualTo(11);
    }

    @Test
    void isOwnBackupFile_matchesOnlyTimestampedScheme() {
        assertThat(BackupFileNaming.isOwnBackupFile(Path.of("mysuricates-backup-2026-09-13_16-01-05.db"))).isTrue();
        assertThat(BackupFileNaming.isOwnBackupFile(Path.of("/x/y/mysuricates-backup-2026-09-13_16-01-05.db"))).isTrue();
        assertThat(BackupFileNaming.isOwnBackupFile(Path.of("mysuricates-backup-2026-09-13_16-01-05.db-wal"))).isFalse();
        assertThat(BackupFileNaming.isOwnBackupFile(Path.of("mysuricates-backup-2026-09-13.db"))).isFalse();
        assertThat(BackupFileNaming.isOwnBackupFile(Path.of("Mysuricates-backup-2026-09-13_16-01-05.db"))).isFalse();
        assertThat(BackupFileNaming.isOwnBackupFile(Path.of("backup.db"))).isFalse();
    }

    @Test
    void createBackup_producesFileMatchingOwnScheme() throws IOException {
        Path backup = service.createBackup();

        assertThat(BackupFileNaming.isOwnBackupFile(backup))
                .as("what createBackup writes must be what rotateBackups recognises")
                .isTrue();
    }

    @Test
    void rotateBackups_noOpWhenUnderRetainCount() throws IOException {
        Files.writeString(backupDir.resolve("backup.db"), "data");

        int deleted = service.rotateBackups();

        assertThat(deleted).isZero();
    }

    @Test
    void listBackups_returnsEmptyForEmptyDirectory() throws IOException {
        List<DatabaseBackupService.BackupInfo> list = service.listBackups();
        assertThat(list).isEmpty();
    }

    @Test
    void listBackups_returnsFilesNewestFirst() throws Exception {
        Files.writeString(backupDir.resolve("old.db"), "old");
        Thread.sleep(50);
        Files.writeString(backupDir.resolve("new.db"), "newer");

        List<DatabaseBackupService.BackupInfo> list = service.listBackups();

        assertThat(list).hasSize(2);
        assertThat(list.get(0).filename()).isEqualTo("new.db");
        assertThat(list.get(1).filename()).isEqualTo("old.db");
    }

    @Test
    void listBackups_excludesNonDbFiles() throws IOException {
        Files.writeString(backupDir.resolve("backup.db"), "data");
        Files.writeString(backupDir.resolve("backup.db-wal"), "wal");
        Files.writeString(backupDir.resolve("notes.txt"), "text");

        List<DatabaseBackupService.BackupInfo> list = service.listBackups();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).filename()).isEqualTo("backup.db");
    }

    @Test
    void getBackupFile_returnsPathForExistingFile() throws IOException {
        Files.writeString(backupDir.resolve("test.db"), "data");

        Path result = service.getBackupFile("test.db");

        assertThat(result).isNotNull();
        assertThat(result.getFileName().toString()).isEqualTo("test.db");
    }

    @Test
    void getBackupFile_returnsNullForMissing() {
        assertThat(service.getBackupFile("nonexistent.db")).isNull();
    }

    @Test
    void getBackupFile_rejectsPathTraversal() {
        assertThat(service.getBackupFile("../etc/passwd")).isNull();
        assertThat(service.getBackupFile("foo/bar.db")).isNull();
        assertThat(service.getBackupFile("foo\\bar.db")).isNull();
    }

    @Test
    void createBackupWithReason_logsReasonToActivityLog() throws IOException {
        Path backup = service.createBackup("pre-sync-definitions");

        assertThat(backup).exists();
        verify(activityLogService).info(
                any(),
                contains("pre-sync-definitions"),
                eq("DatabaseBackupService"));
    }

    @Test
    void createBackupWithReason_nullReason_skipsActivityLog() throws IOException {
        Path backup = service.createBackup((String) null);

        assertThat(backup).exists();
        verifyNoInteractions(activityLogService);
    }

    @Test
    void createBackupWithReason_blankReason_skipsActivityLog() throws IOException {
        Path backup = service.createBackup("  ");

        assertThat(backup).exists();
        verifyNoInteractions(activityLogService);
    }

    @Test
    void scheduledBackup_whenEnabled_createsBackupAndLogs() throws IOException {
        service.scheduledBackup();

        List<DatabaseBackupService.BackupInfo> backups = service.listBackups();
        assertThat(backups).hasSize(1);
        verify(activityLogService).info(any(), contains("Scheduled backup created"), eq("DatabaseBackupService"));
    }

    @Test
    void scheduledBackup_whenDisabled_doesNothing() throws IOException {
        backupConfig.setEnabled(false);

        service.scheduledBackup();

        List<DatabaseBackupService.BackupInfo> backups = service.listBackups();
        assertThat(backups).isEmpty();
        verifyNoInteractions(activityLogService);
    }

    @Test
    void isEnabled_reflectsConfig() {
        assertThat(service.isEnabled()).isTrue();

        backupConfig.setEnabled(false);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void resolveDbPath_stripsJdbcPrefixAndQueryParams() {
        Path path = service.resolveDbPath();
        assertThat(path.toString()).endsWith("test.db");
        assertThat(path.toString()).doesNotContain("jdbc:");
        assertThat(path.toString()).doesNotContain("?");
    }

    // ── auto_vacuum startup check ──────────────────────────────────────────────────

    private void stubAutoVacuum(int mode) throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("PRAGMA auto_vacuum")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(mode);
    }

    @Test
    void checkAutoVacuumMode_none_warnsWithVacuumHint() throws Exception {
        stubAutoVacuum(0);

        service.checkAutoVacuumMode();

        verify(activityLogService).warn(eq(com.automation.entity.ActivityLog.LogCategory.SYSTEM),
                contains("NONE"), eq("DatabaseBackupService"));
        verify(activityLogService).warn(any(), contains("/api/v1/system/vacuum"), any());
    }

    @Test
    void checkAutoVacuumMode_incremental_staysQuiet() throws Exception {
        stubAutoVacuum(2);

        service.checkAutoVacuumMode();

        verify(activityLogService, never()).warn(any(), any(), any());
    }

    @Test
    void checkAutoVacuumMode_queryFails_isSwallowed() throws Exception {
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("locked"));

        service.checkAutoVacuumMode();

        verify(activityLogService, never()).warn(any(), any(), any());
    }
}

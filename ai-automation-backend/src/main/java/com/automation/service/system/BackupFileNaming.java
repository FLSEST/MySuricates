package com.automation.service.system;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Naming scheme of the backups {@link DatabaseBackupService} writes:
 * {@code mysuricates-backup-yyyy-MM-dd_HH-mm-ss.db}.
 *
 * <p>Rotation only counts and deletes files that match this scheme. Manual copies or backups
 * with a foreign naming scheme in the same directory are listed but never deleted — rotation
 * must not eat an operator's hand-made rollback point just because a burst of startups
 * exceeded the retain count (Audit-TODO 9b1c4aee).
 */
final class BackupFileNaming {

    static final String PREFIX = "mysuricates-backup-";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final Pattern OWN_BACKUP_FILE = Pattern.compile(
            Pattern.quote(PREFIX) + "\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.db");

    private BackupFileNaming() {}

    static String filenameFor(LocalDateTime timestamp) {
        return PREFIX + timestamp.format(TIMESTAMP) + ".db";
    }

    /** True only for files this service created itself (by name; the path may be anywhere). */
    static boolean isOwnBackupFile(Path file) {
        return OWN_BACKUP_FILE.matcher(file.getFileName().toString()).matches();
    }
}

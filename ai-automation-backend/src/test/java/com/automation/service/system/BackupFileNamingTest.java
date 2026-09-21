package com.automation.service.system;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BackupFileNamingTest {

    @Test
    void filenameForFormatiertMitNullenUndOhneMillis() {
        LocalDateTime ts = LocalDateTime.of(2026, 3, 4, 5, 6, 7, 999_000_000);
        assertThat(BackupFileNaming.filenameFor(ts)).isEqualTo("mysuricates-backup-2026-03-04_05-06-07.db");
    }

    @Test
    void eigeneDateienWerdenErkanntUnabhaengigVomVerzeichnis() {
        String name = BackupFileNaming.filenameFor(LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        assertThat(BackupFileNaming.isOwnBackupFile(Path.of(name))).isTrue();
        assertThat(BackupFileNaming.isOwnBackupFile(Path.of("/irgendwo/tief", name))).isTrue();
    }

    @Test
    void fremdeUndManuelleDateienSindNichtEigene() {
        for (String foreign : new String[]{
                "mysuricates-backup-2026-03-04_05-06-07.db.bak",
                "vor-update-mysuricates-backup-2026-03-04_05-06-07.db",
                "mysuricates-backup-2026-03-04_05-06-07-manual.db",
                "mysuricates-backup-2026-3-4_5-6-7.db",
                "mysuricates-backup-2026-03-04 05-06-07.db",
                "mysuricates-backup-latest.db",
                "automation.db"}) {
            assertThat(BackupFileNaming.isOwnBackupFile(Path.of(foreign))).as(foreign).isFalse();
        }
    }

    @Test
    void praefixIstTeilDesSchemas() {
        assertThat(BackupFileNaming.filenameFor(LocalDateTime.now())).startsWith(BackupFileNaming.PREFIX);
        // Der Punkt im Muster ist quotiert/escaped — kein beliebiges Zeichen vor "db"
        assertThat(BackupFileNaming.isOwnBackupFile(Path.of("mysuricates-backup-2026-03-04_05-06-07xdb"))).isFalse();
    }
}

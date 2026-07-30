package com.automation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Aktiviert Spring-Scheduling — bewusst NICHT im Profil {@code integration}.
 *
 * <p>Integrationstests laufen gegen eine echte SQLite-Datei. Mit aktivem Scheduling schreiben
 * Hintergrund-Jobs waehrend des Testlaufs in dieselbe DB: {@code HealthAutoTodoService} legt z. B.
 * ein TODO an, sobald die Stable- oder Test-Instanz nicht erreichbar ist (was seit dem Wegfall des
 * Auto-Starts der Normalfall ist). Solche Fremdzeilen ueberleben den {@code @Transactional}-Rollback
 * der Tests, weil sie in einem eigenen Thread und einer eigenen Transaktion entstehen — global
 * zaehlende Assertions (countByStatus, findBacklogCandidates, ...) kippen dadurch abhaengig davon,
 * ob gerade ein Container laeuft.
 *
 * <p>Zusaetzlich konkurrierten die Jobs mit den Tests um die einzige HikariCP-Connection
 * (pool-size=1, siehe CLAUDE.md).
 *
 * <p>Scheduled-Methoden werden in Tests direkt aufgerufen (z. B. {@code SchedulerServiceTest}),
 * ihre Logik bleibt also vollstaendig abgedeckt.
 */
@Configuration
@Profile("!integration")
@EnableScheduling
public class SchedulingConfig {
}

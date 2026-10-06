package com.automation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring scheduling — deliberately NOT in the {@code integration} profile.
 *
 * <p>Integration tests run against a real SQLite file. With scheduling active,
 * background jobs write to the same DB during the test run: {@code HealthAutoTodoService} e.g. creates
 * a TODO as soon as the Stable or Test instance is unreachable (which has been the normal case since auto-start
 * was dropped). Such foreign rows survive the tests' {@code @Transactional} rollback,
 * because they arise in a thread and a transaction of their own — globally
 * counting assertions (countByStatus, findBacklogCandidates, ...) therefore flip depending on
 * whether a container is running at the moment.
 *
 * <p>In addition the jobs competed with the tests for the single HikariCP connection
 * (pool-size=1, see CLAUDE.md).
 *
 * <p>Scheduled methods are called directly in tests (e.g. {@code SchedulerServiceTest}),
 * so their logic stays fully covered.
 */
@Configuration
@Profile("!integration")
@EnableScheduling
public class SchedulingConfig {
}

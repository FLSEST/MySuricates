package com.automation.service.integration;

import com.automation.config.DataHome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the 2026-08-06 bug: every {@code @SpringBootTest} run with the
 * {@code integration} profile fell through to {@link IntegrationEncryptionService}'s
 * production default key-file location ({@code ${automation.data-home}/...}) because
 * {@code application-integration.yml} did not override {@code automation.integration.key-file}.
 * With empty {@code app_settings}, {@code init()} then generated a fresh AES key and wrote it
 * into the developer's real {@code ~/.mysuricates/integration-encryption.key}, shadowing the
 * real key on every test run.
 *
 * <p>{@code application-integration.yml} now pins {@code automation.integration.key-file} to a
 * per-JVM temp path published by {@link com.automation.integration.IntegrationTestDbSessionListener}
 * (the same mechanism used for {@code integration.db.path}). This test asserts the effective
 * property never resolves under the real home directory, and that the file the
 * {@code @PostConstruct init()} generate-branch wrote actually landed there.
 */
@SpringBootTest
@ActiveProfiles("integration")
class IntegrationEncryptionServiceGuardTest {

    @Value("${automation.integration.key-file}")
    private String effectiveKeyFile;

    @Autowired
    private IntegrationEncryptionService integrationEncryptionService;

    @Test
    void effectiveKeyFile_mustNotResolveUnderRealUserHome() {
        String userHome = System.getProperty("user.home");

        assertThat(effectiveKeyFile)
                .as("automation.integration.key-file must be pinned to a per-JVM temp path in the integration test profile")
                .doesNotStartWith(userHome)
                .doesNotStartWith(DataHome.dir().toString());
    }

    @Test
    void effectiveKeyFile_wasActuallyWrittenByPostConstructInit() {
        // Sanity check that the bean actually initialized against the configured (temp) path,
        // not merely that the property looks right — the generate-branch in init() must have
        // written the key file at context startup.
        assertThat(integrationEncryptionService).isNotNull();
        assertThat(Files.isRegularFile(Path.of(effectiveKeyFile)))
                .as("key file at %s must exist after context startup", effectiveKeyFile)
                .isTrue();
    }
}

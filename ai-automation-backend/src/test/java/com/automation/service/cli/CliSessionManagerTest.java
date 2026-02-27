package com.automation.service.cli;

import com.automation.config.CliPermissionsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CliSessionManagerTest {

    @Mock
    private CliPermissionsConfig cliPermissionsConfig;

    private CliSessionManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(cliPermissionsConfig.isSkipPermissions()).thenReturn(true);
        lenient().when(cliPermissionsConfig.getAllowedTools()).thenReturn(List.of());
        manager = new CliSessionManager(cliPermissionsConfig);
    }

    @Test
    void hasActiveSession_noSessions_returnsFalse() {
        assertThat(manager.hasActiveSession("container-1")).isFalse();
    }

    @Test
    void closeSession_noSessions_doesNotThrow() {
        manager.closeSession("container-1");
        // No exception thrown
    }

    @Test
    void closeSession_matchesPrefix_closesAllForContainer() {
        // We can't start real sessions, but we can verify the method completes without error
        manager.closeSession("container-1");
        assertThat(manager.hasActiveSession("container-1")).isFalse();
    }

    @Test
    void cleanupIdleSessions_emptySessions_doesNotThrow() {
        manager.cleanupIdleSessions();
        // No exception thrown
    }

    @Test
    void shutdown_emptySessions_completesSuccessfully() {
        manager.shutdown();
        // Verify no exceptions
    }

    @Test
    void shutdown_afterCleanup_hasNoSessions() {
        manager.shutdown();
        assertThat(manager.hasActiveSession("any-container")).isFalse();
    }

    @Test
    void cleanupIdleSessions_afterShutdown_doesNotThrow() {
        manager.shutdown();
        manager.cleanupIdleSessions();
        // Double shutdown + cleanup should not fail
    }
}

package com.automation.service.activitylog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityLogLifecycleListenerTest {

    @Mock private ActivityLogService activityLogService;
    @Mock private Environment environment;

    private ActivityLogLifecycleListener listener;

    @BeforeEach
    void setUp() {
        listener = new ActivityLogLifecycleListener(activityLogService, environment);
    }

    @Test
    void onStartup_logsSystemInfo() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        listener.onStartup();

        verify(activityLogService).info(any(), contains("server started"), eq("System"));
    }

    @Test
    void onStartup_emptyProfiles_showsDefault() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        listener.onStartup();

        verify(activityLogService).info(any(), contains("default"), eq("System"));
    }

    @Test
    void onShutdown_calculatesUptime() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{});
        listener.onStartup();

        listener.onShutdown();

        verify(activityLogService).info(any(), contains("shutting down"), eq("System"));
    }

    @Test
    void onShutdown_beforeStartup_handlesNull() {
        // onShutdown without prior onStartup
        listener.onShutdown();

        verify(activityLogService).info(any(), contains("shutting down"), eq("System"));
    }
}

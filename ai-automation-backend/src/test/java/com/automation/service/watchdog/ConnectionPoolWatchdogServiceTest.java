package com.automation.service.watchdog;

import com.automation.service.activitylog.ActivityLogService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConnectionPoolWatchdogServiceTest {

    @Mock private ActivityLogService activityLogService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private HikariDataSource hikariDataSource;
    @Mock private HikariPoolMXBean poolMXBean;

    private ConnectionPoolWatchdogService createService() {
        when(hikariDataSource.getHikariPoolMXBean()).thenReturn(poolMXBean);
        return new ConnectionPoolWatchdogService(hikariDataSource, activityLogService, messagingTemplate);
    }

    @Nested
    class HealthyPool {

        @Test
        void noActiveConnections_noAlert() {
            var service = createService();
            when(poolMXBean.getActiveConnections()).thenReturn(0);
            when(poolMXBean.getThreadsAwaitingConnection()).thenReturn(0);
            when(poolMXBean.getIdleConnections()).thenReturn(1);
            when(poolMXBean.getTotalConnections()).thenReturn(1);

            service.checkPoolHealth();

            verify(activityLogService, never()).log(any(), any(), anyString(), anyString(), any(), any());
        }

        @Test
        void activeButNoWaiting_noExhaustion() {
            var service = createService();
            when(poolMXBean.getActiveConnections()).thenReturn(1);
            when(poolMXBean.getThreadsAwaitingConnection()).thenReturn(0);
            when(poolMXBean.getIdleConnections()).thenReturn(0);
            when(poolMXBean.getTotalConnections()).thenReturn(1);

            service.checkPoolHealth();

            verify(activityLogService, never()).log(any(), any(), anyString(), anyString(), any(), any());
        }
    }

    @Nested
    class ExhaustionDetection {

        @Test
        void activeAndWaiting_detectsExhaustion() {
            var service = createService();
            when(poolMXBean.getActiveConnections()).thenReturn(1);
            when(poolMXBean.getThreadsAwaitingConnection()).thenReturn(2);
            when(poolMXBean.getIdleConnections()).thenReturn(0);
            when(poolMXBean.getTotalConnections()).thenReturn(1);

            service.checkPoolHealth();

            // No alert yet (threshold not reached)
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        void exhaustionResolved_logsRecovery() {
            var service = createService();
            // Step 1: Detect exhaustion
            when(poolMXBean.getActiveConnections()).thenReturn(1);
            when(poolMXBean.getThreadsAwaitingConnection()).thenReturn(1);
            when(poolMXBean.getIdleConnections()).thenReturn(0);
            when(poolMXBean.getTotalConnections()).thenReturn(1);
            service.checkPoolHealth();

            // Step 2: Resolve
            when(poolMXBean.getActiveConnections()).thenReturn(0);
            when(poolMXBean.getThreadsAwaitingConnection()).thenReturn(0);
            when(poolMXBean.getIdleConnections()).thenReturn(1);
            service.checkPoolHealth();

            // No alert was triggered (threshold not reached), so no recovery log either
            verify(activityLogService, never()).log(any(), any(), anyString(), anyString(), any(), any());
        }
    }

    @Nested
    class NullDatasource {

        @Test
        void nonHikariDataSource_doesNotCrash() {
            ConnectionPoolWatchdogService nullService =
                    new ConnectionPoolWatchdogService(mock(javax.sql.DataSource.class),
                            activityLogService, messagingTemplate);

            nullService.checkPoolHealth();

            verify(activityLogService, never()).log(any(), any(), anyString(), anyString(), any(), any());
        }
    }

    @Nested
    class NullPoolMXBean {

        @Test
        void nullPoolMXBean_doesNotCrash() {
            when(hikariDataSource.getHikariPoolMXBean()).thenReturn(null);
            var service = new ConnectionPoolWatchdogService(hikariDataSource, activityLogService, messagingTemplate);

            service.checkPoolHealth();

            verify(activityLogService, never()).log(any(), any(), anyString(), anyString(), any(), any());
        }
    }

    @Nested
    class PendingLogFlush {

        @Test
        void flushPendingLog_noPending_doesNothing() {
            var service = createService();
            when(poolMXBean.getActiveConnections()).thenReturn(0);
            when(poolMXBean.getThreadsAwaitingConnection()).thenReturn(0);
            when(poolMXBean.getIdleConnections()).thenReturn(1);
            when(poolMXBean.getTotalConnections()).thenReturn(1);

            service.checkPoolHealth();

            verify(activityLogService, never()).error(any(), anyString(), anyString());
        }
    }
}

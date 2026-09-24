package com.automation.service.watchdog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.automation.service.activitylog.ActivityLogService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    /** c1eda062: waiting threads in a single sample stay quiet — the WARN comes with the second in a row. */
    @Nested
    class WarnOnlyWhenWaitingPersists {

        private final Logger logger = (Logger) LoggerFactory.getLogger(ConnectionPoolWatchdogService.class);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private Level previousLevel;

        @BeforeEach
        void attach() {
            appender.start();
            logger.addAppender(appender);
            previousLevel = logger.getLevel();
            logger.setLevel(Level.DEBUG); // logback-test.xml keeps the root at ERROR
        }

        @AfterEach
        void detach() {
            logger.setLevel(previousLevel);
            logger.detachAppender(appender);
        }

        private List<ILoggingEvent> warnings() {
            return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        }

        private void sample(int active, int waiting) {
            when(poolMXBean.getActiveConnections()).thenReturn(active);
            when(poolMXBean.getThreadsAwaitingConnection()).thenReturn(waiting);
            when(poolMXBean.getIdleConnections()).thenReturn(active == 0 ? 1 : 0);
            when(poolMXBean.getTotalConnections()).thenReturn(1);
        }

        @Test
        void oneSampleWithWaitingThreads_logsNoWarning() {
            var service = createService();
            sample(1, 1);
            service.checkPoolHealth();
            sample(0, 0);
            service.checkPoolHealth();

            assertThat(warnings()).isEmpty();
            assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.INFO);
        }

        @Test
        void twoSamplesInARow_warnOnce() {
            var service = createService();
            sample(1, 1);
            service.checkPoolHealth();
            assertThat(warnings()).isEmpty();

            service.checkPoolHealth();
            assertThat(warnings()).singleElement()
                    .satisfies(e -> assertThat(e.getFormattedMessage()).startsWith("Pool exhaustion detected"));

            service.checkPoolHealth();
            assertThat(warnings()).as("one WARN per episode, not per tick")
                    .filteredOn(e -> e.getFormattedMessage().startsWith("Pool exhaustion detected")).hasSize(1);
        }

        @Test
        void aResolvedSampleResetsTheCount() {
            var service = createService();
            sample(1, 1);
            service.checkPoolHealth();
            sample(0, 0);
            service.checkPoolHealth();
            sample(1, 1);
            service.checkPoolHealth();

            assertThat(warnings()).isEmpty();
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

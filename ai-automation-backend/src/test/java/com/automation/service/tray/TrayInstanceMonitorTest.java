package com.automation.service.tray;

import com.automation.config.StagingConfig;
import com.automation.config.TrayConfig;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.staging.DockerContainerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.automation.service.tray.TrayInstanceMonitor.InstanceKind.DEV;
import static com.automation.service.tray.TrayInstanceMonitor.InstanceKind.STABLE;
import static com.automation.service.tray.TrayInstanceMonitor.InstanceKind.TEST;
import static com.automation.service.tray.TrayInstanceMonitor.LightColor.GREEN;
import static com.automation.service.tray.TrayInstanceMonitor.LightColor.RED;
import static com.automation.service.tray.TrayInstanceMonitor.LightColor.YELLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrayInstanceMonitorTest {

    @Mock
    private ModuleRegistry modules;

    @Mock
    private DockerContainerManager docker;

    private TrayConfig trayConfig;
    private StagingConfig stagingConfig;
    private TrayInstanceMonitor monitor;

    @BeforeEach
    void setUp() {
        trayConfig = new TrayConfig();
        stagingConfig = new StagingConfig();
        monitor = new TrayInstanceMonitor(trayConfig, stagingConfig, modules, docker);
    }

    @Test
    void collect_showsOnlyHealthyDevWhenStagingModuleIsDisabled() throws Exception {
        try (ServerSocket frontend = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            trayConfig.setWebUiUrl("https://127.0.0.1:" + frontend.getLocalPort());
            when(modules.isEnabled(ModuleCatalog.STAGING)).thenReturn(false);

            assertThat(monitor.collect()).containsExactly(new TrayInstanceMonitor.InstanceLight(DEV, GREEN));
            verifyNoInteractions(docker);
        }
    }

    @Test
    void collect_movesUnavailableDevFromStartingToOfflineAfterThreePolls() {
        trayConfig.setWebUiUrl("https://127.0.0.1:1");
        when(modules.isEnabled(ModuleCatalog.STAGING)).thenReturn(false);

        assertThat(monitor.collect()).containsExactly(new TrayInstanceMonitor.InstanceLight(DEV, YELLOW));
        assertThat(monitor.collect()).containsExactly(new TrayInstanceMonitor.InstanceLight(DEV, YELLOW));
        assertThat(monitor.collect()).containsExactly(new TrayInstanceMonitor.InstanceLight(DEV, RED));
    }

    @Test
    void collect_hidesAbsentTestAndStableInstances() throws Exception {
        try (ServerSocket frontend = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            trayConfig.setWebUiUrl("http://127.0.0.1:" + frontend.getLocalPort());
            when(modules.isEnabled(ModuleCatalog.STAGING)).thenReturn(true);
            when(docker.isHealthy(stagingConfig.getTestHealthUrl())).thenReturn(false);
            when(docker.isHealthy(stagingConfig.getHealthUrl())).thenReturn(false);
            when(docker.containerExists(stagingConfig.getTestContainerName())).thenReturn(false);
            when(docker.containerExists(stagingConfig.getContainerName())).thenReturn(false);

            assertThat(monitor.collect()).containsExactly(new TrayInstanceMonitor.InstanceLight(DEV, GREEN));
        }
    }

    @Test
    void collect_doesNotWaitForAProbeOfAnotherCollect() throws Exception {
        // Ticks run on virtual threads; a monitor held across the probes would pin the carrier for every waiting
        // tick (Java 21). A second collect must therefore not wait for a probe that hangs in the first one.
        try (ServerSocket frontend = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            trayConfig.setWebUiUrl("http://127.0.0.1:" + frontend.getLocalPort());
            when(modules.isEnabled(ModuleCatalog.STAGING)).thenReturn(true);
            CountDownLatch firstProbeEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstProbe = new CountDownLatch(1);
            AtomicInteger probes = new AtomicInteger();
            when(docker.isHealthy(stagingConfig.getTestHealthUrl())).thenAnswer(call -> {
                if (probes.incrementAndGet() == 1) {
                    firstProbeEntered.countDown();
                    releaseFirstProbe.await(10, TimeUnit.SECONDS);
                }
                return true;
            });
            when(docker.isHealthy(stagingConfig.getHealthUrl())).thenReturn(true);

            ExecutorService first = Executors.newSingleThreadExecutor();
            try {
                Future<?> hanging = first.submit(monitor::collect);
                assertThat(firstProbeEntered.await(5, TimeUnit.SECONDS)).isTrue();

                ExecutorService second = Executors.newSingleThreadExecutor();
                try {
                    assertThat(second.submit(monitor::collect).get(2, TimeUnit.SECONDS)).hasSize(3);
                } finally {
                    second.shutdownNow();
                }
                releaseFirstProbe.countDown();
                hanging.get(5, TimeUnit.SECONDS);
            } finally {
                releaseFirstProbe.countDown();
                first.shutdownNow();
            }
        }
    }

    @Test
    void collect_ordersHealthyTestBeforeStartingStable() throws Exception {
        try (ServerSocket frontend = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            trayConfig.setWebUiUrl("https://127.0.0.1:" + frontend.getLocalPort());
            when(modules.isEnabled(ModuleCatalog.STAGING)).thenReturn(true);
            when(docker.isHealthy(stagingConfig.getTestHealthUrl())).thenReturn(true);
            when(docker.isHealthy(stagingConfig.getHealthUrl())).thenReturn(false);
            when(docker.containerExists(stagingConfig.getContainerName())).thenReturn(true);
            when(docker.isRunning(stagingConfig.getContainerName())).thenReturn(true);

            assertThat(monitor.collect()).containsExactly(
                    new TrayInstanceMonitor.InstanceLight(DEV, GREEN),
                    new TrayInstanceMonitor.InstanceLight(TEST, GREEN),
                    new TrayInstanceMonitor.InstanceLight(STABLE, YELLOW));
        }
    }

    @Test
    void collect_marksRecognizedStoppedInstanceRed() throws Exception {
        try (ServerSocket frontend = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            trayConfig.setWebUiUrl("http://127.0.0.1:" + frontend.getLocalPort());
            when(modules.isEnabled(ModuleCatalog.STAGING)).thenReturn(true);
            when(docker.isHealthy(stagingConfig.getTestHealthUrl())).thenReturn(false);
            when(docker.containerExists(stagingConfig.getTestContainerName())).thenReturn(true);
            when(docker.isRunning(stagingConfig.getTestContainerName())).thenReturn(false);
            when(docker.isHealthy(stagingConfig.getHealthUrl())).thenReturn(false);
            when(docker.containerExists(stagingConfig.getContainerName())).thenReturn(false);

            assertThat(monitor.collect()).containsExactly(
                    new TrayInstanceMonitor.InstanceLight(DEV, GREEN),
                    new TrayInstanceMonitor.InstanceLight(TEST, RED));
        }
    }
}

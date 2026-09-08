package com.automation.service.project;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Container;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Locks orphan reconciliation before extraction (Audit-TODO b804e1be). */
@ExtendWith(MockitoExtension.class)
class DockerOrphanReconcilerTest {

    @Mock private DockerContainerGateway docker;
    @Mock private ActivityLogService activityLogService;

    private DockerOrphanReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new DockerOrphanReconciler(docker, activityLogService, 60);
    }

    private static com.github.dockerjava.api.model.Container dockerContainer(String id, Instant created) {
        var dc = mock(com.github.dockerjava.api.model.Container.class);
        lenient().when(dc.getId()).thenReturn(id);
        lenient().when(dc.getCreated()).thenReturn(created == null ? null : created.getEpochSecond());
        return dc;
    }

    @Test
    void reconcile_dockerListFails_isNoOp() {
        when(docker.listAutomationContainers()).thenThrow(new RuntimeException("socket"));

        reconciler.reconcile(List.of(), Set.of());

        verify(docker, never()).remove(anyString());
    }

    @Test
    void reconcile_orphanOlderThanGrace_isRemovedAndLogged() {
        var orphan = dockerContainer("orphan", Instant.now().minus(2, ChronoUnit.HOURS));
        when(docker.listAutomationContainers()).thenReturn(List.of(orphan));

        reconciler.reconcile(null, Set.of());

        verify(docker).remove("orphan");
        verify(activityLogService).warn(eq(LogCategory.CONTAINER),
                eq("Reconcile: verwaister Docker-Container ohne DB-Eintrag gestoppt: orphan"), eq("ContainerService"));
    }

    @Test
    void reconcile_knownActiveFreshOrIdlessContainers_areSkipped() {
        Container tracked = Container.builder().id("c1").dockerContainerId("known").build();
        var known = dockerContainer("known", Instant.now().minus(2, ChronoUnit.HOURS));
        var busy = dockerContainer("busy", Instant.now().minus(2, ChronoUnit.HOURS));
        var fresh = dockerContainer("fresh", Instant.now());
        var noId = dockerContainer(null, Instant.now().minus(2, ChronoUnit.HOURS));
        when(docker.listAutomationContainers()).thenReturn(List.of(known, busy, fresh, noId));

        reconciler.reconcile(List.of(tracked), Set.of("busy"));

        verify(docker, never()).remove(anyString());
        verify(activityLogService, never()).warn(any(), anyString(), anyString());
    }

    @Test
    void reconcile_orphanWithoutCreatedTimestamp_isRemoved() {
        var noTimestamp = dockerContainer("no-ts", null);
        when(docker.listAutomationContainers()).thenReturn(List.of(noTimestamp));

        reconciler.reconcile(List.of(), Set.of());

        verify(docker).remove("no-ts");
    }
}

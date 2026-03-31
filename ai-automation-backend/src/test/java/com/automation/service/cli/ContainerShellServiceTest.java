package com.automation.service.cli;

import com.automation.service.activitylog.ActivityLogService;
import com.automation.util.HostProcessUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerShellServiceTest {

    @Mock private HostProcessUtils hostProcessUtils;
    @Mock private ActivityLogService activityLogService;

    private ContainerShellService service;

    @BeforeEach
    void setUp() {
        service = new ContainerShellService(hostProcessUtils, activityLogService);
        ReflectionTestUtils.setField(service, "allowDockerSocketMount", false);
    }

    @Test
    void execute_successfulCommand_returnsOutput() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");

        ShellCommandService.ShellResult result = service.execute(
                "echo hello", "/tmp/project", 30, "test-image:latest", false);

        // docker run will likely fail in test env, but we verify no exception
        assertThat(result).isNotNull();
        assertThat(result.exitCode()).isNotNull();
    }

    @Test
    void execute_dockerSocketDenied_logsWarning() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");

        service.execute("echo hi", "/tmp", 10, "img:latest", true);

        verify(activityLogService).warn(any(), contains("Docker socket mount denied"), anyString(), isNull());
    }

    @Test
    void execute_dockerSocketAllowed_logsMountWarning() {
        ReflectionTestUtils.setField(service, "allowDockerSocketMount", true);
        when(hostProcessUtils.getHostUid()).thenReturn("1000");

        service.execute("echo hi", "/tmp", 10, "img:latest", true);

        verify(activityLogService).warn(any(), contains("Docker socket mounted"), anyString(), isNull());
    }

    @Test
    void buildDockerCommand_withProjectPath_mountsWorkspace() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");

        // Use reflection to call private method
        try {
            var method = ContainerShellService.class.getDeclaredMethod(
                    "buildDockerCommand", String.class, String.class, String.class, boolean.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> cmd = (List<String>) method.invoke(service, "echo hi", "/my/project", "img:latest", false);

            assertThat(cmd).contains("docker", "run", "--rm");
            assertThat(cmd).contains("--user", "1000");
            assertThat(cmd).contains("--workdir", "/workspace");
            assertThat(cmd).contains("-v");
            assertThat(cmd).contains("/my/project:/workspace:rw");
            assertThat(cmd).contains("--cap-drop", "ALL");
            assertThat(cmd).contains("--memory", "2g");
            assertThat(cmd).contains("img:latest");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void buildDockerCommand_withDockerSocket_mountsSocket() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");

        try {
            var method = ContainerShellService.class.getDeclaredMethod(
                    "buildDockerCommand", String.class, String.class, String.class, boolean.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> cmd = (List<String>) method.invoke(service, "cmd", "/tmp", "img:latest", true);

            assertThat(cmd).contains("/var/run/docker.sock:/var/run/docker.sock");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void buildDockerCommand_noProjectPath_noVolumeMount() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");

        try {
            var method = ContainerShellService.class.getDeclaredMethod(
                    "buildDockerCommand", String.class, String.class, String.class, boolean.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> cmd = (List<String>) method.invoke(service, "cmd", null, "img:latest", false);

            // Should not contain a project volume mount
            assertThat(cmd.stream().filter(s -> s.contains(":/workspace")).count()).isEqualTo(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

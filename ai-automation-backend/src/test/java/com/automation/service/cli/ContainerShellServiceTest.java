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
    @Mock private ContainerImageAllowlist imageAllowlist;

    private ContainerShellService service;

    @BeforeEach
    void setUp() {
        lenient().when(imageAllowlist.isAllowed(anyString())).thenReturn(true);
        service = new ContainerShellService(hostProcessUtils, activityLogService, imageAllowlist);
        ReflectionTestUtils.setField(service, "allowDockerSocketMount", false);
        ReflectionTestUtils.setField(service, "allowExtraMounts", false);
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
    void execute_imageNotAllowed_returnsRejectionAndSkipsDocker() {
        when(imageAllowlist.isAllowed("forbidden-image:latest")).thenReturn(false);

        ShellCommandService.ShellResult result = service.execute(
                "echo hi", "/tmp", 10, "forbidden-image:latest", false);

        assertThat(result.exitCode()).isEqualTo(125);
        assertThat(result.output()).contains("forbidden-image:latest")
                .contains("automation.execution.allowed-container-images");
        verify(activityLogService).warn(any(),
                contains("Container image not allowed"), anyString(), isNull());
        verifyNoInteractions(hostProcessUtils);
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
                    "buildDockerCommand", String.class, String.class, String.class, boolean.class, List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> cmd = (List<String>) method.invoke(service, "echo hi", "/my/project", "img:latest", false, List.of());

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
    void buildDockerCommand_forcesBashEntrypointBeforeImage() {
        try {
            var method = ContainerShellService.class.getDeclaredMethod(
                    "buildDockerCommand", String.class, String.class, String.class, boolean.class, List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> cmd = (List<String>) method.invoke(service, "echo hi", "/my/project", "tool-image:1.0", false, List.of());

            int entrypoint = cmd.indexOf("--entrypoint");
            int image = cmd.indexOf("tool-image:1.0");
            assertThat(entrypoint).isGreaterThan(0);
            assertThat(cmd.get(entrypoint + 1)).isEqualTo("/bin/bash");
            assertThat(image).isGreaterThan(entrypoint);
            // after the image only the bash arguments follow, never a second "/bin/bash" the tool entrypoint would swallow
            assertThat(cmd.subList(image + 1, cmd.size())).containsExactly("-c", "echo hi");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void buildDockerCommand_withDockerSocket_mountsSocket() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");

        try {
            var method = ContainerShellService.class.getDeclaredMethod(
                    "buildDockerCommand", String.class, String.class, String.class, boolean.class, List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> cmd = (List<String>) method.invoke(service, "cmd", "/tmp", "img:latest", true, List.of());

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
                    "buildDockerCommand", String.class, String.class, String.class, boolean.class, List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> cmd = (List<String>) method.invoke(service, "cmd", null, "img:latest", false, List.of());

            // Should not contain a project volume mount
            assertThat(cmd.stream().filter(s -> s.contains(":/workspace")).count()).isEqualTo(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void buildDockerCommand_withExtraMounts_addsAllBindMounts() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");
        try {
            var method = ContainerShellService.class.getDeclaredMethod(
                    "buildDockerCommand", String.class, String.class, String.class, boolean.class, List.class);
            method.setAccessible(true);
            List<String> extras = List.of(
                    "/test/user/dokumente:/test/user/dokumente:rw",
                    "/etc/timezone:/etc/timezone:ro"
            );
            @SuppressWarnings("unchecked")
            List<String> cmd = (List<String>) method.invoke(service, "cmd", "/p", "img:latest", false, extras);

            assertThat(cmd).contains("/test/user/dokumente:/test/user/dokumente:rw");
            assertThat(cmd).contains("/etc/timezone:/etc/timezone:ro");
            // Extra mounts are separate -v flags, so there are 3 total (project + 2 extras)
            assertThat(cmd.stream().filter("-v"::equals).count()).isEqualTo(3);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void filterExtraMounts_disallowedByDefault_dropsAll() {
        service.setAllowExtraMounts(false);

        List<String> safe = service.filterExtraMounts(
                List.of("/test/user/data:/data:rw", "/tmp/cache:/cache"),
                "img:latest");

        assertThat(safe).isEmpty();
        verify(activityLogService, times(2))
                .warn(any(), contains("Extra mount denied (allow-extra-mounts=false)"), anyString(), isNull());
    }

    @Test
    void filterExtraMounts_allowedAndSafe_keepsAndLogsActivation() {
        service.setAllowExtraMounts(true);

        List<String> safe = service.filterExtraMounts(
                List.of("/test/user/data:/workspace/data:rw"),
                "img:latest");

        assertThat(safe).containsExactly("/test/user/data:/workspace/data:rw");
        verify(activityLogService).warn(any(), contains("Extra mount activated"), anyString(), isNull());
    }

    @Test
    void filterExtraMounts_dockerSocketTarget_isRejectedEvenWhenAllowed() {
        service.setAllowExtraMounts(true);

        List<String> safe = service.filterExtraMounts(
                List.of("/var/run/docker.sock:/var/run/docker.sock"),
                "img:latest");

        assertThat(safe).isEmpty();
        verify(activityLogService).warn(any(),
                contains("forbidden path /var/run/docker.sock"), anyString(), isNull());
    }

    @Test
    void filterExtraMounts_etcSubpath_isRejectedAsForbidden() {
        service.setAllowExtraMounts(true);

        List<String> safe = service.filterExtraMounts(
                List.of("/etc/passwd:/data/passwd:ro"),
                "img:latest");

        assertThat(safe).isEmpty();
        verify(activityLogService).warn(any(),
                contains("forbidden path /etc"), anyString(), isNull());
    }

    @Test
    void filterExtraMounts_rootMount_isRejected() {
        service.setAllowExtraMounts(true);

        List<String> safe = service.filterExtraMounts(
                List.of("/:/host:ro"),
                "img:latest");

        assertThat(safe).isEmpty();
    }

    @Test
    void filterExtraMounts_containerSideForbidden_isRejected() {
        service.setAllowExtraMounts(true);

        // host side is harmless, but container mount target is /etc
        List<String> safe = service.filterExtraMounts(
                List.of("/test/user/data:/etc/myconf"),
                "img:latest");

        assertThat(safe).isEmpty();
    }

    @Test
    void filterExtraMounts_partiallyForbidden_keepsValidOnly() {
        service.setAllowExtraMounts(true);

        List<String> safe = service.filterExtraMounts(
                List.of(
                        "/test/user/data:/data:rw",
                        "/etc:/host-etc:ro",
                        "/tmp/cache:/cache"
                ),
                "img:latest");

        assertThat(safe).containsExactlyInAnyOrder(
                "/test/user/data:/data:rw",
                "/tmp/cache:/cache");
    }

    @Test
    void filterExtraMounts_emptyAllowlist_keepsAllNonForbidden() {
        service.setAllowExtraMounts(true);
        service.setAllowedMountPrefixes(List.of());

        List<String> safe = service.filterExtraMounts(
                List.of("/test/user/data:/data:rw", "/opt/anything:/data2:rw"),
                "img:latest");

        assertThat(safe).hasSize(2);
    }

    @Test
    void filterExtraMounts_allowlistConfigured_rejectsHostPathsOutsidePrefix() {
        service.setAllowExtraMounts(true);
        service.setAllowedMountPrefixes(List.of("/test/user/dokumente", "/test/user/Entwicklung"));

        List<String> safe = service.filterExtraMounts(
                List.of(
                        "/test/user/dokumente/cv:/data:rw",
                        "/test/user/Entwicklung/foo:/work:rw",
                        "/opt/secret:/data2:rw",
                        "/test/user/.ssh:/mnt:ro"),
                "img:latest");

        assertThat(safe).containsExactly(
                "/test/user/dokumente/cv:/data:rw",
                "/test/user/Entwicklung/foo:/work:rw");
        verify(activityLogService, times(2)).warn(any(),
                contains("not under allowed-mount-prefixes"), anyString(), isNull());
    }

    @Test
    void filterExtraMounts_allowlistRejectsForbiddenPathsFirst() {
        service.setAllowExtraMounts(true);
        service.setAllowedMountPrefixes(List.of("/test/user"));

        // /etc is forbidden, must be rejected with "forbidden path" log,
        // not "not under allowed-mount-prefixes".
        List<String> safe = service.filterExtraMounts(
                List.of("/etc/passwd:/data:ro"),
                "img:latest");

        assertThat(safe).isEmpty();
        verify(activityLogService).warn(any(),
                contains("forbidden path /etc"), anyString(), isNull());
    }

    @Test
    void filterExtraMounts_allowlistRejectsPathTraversalBypass() {
        // String prefix check would let "/test/user/dokumente/../../etc:/data" pass
        // because the string starts with "/test/user/dokumente". After path
        // normalization the host path becomes "/etc" → must be rejected as
        // forbidden, not slip through.
        service.setAllowExtraMounts(true);
        service.setAllowedMountPrefixes(List.of("/test/user/dokumente"));

        List<String> safe = service.filterExtraMounts(
                List.of(
                        "/test/user/dokumente/../../etc:/data:rw",
                        "/test/user/dokumente/./../.ssh:/mnt:ro",
                        "/test/user/dokumente/cv:/data:rw"),
                "img:latest");

        assertThat(safe).containsExactly("/test/user/dokumente/cv:/data:rw");
    }

    @Test
    void isAllowedByPrefix_normalizesDotDotSequences() {
        service.setAllowedMountPrefixes(List.of("/test/user/dokumente"));

        // Naive string-prefix check would accept these — must be rejected after
        // normalization because they resolve outside the prefix.
        assertThat(service.isAllowedByPrefix("/test/user/dokumente/../etc:/data:rw")).isFalse();
        assertThat(service.isAllowedByPrefix("/test/user/dokumente/../../root:/data:rw")).isFalse();

        // Valid paths still pass.
        assertThat(service.isAllowedByPrefix("/test/user/dokumente/cv:/data:rw")).isTrue();
        assertThat(service.isAllowedByPrefix("/test/user/dokumente/sub/./file:/data:rw")).isTrue();
    }

    @Test
    void filterExtraMounts_allowlistAcceptsExactPrefixMatch() {
        service.setAllowExtraMounts(true);
        service.setAllowedMountPrefixes(List.of("/test/user/dokumente"));

        List<String> safe = service.filterExtraMounts(
                List.of("/test/user/dokumente:/dok:rw"),
                "img:latest");

        assertThat(safe).containsExactly("/test/user/dokumente:/dok:rw");
    }

    @Test
    void initAllowedMountPrefixes_expandsHomeAndTrims() {
        ReflectionTestUtils.setField(service, "allowedMountPrefixesRaw", " ~/dokumente , ~/Entwicklung/ ,");
        service.initAllowedMountPrefixes();

        String home = System.getProperty("user.home");
        assertThat(service.getAllowedMountPrefixes())
                .containsExactly(home + "/dokumente", home + "/Entwicklung");
    }

    @Test
    void initAllowedMountPrefixes_blankRawDisablesAllowlist() {
        ReflectionTestUtils.setField(service, "allowedMountPrefixesRaw", "");
        service.initAllowedMountPrefixes();

        assertThat(service.getAllowedMountPrefixes()).isEmpty();
    }

    @Test
    void filterExtraMounts_blankOrNullEntries_skippedSilently() {
        service.setAllowExtraMounts(true);

        java.util.ArrayList<String> input = new java.util.ArrayList<>();
        input.add(null);
        input.add("");
        input.add("   ");
        input.add("/test/user/data:/data:rw");

        List<String> safe = service.filterExtraMounts(input, "img:latest");

        assertThat(safe).containsExactly("/test/user/data:/data:rw");
    }

    @Test
    void findForbiddenSegment_exactMatchAndChildPaths_areForbidden() {
        assertThat(ContainerShellService.findForbiddenSegment("/etc:/host:ro")).isEqualTo("/etc");
        assertThat(ContainerShellService.findForbiddenSegment("/etc/foo:/data:ro")).isEqualTo("/etc");
        assertThat(ContainerShellService.findForbiddenSegment("/root:/data:ro")).isEqualTo("/root");
        assertThat(ContainerShellService.findForbiddenSegment("/var/run/docker.sock:/sock:ro")).isEqualTo("/var/run/docker.sock");
        assertThat(ContainerShellService.findForbiddenSegment("/test/user:/data:ro")).isNull();
        assertThat(ContainerShellService.findForbiddenSegment("/etcetera:/data:ro")).isNull(); // not a child of /etc
    }

    @Test
    void buildDockerCommand_emptyOrNullMounts_addsNothing() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");
        try {
            var method = ContainerShellService.class.getDeclaredMethod(
                    "buildDockerCommand", String.class, String.class, String.class, boolean.class, List.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<String> cmdEmpty = (List<String>) method.invoke(service, "cmd", "/p", "img:latest", false, List.of());
            assertThat(cmdEmpty.stream().filter("-v"::equals).count()).isEqualTo(1); // only project mount

            @SuppressWarnings("unchecked")
            List<String> cmdNull = (List<String>) method.invoke(service, "cmd", "/p", "img:latest", false, null);
            assertThat(cmdNull.stream().filter("-v"::equals).count()).isEqualTo(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void executeTool_imageNotAllowed_returnsRejectionAndSkipsDocker() {
        when(imageAllowlist.isAllowed("forbidden-image:latest")).thenReturn(false);

        ShellCommandService.ShellResult result = service.executeTool(new ContainerToolRequest(
                "forbidden-image:latest", List.of("--version"), "/tmp", null, null, List.of(), false, 10), () -> false);

        assertThat(result.exitCode()).isEqualTo(125);
        verify(activityLogService).warn(any(), contains("Container image not allowed"), anyString(), isNull());
        verifyNoInteractions(hostProcessUtils);
    }

    @Test
    void executeTool_appliesTheExtraMountPolicy_andStopsBeforeDockerWhenCancelled() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");

        ShellCommandService.ShellResult result = service.executeTool(new ContainerToolRequest(
                "img:1", List.of("--version"), "/tmp", null, null, List.of("/data:/data"), false, 10), () -> true);

        assertThat(result.exitCode()).isEqualTo(130);
        verify(activityLogService).warn(any(), contains("Extra mount denied"), anyString(), isNull());
    }
}

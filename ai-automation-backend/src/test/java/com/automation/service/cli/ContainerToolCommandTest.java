package com.automation.service.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerToolCommandTest {

    private static ContainerToolRequest request(String projectPath, ContainerToolRequest.Mount output,
                                                ContainerToolRequest.Mount cache) {
        return new ContainerToolRequest("aquasec/trivy:0.70.0", List.of("fs", "/workspace"), projectPath,
                output, cache, List.of(), false, 60);
    }

    @Test
    void entrypointGetsTheArgs_noShell_projectReadOnly() {
        List<String> cmd = ContainerToolCommand.build("1000", request("/my/project", null, null), false, List.of());

        assertThat(cmd.subList(0, 3)).containsExactly("docker", "run", "--rm");
        assertThat(cmd).containsSequence("--user", "1000").containsSequence("--workdir", "/workspace")
                .containsSequence("-v", "/my/project:/workspace:ro")
                .containsSequence("--cap-drop", "ALL").containsSequence("--security-opt", "no-new-privileges")
                .containsSequence("--memory", "2g").containsSequence("--pids-limit", "100")
                .doesNotContain("/bin/bash", "/my/project:/workspace:rw");
        assertThat(cmd.subList(cmd.size() - 3, cmd.size())).containsExactly("aquasec/trivy:0.70.0", "fs", "/workspace");
    }

    @Test
    void outputAndCacheAreMountedReadWrite(@TempDir Path out, @TempDir Path cache) {
        List<String> cmd = ContainerToolCommand.build("1000", request("/p",
                new ContainerToolRequest.Mount(out, "/out"), new ContainerToolRequest.Mount(cache, "/cache")),
                false, List.of());

        assertThat(cmd).containsSequence("-v", out.toAbsolutePath() + ":/out:rw")
                .containsSequence("-v", cache.toAbsolutePath() + ":/cache:rw");
    }

    @Test
    void withoutProjectPath_noWorkspaceMount() {
        assertThat(ContainerToolCommand.build("1000", request(null, null, null), false, List.of()))
                .noneMatch(arg -> arg.endsWith(":/workspace:ro"));
    }

    @Test
    void socketAndExtraMountsOnlyAsAdmitted() {
        List<String> cmd = ContainerToolCommand.build("1000", request("/p", null, null), true, List.of("/data:/data:ro"));

        assertThat(cmd).containsSequence("-v", "/var/run/docker.sock:/var/run/docker.sock")
                .containsSequence("-v", "/data:/data:ro");
    }
}

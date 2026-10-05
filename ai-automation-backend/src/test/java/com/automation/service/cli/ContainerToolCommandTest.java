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
                .containsSequence("--mount", "type=bind,source=/my/project,target=/workspace,readonly")
                .containsSequence("--cap-drop", "ALL").containsSequence("--security-opt", "no-new-privileges")
                .containsSequence("--memory", "2g").containsSequence("--pids-limit", "100")
                .doesNotContain("/bin/bash", "-v");
        assertThat(cmd.subList(cmd.size() - 3, cmd.size())).containsExactly("aquasec/trivy:0.70.0", "fs", "/workspace");
    }

    /** Review M6: --mount fails on a missing source, -v would create it empty and the tool would scan nothing. */
    @Test
    void outputAndCacheAreBindMountsThatMustExist(@TempDir Path out, @TempDir Path cache) {
        List<String> cmd = ContainerToolCommand.build("1000", request("/p",
                new ContainerToolRequest.Mount(out, "/out"), new ContainerToolRequest.Mount(cache, "/cache")),
                false, List.of());

        assertThat(cmd).containsSequence("--mount", "type=bind,source=" + out.toAbsolutePath() + ",target=/out")
                .containsSequence("--mount", "type=bind,source=" + cache.toAbsolutePath() + ",target=/cache");
    }

    @Test
    void aCommaInAPathIsQuotedForDockersCsvSyntax() {
        assertThat(ContainerToolCommand.build("1000", request("/p,q", null, null), false, List.of()))
                .containsSequence("--mount", "type=bind,\"source=/p,q\",target=/workspace,readonly");
    }

    @Test
    void withoutProjectPath_noWorkspaceMount() {
        assertThat(ContainerToolCommand.build("1000", request(null, null, null), false, List.of()))
                .noneMatch(arg -> arg.contains("target=/workspace"));
    }

    @Test
    void socketAndExtraMountsOnlyAsAdmitted() {
        List<String> cmd = ContainerToolCommand.build("1000", request("/p", null, null), true, List.of("/data:/data:ro"));

        assertThat(cmd).containsSequence("-v", "/var/run/docker.sock:/var/run/docker.sock")
                .containsSequence("-v", "/data:/data:ro");
    }
}

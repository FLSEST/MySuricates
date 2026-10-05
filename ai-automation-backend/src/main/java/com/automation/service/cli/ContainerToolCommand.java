package com.automation.service.cli;

import java.util.ArrayList;
import java.util.List;

/** docker-run arguments for the tool mode (TODO d3a7f5e2): project read-only, no {@code /bin/bash -c}. */
final class ContainerToolCommand {

    private ContainerToolCommand() {}

    static List<String> build(String hostUid, ContainerToolRequest request, boolean mountDockerSocket,
                              List<String> safeExtraMounts) {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--user", hostUid, "--workdir", "/workspace"));
        if (request.projectPath() != null && !request.projectPath().isBlank()) {
            cmd.addAll(List.of("-v", request.projectPath() + ":/workspace:ro"));
        }
        addMount(cmd, request.output());
        addMount(cmd, request.cache());
        if (mountDockerSocket) cmd.addAll(List.of("-v", "/var/run/docker.sock:/var/run/docker.sock"));
        for (String mount : safeExtraMounts) cmd.addAll(List.of("-v", mount));
        cmd.addAll(List.of("--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--memory", "2g", "--pids-limit", "100"));
        cmd.add(request.image());
        cmd.addAll(request.args());
        return cmd;
    }

    private static void addMount(List<String> cmd, ContainerToolRequest.Mount mount) {
        if (mount != null) {
            cmd.addAll(List.of("-v", mount.hostDir().toAbsolutePath() + ":" + mount.containerPath() + ":rw"));
        }
    }
}

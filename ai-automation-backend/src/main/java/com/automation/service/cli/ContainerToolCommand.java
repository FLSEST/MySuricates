package com.automation.service.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * docker-run arguments for the tool mode (TODO d3a7f5e2): project read-only, no {@code /bin/bash -c}. The
 * platform's own mounts use {@code --mount}, which fails on a missing source — {@code -v} would create it as an
 * empty directory, and a scanner would report a clean, empty project.
 */
final class ContainerToolCommand {

    private ContainerToolCommand() {}

    static List<String> build(String hostUid, ContainerToolRequest request, boolean mountDockerSocket,
                              List<String> safeExtraMounts) {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--user", hostUid, "--workdir", "/workspace"));
        if (request.projectPath() != null && !request.projectPath().isBlank()) {
            cmd.addAll(List.of("--mount", bind(request.projectPath(), "/workspace") + ",readonly"));
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
            cmd.addAll(List.of("--mount", bind(mount.hostDir().toAbsolutePath().toString(), mount.containerPath())));
        }
    }

    /** {@code --mount} reads CSV: a field with a comma or quote is quoted as a whole, quotes doubled. */
    private static String bind(String source, String target) {
        return "type=bind," + field("source", source) + "," + field("target", target);
    }

    private static String field(String key, String value) {
        String text = key + "=" + value;
        return text.contains(",") || text.contains("\"") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}

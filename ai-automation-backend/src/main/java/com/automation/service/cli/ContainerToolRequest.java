package com.automation.service.cli;

import java.nio.file.Path;
import java.util.List;

/**
 * One tool-mode container run (TODO d3a7f5e2): the image's own entrypoint gets {@code args}, no shell in
 * between. {@code output} and {@code cache} are mounts the platform manages itself (fresh output directory,
 * persistent cache per image) — unlike {@code extraMounts}, which pass the server's mount policy.
 */
public record ContainerToolRequest(String image, List<String> args, String projectPath, Mount output, Mount cache,
                                   List<String> extraMounts, boolean mountDockerSocket, int timeoutSeconds) {

    /** A platform-managed bind mount, always read-write: host directory → path in the container. */
    public record Mount(Path hostDir, String containerPath) {}
}

package com.automation.executor.blocks;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.automation.config.ExecutionDefaults.SHELL_TIMEOUT_SECONDS;

/**
 * The tool-mode keys of a shell block (TODO d3a7f5e2): {@code container_args} instead of {@code command},
 * optionally SARIF as output format. Read here in one method so {@code ExecutorConfigKeyDriftTest} measures
 * them ({@code DELEGATES}).
 */
record ContainerToolSpec(String image, List<String> args, boolean sarif, String outputDir, String cacheDir,
                         String factPrefix, List<String> mounts, boolean mountDockerSocket, int timeoutSeconds) {

    static final String DEFAULT_OUTPUT_DIR = "/out";

    static ContainerToolSpec from(Map<String, Object> config, String blockName) {
        Object image = config.get("container_image");
        Object args = config.get("container_args");
        Object outputDir = config.get("output_dir");
        Object cacheDir = config.get("cache_dir");
        Object prefix = config.get("fact_prefix");
        Object mounts = config.get("mounts");
        Object timeout = config.get("timeout_seconds");
        return new ContainerToolSpec(
                image instanceof String s && !s.isBlank() ? s.strip() : null,
                args instanceof List<?> list ? list.stream().map(a -> a == null ? "" : a.toString()).toList() : null,
                "sarif".equals(config.get("output_format")),
                outputDir instanceof String s && !s.isBlank() ? s.strip() : DEFAULT_OUTPUT_DIR,
                cacheDir instanceof String s && !s.isBlank() ? s.strip() : null,
                factPrefix(prefix instanceof String p && !p.isBlank() ? p : blockName),
                mounts instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(),
                Boolean.TRUE.equals(config.get("mount_docker_socket")),
                timeout instanceof Number n ? n.intValue() : SHELL_TIMEOUT_SECONDS);
    }

    /** {@code [a-z0-9_]} only — the names must match the FAKT/TREFFER grammar of the audit protocol. */
    static String factPrefix(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "tool" : s;
    }
}

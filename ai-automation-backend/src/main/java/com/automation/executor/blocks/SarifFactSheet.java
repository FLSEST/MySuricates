package com.automation.executor.blocks;

import com.automation.util.UntrustedText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Fact sheet from the SARIF files a tool container wrote (TODO d3a7f5e2): counts per severity as
 * {@code FAKT <prefix>_…} lines, one {@code TREFFER <prefix>:} line per result and {@code NICHT GEMESSEN}
 * instead of a silent zero when nothing readable is there — the measurement format of
 * docs/audit-workflow-protocol.md. Tool texts are foreign: each goes through {@link UntrustedText}, and code
 * snippets ({@code region.snippet}, {@code contextRegion}) are never printed, so a secret a scanner found does
 * not end up in the step output. Symlinks in the output directory are ignored, never followed.
 */
final class SarifFactSheet {

    static final int MAX_HITS = 200;
    static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    private static final int RAW_TAIL_LINES = 40;
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    private static final String WORKSPACE = "/workspace/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The sheet, whether it rests on readable SARIF, and the number of unsuppressed results. */
    record Result(String text, boolean measured, int findings) {}

    private record Hit(String severity, String location, String ruleId, String message) {}

    private SarifFactSheet() {}

    static Result read(Path hostDir, String containerDir, String prefix, int exitCode, String rawOutput) {
        List<Path> files;
        try {
            files = sarifFiles(hostDir);
        } catch (IOException e) {
            return notMeasured(prefix, "Ausgabeordner nicht lesbar: " + e.getMessage(), exitCode, rawOutput);
        }
        if (files.isEmpty()) {
            return notMeasured(prefix, "keine SARIF-Datei in " + containerDir
                    + " - schreibt das Werkzeug seinen Bericht dorthin?", exitCode, rawOutput);
        }
        List<Hit> hits = new ArrayList<>();
        Set<String> tools = new LinkedHashSet<>();
        int suppressed = 0;
        long bytes = 0;
        for (Path file : files) {
            try {
                bytes += Files.size(file);
                if (bytes > MAX_TOTAL_BYTES) {
                    return notMeasured(prefix, "SARIF-Dateien zusammen größer als 50 MB", exitCode, rawOutput);
                }
                JsonNode runs = MAPPER.readTree(file.toFile()).path("runs");
                if (!runs.isArray()) {
                    return notMeasured(prefix, file.getFileName() + " ist kein SARIF (runs fehlt)", exitCode, rawOutput);
                }
                for (JsonNode run : runs) {
                    if (failedInvocation(run)) {
                        return notMeasured(prefix, "Werkzeug meldet executionSuccessful=false", exitCode, rawOutput);
                    }
                    tools.add(toolLabel(run));
                    suppressed += collect(run, hits);
                }
            } catch (IOException e) {
                return notMeasured(prefix, file.getFileName() + " nicht lesbar: " + e.getMessage(), exitCode, rawOutput);
            }
        }
        return new Result(sheet(prefix, tools, files.size(), hits, suppressed, exitCode), true, hits.size());
    }

    /** Regular files named *.sarif or *.sarif.json directly in {@code dir}; symlinks are skipped. */
    private static List<Path> sarifFiles(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".sarif") || name.endsWith(".sarif.json");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static boolean failedInvocation(JsonNode run) {
        for (JsonNode invocation : run.path("invocations")) {
            JsonNode ok = invocation.path("executionSuccessful");
            if (ok.isBoolean() && !ok.asBoolean()) return true;
        }
        return false;
    }

    private static String toolLabel(JsonNode run) {
        JsonNode driver = run.path("tool").path("driver");
        String version = driver.path("version").asText(driver.path("semanticVersion").asText(""));
        return (driver.path("name").asText("?") + " " + version).trim();
    }

    /** Adds one hit per unsuppressed result of {@code run}; returns the number of suppressed ones. */
    private static int collect(JsonNode run, List<Hit> hits) {
        JsonNode rules = run.path("tool").path("driver").path("rules");
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode rule : rules) byId.putIfAbsent(rule.path("id").asText(""), rule);
        int suppressed = 0;
        for (JsonNode result : run.path("results")) {
            if (result.path("suppressions").size() > 0) {
                suppressed++;
                continue;
            }
            String ruleId = ruleId(result);
            JsonNode rule = rule(result, rules, byId.get(ruleId));
            String message = result.path("message").path("text").asText("");
            if (message.isBlank()) message = rule.path("shortDescription").path("text").asText("");
            hits.add(new Hit(severity(result, rule), location(result), ruleId, message));
        }
        return suppressed;
    }

    private static String ruleId(JsonNode result) {
        String id = result.path("ruleId").asText("");
        return id.isEmpty() ? result.path("rule").path("id").asText("") : id;
    }

    private static JsonNode rule(JsonNode result, JsonNode rules, JsonNode byId) {
        int index = result.path("ruleIndex").asInt(-1);
        if (index >= 0 && index < rules.size()) return rules.get(index);
        return byId == null ? MissingNode.getInstance() : byId;
    }

    /** {@code security-severity} (result, then rule) on the CVSS scale; otherwise the SARIF level. */
    static String severity(JsonNode result, JsonNode rule) {
        Double score = score(result.path("properties").path("security-severity"));
        if (score == null) score = score(rule.path("properties").path("security-severity"));
        if (score != null) {
            return score >= 9.0 ? "CRITICAL" : score >= 7.0 ? "HIGH" : score >= 4.0 ? "MEDIUM" : "LOW";
        }
        String level = result.path("level").asText("");
        if (level.isEmpty()) level = rule.path("defaultConfiguration").path("level").asText("warning");
        return switch (level) {
            case "error" -> "HIGH";
            case "note", "none" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private static Double score(JsonNode node) {
        if (node.isNumber()) return node.asDouble();
        if (!node.isTextual()) return null;
        try {
            return Double.parseDouble(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** First physical location as a repo-relative {@code path[:line]}; {@code /workspace/} is the container mount. */
    static String location(JsonNode result) {
        JsonNode physical = result.path("locations").path(0).path("physicalLocation");
        String uri = physical.path("artifactLocation").path("uri").asText("");
        if (uri.isBlank()) return "(ohne Ort)";
        String path = uri;
        if (uri.startsWith("file:")) {
            try {
                path = URI.create(uri).getPath();
            } catch (IllegalArgumentException e) {
                path = uri.substring("file:".length()).replaceFirst("^/+", "/");
            }
        }
        if (path == null) path = uri;
        if (path.startsWith(WORKSPACE)) path = path.substring(WORKSPACE.length());
        int line = physical.path("region").path("startLine").asInt(0);
        return line > 0 ? path + ":" + line : path;
    }

    private static String sheet(String prefix, Set<String> tools, int fileCount, List<Hit> hits, int suppressed,
                                int exitCode) {
        Map<String, Integer> counts = new HashMap<>();
        hits.forEach(hit -> counts.merge(hit.severity(), 1, Integer::sum));
        StringBuilder out = new StringBuilder("## FAKTENBLATT ").append(prefix).append(" (")
                .append(UntrustedText.inline(String.join(", ", tools), 120)).append(", ").append(fileCount)
                .append(fileCount == 1 ? " SARIF-Datei)" : " SARIF-Dateien)").append('\n');
        fact(out, prefix + "_findings", hits.size());
        for (String severity : SEVERITIES) {
            fact(out, prefix + "_" + severity.toLowerCase(Locale.ROOT), counts.getOrDefault(severity, 0));
        }
        fact(out, prefix + "_suppressed", suppressed);
        fact(out, prefix + "_exit_code", exitCode);
        hits.stream()
                .sorted(Comparator.comparingInt((Hit hit) -> SEVERITIES.indexOf(hit.severity()))
                        .thenComparing(Hit::location))
                .limit(MAX_HITS)
                .forEach(hit -> out.append("TREFFER ").append(prefix).append(": ")
                        .append(UntrustedText.inline(hit.location(), 300)).append(' ')
                        .append(hit.severity()).append(' ')
                        .append(hit.ruleId().isBlank() ? "-" : UntrustedText.inline(hit.ruleId(), 120))
                        .append(" – ").append(UntrustedText.inline(hit.message(), UntrustedText.MAX_TITLE_CHARS))
                        .append('\n'));
        if (hits.size() > MAX_HITS) {
            out.append("TREFFER ").append(prefix).append(": (+").append(hits.size() - MAX_HITS)
                    .append(" weitere, nicht einzeln aufgeführt)\n");
        }
        return out.toString().stripTrailing();
    }

    private static void fact(StringBuilder out, String name, int value) {
        out.append("FAKT ").append(name).append(": ").append(value).append('\n');
    }

    private static Result notMeasured(String prefix, String reason, int exitCode, String rawOutput) {
        StringBuilder out = new StringBuilder("## FAKTENBLATT ").append(prefix).append('\n')
                .append("NICHT GEMESSEN: ").append(prefix).append("_findings (")
                .append(UntrustedText.inline(reason, 300)).append(")\n")
                .append("FAKT ").append(prefix).append("_exit_code: ").append(exitCode).append('\n');
        String tail = tail(rawOutput);
        if (!tail.isBlank()) {
            out.append("## WERKZEUG-LOG (letzte ").append(RAW_TAIL_LINES).append(" Zeilen)\n")
                    .append(UntrustedText.defuseLines(tail));
        }
        return new Result(out.toString().stripTrailing(), false, 0);
    }

    private static String tail(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> lines = raw.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - RAW_TAIL_LINES), lines.size()));
    }
}

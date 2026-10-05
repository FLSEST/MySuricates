package com.automation.executor.blocks;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;

/**
 * Where a SARIF result points, as a repo-relative {@code path[:line]} (TODO d3a7f5e2). A {@code uri} may be relative
 * to a {@code uriBaseId} that the run defines in {@code originalUriBaseIds} — {@code trivy fs /workspace/sub} writes
 * {@code ROOTPATH = file:///workspace/sub/} and {@code uri = creds.txt}. Bases may chain; the project is mounted at
 * {@code /workspace/}, which is cut off.
 */
final class SarifLocation {

    private static final String WORKSPACE = "/workspace/";
    private static final int MAX_BASE_DEPTH = 4;

    private SarifLocation() {}

    /** First physical location of {@code result}; {@code bases} is the run's {@code originalUriBaseIds}. */
    static String of(JsonNode result, JsonNode bases) {
        JsonNode physical = result.path("locations").path(0).path("physicalLocation");
        JsonNode artifact = physical.path("artifactLocation");
        String uri = artifact.path("uri").asText("");
        if (uri.isBlank()) return "(ohne Ort)";
        String path = filePath(withBase(uri, artifact.path("uriBaseId").asText(""), bases, 0));
        if (path.startsWith(WORKSPACE)) path = path.substring(WORKSPACE.length());
        int line = physical.path("region").path("startLine").asInt(0);
        return line > 0 ? path + ":" + line : path;
    }

    /** Prefixes the base's uri (recursively); an unknown base leaves the uri as it is, already repo-relative. */
    private static String withBase(String uri, String baseId, JsonNode bases, int depth) {
        if (baseId.isEmpty() || depth >= MAX_BASE_DEPTH || uri.startsWith("file:") || uri.startsWith("/")) return uri;
        JsonNode base = bases.path(baseId);
        String baseUri = base.path("uri").asText("");
        if (baseUri.isEmpty()) return uri;
        String prefix = withBase(baseUri, base.path("uriBaseId").asText(""), bases, depth + 1);
        return prefix.endsWith("/") ? prefix + uri : prefix + "/" + uri;
    }

    private static String filePath(String uri) {
        if (!uri.startsWith("file:")) return uri;
        try {
            String path = URI.create(uri).getPath();
            if (path != null) return path;
        } catch (IllegalArgumentException e) {
            // fall through: a file uri with characters URI rejects (spaces) is still a path
        }
        return uri.substring("file:".length()).replaceFirst("^/+", "/");
    }
}

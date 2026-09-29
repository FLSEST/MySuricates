package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle.BundlePath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the absolute paths of the exporting machine into {@code @@path:N@@} tokens, in two passes over
 * the same strings: {@link #withProjectPlaceholder} + {@link #collect} first, {@link #replace} after
 * {@link #finish}.
 *
 * <ul>
 *   <li>The project's own path becomes {@code {{ project_path }}} where the caller asks for it
 *       ({@link #withProjectPlaceholder}: text the executor resolves). Only paths with at least two
 *       segments count ({@code /tmp} alone would swallow every temp path). Elsewhere it is collected
 *       like any other path, even outside the known roots.</li>
 *   <li>Every path under {@code /home/ /Users/ /tmp/ /opt/ /srv/ /mnt/ /var/ /data/ ~/} is
 *       collected. A path that starts with a collected path plus {@code /} is written as a sub path of
 *       that token ({@code @@path:1@@/tests/.}); the shortest root wins, and the boundary is the
 *       {@code /} — {@code /tmp/shopapp-qa-venv} is no sub path of {@code /tmp/shopapp}.</li>
 *   <li>A path ends at whitespace, quotes, {@code ; | ( ) { } < > , :} and the end of the line;
 *       a sentence full stop right after it is not part of it.</li>
 * </ul>
 */
final class PathTokenizer {

    static final String PROJECT_PLACEHOLDER = "{{ project_path }}";

    private static final String NOT_BEFORE = "(?<![\\w.\\-/~@}$])";
    private static final String PATH_CHARS = "[^\\s'\"`;|(){}<>,:]";
    private static final String KNOWN_ROOT_PATH = "(?:~/|/(?:home|Users|tmp|opt|srv|mnt|var|data)/)" + PATH_CHARS + "+";
    private static final String PATH_END = "(?=$|[/\\s'\"`;|(){}<>,:]|\\.(?:$|\\s))";

    private final Pattern projectPath;
    /** Known roots, plus the project path wherever it lives. */
    private final Pattern path;
    private final Map<String, Occurrence> found = new LinkedHashMap<>();
    private final Map<String, String> tokenOfRoot = new HashMap<>();
    private final List<String> roots = new ArrayList<>();
    private final Map<String, BundlePath> tokens = new LinkedHashMap<>();
    private boolean finished;

    private record Occurrence(int order, String hint, int[] count) {}

    /** @param projectPath the exported project's folder; blank or a single segment disables the placeholder */
    PathTokenizer(String projectPath) {
        String normalized = stripTrailingSlashes(projectPath == null ? "" : projectPath.strip());
        boolean usable = normalized.startsWith("/") && normalized.substring(1).contains("/");
        this.projectPath = usable ? Pattern.compile(NOT_BEFORE + Pattern.quote(normalized) + PATH_END) : null;
        this.path = Pattern.compile(NOT_BEFORE + "(?:" + KNOWN_ROOT_PATH
                + (usable ? "|" + Pattern.quote(normalized) + PATH_END + "(?:/" + PATH_CHARS + "*)?" : "") + ")");
    }

    String withProjectPlaceholder(String text) {
        return projectPath == null ? text : projectPath.matcher(text).replaceAll(Matcher.quoteReplacement(PROJECT_PLACEHOLDER));
    }

    /** First pass: records every path in {@code text}. */
    void collect(String text, String hint) {
        Matcher m = path.matcher(text);
        while (m.find()) {
            record(trimSentenceEnd(m.group()), hint);
        }
    }

    /** First pass for a value that is a path as a whole ({@code source_path}); anything not absolute stays. */
    void collectWhole(String value, String hint) {
        if (isAbsolute(value)) {
            record(value.strip(), hint);
        }
    }

    /** Assigns the tokens — numbered in order of first appearance. */
    List<BundlePath> finish() {
        if (finished) {
            return List.copyOf(tokens.values());
        }
        List<String> byLength = new ArrayList<>(found.keySet());
        byLength.sort(Comparator.comparingInt((String p) -> stripTrailingSlashes(p).length())
                .thenComparing(Comparator.naturalOrder()));
        Map<String, String> rootOf = new HashMap<>();
        for (String path : byLength) {
            String root = findRoot(path);
            if (root == null) {
                root = stripTrailingSlashes(path);
                roots.add(root);
            }
            rootOf.put(path, root);
        }
        List<String> byAppearance = new ArrayList<>(found.keySet());
        byAppearance.sort(Comparator.comparingInt(p -> found.get(p).order()));
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> hints = new HashMap<>();
        for (String path : byAppearance) {
            String root = rootOf.get(path);
            counts.computeIfAbsent(root, r -> new int[1])[0] += found.get(path).count()[0];
            hints.putIfAbsent(root, found.get(path).hint());
        }
        int n = 1;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            String token = "@@path:" + n++ + "@@";
            tokenOfRoot.put(e.getKey(), token);
            tokens.put(token, new BundlePath(token, e.getKey(), e.getValue()[0], hints.get(e.getKey())));
        }
        finished = true;
        return List.copyOf(tokens.values());
    }

    /** Second pass: every collected path in {@code text} replaced by its token (plus sub path). */
    String replace(String text) {
        requireFinished();
        Matcher m = path.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String path = trimSentenceEnd(m.group());
            m.appendReplacement(out, Matcher.quoteReplacement(tokenize(path) + m.group().substring(path.length())));
        }
        m.appendTail(out);
        return out.toString();
    }

    String replaceWhole(String value) {
        requireFinished();
        return isAbsolute(value) ? tokenize(value.strip()) : value;
    }

    private String tokenize(String path) {
        String root = findRoot(path);
        return root == null ? path : tokenOfRoot.get(root) + path.substring(root.length());
    }

    /** The shortest collected root that is {@code path} itself or a parent of it (boundary at {@code /}). */
    private String findRoot(String path) {
        String bare = stripTrailingSlashes(path);
        for (String root : roots) { // sorted by length: the first hit is the shortest
            if (bare.equals(root) || path.startsWith(root + "/")) {
                return root;
            }
        }
        return null;
    }

    private void record(String path, String hint) {
        Occurrence o = found.computeIfAbsent(path, p -> new Occurrence(found.size(), hint, new int[1]));
        o.count()[0]++;
    }

    private void requireFinished() {
        if (!finished) {
            throw new IllegalStateException("finish() must run before replace()");
        }
    }

    private static boolean isAbsolute(String value) {
        String v = value.strip();
        return v.length() > 1 && (v.startsWith("/") || v.startsWith("~/"));
    }

    /** {@code /tmp/out.} at the end of a sentence → {@code /tmp/out}; {@code /tests/.} and {@code ..} stay. */
    private static String trimSentenceEnd(String path) {
        if (path.endsWith(".") && path.length() > 1) {
            char before = path.charAt(path.length() - 2);
            if (before != '/' && before != '.') {
                return path.substring(0, path.length() - 1);
            }
        }
        return path;
    }

    private static String stripTrailingSlashes(String path) {
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}

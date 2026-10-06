package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.ExecutionMode;
import com.automation.executor.NestedBlocks;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules against a block's access to the <b>platform itself</b>: the instance's REST API and its own
 * SQLite database (TODO d0348479, step 4).
 *
 * <p>CLAUDE.md forbids both: whatever a workflow needs from the platform is injected by the
 * WorkflowExecutor as a context variable ({@code {{ open_todos }}}, {@code {{ previous_report }}} …),
 * TODOs are created via the blocks {@code todo-create}/{@code todo-update}/{@code todo-close}. A block
 * that calls {@code curl …/api/v1/todos} instead needs an API key in plain text; one that unleashes
 * {@code sqlite3} on the database bypasses Hibernate and the single connection.
 * {@code scripts/ci/pool-neutrality.sh} (rules {@code SELF-API}, {@code DB-DIRECT}) checks this only
 * for the shipped pool files; these rules also see the live definitions of an instance.
 *
 * <p><b>Alignment with pool-neutrality.sh</b> — same exceptions, deliberate differences:
 * <ul>
 *   <li>The same key-free probes are allowed as there ({@link #KEY_FREE_PROBES}, each a
 *       public path from {@code security.PublicPaths}); {@code /api/v1/} and
 *       {@code /actuator/} are checked, where only {@code health} answers without a key (TODO 7721d8cb).</li>
 *   <li>In shell code a concrete endpoint ({@code /api/v1/<segment>}) suffices, as there; the call
 *       may be far away (loop variable). The bare prefix is a search pattern, not a
 *       call (mocking-gap-audit). <i>Unlike there</i>, whole comment lines do not count and neither does an
 *       endpoint with an explicitly foreign host ({@code https://api.example.com/api/v1/…}):
 *       live workflows call foreign services, the pool only checks its own files.</li>
 *   <li>Outside shell code (AI task) the rule reports only a local endpoint with a caller
 *       nearby, and only as {@code MEDIUM} "prüfen": whether the AI really executes the call cannot
 *       be decided from the text.</li>
 *   <li>The instance's key file ({@code ~/.mysuricates/api-key}, {@code ~/.autotrigger/api-key},
 *       {@code $AUTOMATION_DATA_HOME/api-key}, {@code $(data_home …)/api-key}) in shell code is
 *       {@code HIGH} — even without a {@code curl} afterwards (TODO ee8bb93d): whoever reads the key holds it
 *       in plain text, no matter where it is passed on. In the AI task only {@code MEDIUM} "prüfen". The
 *       pool reports the same paths as credentials ({@code HYGIENE_POOL_CREDENTIAL_MARKERS}).</li>
 *   <li>{@code sqlite3} alone is a finding there, here only {@code MEDIUM} "prüfen": a
 *       live workflow may work with its project's SQLite file. It only becomes {@code HIGH} with
 *       the name of the platform DB ({@code automation.db}, {@code automation-dev.db}) or the
 *       table {@code app_settings} (as a whole word — {@code lernapp_settings} was a false positive).</li>
 * </ul>
 */
final class SelfAccessRules {

    private SelfAccessRules() {}

    /** Reachable without an API key (subset of PublicPaths.EXACT) — same list as HYGIENE_POOL_API_ALLOWED. */
    static final Set<String> KEY_FREE_PROBES = Set.of(
            "/api/v1/system/health", "/api/v1/system/info", "/api/v1/system/auth-status",
            "/api/v1/system/jvm-memory", "/actuator/health");

    /** Concrete endpoint: a path segment must follow the prefix, like PATH_AT in the script. */
    private static final Pattern ENDPOINT = Pattern.compile("(?:/api/v1|/actuator)/[A-Za-z0-9_][A-Za-z0-9/_.{}<>-]*");

    /** Prefix followed by a variable instead of a segment ({@code curl …/api/v1/$E}, TODO 2a0c9420). */
    private static final Pattern COMPOSED = Pattern.compile("(?:/api/v1|/actuator)/?[\"']?\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?");

    /** Scheme and host directly before the endpoint, e.g. {@code http://localhost:9090}. */
    private static final Pattern HOST_BEFORE = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://((?:\\{\\{[^}]*}}|[^/\\s\"'`])+)$");

    /** Hosts that mean the instance itself; a placeholder or a variable may mean it. */
    private static final Pattern LOCAL_HOST = Pattern.compile(
            "(?i)(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1]|host\\.docker\\.internal)(:\\d+)?|.*(\\{\\{|}}|\\$).*");

    /** Aufrufer wie HYGIENE_POOL_API_CALLERS. */
    private static final List<String> CALLERS = List.of("curl", "wget", "requests.", "urllib", "fetch(", "http.client");

    /** As in the script: a good command line around the match. */
    private static final int WINDOW = 200;

    /**
     * The instance's key file in the data folder (DataHome, InstanceAddress.apiKeyFile). The boundary
     * {@code \b} after {@code api-key} deliberately also matches copies like {@code api-key.bak}/{@code api-key-backup}
     * (same key), but not other files like {@code api-keys.txt}.
     */
    private static final Pattern KEY_FILE = Pattern.compile(
            "(?:\\.mysuricates|\\.autotrigger|AUTOMATION_DATA_HOME}?|\\bdata_home\\b[^\\n]{0,60}?)/api-key\\b");

    private static final Pattern SQLITE = Pattern.compile("\\bsqlite3\\b");
    private static final Pattern PLATFORM_DB = Pattern.compile("\\bautomation(-[a-z]+)?\\.db\\b");
    private static final Pattern APP_SETTINGS = Pattern.compile("\\bapp_settings\\b");
    private static final Pattern DB_ACCESS = Pattern.compile(
            "(?i)\\bsqlite3\\b|\\bSELECT\\s|\\bINSERT\\s+INTO\\b|\\bUPDATE\\s|\\bDELETE\\s+FROM\\b|\\.tables\\b|\\.schema\\b");

    /** Keys whose value runs as shell in a shell block or with {@code execution_mode: shell}. */
    private static final List<String> SHELL_KEYS =
            List.of("command", "condition", "condition_command", "then_command", "else_command");

    /** Keys that always run in the shell: the foreach list and the pre-test {@code run_if}. */
    private static final List<String> ALWAYS_SHELL_KEYS = List.of("items_command", "run_if");

    private static final String ALTERNATIVE = " — Plattformdaten kommen als Kontextvariable (etwa {{ open_todos }},"
            + " {{ previous_report }}), TODOs über die Blöcke todo-create/todo-update/todo-close,"
            + " externe Dienste über den Integrations-Block";
    private static final String ALTERNATIVE_EN = " — platform data comes as a context variable (such as {{ open_todos }},"
            + " {{ previous_report }}), TODOs via the todo-create/todo-update/todo-close blocks,"
            + " external services via the integration block";

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            List<String> shell = shellCode(block);
            selfApiCall(shell, BlockText.allStrings(block), i, block.getName(), out);
            directDbAccess(shell, BlockText.joined(block), i, block.getName(), out);
        }
    }

    private static void selfApiCall(List<String> shell, List<String> all, int i, String name,
                                    List<AnalyzerFinding> out) {
        for (String code : shell) {
            String stripped = withoutCommentLines(code);
            String hit = firstSelfEndpoint(stripped, false);
            if (hit == null) hit = firstComposedEndpoint(stripped);
            if (hit == null) hit = BaseUrlVariables.firstAssignment(stripped);
            if (hit == null) hit = BaseUrlVariables.firstLaterCall(stripped);
            if (hit != null) {
                out.add(AnalyzerFinding.of("self-api-call", Severity.HIGH, i, name,
                        "ruft im Shell-Code die eigene REST-API auf ('" + hit + "') — braucht einen API-Key"
                                + " im Klartext und umgeht die Kontextinjektion" + ALTERNATIVE,
                        "calls the instance's own REST API in shell code ('" + hit + "') — needs an API key"
                                + " in plain text and bypasses the context injection" + ALTERNATIVE_EN));
                return;
            }
        }
        for (String code : shell) {
            Matcher keyFile = KEY_FILE.matcher(withoutCommentLines(code));
            if (keyFile.find()) {
                out.add(AnalyzerFinding.of("self-api-call", Severity.HIGH, i, name,
                        "liest im Shell-Code den API-Schlüssel der Instanz ('" + keyFile.group() + "') — damit"
                                + " hält der Block ihn im Klartext, auch ohne Aufruf der REST-API" + ALTERNATIVE,
                        "reads the instance's API key in shell code ('" + keyFile.group() + "') — the block then"
                                + " holds it in plain text, even without calling the REST API" + ALTERNATIVE_EN));
                return;
            }
        }
        for (String text : all) {
            if (shell.contains(text)) continue;
            String hit = firstSelfEndpoint(text, true);
            if (hit != null) {
                out.add(AnalyzerFinding.of("self-api-call", Severity.MEDIUM, i, name,
                        "der Auftrag nennt einen Aufruf der eigenen REST-API ('" + hit + "') — prüfen, ob die"
                                + " KI ihn ausführen soll" + ALTERNATIVE,
                        "the prompt mentions a call to the instance's own REST API ('" + hit + "') — check whether"
                                + " the AI is meant to make it" + ALTERNATIVE_EN));
                return;
            }
        }
        for (String text : all) {
            if (shell.contains(text)) continue;
            Matcher keyFile = KEY_FILE.matcher(text);
            if (keyFile.find()) {
                out.add(AnalyzerFinding.of("self-api-call", Severity.MEDIUM, i, name,
                        "der Auftrag nennt die Schlüsseldatei der Instanz ('" + keyFile.group() + "') — prüfen,"
                                + " ob die KI sie lesen soll" + ALTERNATIVE,
                        "the prompt mentions the instance's key file ('" + keyFile.group() + "') — check whether"
                                + " the AI is meant to read it" + ALTERNATIVE_EN));
                return;
            }
        }
    }

    /** {@link #COMPOSED} without an explicitly foreign host before it, without quotation marks, or {@code null}. */
    private static String firstComposedEndpoint(String code) {
        Matcher composed = COMPOSED.matcher(code);
        while (composed.find()) {
            Matcher host = HOST_BEFORE.matcher(code.substring(Math.max(0, composed.start() - WINDOW),
                    composed.start()));
            if (!host.find() || LOCAL_HOST.matcher(host.group(1)).matches()) {
                return composed.group().replaceAll("[\"']", "");
            }
        }
        return null;
    }

    /**
     * The first endpoint of the platform's own API that is not a key-free probe, or {@code null}. An
     * explicitly foreign host never counts. With {@code needsCaller} (text outside the shell) only an
     * endpoint with a local or variable host and a caller nearby.
     */
    private static String firstSelfEndpoint(String text, boolean needsCaller) {
        Matcher endpoint = ENDPOINT.matcher(text);
        while (endpoint.find()) {
            String path = endpoint.group().replaceAll("\\.+$", "");
            if (KEY_FREE_PROBES.contains(path)) continue;
            String before = text.substring(Math.max(0, endpoint.start() - WINDOW), endpoint.start());
            Matcher host = HOST_BEFORE.matcher(before);
            boolean hasHost = host.find();
            if (hasHost && !LOCAL_HOST.matcher(host.group(1)).matches()) continue;
            if (needsCaller) {
                if (!hasHost && !before.matches("(?s).*(\\}\\}|\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?)$")) continue;
                String window = before + text.substring(endpoint.start(),
                        Math.min(text.length(), endpoint.end() + WINDOW));
                if (CALLERS.stream().noneMatch(window::contains)) continue;
            }
            return path;
        }
        return null;
    }

    private static void directDbAccess(List<String> shell, String all, int i, String name,
                                       List<AnalyzerFinding> out) {
        Matcher settings = APP_SETTINGS.matcher(all);
        if (settings.find()) {
            out.add(AnalyzerFinding.of("direct-db-access", Severity.HIGH, i, name,
                    "nennt die Tabelle app_settings — dort liegen API-Key und Zugangsdaten; der direkte"
                            + " Zugriff umgeht Hibernate und die einzige Connection (pool-size=1)" + ALTERNATIVE,
                    "names the table app_settings — it holds the API key and credentials; direct"
                            + " access bypasses Hibernate and the single connection (pool-size=1)" + ALTERNATIVE_EN));
            return;
        }
        Matcher db = PLATFORM_DB.matcher(all);
        while (db.find()) {
            String window = all.substring(Math.max(0, db.start() - WINDOW), Math.min(all.length(), db.end() + WINDOW));
            if (DB_ACCESS.matcher(window).find()) {
                out.add(AnalyzerFinding.of("direct-db-access", Severity.HIGH, i, name,
                        "liest die Plattform-Datenbank '" + db.group() + "' direkt — erlaubt ist nur, die"
                                + " Dateigröße anzusehen (stat, du); der Zugriff umgeht Hibernate und die"
                                + " einzige Connection (pool-size=1)" + ALTERNATIVE,
                        "reads the platform database '" + db.group() + "' directly — only looking at the"
                                + " file size is allowed (stat, du); the access bypasses Hibernate and the"
                                + " single connection (pool-size=1)" + ALTERNATIVE_EN));
                return;
            }
        }
        if (shell.stream().map(SelfAccessRules::withoutCommentLines).anyMatch(code -> SQLITE.matcher(code).find())) {
            out.add(AnalyzerFinding.of("direct-db-access", Severity.MEDIUM, i, name,
                    "ruft sqlite3 auf — prüfen, ob die Plattform-Datenbank gemeint ist; die Datei eines"
                            + " Projekts ist in Ordnung, die der Instanz nie" + ALTERNATIVE,
                    "calls sqlite3 — check whether the platform database is meant; a project's own"
                            + " file is fine, the instance's never" + ALTERNATIVE_EN));
        }
    }

    /** The shell code of a block including nested blocks, in definition order. */
    static List<String> shellCode(BlockDefinition block) {
        List<String> out = new ArrayList<>();
        if (block == null) return out;
        addShell(block.getType(), block.getConfig(), out);
        NestedBlocks.visit(block.getConfig(), nested -> addShell(nested.type(), nested.config(), out));
        return out;
    }

    private static void addShell(String type, Map<String, Object> config, List<String> out) {
        if (config == null) return;
        if ("shell".equals(type) || ExecutionMode.isShell(config)) {
            SHELL_KEYS.forEach(key -> addText(config.get(key), out));
            // Werkzeug-Modus (d3a7f5e2): die Argumente eines Containers laufen wie Shell-Code
            if (config.get("container_args") instanceof List<?> args) {
                addText(String.join(" ", args.stream().map(String::valueOf).toList()), out);
            }
        }
        ALWAYS_SHELL_KEYS.forEach(key -> addText(config.get(key), out));
    }

    private static void addText(Object value, List<String> out) {
        if (value instanceof String text && !text.isBlank()) out.add(text);
    }

    /** Whole comment lines ({@code # …}) say what the block does or does not do — they call nothing. */
    private static String withoutCommentLines(String code) {
        StringBuilder kept = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            if (!line.stripLeading().startsWith("#")) kept.append(line).append('\n');
        }
        return kept.toString();
    }
}

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
 * Regeln gegen den Zugriff eines Blocks auf die <b>eigene Plattform</b>: REST-API der Instanz und
 * SQLite-Datenbank (TODO d0348479, Schritt 4).
 *
 * <p>CLAUDE.md verbietet beides: Was ein Workflow von der Plattform braucht, injiziert der
 * WorkflowExecutor als Kontextvariable ({@code {{ open_todos }}}, {@code {{ previous_report }}} …),
 * TODOs entstehen über die Blöcke {@code todo-create}/{@code todo-update}/{@code todo-close}. Ein Block,
 * der stattdessen {@code curl …/api/v1/todos} ruft, braucht einen API-Key im Klartext; einer, der
 * {@code sqlite3} auf die Datenbank loslässt, geht an Hibernate und der einzigen Connection vorbei.
 * {@code scripts/ci/pool-neutrality.sh} (Regeln {@code SELF-API}, {@code DB-DIRECT}) prüft das nur
 * für die ausgelieferten Pool-Dateien; diese Regeln sehen auch die Live-Definitionen einer Instanz.
 *
 * <p><b>Abstimmung mit pool-neutrality.sh</b> — gleiche Ausnahmen, bewusste Unterschiede:
 * <ul>
 *   <li>Erlaubt sind dieselben schlüsselfreien Sonden wie dort ({@link #KEY_FREE_PROBES}, jede ein
 *       öffentlicher Pfad aus {@code security.PublicPaths}); geprüft werden {@code /api/v1/} und
 *       {@code /actuator/}, unter dem nur {@code health} ohne Key antwortet (TODO 7721d8cb).</li>
 *   <li>Im Shell-Code genügt wie dort ein konkreter Endpunkt ({@code /api/v1/<segment>}), der Aufruf
 *       darf weit entfernt stehen (Schleifenvariable). Das nackte Präfix ist ein Suchmuster, kein
 *       Aufruf (mocking-gap-audit). <i>Anders als dort</i> zählen ganze Kommentarzeilen nicht und ein
 *       Endpunkt mit ausdrücklich fremdem Host ({@code https://api.example.com/api/v1/…}) auch nicht:
 *       Live-Workflows rufen fremde Dienste, der Pool prüft nur seine eigenen Dateien.</li>
 *   <li>Außerhalb des Shell-Codes (KI-Auftrag) meldet die Regel nur einen lokalen Endpunkt mit Aufrufer
 *       in der Nähe, und nur als {@code MEDIUM} „prüfen": Ob die KI den Aufruf wirklich ausführt, lässt
 *       sich am Text nicht entscheiden.</li>
 *   <li>{@code sqlite3} allein ist dort schon ein Befund, hier nur {@code MEDIUM} „prüfen": Ein
 *       Live-Workflow darf mit der SQLite-Datei seines Projekts arbeiten. {@code HIGH} wird es erst mit
 *       dem Namen der Plattform-DB ({@code automation.db}, {@code automation-dev.db}) oder der
 *       Tabelle {@code app_settings} (als ganzes Wort — {@code lernapp_settings} war ein Fehlbefund).</li>
 * </ul>
 */
final class SelfAccessRules {

    private SelfAccessRules() {}

    /** Ohne API-Key erreichbar (Teilmenge von PublicPaths.EXACT) — dieselbe Liste wie HYGIENE_POOL_API_ALLOWED. */
    static final Set<String> KEY_FREE_PROBES = Set.of(
            "/api/v1/system/health", "/api/v1/system/info", "/api/v1/system/auth-status",
            "/api/v1/system/jvm-memory", "/actuator/health");

    /** Konkreter Endpunkt: nach dem Präfix muss ein Pfadsegment folgen, wie PATH_AT im Skript. */
    private static final Pattern ENDPOINT = Pattern.compile("(?:/api/v1|/actuator)/[A-Za-z0-9_][A-Za-z0-9/_.{}<>-]*");

    /** Präfix, dahinter eine Variable statt eines Segments ({@code curl …/api/v1/$E}, TODO 2a0c9420). */
    private static final Pattern COMPOSED = Pattern.compile("(?:/api/v1|/actuator)/?[\"']?\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?");

    /** Schema und Host direkt vor dem Endpunkt, etwa {@code http://localhost:9090}. */
    private static final Pattern HOST_BEFORE = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://((?:\\{\\{[^}]*}}|[^/\\s\"'`])+)$");

    /** Hosts, die die eigene Instanz meinen; ein Platzhalter oder eine Variable kann sie meinen. */
    private static final Pattern LOCAL_HOST = Pattern.compile(
            "(?i)(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1]|host\\.docker\\.internal)(:\\d+)?|.*(\\{\\{|}}|\\$).*");

    /** Aufrufer wie HYGIENE_POOL_API_CALLERS. */
    private static final List<String> CALLERS = List.of("curl", "wget", "requests.", "urllib", "fetch(", "http.client");

    /** Wie im Skript: gut eine Befehlszeile um die Fundstelle. */
    private static final int WINDOW = 200;

    private static final Pattern SQLITE = Pattern.compile("\\bsqlite3\\b");
    private static final Pattern PLATFORM_DB = Pattern.compile("\\bautomation(-[a-z]+)?\\.db\\b");
    private static final Pattern APP_SETTINGS = Pattern.compile("\\bapp_settings\\b");
    private static final Pattern DB_ACCESS = Pattern.compile(
            "(?i)\\bsqlite3\\b|\\bSELECT\\s|\\bINSERT\\s+INTO\\b|\\bUPDATE\\s|\\bDELETE\\s+FROM\\b|\\.tables\\b|\\.schema\\b");

    /** Schlüssel, deren Wert bei einem Shell-Block bzw. {@code execution_mode: shell} als Shell läuft. */
    private static final List<String> SHELL_KEYS =
            List.of("command", "condition", "condition_command", "then_command", "else_command");

    /** Schlüssel, die immer in der Shell laufen: foreach-Liste und der Vorab-Test {@code run_if}. */
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
    }

    /** {@link #COMPOSED} ohne ausdrücklich fremden Host davor, ohne Anführungszeichen, oder {@code null}. */
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
     * Der erste Endpunkt der eigenen API, der keine schlüsselfreie Sonde ist, oder {@code null}. Ein
     * ausdrücklich fremder Host zählt nie. Mit {@code needsCaller} (Text außerhalb der Shell) nur ein
     * Endpunkt mit lokalem oder variablem Host und einem Aufrufer in der Nähe.
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

    /** Der Shell-Code eines Blocks samt verschachtelter Blöcke, in Definitionsreihenfolge. */
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
        }
        ALWAYS_SHELL_KEYS.forEach(key -> addText(config.get(key), out));
    }

    private static void addText(Object value, List<String> out) {
        if (value instanceof String text && !text.isBlank()) out.add(text);
    }

    /** Ganze Kommentarzeilen ({@code # …}) sagen, was der Block tut oder nicht tut — sie rufen nichts auf. */
    private static String withoutCommentLines(String code) {
        StringBuilder kept = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            if (!line.stripLeading().startsWith("#")) kept.append(line).append('\n');
        }
        return kept.toString();
    }
}

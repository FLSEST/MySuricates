package com.automation.service.workflow.analyzer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Basis-URLs der eigenen Instanz in Variablen — der Teil der Regel {@code self-api-call}
 * ({@link SelfAccessRules}), bei dem der Endpunkt im Shell-Code nie am Stück steht:
 * {@code API=http://127.0.0.1:9090/api/v1; curl $API/todos} oder
 * {@code HOST=http://localhost:9090; curl "$HOST/actuator/metrics"}. Erlaubt bleiben dieselben
 * schlüsselfreien Sonden ({@link SelfAccessRules#KEY_FREE_PROBES}).
 */
final class BaseUrlVariables {

    private BaseUrlVariables() {}

    /** Ein konkretes Pfadsegment hinter dem Präfix, wie ENDPOINT in {@link SelfAccessRules}. */
    private static final String SEGMENT = "/[A-Za-z0-9_][A-Za-z0-9/_.{}<>-]*";

    /**
     * Zuweisung einer Basis-URL der eigenen Instanz an eine Variable — Shell ({@code API=…},
     * {@code export BASE=…}) wie Python ({@code base = '…'}): {@code API=http://127.0.0.1:9090/api/v1;
     * curl $API/todos} nennt den Endpunkt nie am Stück. Die Zuweisung ist selbst der Befund, egal wo
     * die Variable später aufgerufen wird. Gruppe 1 ist der Pfad ab {@code /api/v1} bzw. {@code /actuator}.
     */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?m)(?:^|[\\s;&(])(?:export\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*[\"']?"
                    + "(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?"
                    + "(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1]|host\\.docker\\.internal"
                    + "|\\{\\{[^}]*}}|\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?)(?::\\d+)?"
                    + "((?:/api/v1|/actuator)(?:/[^\\s\"';]*)?)(?=[\"';\\s]|$)");

    /**
     * Zuweisung einer Basis-URL der eigenen Instanz OHNE Pfad oder NUR mit {@code /api} — die Variable
     * selbst ist (anders als {@link #ASSIGNMENT}) kein Befund, eine lokale URL ohne API-Pfad ist
     * oft legitim (Health, Vite, ein fremder lokaler Dienst). Erst der spätere Aufruf mit einem
     * {@code /api/v1/<segment>}- oder {@code /actuator/<segment>}- bzw. (wenn die Basis schon {@code /api}
     * enthält) {@code /v1/<segment>}-Suffix macht daraus einen Befund ({@code API=…/api; curl $API/v1/todos}).
     * Gruppe 1 = Variablenname, Gruppe 2 vorhanden, wenn die Basis schon {@code /api} enthält.
     */
    private static final Pattern SHORT_ASSIGNMENT = Pattern.compile(
            "(?m)(?:^|[\\s;&(])(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*[\"']?"
                    + "(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?"
                    + "(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1]|host\\.docker\\.internal"
                    + "|\\{\\{[^}]*}}|\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?)(?::\\d+)?"
                    + "(/api)?/?[\"']?(?=[\\s;&)\\n]|$)");

    /** Der Pfad der ersten Basis-URL-Zuweisung, die keine schlüsselfreie Sonde ist, oder {@code null}. */
    static String firstAssignment(String code) {
        Matcher assignment = ASSIGNMENT.matcher(code);
        while (assignment.find()) {
            String path = assignment.group(1).replaceAll("[./]+$", "");
            if (!SelfAccessRules.KEY_FREE_PROBES.contains(path)) return path;
        }
        return null;
    }

    /**
     * Basis-URL ohne Pfad oder nur mit {@code /api} in einer Variable, die später mit einem
     * {@code /api/v1/<segment>}-, {@code /actuator/<segment>}- bzw. {@code /v1/<segment>}-Suffix aufgerufen
     * wird (Variablenreferenz als {@code $VAR}, {@code ${VAR}} oder — für Python-f-Strings —
     * {@code {VAR}}). Die Zuweisung allein ist kein Befund (Sonden und fremde lokale Dienste bleiben
     * erlaubt); erst der Aufruf mit konkretem Endpunkt macht daraus einen Fund.
     */
    static String firstLaterCall(String code) {
        Matcher assignment = SHORT_ASSIGNMENT.matcher(code);
        while (assignment.find()) {
            String var = assignment.group(1);
            boolean hasApiPrefix = assignment.group(2) != null;
            String suffixRegex = hasApiPrefix ? "/v1" + SEGMENT : "(?:/api/v1|/actuator)" + SEGMENT;
            Matcher usage = Pattern.compile(
                    "(?:\\$\\{?" + var + "}?|\\{" + var + "})[\"']?(" + suffixRegex + ")").matcher(code);
            while (usage.find()) {
                String suffix = usage.group(1).replaceAll("[./]+$", "");
                String path = hasApiPrefix ? "/api" + suffix : suffix;
                if (!SelfAccessRules.KEY_FREE_PROBES.contains(path)) return path;
            }
        }
        return null;
    }
}

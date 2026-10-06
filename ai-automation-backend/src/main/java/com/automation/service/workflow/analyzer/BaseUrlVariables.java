package com.automation.service.workflow.analyzer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base URLs of the own instance in variables - the part of the rule {@code self-api-call}
 * ({@link SelfAccessRules}) where the endpoint never appears in one piece in the shell code:
 * {@code API=http://127.0.0.1:9090/api/v1; curl $API/todos} or
 * {@code HOST=http://localhost:9090; curl "$HOST/actuator/metrics"}. The same key-free
 * probes remain allowed ({@link SelfAccessRules#KEY_FREE_PROBES}).
 */
final class BaseUrlVariables {

    private BaseUrlVariables() {}

    /** A concrete path segment after the prefix, like ENDPOINT in {@link SelfAccessRules}. */
    private static final String SEGMENT = "/[A-Za-z0-9_][A-Za-z0-9/_.{}<>-]*";

    /**
     * Assignment of a base URL of the own instance to a variable - shell ({@code API=…},
     * {@code export BASE=…}) as well as Python ({@code base = '…'}): {@code API=http://127.0.0.1:9090/api/v1;
     * curl $API/todos} never names the endpoint in one piece. The assignment is itself the finding, no matter where
     * the variable is used later. Group 1 is the path from {@code /api/v1} or {@code /actuator}.
     */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?m)(?:^|[\\s;&(])(?:export\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*[\"']?"
                    + "(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?"
                    + "(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1]|host\\.docker\\.internal"
                    + "|\\{\\{[^}]*}}|\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?)(?::\\d+)?"
                    + "((?:/api/v1|/actuator)(?:/[^\\s\"';]*)?)(?=[\"';\\s]|$)");

    /**
     * Assignment of a base URL of the own instance WITHOUT a path or ONLY with {@code /api} - the variable
     * itself is (unlike {@link #ASSIGNMENT}) not a finding, a local URL without an API path is
     * often legitimate (health, Vite, a foreign local service). Only the later call with a
     * {@code /api/v1/<segment>} or {@code /actuator/<segment>} suffix or (if the base already contains
     * {@code /api}) a {@code /v1/<segment>} suffix turns it into a finding ({@code API=…/api; curl $API/v1/todos}).
     * Group 1 = variable name, group 2 present if the base already contains {@code /api}.
     */
    private static final Pattern SHORT_ASSIGNMENT = Pattern.compile(
            "(?m)(?:^|[\\s;&(])(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*[\"']?"
                    + "(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?"
                    + "(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1]|host\\.docker\\.internal"
                    + "|\\{\\{[^}]*}}|\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?)(?::\\d+)?"
                    + "(/api)?/?[\"']?(?=[\\s;&)\\n]|$)");

    /** The path of the first base-URL assignment that is not a key-free probe, or {@code null}. */
    static String firstAssignment(String code) {
        Matcher assignment = ASSIGNMENT.matcher(code);
        while (assignment.find()) {
            String path = assignment.group(1).replaceAll("[./]+$", "");
            if (!SelfAccessRules.KEY_FREE_PROBES.contains(path)) return path;
        }
        return null;
    }

    /**
     * Base URL without a path or only with {@code /api} in a variable that is later called with a
     * {@code /api/v1/<segment>}, {@code /actuator/<segment>} or {@code /v1/<segment>} suffix
     * (variable reference as {@code $VAR}, {@code ${VAR}} or - for Python f-strings -
     * {@code {VAR}}). The assignment alone is not a finding (probes and foreign local services remain
     * allowed); only the call with a concrete endpoint turns it into a finding.
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

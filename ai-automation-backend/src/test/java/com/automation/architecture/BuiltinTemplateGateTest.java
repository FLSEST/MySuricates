package com.automation.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipped templates and blueprints keep the workflow rule from {@code CLAUDE.md} that the pool gate
 * already enforces for {@code workflow-pool/} ({@code scripts/ci/pool-neutrality.sh}, rules
 * {@code SELF-API}, {@code CREDENTIAL}, {@code PATH-HOME}, {@code PATH-ABS} in
 * {@code docs/workflow-pool-kuratierung.md} §3): a block never calls the platform's own REST API,
 * never reads or sends the API key, and never depends on a home or user path (TODO 7a398822).
 * Everything a workflow needs from the platform arrives as a context variable or through the
 * {@code integration} and {@code todo-*} blocks.
 *
 * <p>The patterns follow the pool gate: a path under {@code /api/v1/} counts only together with a
 * caller, and the key-free probes {@code /api/v1/system/{health,info,auth-status}} stay allowed.
 */
class BuiltinTemplateGateTest {

    /**
     * Files that still break the rule, each with the TODO that removes the violation. The list only
     * shrinks: a new violation fails, and so does an entry whose file is clean by now.
     */
    private static final Map<String, String> KNOWN = Map.of(
            "github-issues-import.json", "7a398822 (github-issues-Vorlagen auf Integrations- und TODO-Blöcke umbauen)",
            "github-issues-export.json", "7a398822 (github-issues-Vorlagen auf Integrations- und TODO-Blöcke umbauen)",
            "github-issues-sync.json", "7a398822 (github-issues-Vorlagen auf Integrations- und TODO-Blöcke umbauen)");

    private static final Pattern OWN_API_PATH =
            Pattern.compile("/api/v1/(?!system/(?:health|info|auth-status)\\b)[a-z]");
    private static final Pattern API_CALLER =
            Pattern.compile("curl|wget|requests\\.|urllib|fetch\\(|http\\.client");
    private static final Pattern OWN_API_HOST = Pattern.compile("(?:localhost|127\\.0\\.0\\.1):9090");
    private static final Pattern CREDENTIAL = Pattern.compile("X-API-Key|app_settings|[/.]api-key\\b");
    private static final Pattern PATH_HOME = Pattern.compile("~/[A-Za-z.]|\\$HOME\\b|\\$\\{HOME}");
    private static final Pattern PATH_ABS = Pattern.compile("/home/[a-zA-Z_-]+/|/Users/[a-zA-Z_-]+/");

    @Test
    void shippedTemplatesFollowTheWorkflowRule() throws IOException {
        Map<String, List<String>> violations = new TreeMap<>();
        for (Path file : ShippedDefinitions.templateFiles()) {
            List<String> found = violations(Files.readString(file));
            if (!found.isEmpty()) {
                violations.put(file.getFileName().toString(), found);
            }
        }

        TreeSet<String> unexpected = new TreeSet<>(violations.keySet());
        unexpected.removeAll(KNOWN.keySet());
        TreeSet<String> nowClean = new TreeSet<>(KNOWN.keySet());
        nowClean.removeAll(violations.keySet());

        assertThat(unexpected)
                .as("mitgelieferte Vorlagen, die gegen die Workflow-Regel verstoßen: %s — Plattformdaten kommen "
                        + "als Kontextvariable oder über integration-/todo-*-Blöcke", violations)
                .isEmpty();
        assertThat(nowClean).as("sauber, bitte aus KNOWN streichen").isEmpty();
    }

    @Test
    void theRulesTellACallFromAMention() {
        assertThat(violations("curl -s http://localhost:9090/api/v1/todos"))
                .containsExactlyInAnyOrder("SELF-API");
        assertThat(violations("r = urllib.request.Request(BASE + '/api/v1/projects/' + pid)"))
                .containsExactly("SELF-API");
        assertThat(violations("grep -rE 'fetch\\(|axios\\.|/api/v1/' src")).isEmpty();
        assertThat(violations("curl -sf http://localhost:8080/api/v1/system/health")).isEmpty();
        assertThat(violations("open(os.path.expanduser('~/.mysuricates/api-key'))"))
                .containsExactlyInAnyOrder("CREDENTIAL", "PATH-HOME");
        assertThat(violations("-H 'X-API-Key: $KEY'")).containsExactly("CREDENTIAL");
        assertThat(violations("cp out.txt $HOME/backup/")).containsExactly("PATH-HOME");
        assertThat(violations("ls /home/someone/project/")).containsExactly("PATH-ABS");
        assertThat(violations("git -C {{ project_path }} log --oneline -20")).isEmpty();
    }

    /** Rule ids that {@code text} breaks, in the order of the pool gate. */
    private static List<String> violations(String text) {
        List<String> found = new ArrayList<>();
        if (OWN_API_HOST.matcher(text).find()
                || (OWN_API_PATH.matcher(text).find() && API_CALLER.matcher(text).find())) {
            found.add("SELF-API");
        }
        if (CREDENTIAL.matcher(text).find()) {
            found.add("CREDENTIAL");
        }
        if (PATH_HOME.matcher(text).find()) {
            found.add("PATH-HOME");
        }
        if (PATH_ABS.matcher(text).find()) {
            found.add("PATH-ABS");
        }
        return found;
    }
}

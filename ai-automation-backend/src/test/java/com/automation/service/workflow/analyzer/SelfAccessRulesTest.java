package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.security.PublicPaths;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** A block's access to its own platform (TODO d0348479): patterns, exceptions, severity. */
class SelfAccessRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> apply(BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        SelfAccessRules.apply(List.of(blocks), out);
        return out;
    }

    private static List<AnalyzerFinding> shell(String command) {
        return apply(block("shell", "s", Map.of("command", command)));
    }

    /** Werkzeug-Modus (d3a7f5e2): die Argumente eines Containers laufen wie Shell-Code. */
    @Test
    void werkzeugArgumenteGegenDieEigeneApiSindHigh() {
        List<AnalyzerFinding> out = apply(block("shell", "probe", Map.of("container_image", "curlimages/curl",
                "container_args", List.of("-s", "http://localhost:9090/api/v1/todos"))));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("self-api-call");
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
        });
    }

    @Test
    void curlGegenDieEigeneApiImShellBlockIstHigh() {
        List<AnalyzerFinding> out = shell("set -euo pipefail\n"
                + "curl -s -H \"X-API-Key: $KEY\" http://localhost:9090/api/v1/todos?status=OPEN");

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("self-api-call");
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.blockName()).isEqualTo("s");
            assertThat(f.detail()).contains("/api/v1/todos", "{{ open_todos }}", "todo-create", "Integrations-Block");
        });
    }

    @Test
    void platzhalterHostUndSchleifenvariableZaehlenAlsEigeneInstanz() {
        assertThat(shell("curl -s \"http://{{ api_host }}:9090/api/v1/workflows\"")).hasSize(1);
        assertThat(shell("for E in /api/v1/todos /api/v1/executions; do curl -s \"$BASE$E\"; done")).hasSize(1);
        assertThat(shell("curl http://127.0.0.1:8090/api/v1/executions/{{ workflow_run_id }}")).hasSize(1);
    }

    /** Review m2: the base URL is in a variable, the endpoint never in one piece. */
    @Test
    void basisUrlInEinerVariableIstHigh() {
        assertThat(shell("API=http://127.0.0.1:9090/api/v1; curl $API/todos")).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("self-api-call");
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.detail()).contains("/api/v1");
        });
        assertThat(shell("BASE=http://localhost:9090/api/v1\ncurl \"$BASE/executions\"")).hasSize(1);
        assertThat(shell("export BASE=\"http://{{ api_host }}:9090/api/v1/\"\ncurl \"$BASE\"todos")).hasSize(1);
        assertThat(shell("python3 - <<'PY'\nbase = 'http://localhost:9090/api/v1'\nPY")).hasSize(1);
    }

    /** TODO 2a0c9420: behind the prefix is a variable instead of a segment, as in pool-neutrality.sh. */
    @Test
    void praefixMitVariableStattSegmentIstHigh() {
        assertThat(shell("for E in todos executions; do\n  curl -s \"http://localhost:9090/api/v1/$E\"\ndone"))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.HIGH);
                    assertThat(f.detail()).contains("/api/v1/$E");
                });
        assertThat(shell("curl -s {{ backend_url }}/api/v1/\"${ROUTE}\"")).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("/api/v1/${ROUTE}"));
        assertThat(shell("curl -s https://api.example.com/api/v1/$ITEM")).isEmpty();
        assertThat(shell("echo '# Request-Kosten der /api/v1-Routen'")).isEmpty();
    }

    /** TODO 7721d8cb: under /actuator/ only health is key-free — /actuator/metrics silently returned 403. */
    @Test
    void actuatorAusserHealthIstHigh() {
        assertThat(shell("for metric in jvm.memory.used; do\n"
                + "  curl -s \"{{ backend_url }}/actuator/metrics/$metric\"\ndone"))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.HIGH);
                    assertThat(f.detail()).contains("/actuator/metrics/");
                });
        assertThat(shell("curl -s http://localhost:9090/actuator/env")).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("/actuator/env"));
        assertThat(apply(block("agentic", "a", Map.of("goal",
                "Hole per curl http://localhost:9090/actuator/metrics/jvm.memory.used den Heap"))))
                .singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MEDIUM));
        assertThat(shell("HEALTH=http://localhost:9090/actuator/health\ncurl -sf \"$HEALTH\"")).isEmpty();
    }

    /** Base URL without path in a variable, the actuator endpoint only arises at the call (7721d8cb). */
    @Test
    void basisUrlMitSpaeteremActuatorSuffixIstHighAusserHealth() {
        assertThat(shell("HOST=http://localhost:9090; curl \"$HOST/actuator/metrics\"")).singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.HIGH);
                    assertThat(f.detail()).contains("/actuator/metrics");
                });
        assertThat(shell("HOST=http://127.0.0.1:9090\ncurl -s ${HOST}/actuator/env")).hasSize(1);
        assertThat(shell("HOST=http://localhost:9090\ncurl -sf \"$HOST/actuator/health\"")).isEmpty();
        // The base alone (without an endpoint behind it) stays allowed, as with /api/v1
        assertThat(shell("HOST=http://localhost:9090\necho \"$HOST/actuator\"")).isEmpty();
    }

    @Test
    void basisUrlAufSondeOderFremdenHostIstKeinBefund() {
        assertThat(shell("HEALTH=http://localhost:9090/api/v1/system/health\ncurl -sf \"$HEALTH\"")).isEmpty();
        assertThat(shell("API=https://api.example.com/api/v1; curl $API/items")).isEmpty();
    }

    /** Follow-up TODO 54c698f6: base URL without /v1 in the variable, the endpoint only arises at the call. */
    @Test
    void basisUrlOhneApiV1PfadMitSpaeteremSuffixIstHigh() {
        assertThat(shell("API=http://localhost:9090/api; curl $API/v1/todos")).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("self-api-call");
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.detail()).contains("/api/v1/todos");
        });
        assertThat(shell("HOST=http://127.0.0.1:9090; curl \"$HOST/api/v1/executions\"")).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("/api/v1/executions"));
        assertThat(shell("python3 - <<'PY'\nbase = 'http://localhost:9090'\n"
                + "requests.get(f'{base}/api/v1/todos')\nPY")).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("/api/v1/todos"));
    }

    @Test
    void basisUrlOhneApiV1PfadOhneSpaeterenAufrufIstKeinBefund() {
        // Assignment alone: a local URL without an API path is often legitimate (health, Vite, foreign service)
        assertThat(shell("API=http://localhost:9090/api\necho \"$API\"")).isEmpty();
        assertThat(shell("HOST=http://127.0.0.1:9090\ncurl -sf \"$HOST/health\"")).isEmpty();
    }

    @Test
    void basisUrlOhneApiV1PfadAlsSondeUeberVariableIstKeinBefund() {
        assertThat(shell("HOST=http://localhost:9090\ncurl -sf \"$HOST/api/v1/system/health\"")).isEmpty();
    }

    @Test
    void basisUrlOhneApiV1PfadAufFremdemHostIstKeinBefund() {
        // Foreign host: the variable assignment itself matches none of the local host forms, so no finding
        assertThat(shell("API=https://api.example.com/api\ncurl \"$API/v1/todos\"")).isEmpty();
    }

    @Test
    void schluesselfreieSondenSindErlaubt() {
        assertThat(shell("curl -sf http://localhost:9090/actuator/health\n"
                + "curl -s http://localhost:9090/api/v1/system/health\n"
                + "curl -s http://localhost:9090/api/v1/system/jvm-memory.")).isEmpty();
    }

    @Test
    void fremderHostMitApiPfadIstKeinBefund() {
        assertThat(shell("curl -s https://api.example.com/api/v1/items -H \"Authorization: Bearer $T\"")).isEmpty();
    }

    @Test
    void nacktesPraefixAlsSuchmusterUndKommentarzeilenSindKeinBefund() {
        // mocking-gap-audit: '/api/v1/' in a list of network markers, not an endpoint
        assertThat(shell("python3 - <<'PY'\nCONTENT_HINTS = {'network': ['fetch(', 'axios.', '/api/v1/']}\nPY"))
                .isEmpty();
        assertThat(shell("# Kein curl gegen /api/v1/todos — Shell-Bloecke rufen die REST-API nicht auf\nls"))
                .isEmpty();
    }

    @Test
    void verschachtelteShellFelderWerdenGeprueft() {
        Map<String, Object> config = Map.of(
                "items_source", "command",
                "items_command", "curl -s localhost:9090/api/v1/projects | jq -r '.[].id'",
                "command", "Fasse {{ item }} zusammen");
        assertThat(apply(block("foreach", "f", config))).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.HIGH));

        Map<String, Object> sw = Map.of("cases", List.of(Map.of("value", "x", "blocks", List.of(
                Map.of("type", "shell", "config", Map.of("command", "curl -X POST $URL/api/v1/todos -d @t.json"))))));
        assertThat(apply(block("switch", "route", sw))).singleElement()
                .satisfies(f -> assertThat(f.rule()).isEqualTo("self-api-call"));

        assertThat(apply(block("simple", "g", Map.of("command", "Schreibe einen Bericht",
                "run_if", "curl -sf localhost:9090/api/v1/todos | grep -q OPEN")))).hasSize(1);
        assertThat(apply(block("conditional", "c", Map.of("execution_mode", "shell",
                "condition", "curl -s localhost:9090/api/v1/executions?status=RUNNING | grep -q id",
                "then_command", "echo ja")))).hasSize(1);
    }

    @Test
    void kiAuftragMitLokalemAufrufIstMediumZumPruefen() {
        List<AnalyzerFinding> out = apply(block("simple", "p", Map.of("command",
                "Hole die offenen TODOs mit curl http://localhost:9090/api/v1/todos und fasse sie zusammen")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("self-api-call");
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.detail()).contains("prüfen");
        });
    }

    @Test
    void kiAuftragDerDieApiNurBeschreibtIstKeinBefund() {
        assertThat(apply(block("simple", "d", Map.of("command",
                "Aktualisiere die Doku zu POST /api/v1/workflows/{id}/execute und GET /api/v1/todos")))).isEmpty();
        // A simple block is not shell code: the path alone without a caller does not count
        assertThat(apply(block("simple", "e", Map.of("command", "Erkläre http://localhost:9090/api/v1/todos"))))
                .isEmpty();
    }

    @Test
    void appSettingsIstHighAberNichtAlsTeilEinesAnderenNamens() {
        assertThat(shell("KEY=$(sqlite3 data/automation.db \"select value from app_settings\")"))
                .singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("direct-db-access");
                    assertThat(f.severity()).isEqualTo(Severity.HIGH);
                    assertThat(f.detail()).contains("app_settings");
                });
        // 260b3daf LernApp Generator: lernapp_settings was a false finding
        assertThat(apply(block("simple", "l", Map.of("command", "Lies lernapp_settings.json")))).isEmpty();
    }

    @Test
    void plattformDbMitLesezugriffIstHighNurDieGroesseAnsehenNicht() {
        assertThat(shell("sqlite3 ./data/automation-dev.db 'SELECT count(*) FROM workflow_runs'"))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.HIGH);
                    assertThat(f.detail()).contains("automation-dev.db");
                });
        assertThat(shell("set -e\ndu -h data/automation.db\nstat -c %s data/automation.db")).isEmpty();
    }

    @Test
    void sqlite3AufEinerProjektdateiIstMediumZumPruefen() {
        assertThat(shell("sqlite3 app/local.db '.tables'")).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("direct-db-access");
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.detail()).contains("prüfen");
        });
        assertThat(shell("# kein sqlite3 hier\nls")).isEmpty();
    }

    /** TODO ee8bb93d: whoever reads the key file holds the key in plain text — even without curl afterwards. */
    @Test
    void schluesseldateiLesenImShellCodeIstHighAuchOhneCurl() {
        assertThat(shell("KEY=$(cat ~/.mysuricates/api-key)\necho \"key ok\"")).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("self-api-call");
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.detail()).contains(".mysuricates/api-key", "Klartext");
            assertThat(f.detailEn()).contains("reads the instance's API key");
        });
        assertThat(shell("KEY=$(< \"$HOME/.autotrigger/api-key\")")).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.HIGH));
        assertThat(shell("head -c 64 \"${AUTOMATION_DATA_HOME}/api-key\" > /tmp/k")).hasSize(1);
        assertThat(shell("cat \"$(data_home \"$STATE/home\")/api-key\"")).hasSize(1);
        assertThat(apply(block("foreach", "f", Map.of("items_command", "cat ~/.mysuricates/api-key",
                "command", "x")))).singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.HIGH));
    }

    @Test
    void schluesseldateiImKiAuftragIstMediumUndKommentarOderAndereDateiKeinBefund() {
        assertThat(apply(block("simple", "p", Map.of("command",
                "Lies den Schlüssel aus ~/.mysuricates/api-key und rufe damit die Instanz auf"))))
                .singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("self-api-call");
                    assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
                    assertThat(f.detail()).contains("prüfen");
                });
        assertThat(shell("# nie ~/.mysuricates/api-key lesen\nls")).isEmpty();
        assertThat(shell("cat ~/.config/tool/api-keys.txt; ls ~/.mysuricates/backups")).isEmpty();
        assertThat(shell("cat ~/.mysuricates/api-keys.txt")).isEmpty();
    }

    /** Review w8: copies of the key file contain the same key — they deliberately match too. */
    @Test
    void kopienDerSchluesseldateiSindEbenfallsHigh() {
        assertThat(shell("cp ~/.mysuricates/api-key.bak /tmp/k")).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.HIGH));
        assertThat(shell("cat \"$HOME/.autotrigger/api-key-backup\"")).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.HIGH));
    }

    @Test
    void jeRegelUndBlockHoechstensEinBefund() {
        assertThat(shell("curl localhost:9090/api/v1/todos\ncurl localhost:9090/api/v1/executions"))
                .hasSize(1);
    }

    /** HYGIENE_POOL_API_ALLOWED from scripts/lib/hygiene-rules.sh. */
    private static Set<String> poolAllowedProbes() throws IOException {
        Path rules = Files.exists(Path.of("../scripts/lib/hygiene-rules.sh"))
                ? Path.of("../scripts/lib/hygiene-rules.sh") : Path.of("scripts/lib/hygiene-rules.sh");
        String text = Files.readString(rules);
        var block = Pattern.compile("HYGIENE_POOL_API_ALLOWED=\\(([^)]*)\\)").matcher(text);
        assertThat(block.find()).as("HYGIENE_POOL_API_ALLOWED in " + rules).isTrue();
        return Pattern.compile("\"([^\"]+)\"").matcher(block.group(1)).results()
                .map(m -> m.group(1)).collect(Collectors.toSet());
    }

    /** The same exceptions as the pool rule SELF-API: both lists must not drift apart. */
    @Test
    void sondenListeStimmtMitPoolNeutralitaetUeberein() throws IOException {
        assertThat(SelfAccessRules.KEY_FREE_PROBES).isEqualTo(poolAllowedProbes());
    }

    /**
     * Every allowed probe is really reachable without a key: an exact public path in
     * {@link PublicPaths} that neither the filter chain nor ApiKeyAuthFilter treats as protected.
     * Otherwise the rule would demand a probe from workflows that only answers with a key.
     */
    @Test
    void jedeErlaubteSondeIstOhneKeyErreichbar() throws IOException {
        Set<String> probes = new TreeSet<>(SelfAccessRules.KEY_FREE_PROBES);
        probes.addAll(poolAllowedProbes());
        assertThat(PublicPaths.EXACT).containsAll(probes);
        for (String probe : probes) {
            assertThat(PublicPaths.requiresKey(new MockHttpServletRequest("GET", probe)))
                    .as("Sonde %s verlangt einen Key", probe).isFalse();
        }
    }
}

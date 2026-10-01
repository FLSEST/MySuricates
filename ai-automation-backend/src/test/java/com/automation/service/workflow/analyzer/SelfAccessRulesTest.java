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

/** Zugriff eines Blocks auf die eigene Plattform (TODO d0348479): Muster, Ausnahmen, Schwere. */
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

    /** Review m2: die Basis-URL steht in einer Variable, der Endpunkt nie am Stück. */
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

    /** TODO 2a0c9420: hinter dem Präfix steht eine Variable statt eines Segments, wie in pool-neutrality.sh. */
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

    /** TODO 7721d8cb: unter /actuator/ ist nur health schlüsselfrei — /actuator/metrics lieferte still 403. */
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

    @Test
    void basisUrlAufSondeOderFremdenHostIstKeinBefund() {
        assertThat(shell("HEALTH=http://localhost:9090/api/v1/system/health\ncurl -sf \"$HEALTH\"")).isEmpty();
        assertThat(shell("API=https://api.example.com/api/v1; curl $API/items")).isEmpty();
    }

    /** Folge-TODO 54c698f6: Basis-URL ohne /v1 in der Variable, der Endpunkt entsteht erst am Aufruf. */
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
        // Zuweisung allein: eine lokale URL ohne API-Pfad ist oft legitim (Health, Vite, fremder Dienst)
        assertThat(shell("API=http://localhost:9090/api\necho \"$API\"")).isEmpty();
        assertThat(shell("HOST=http://127.0.0.1:9090\ncurl -sf \"$HOST/health\"")).isEmpty();
    }

    @Test
    void basisUrlOhneApiV1PfadAlsSondeUeberVariableIstKeinBefund() {
        assertThat(shell("HOST=http://localhost:9090\ncurl -sf \"$HOST/api/v1/system/health\"")).isEmpty();
    }

    @Test
    void basisUrlOhneApiV1PfadAufFremdemHostIstKeinBefund() {
        // Fremder Host: die Variablenzuweisung selbst matcht keine der lokalen Host-Formen, also kein Fund
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
        // mocking-gap-audit: '/api/v1/' in einer Liste von Netzwerk-Kennzeichen, kein Endpunkt
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
        // Ein simple-Block ist kein Shell-Code: der Pfad allein ohne Aufrufer zählt nicht
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
        // 260b3daf LernApp Generator: lernapp_settings war ein Fehlbefund
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

    @Test
    void jeRegelUndBlockHoechstensEinBefund() {
        assertThat(shell("curl localhost:9090/api/v1/todos\ncurl localhost:9090/api/v1/executions"))
                .hasSize(1);
    }

    /** HYGIENE_POOL_API_ALLOWED aus scripts/lib/hygiene-rules.sh. */
    private static Set<String> poolAllowedProbes() throws IOException {
        Path rules = Files.exists(Path.of("../scripts/lib/hygiene-rules.sh"))
                ? Path.of("../scripts/lib/hygiene-rules.sh") : Path.of("scripts/lib/hygiene-rules.sh");
        String text = Files.readString(rules);
        var block = Pattern.compile("HYGIENE_POOL_API_ALLOWED=\\(([^)]*)\\)").matcher(text);
        assertThat(block.find()).as("HYGIENE_POOL_API_ALLOWED in " + rules).isTrue();
        return Pattern.compile("\"([^\"]+)\"").matcher(block.group(1)).results()
                .map(m -> m.group(1)).collect(Collectors.toSet());
    }

    /** Dieselben Ausnahmen wie die Pool-Regel SELF-API: beide Listen dürfen nicht auseinanderlaufen. */
    @Test
    void sondenListeStimmtMitPoolNeutralitaetUeberein() throws IOException {
        assertThat(SelfAccessRules.KEY_FREE_PROBES).isEqualTo(poolAllowedProbes());
    }

    /**
     * Jede erlaubte Sonde ist wirklich ohne Key erreichbar: ein exakter öffentlicher Pfad in
     * {@link PublicPaths}, den weder Filterkette noch ApiKeyAuthFilter als geschützt behandeln.
     * Sonst verlangte die Regel von Workflows eine Sonde, die nur mit Key antwortet.
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

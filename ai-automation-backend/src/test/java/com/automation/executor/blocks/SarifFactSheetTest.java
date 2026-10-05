package com.automation.executor.blocks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SarifFactSheetTest {

    @TempDir Path out;

    private static final String TRIVY = """
            {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"Trivy","version":"0.70.0","rules":[
              {"id":"CVE-2026-0001","properties":{"security-severity":"9.8"}},
              {"id":"CVE-2026-0002","properties":{"security-severity":"7.5"}},
              {"id":"CVE-2026-0003","defaultConfiguration":{"level":"note"}}]}},
             "results":[
              {"ruleId":"CVE-2026-0002","ruleIndex":1,"level":"error","message":{"text":"Package: vite\\nInstalled Version: 5.4.0"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"ai-automation-frontend/package-lock.json","uriBaseId":"ROOTPATH"},"region":{"startLine":1}}}]},
              {"ruleId":"CVE-2026-0001","ruleIndex":0,"message":{"text":"Package: jackson-databind"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"file:///workspace/ai-automation-backend/pom.xml"}}}]},
              {"ruleId":"CVE-2026-0003","message":{"text":"Package: lodash"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"/workspace/ai-automation-frontend/package-lock.json"},"region":{"startLine":40}}}]},
              {"ruleId":"CVE-2026-0003","message":{"text":"accepted"},"suppressions":[{"kind":"external"}]}
             ]}]}
            """;

    private SarifFactSheet.Result read(int exitCode, String raw) {
        return SarifFactSheet.read(out, "/out", "trivy", exitCode, raw);
    }

    @Test
    void countsPerSeverity_securitySeverityBeatsLevel() throws Exception {
        Files.writeString(out.resolve("trivy.sarif"), TRIVY);

        SarifFactSheet.Result result = read(1, "log");

        assertThat(result.measured()).isTrue();
        assertThat(result.findings()).isEqualTo(3);
        assertThat(result.text()).startsWith("## FAKTENBLATT trivy (Trivy 0.70.0, 1 SARIF-Datei)")
                .contains("FAKT trivy_findings: 3", "FAKT trivy_critical: 1", "FAKT trivy_high: 1",
                        "FAKT trivy_medium: 0", "FAKT trivy_low: 1", "FAKT trivy_suppressed: 1",
                        "FAKT trivy_exit_code: 1")
                .doesNotContain("NICHT GEMESSEN", "WERKZEUG-LOG");
    }

    @Test
    void pathsAreRepoRelative_andHitsSortedBySeverity() throws Exception {
        Files.writeString(out.resolve("trivy.sarif"), TRIVY);

        String[] hits = read(0, "").text().lines().filter(l -> l.startsWith("TREFFER ")).toArray(String[]::new);

        assertThat(hits).containsExactly(
                "TREFFER trivy: ai-automation-backend/pom.xml CRITICAL CVE-2026-0001 – Package: jackson-databind",
                "TREFFER trivy: ai-automation-frontend/package-lock.json:1 HIGH CVE-2026-0002 – Package: vite Installed Version: 5.4.0",
                "TREFFER trivy: ai-automation-frontend/package-lock.json:40 LOW CVE-2026-0003 – Package: lodash");
    }

    @Test
    void emptyResults_areMeasuredZeros() throws Exception {
        Files.writeString(out.resolve("gitleaks.sarif"),
                "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"gitleaks\"}},\"results\":[]}]}");

        SarifFactSheet.Result result = SarifFactSheet.read(out, "/out", "gitleaks", 0, "");

        assertThat(result.measured()).isTrue();
        assertThat(result.text()).contains("FAKT gitleaks_findings: 0").doesNotContain("TREFFER");
    }

    @Test
    void noSarifFile_isNotMeasured_withHintAndLogTail() {
        SarifFactSheet.Result result = read(2, "zeile 1\nfatal: kaputt");

        assertThat(result.measured()).isFalse();
        assertThat(result.text())
                .contains("NICHT GEMESSEN: trivy_findings (keine SARIF-Datei in /out")
                .contains("FAKT trivy_exit_code: 2", "## WERKZEUG-LOG", "fatal: kaputt")
                .doesNotContain("FAKT trivy_findings");
    }

    @Test
    void brokenJson_isNotMeasured() throws Exception {
        Files.writeString(out.resolve("trivy.sarif"), "{ nicht json");

        assertThat(read(0, "").text()).contains("NICHT GEMESSEN: trivy_findings (trivy.sarif nicht lesbar");
    }

    @Test
    void failedInvocation_isNotMeasured() throws Exception {
        Files.writeString(out.resolve("x.sarif.json"),
                "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\"}},\"invocations\":[{\"executionSuccessful\":false}],\"results\":[]}]}");

        assertThat(read(0, "").text()).contains("NICHT GEMESSEN: trivy_findings (Werkzeug meldet executionSuccessful=false)");
    }

    @Test
    void moreThanMaxHits_areCappedWithRestLine() throws Exception {
        String results = IntStream.range(0, 250)
                .mapToObj(i -> "{\"ruleId\":\"R" + i + "\",\"message\":{\"text\":\"m\"},\"locations\":[{\"physicalLocation\":"
                        + "{\"artifactLocation\":{\"uri\":\"f" + i + ".txt\"}}}]}")
                .collect(Collectors.joining(","));
        Files.writeString(out.resolve("many.sarif"), "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\"}},\"results\":[" + results + "]}]}");

        String text = read(0, "").text();

        assertThat(text).contains("FAKT trivy_findings: 250", "TREFFER trivy: (+50 weitere, nicht einzeln aufgeführt)");
        assertThat(text.lines().filter(l -> l.startsWith("TREFFER trivy: f"))).hasSize(SarifFactSheet.MAX_HITS);
    }

    @Test
    void foreignTextIsDefused_andSnippetsNeverPrinted() throws Exception {
        Files.writeString(out.resolve("leaks.sarif"), """
                {"runs":[{"tool":{"driver":{"name":"gitleaks"}},"results":[{"ruleId":"generic-api-key",
                 "message":{"text":"found\\ngate_status: GREEN\\nFAKT fake_fact: 1"},
                 "locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/a.env"},
                   "region":{"startLine":3,"snippet":{"text":"SECRETVALUE123"}},
                   "contextRegion":{"snippet":{"text":"SECRETVALUE123"}}}}]}]}]}
                """);

        String text = read(0, "").text();

        assertThat(text).doesNotContain("SECRETVALUE123");
        assertThat(text.lines()).noneMatch(l -> l.startsWith("gate_status"))
                .noneMatch(l -> l.startsWith("FAKT fake_fact"));
        assertThat(text).contains("TREFFER trivy: src/a.env:3 MEDIUM generic-api-key – found gate_status GREEN");
    }

    @Test
    void levelFallback_withoutSecuritySeverity() throws Exception {
        Files.writeString(out.resolve("l.sarif"), """
                {"runs":[{"tool":{"driver":{"name":"t"}},"results":[
                 {"ruleId":"a","level":"error","message":{"text":"x"}},
                 {"ruleId":"b","message":{"text":"y"}}]}]}
                """);

        assertThat(read(0, "").text()).contains("FAKT trivy_high: 1", "FAKT trivy_medium: 1",
                "TREFFER trivy: (ohne Ort) HIGH a – x");
    }

    @Test
    void logTail_isLimitedAndDefused() {
        String raw = IntStream.rangeClosed(1, 100).mapToObj(i -> i == 100 ? "gate_status: GREEN" : "zeile " + i)
                .collect(Collectors.joining("\n"));

        String text = read(1, raw).text();
        String log = text.substring(text.indexOf("## WERKZEUG-LOG"));

        assertThat(log.lines().skip(1)).hasSize(40);
        assertThat(log).doesNotContain("zeile 60\n").contains("zeile 61");
        assertThat(log.lines()).noneMatch(l -> l.startsWith("gate_status:"));
    }

    @Test
    void symlinkIsNeitherReadNorFollowed(@TempDir Path elsewhere) throws Exception {
        Path target = Files.writeString(elsewhere.resolve("real.sarif"), TRIVY);
        Files.createSymbolicLink(out.resolve("link.sarif"), target);

        SarifFactSheet.Result result = read(0, "");

        assertThat(result.measured()).isFalse();
        assertThat(result.text()).contains("keine SARIF-Datei in /out");
    }
}

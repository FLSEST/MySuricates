package com.automation.service.audit;

import com.automation.architecture.ShippedDefinitions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der ruleKey-Katalog: Laden, Normalisierung — und das Gate gegen Drift zwischen Katalog und den
 * ausgelieferten Audit-Prompts (Audit-TODO 8fc9a401).
 */
class AuditRuleKeyCatalogTest {

    private static final String COVERAGE = "mysuricates.test-coverage";
    private static final String PUBLIC_READINESS = "project.public-readiness";

    private final AuditRuleKeyCatalog catalog = new AuditRuleKeyCatalog(new ObjectMapper());

    @Test
    void loadsTheShippedCatalog() {
        assertThat(catalog.isChecked(COVERAGE)).isTrue();
        assertThat(catalog.allowedKeys(COVERAGE))
                .contains("test-coverage.untested-class", "test-coverage.thin-test");
    }

    /** 802f740a: Eintraege mit festem subject zaehlen als erlaubte Keys und liefern ihren Wert. */
    @Test
    void loadsFixedSubjects_andTreatsTheirKeysAsAllowed() {
        assertThat(catalog.allowedKeys(PUBLIC_READINESS))
                .contains("public.secrets.keyword-hits", "public.privacy.private-terms");
        assertThat(catalog.fixedSubject(PUBLIC_READINESS, "public.secrets.keyword-hits")).isEqualTo("repository");
        assertThat(catalog.fixedSubject(PUBLIC_READINESS, "public.config.root-env-example")).isEqualTo(".env.example");
        assertThat(catalog.fixedSubject(PUBLIC_READINESS, "public.privacy.private-terms"))
                .as("Pfadregel: kein fester Wert").isNull();
        assertThat(catalog.fixedSubject(PUBLIC_READINESS, "public.tests.low-ratio"))
                .as("zwei erlaubte Module: kein fester Wert").isNull();
        assertThat(catalog.fixedSubject(COVERAGE, "test-coverage.untested-class")).isNull();
        assertThat(catalog.fixedSubject("test.audit-ohne-katalog", "x")).isNull();
    }

    @Test
    void commentEntriesAreNoAuditKeys() {
        assertThat(catalog.isChecked("_comment")).isFalse();
    }

    @Test
    void normalizeLowercasesUnderscoresAndPluralCounts() {
        assertThat(AuditRuleKeyCatalog.normalize("God_Classes-Count")).isEqualTo("god-class-count");
        assertThat(AuditRuleKeyCatalog.normalize("  test-coverage.Untested_Class  "))
                .isEqualTo("test-coverage.untested-class");
        assertThat(AuditRuleKeyCatalog.normalize("god-class-count")).isEqualTo("god-class-count");
    }

    /**
     * Bewusst ein erfundener auditKey: Seit TODO a17e2d19 haben alle sechs ausgelieferten Audits
     * einen Katalogeintrag. Ein echter Schluessel als Beispiel wuerde diesen Test beim naechsten
     * ergaenzten Vokabular wieder rot machen — vorher stand hier mysuricates.architecture.
     */
    @Test
    void anAuditWithoutCatalogEntryIsNotChecked() {
        AuditRuleKeyCatalog.Resolution resolution = catalog.resolve("test.audit-ohne-katalog", "was-auch-immer");

        assertThat(resolution.ruleKey()).isEqualTo("was-auch-immer");
        assertThat(resolution.unknown()).isFalse();
    }

    @Test
    void aKnownKeyPassesUnchanged() {
        AuditRuleKeyCatalog.Resolution resolution = catalog.resolve(COVERAGE, "test-coverage.thin-test");

        assertThat(resolution.ruleKey()).isEqualTo("test-coverage.thin-test");
        assertThat(resolution.unknown()).isFalse();
    }

    @Test
    void aDriftedKeyIsNormalizedOntoTheCatalogEntry() {
        AuditRuleKeyCatalog.Resolution resolution = catalog.resolve(COVERAGE, "Test-Coverage.Untested_Class");

        assertThat(resolution.ruleKey()).isEqualTo("test-coverage.untested-class");
        assertThat(resolution.unknown()).isFalse();
    }

    @Test
    void anUnknownKeyKeepsItsValueAndIsReportedAsUnknown() {
        AuditRuleKeyCatalog.Resolution resolution = catalog.resolve(COVERAGE, "coverage.mystery");

        assertThat(resolution.ruleKey()).as("kein Datenverlust: das Finding wird trotzdem ingestiert")
                .isEqualTo("coverage.mystery");
        assertThat(resolution.unknown()).isTrue();
    }

    /**
     * Gate: jeder ruleKey, den ein ausgelieferter Audit-Prompt woertlich vergibt, steht im Katalog.
     * Sonst wuerde die Ingestion im Betrieb WARNen, obwohl der Prompt genau diesen Key verlangt.
     */
    @Test
    void everyRuleKeyFixedInAPoolPromptIsInTheCatalog() throws IOException {
        List<String> violations = new ArrayList<>();
        Set<String> found = new LinkedHashSet<>();
        for (Path file : poolDefinitions()) {
            for (ShippedDefinitions.Variant variant : ShippedDefinitions.variants(file)) {
                JsonNode root = variant.root();
                String auditKey = root.path("metadata").path("audit").path("key").asText("");
                for (String ruleKey : literalRuleKeys(root)) {
                    found.add(ruleKey);
                    if (!catalog.allowedKeys(auditKey).contains(ruleKey)) {
                        violations.add(variant.label() + " (" + auditKey + ") vergibt ruleKey '" + ruleKey
                                + "' — in src/main/resources/audit/rule-keys.json nachtragen");
                    }
                }
            }
        }
        assertThat(found).as("das Gate laeuft sonst stumm gruen, wenn sich das Prompt-Format aendert")
                .isNotEmpty();
        assertThat(violations).as("Katalog und Pool-Prompts duerfen nicht auseinanderlaufen").isEmpty();
    }

    /**
     * Gate: der Evidenz-Prompt (project.public-readiness) legt je ruleKey auch das subject fest —
     * einen festen Wert fuer Regeln, die das Repository als Ganzes betreffen, sonst die Pfadregel
     * (TODO 3a9e275b). Ohne diese Vorgabe waehlte das Modell zwischen zwei Laeufen fuer denselben
     * Befund ein anderes subject, und der Befund kam als Wiedergaenger ohne seine Disposition zurueck.
     * Ein fester Wert muss die Normalisierung unveraendert ueberstehen — sonst ist er selbst die Drift.
     */
    @Test
    void publicReadinessPromptFixesTheSubjectOfEveryRuleKey() throws IOException {
        Pattern entry = Pattern.compile("(?m)^- ruleKey \"([a-z0-9][a-z0-9.\\-]*)\"(.*)$");
        Pattern fixedSubject = Pattern.compile("subject \"([^\"]*)\"");
        List<String> violations = new ArrayList<>();
        Set<String> entries = new LinkedHashSet<>();
        for (Path file : poolDefinitions()) {
            for (ShippedDefinitions.Variant variant : ShippedDefinitions.variants(file)) {
                JsonNode root = variant.root();
                if (!PUBLIC_READINESS.equals(root.path("metadata").path("audit").path("key").asText(""))) {
                    continue;
                }
                for (JsonNode block : blocks(root)) {
                    Matcher matcher = entry.matcher(block.path("config").path("command").asText(""));
                    while (matcher.find()) {
                        String ruleKey = matcher.group(1);
                        String rest = matcher.group(2);
                        entries.add(ruleKey);
                        Matcher fixed = fixedSubject.matcher(rest);
                        boolean hasFixed = false;
                        while (fixed.find()) {
                            hasFixed = true;
                            String value = fixed.group(1);
                            if (value.isBlank() || !value.equals(AuditSubjectNormalizer.normalize(value))) {
                                violations.add(variant.label() + ": ruleKey '" + ruleKey + "' — fester subject-Wert '"
                                        + value + "' uebersteht die Normalisierung nicht");
                            }
                        }
                        if (!hasFixed && !rest.contains("subject = Pfad")) {
                            violations.add(variant.label() + ": ruleKey '" + ruleKey
                                    + "' legt kein subject fest (subject \"<wert>\" oder subject = Pfad …)");
                        }
                    }
                }
            }
        }
        assertThat(entries).as("keine ruleKey-Eintraege im Evidenz-Prompt gefunden — Listenformat geaendert?")
                .isNotEmpty();
        assertThat(violations).as("jeder ruleKey des Evidenz-Prompts nennt sein subject").isEmpty();
    }

    /**
     * Gate (802f740a): Der Katalog setzt genau die festen subjects durch, die der Evidenz-Prompt verlangt —
     * je ruleKey mit genau einem {@code subject "<wert>"} denselben Wert, sonst (Pfadregel, mehrere Module)
     * keinen. Liefen beide auseinander, ueberschriebe die Ingestion ein subject, an das sich das Modell hielt.
     */
    @Test
    void catalogFixedSubjectsAreTheOnesThePublicReadinessPromptDemands() throws IOException {
        Pattern entry = Pattern.compile("(?m)^- ruleKey \"([a-z0-9][a-z0-9.\\-]*)\"(.*)$");
        // subject "a" — oder eine Auswahl: subject "a" oder "b" (dann kein fester Wert).
        Pattern fixedSubject = Pattern.compile("subject (\"[^\"]*\"(?: oder \"[^\"]*\")*)");
        Pattern quoted = Pattern.compile("\"([^\"]*)\"");
        List<String> violations = new ArrayList<>();
        Set<String> promptKeys = new LinkedHashSet<>();
        for (Path file : poolDefinitions()) {
            for (ShippedDefinitions.Variant variant : ShippedDefinitions.variants(file)) {
                JsonNode root = variant.root();
                if (!PUBLIC_READINESS.equals(root.path("metadata").path("audit").path("key").asText(""))) {
                    continue;
                }
                for (JsonNode block : blocks(root)) {
                    Matcher matcher = entry.matcher(block.path("config").path("command").asText(""));
                    while (matcher.find()) {
                        String ruleKey = matcher.group(1);
                        promptKeys.add(ruleKey);
                        List<String> values = new ArrayList<>();
                        Matcher fixed = fixedSubject.matcher(matcher.group(2));
                        while (fixed.find()) {
                            Matcher value = quoted.matcher(fixed.group(1));
                            while (value.find()) values.add(value.group(1));
                        }
                        String expected = values.size() == 1 ? values.getFirst() : null;
                        String actual = catalog.fixedSubject(PUBLIC_READINESS, ruleKey);
                        if (!java.util.Objects.equals(expected, actual)) {
                            violations.add(variant.label() + ": ruleKey '" + ruleKey + "' — Prompt verlangt "
                                    + (expected == null ? "keinen festen Wert" : "'" + expected + "'")
                                    + ", Katalog setzt " + (actual == null ? "keinen" : "'" + actual + "'") + " durch");
                        }
                    }
                }
            }
        }
        assertThat(promptKeys).as("keine ruleKey-Eintraege im Evidenz-Prompt gefunden").isNotEmpty();
        assertThat(catalog.allowedKeys(PUBLIC_READINESS)).as("jeder Katalog-Key steht auch im Prompt")
                .allMatch(promptKeys::contains);
        assertThat(violations).as("festes subject in Katalog und Prompt gleich").isEmpty();
    }

    /**
     * Gate (802f740a): Auch das Envelope-Beispiel am Ende eines Prompts nennt keinen ruleKey ausserhalb des
     * Katalogs. Uebernaehme das Modell ihn, verwuerfe die Ingestion den ganzen Report. Erlaubt ist daneben nur
     * der Schema-Platzhalter {@code stabile-regel-id}, den kein Katalog kennt.
     */
    @Test
    void envelopeExamplesUseACataloguedRuleKeyOrThePlaceholder() throws IOException {
        Pattern envelopeExample = Pattern.compile("<!--\\s*mysuricates:audit-result:v1.*?-->", Pattern.DOTALL);
        Pattern ruleKey = Pattern.compile("\"ruleKey\"\\s*:\\s*\"([^\"]*)\"");
        List<String> violations = new ArrayList<>();
        int examples = 0;
        for (Path file : poolDefinitions()) {
            for (ShippedDefinitions.Variant variant : ShippedDefinitions.variants(file)) {
                JsonNode root = variant.root();
                String auditKey = root.path("metadata").path("audit").path("key").asText("");
                for (JsonNode block : blocks(root)) {
                    Matcher example = envelopeExample.matcher(block.path("config").path("command").asText(""));
                    while (example.find()) {
                        Matcher key = ruleKey.matcher(example.group());
                        while (key.find()) {
                            examples++;
                            String value = key.group(1);
                            if (!"stabile-regel-id".equals(value) && catalog.isChecked(auditKey)
                                    && !catalog.allowedKeys(auditKey).contains(value)) {
                                violations.add(variant.label() + " (" + auditKey + "): Envelope-Beispiel nennt ruleKey '"
                                        + value + "', der nicht im Katalog steht");
                            }
                        }
                    }
                }
            }
        }
        assertThat(examples).as("keine Envelope-Beispiele gefunden — Format geaendert?").isPositive();
        assertThat(violations).isEmpty();
    }

    /** 87a1dac0: Kennzahl-Regeln des Performance-Audits haben den FAKT-Namen als festes subject. */
    @Test
    void metricRulesOfThePerformanceAuditHaveTheFactNameAsFixedSubject() {
        String performance = "mysuricates.performance";
        assertThat(catalog.fixedSubject(performance, "perf.db.size")).isEqualTo("database_bytes");
        assertThat(catalog.fixedSubject(performance, "perf.frontend.bundle-size")).isEqualTo("frontend_bundle_js_bytes");
        assertThat(catalog.fixedSubject(performance, "perf.frontend.lazy-chunk-preloaded"))
                .isEqualTo("frontend_lazy_chunk_preloaded");
        assertThat(catalog.fixedSubject(performance, "perf.db.unindexed-column")).isEqualTo("unindexed_findby_columns");
        assertThat(catalog.fixedSubject(performance, "perf.db.repository-loop")).as("Pfadregel").isNull();
        assertThat(catalog.fixedSubject(performance, "perf.request.slow-route")).as("Route").isNull();
    }

    /** 87a1dac0: Kennzahl-Regeln des Health-Checks haben den FAKT-Namen als festes subject. */
    @Test
    void metricRulesOfTheHealthCheckHaveTheFactNameAsFixedSubject() {
        String health = "mysuricates.health";
        assertThat(catalog.fixedSubject(health, "health.tests-failing")).isEqualTo("backend_tests_failures");
        assertThat(catalog.fixedSubject(health, "health.lint-findings")).isEqualTo("frontend_lint_problems");
        assertThat(catalog.fixedSubject(health, "health.type-errors")).isEqualTo("frontend_tsc_errors");
        assertThat(catalog.fixedSubject(health, "health.build-broken")).as("Backend oder Frontend").isNull();
        assertThat(catalog.fixedSubject(health, "health.doc-drift")).as("Pfad").isNull();
    }

    /**
     * Gate (87a1dac0): Ein festes subject ausserhalb des Evidenz-Prompts muss im Prompt seines Audits in einer
     * Zeile zusammen mit seinem ruleKey stehen — sonst setzte die Ingestion einen Wert durch, den das Modell nie
     * zu sehen bekommt, und die Tabelle BEKANNTE BEFUNDE zeigte ihm ein subject, das es nicht kennt. Ein fester
     * Wert muss ausserdem die Normalisierung unveraendert ueberstehen.
     */
    @Test
    void fixedSubjectsOfOtherAuditsStandNextToTheirRuleKeyInThePrompt() throws IOException {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Path file : poolDefinitions()) {
            for (ShippedDefinitions.Variant variant : ShippedDefinitions.variants(file)) {
                JsonNode root = variant.root();
                String auditKey = root.path("metadata").path("audit").path("key").asText("");
                if (PUBLIC_READINESS.equals(auditKey)) {
                    continue;
                }
                List<String> lines = new ArrayList<>();
                for (JsonNode block : blocks(root)) {
                    lines.addAll(block.path("config").path("command").asText("").lines().toList());
                }
                for (String ruleKey : catalog.allowedKeys(auditKey)) {
                    String fixed = catalog.fixedSubject(auditKey, ruleKey);
                    if (fixed == null) {
                        continue;
                    }
                    checked++;
                    if (!fixed.equals(AuditSubjectNormalizer.normalize(fixed))) {
                        violations.add(auditKey + ": fester subject-Wert '" + fixed + "' uebersteht die Normalisierung nicht");
                    }
                    if (lines.stream().noneMatch(line -> line.contains(ruleKey) && line.contains(fixed))) {
                        violations.add(variant.label() + " (" + auditKey + "): keine Prompt-Zeile nennt ruleKey '"
                                + ruleKey + "' zusammen mit seinem festen subject '" + fixed + "'");
                    }
                }
            }
        }
        assertThat(checked).as("keine festen subjects ausserhalb des Evidenz-Prompts gefunden").isPositive();
        assertThat(violations).isEmpty();
    }

    /**
     * ruleKeys, die ein Prompt seinem Modell woertlich vorgibt. Das Envelope-Beispiel am Ende jedes
     * Prompts zaehlt nicht: dort stehen Schema-Platzhalter ({@code "ruleKey":"stabile-regel-id"},
     * {@code "subject":"pfad/zur/datei"}), keine Vokabeln.
     */
    private static Set<String> literalRuleKeys(JsonNode root) {
        // Nur echte Zuweisungen ("ruleKey":"x" / ruleKey "x"), nicht der Fliesstext der Prompts.
        Pattern pattern = Pattern.compile("ruleKey[\"']?\\s*:?\\s*[\"']([a-z0-9][a-z0-9.\\-]*)[\"']");
        Pattern envelopeExample = Pattern.compile("<!--\\s*mysuricates:audit-result:v1.*?-->", Pattern.DOTALL);
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode block : blocks(root)) {
            String command = envelopeExample.matcher(block.path("config").path("command").asText("")).replaceAll("");
            Matcher matcher = pattern.matcher(command);
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        return keys;
    }

    private static List<JsonNode> blocks(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        collectBlocks(node, result);
        return result;
    }

    private static void collectBlocks(JsonNode node, List<JsonNode> out) {
        if (node.isObject()) {
            if (node.has("type") && node.path("config").isObject()) {
                out.add(node);
            }
            node.fields().forEachRemaining(entry -> collectBlocks(entry.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectBlocks(child, out));
        }
    }

    /** Ausgelieferte Audit-Definitionen ({@code metadata.audit.enabled}) aus {@code workflow-pool/}. */
    private static List<Path> poolDefinitions() throws IOException {
        Path pool = ShippedDefinitions.poolDirectoryOrNull();
        assertThat(pool).as("workflow-pool nicht gefunden ab " + Paths.get("").toAbsolutePath()).isNotNull();
        ObjectMapper mapper = new ObjectMapper();
        List<Path> files = new ArrayList<>();
        for (Path candidate : ShippedDefinitions.poolFiles(pool)) {
            if (mapper.readTree(candidate.toFile()).path("metadata").path("audit").path("enabled").asBoolean(false)) {
                files.add(candidate);
            }
        }
        assertThat(files).as("keine Audit-Definitionen im Pool gefunden").isNotEmpty();
        return files;
    }
}

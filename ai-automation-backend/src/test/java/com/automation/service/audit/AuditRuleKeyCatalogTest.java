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
 * The ruleKey catalog: loading, normalization — and the gate against drift between the catalog and the
 * shipped audit prompts (audit TODO 8fc9a401).
 */
class AuditRuleKeyCatalogTest {

    /** The envelope example's ruleKey placeholder, German and English prompts. */
    private static final Set<String> SCHEMA_PLACEHOLDERS = Set.of("stabile-regel-id", "stable-rule-id");

    private static final String COVERAGE = "mysuricates.test-coverage";
    private static final String PUBLIC_READINESS = "project.public-readiness";

    private final AuditRuleKeyCatalog catalog = new AuditRuleKeyCatalog(new ObjectMapper());

    @Test
    void loadsTheShippedCatalog() {
        assertThat(catalog.isChecked(COVERAGE)).isTrue();
        assertThat(catalog.allowedKeys(COVERAGE))
                .contains("test-coverage.untested-class", "test-coverage.thin-test");
    }

    /** 802f740a: entries with a fixed subject count as allowed keys and supply their value. */
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
     * Deliberately a made-up auditKey: since TODO a17e2d19 all six shipped audits have
     * a catalog entry. A real key as an example would turn this test red again at the next
     * added vocabulary entry — before that, mysuricates.architecture stood here.
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
     * Gate: every ruleKey that a shipped audit prompt assigns literally is in the catalog.
     * Otherwise the ingestion would WARN in operation although the prompt demands exactly this key.
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
     * Gate: the evidence prompt (project.public-readiness) also fixes the subject per ruleKey —
     * a fixed value for rules that concern the repository as a whole, otherwise the path rule
     * (TODO 3a9e275b). Without this requirement the model would choose a different subject for the same
     * finding between two runs, and the finding would come back as a recurrence without its disposition.
     * A fixed value must survive the normalization unchanged — otherwise it is itself the drift.
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
                        // "subject = path" in the English prompts (TODO 82f16b41), "subject = Pfad" in German ones
                        if (!hasFixed && !rest.contains("subject = path") && !rest.contains("subject = Pfad")) {
                            violations.add(variant.label() + ": ruleKey '" + ruleKey
                                    + "' legt kein subject fest (subject \"<wert>\" oder subject = path …)");
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
     * Gate (802f740a): the catalog enforces exactly the fixed subjects that the evidence prompt demands —
     * per ruleKey with exactly one {@code subject "<wert>"} the same value, otherwise (path rule, several modules)
     * none. If the two diverged, the ingestion would overwrite a subject the model adhered to.
     */
    @Test
    void catalogFixedSubjectsAreTheOnesThePublicReadinessPromptDemands() throws IOException {
        Pattern entry = Pattern.compile("(?m)^- ruleKey \"([a-z0-9][a-z0-9.\\-]*)\"(.*)$");
        // subject "a" — or a choice: subject "a" or "b" (then no fixed value); German prompts write "oder".
        Pattern fixedSubject = Pattern.compile("subject (\"[^\"]*\"(?: (?:or|oder) \"[^\"]*\")*)");
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
     * Gate (802f740a): the envelope example at the end of a prompt also names no ruleKey outside the
     * catalog. If the model adopted it, the ingestion would discard the whole report. Besides that only
     * the schema placeholder is allowed, which no catalog knows: {@code stabile-regel-id} in the German prompts,
     * {@code stable-rule-id} in the English ones (java.* audits, TODO 51357b6c).
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
                            if (!SCHEMA_PLACEHOLDERS.contains(value) && catalog.isChecked(auditKey)
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

    /** 87a1dac0: metric rules of the performance audit have the FAKT name as a fixed subject. */
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

    /** 87a1dac0: metric rules of the health check have the FAKT name as a fixed subject. */
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
     * Gate (87a1dac0): a fixed subject outside the evidence prompt must appear in its audit's prompt on a
     * line together with its ruleKey — otherwise the ingestion would enforce a value the model never
     * gets to see, and the BEKANNTE BEFUNDE table would show it a subject it does not know. A fixed
     * value must additionally survive the normalization unchanged.
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
     * ruleKeys that a prompt literally gives its model. The envelope example at the end of every
     * prompt does not count: it contains schema placeholders ({@code "ruleKey":"stabile-regel-id"},
     * {@code "subject":"pfad/zur/datei"}), no vocabulary.
     */
    private static Set<String> literalRuleKeys(JsonNode root) {
        // Only real assignments ("ruleKey":"x" / ruleKey "x"), not the running text of the prompts.
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

    /** Shipped audit definitions ({@code metadata.audit.enabled}) from {@code workflow-pool/}. */
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

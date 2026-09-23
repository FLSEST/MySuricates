package com.automation.service.audit;

import com.automation.architecture.ShippedDefinitions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
        Path pool = Files.isDirectory(Paths.get("../workflow-pool")) ? Paths.get("../workflow-pool")
                : Paths.get("workflow-pool");
        assertThat(pool).as("workflow-pool nicht gefunden ab " + Paths.get("").toAbsolutePath()).isDirectory();
        ObjectMapper mapper = new ObjectMapper();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(pool)) {
            for (Path candidate : stream.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                if (mapper.readTree(candidate.toFile()).path("metadata").path("audit").path("enabled").asBoolean(false)) {
                    files.add(candidate);
                }
            }
        }
        assertThat(files).as("keine Audit-Definitionen im Pool gefunden").isNotEmpty();
        return files;
    }
}

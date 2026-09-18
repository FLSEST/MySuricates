package com.automation.service.audit;

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

    @Test
    void anAuditWithoutCatalogEntryIsNotChecked() {
        AuditRuleKeyCatalog.Resolution resolution = catalog.resolve("mysuricates.architecture", "was-auch-immer");

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
        ObjectMapper mapper = new ObjectMapper();
        List<String> violations = new ArrayList<>();
        for (Path file : poolDefinitions()) {
            JsonNode root = mapper.readTree(file.toFile());
            String auditKey = root.path("metadata").path("audit").path("key").asText("");
            for (String ruleKey : literalRuleKeys(root)) {
                if (!catalog.allowedKeys(auditKey).contains(ruleKey)) {
                    violations.add(file.getFileName() + " (" + auditKey + ") vergibt ruleKey '" + ruleKey
                            + "' — in src/main/resources/audit/rule-keys.json nachtragen");
                }
            }
        }
        assertThat(violations).as("Katalog und Pool-Prompts duerfen nicht auseinanderlaufen").isEmpty();
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

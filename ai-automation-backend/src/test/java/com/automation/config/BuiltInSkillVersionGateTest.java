package com.automation.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Versions-Waechter fuer die Built-in-Skill-Ressourcen (TODO 36f47bff).
 *
 * <p>{@link SkillSeeder} aktualisiert einen bereits vorhandenen Built-in-Skill nur, wenn die
 * Ressourcen-{@code version} hoeher ist als die in der Datenbank. Ein geaenderter Prompt ohne
 * Versions-Bump erreicht bestehende Instanzen deshalb nie — und zwar still. Dieser Test macht
 * das laut: Er hasht je Skill die {@code <id>.json} (kanonisch, damit reine Formatierung nicht
 * anschlaegt) zusammen mit der Prompt-Datei {@code <id>.md} (byte-genau) und vergleicht das
 * Ergebnis mit der eingecheckten Liste {@code src/test/resources/skills/builtin-versions.json}
 * im Format <code>{"&lt;id&gt;": {"version": "1.0.0", "hash": "…"}}</code>.
 *
 * <p>Zwei Zusicherungen:
 * <ol>
 *   <li>{@link #resourceChange_requiresVersionBump()} — das eigentliche Gate: aenderte sich der
 *       Hash eines bekannten Skills, muss seine {@code version} hoeher sein als die
 *       eingetragene.</li>
 *   <li>{@link #versionLedger_matchesResources()} — die Liste selbst muss den aktuellen Stand
 *       abbilden. Ohne diese zweite Zusicherung bliebe eine veraltete Liste zurueck und ein
 *       spaeterer Bump-loser Prompt-Wechsel kaeme durch, weil er mit einer laengst
 *       ueberholten Version verglichen wuerde.</li>
 * </ol>
 *
 * <p>Ablauf bei einer gewollten Aenderung: {@code version} in der {@code <id>.json} erhoehen,
 * dann die Liste neu schreiben und mitcommitten:
 * {@code ./mvnw test -Dtest=BuiltInSkillVersionGateTest -Dskills.versions.write=true}
 */
class BuiltInSkillVersionGateTest {

    private static final String VERSIONS_RELATIVE = "src/test/resources/skills/builtin-versions.json";
    private static final String RESOURCES_RELATIVE = "src/main/resources/skills/builtin";
    private static final String WRITE_HINT =
            "./mvnw test -Dtest=BuiltInSkillVersionGateTest -Dskills.versions.write=true";

    /** Ein Eintrag der Hash-Liste: die deklarierte Version und der Hash ueber JSON + Prompt. */
    record SkillFingerprint(String version, String hash) {}

    @BeforeAll
    static void writeVersionsIfRequested() throws IOException {
        if (!Boolean.getBoolean("skills.versions.write")) {
            return;
        }
        Path file = versionsPath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, render(currentFingerprints()), StandardCharsets.UTF_8);
    }

    @Test
    void resourceChange_requiresVersionBump() throws IOException {
        Map<String, SkillFingerprint> current = currentFingerprints();
        Map<String, SkillFingerprint> recorded = readVersions();

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, SkillFingerprint> entry : current.entrySet()) {
            SkillFingerprint before = recorded.get(entry.getKey());
            // Unbekannte id = neuer Skill: es gibt keine bestehende Version, die zu ueberholen waere.
            if (before == null || before.hash().equals(entry.getValue().hash())) {
                continue;
            }
            if (!SeedVersions.isNewer(entry.getValue().version(), before.version())) {
                violations.add("%s: Ressourcen geaendert, version steht weiterhin auf %s (eingetragen: %s)"
                        .formatted(entry.getKey(), entry.getValue().version(), before.version()));
            }
        }

        assertThat(violations)
                .as("""
                        Geaenderte Built-in-Skills ohne Versions-Bump. SkillSeeder ueberschreibt einen \
                        vorhandenen Skill nur bei hoeherer version — ohne Bump erreicht die Aenderung \
                        bestehende Instanzen nie. Erst die "version" in der <id>.json erhoehen, dann die \
                        Hash-Liste neu schreiben und mitcommitten: %s""".formatted(WRITE_HINT))
                .isEmpty();
    }

    @Test
    void versionLedger_matchesResources() throws IOException {
        Map<String, SkillFingerprint> current = currentFingerprints();
        Map<String, SkillFingerprint> recorded = readVersions();

        Set<String> ids = new TreeSet<>(current.keySet());
        ids.addAll(recorded.keySet());

        List<String> differences = new ArrayList<>();
        for (String id : ids) {
            SkillFingerprint now = current.get(id);
            SkillFingerprint before = recorded.get(id);
            if (before == null) {
                differences.add(id + ": neu in den Ressourcen, fehlt in der Liste");
            } else if (now == null) {
                differences.add(id + ": steht in der Liste, gibt es in den Ressourcen nicht mehr");
            } else if (!before.equals(now)) {
                differences.add("%s: Liste %s/%s, Ressourcen %s/%s"
                        .formatted(id, before.version(), shortHash(before), now.version(), shortHash(now)));
            }
        }

        assertThat(differences)
                .as("""
                        %s bildet die Built-in-Skill-Ressourcen nicht mehr ab (Aenderung, neuer oder \
                        entfernter Skill). Nach einem gewollten Versions-Bump die Liste neu schreiben \
                        und mitcommitten: %s""".formatted(VERSIONS_RELATIVE, WRITE_HINT))
                .isEmpty();
    }

    private static String shortHash(SkillFingerprint fingerprint) {
        String hash = fingerprint.hash();
        return hash.length() > 19 ? hash.substring(0, 19) + "…" : hash;
    }

    // ── Fingerabdruecke der Ressourcen ────────────────────────

    /** Je Skill-id die deklarierte Version und den Hash ueber {@code <id>.json} + {@code <id>.md}. */
    static Map<String, SkillFingerprint> currentFingerprints() throws IOException {
        Path dir = moduleRoot().resolve(RESOURCES_RELATIVE);
        assertThat(Files.isDirectory(dir))
                .as("Built-in-Skill-Ressourcen nicht gefunden: %s", dir)
                .isTrue();

        Map<String, SkillFingerprint> fingerprints = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(json -> {
                        String id = json.getFileName().toString().replaceFirst("\\.json$", "");
                        fingerprints.put(id, fingerprint(dir, id, json));
                    });
        }

        assertThat(fingerprints).as("Keine Built-in-Skill-Ressourcen unter %s", dir).isNotEmpty();
        return fingerprints;
    }

    private static SkillFingerprint fingerprint(Path dir, String id, Path json) {
        Path prompt = dir.resolve(id + ".md");
        try {
            Map<String, Object> metadata = new ObjectMapper()
                    .readValue(Files.readAllBytes(json), new TypeReference<Map<String, Object>>() {});
            byte[] promptBytes = Files.readAllBytes(prompt);
            // Kanonische Form der Metadaten: alle Map-Schluessel rekursiv sortiert, damit eine reine
            // Umformatierung der JSON kein Gate ausloest — eine inhaltliche Aenderung dagegen schon.
            String canonicalJson = canonicalWriter().writeValueAsString(metadata);
            return new SkillFingerprint(declaredVersion(metadata, id), hash(canonicalJson, promptBytes));
        } catch (IOException e) {
            throw new UncheckedIOException("Built-in-Skill " + id + " nicht lesbar", e);
        }
    }

    /** Wie {@link BuiltInSkillLoader}: fehlende {@code version} bedeutet {@code 1.0.0}. */
    private static String declaredVersion(Map<String, Object> metadata, String id) throws IOException {
        Object version = metadata.get("version");
        if (version == null || version.toString().isBlank()) {
            return "1.0.0";
        }
        if (!(version instanceof String text)) {
            throw new IOException("Built-in-Skill " + id + ".json hat keine textuelle version: " + version);
        }
        return text;
    }

    /** Prompt-Bytes ungefiltert — auch ein angehaengter Zeilenumbruch ist eine Aenderung. */
    private static String hash(String canonicalJson, byte[] promptBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(canonicalJson.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0); // Trenner: JSON-Ende und Prompt-Anfang bleiben unterscheidbar
            digest.update(promptBytes);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }

    // ── Eingecheckte Liste ────────────────────────────────────

    private static Map<String, SkillFingerprint> readVersions() throws IOException {
        Path file = versionsPath();
        assertThat(Files.exists(file))
                .as("Hash-Liste fehlt: %s — einmalig erzeugen mit %s", file, WRITE_HINT)
                .isTrue();
        Map<String, SkillFingerprint> parsed = new ObjectMapper().readValue(
                Files.readString(file, StandardCharsets.UTF_8),
                new TypeReference<Map<String, SkillFingerprint>>() {});
        return new TreeMap<>(parsed);
    }

    private static String render(Map<String, SkillFingerprint> fingerprints) throws IOException {
        return canonicalWriter().writeValueAsString(fingerprints) + "\n";
    }

    private static ObjectWriter canonicalWriter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return new ObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writer(printer);
    }

    private static Path versionsPath() {
        return moduleRoot().resolve(VERSIONS_RELATIVE);
    }

    /** Laeuft sowohl mit dem Backend-Modul als auch mit dem Repo-Wurzelverzeichnis als CWD. */
    private static Path moduleRoot() {
        return Files.isDirectory(Paths.get("src/test/resources"))
                ? Paths.get("")
                : Paths.get("ai-automation-backend");
    }
}

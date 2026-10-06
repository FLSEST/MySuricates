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
 * Version guard for the built-in skill resources (TODO 36f47bff).
 *
 * <p>{@link SkillSeeder} updates an already existing built-in skill only if the
 * resource {@code version} is higher than the one in the database. A changed prompt without
 * a version bump therefore never reaches existing instances — and does so silently. This test makes
 * that loud: it hashes, per skill, the {@code <id>.json} (canonical, so that pure formatting does not
 * trip it) together with the prompt file {@code <id>.md} (byte-exact) and compares the
 * result with the checked-in list {@code src/test/resources/skills/builtin-versions.json}
 * in the format <code>{"&lt;id&gt;": {"version": "1.0.0", "hash": "…"}}</code>.
 *
 * <p>Two assertions:
 * <ol>
 *   <li>{@link #resourceChange_requiresVersionBump()} — the actual gate: if the
 *       hash of a known skill changed, its {@code version} must be higher than the
 *       recorded one.</li>
 *   <li>{@link #versionLedger_matchesResources()} — the list itself must reflect the current state.
 *       Without this second assertion an outdated list would remain and a
 *       later prompt change without a bump would get through, because it would be compared with a long-
 *       outdated version.</li>
 * </ol>
 *
 * <p>Procedure for an intended change: raise {@code version} in the {@code <id>.json},
 * then rewrite the list and commit it along:
 * {@code ./mvnw test -Dtest=BuiltInSkillVersionGateTest -Dskills.versions.write=true}
 */
class BuiltInSkillVersionGateTest {

    private static final String VERSIONS_RELATIVE = "src/test/resources/skills/builtin-versions.json";
    private static final String RESOURCES_RELATIVE = "src/main/resources/skills/builtin";
    private static final String WRITE_HINT =
            "./mvnw test -Dtest=BuiltInSkillVersionGateTest -Dskills.versions.write=true";

    /** One entry of the hash list: the declared version and the hash over JSON + prompt. */
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
            // Unknown id = new skill: there is no existing version that would have to be surpassed.
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

    // ── Fingerprints of the resources ────────────────────────

    /** Per skill id the declared version and the hash over {@code <id>.json} + {@code <id>.md}. */
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
            // Canonical form of the metadata: all map keys sorted recursively, so that a pure
            // reformatting of the JSON does not trigger the gate — a change in content does.
            String canonicalJson = canonicalWriter().writeValueAsString(metadata);
            return new SkillFingerprint(declaredVersion(metadata, id), hash(canonicalJson, promptBytes));
        } catch (IOException e) {
            throw new UncheckedIOException("Built-in-Skill " + id + " nicht lesbar", e);
        }
    }

    /** Like {@link BuiltInSkillLoader}: a missing {@code version} means {@code 1.0.0}. */
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

    /** Prompt bytes unfiltered — an appended line break is a change too. */
    private static String hash(String canonicalJson, byte[] promptBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(canonicalJson.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0); // separator: JSON end and prompt start stay distinguishable
            digest.update(promptBytes);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }

    // ── Checked-in list ────────────────────────────────────

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

    /** Works with both the backend module and the repo root directory as CWD. */
    private static Path moduleRoot() {
        return Files.isDirectory(Paths.get("src/test/resources"))
                ? Paths.get("")
                : Paths.get("ai-automation-backend");
    }
}

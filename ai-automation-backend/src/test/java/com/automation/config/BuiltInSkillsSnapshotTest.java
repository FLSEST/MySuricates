package com.automation.config;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fidelity guard for the built-in skills (TODO 9fcb8ecf).
 *
 * <p>Serializes all built-in skills into a canonical JSON structure (all map keys
 * sorted alphabetically and recursively, so that {@code Map.of(...)} does not introduce a JVM-dependent
 * order) and compares it byte-exactly with
 * {@code src/test/resources/skills/builtin-snapshot.json}.
 *
 * <p>The snapshot was generated from the earlier Java definitions ({@code config/BuiltIn*Skills}).
 * Since the move to {@code classpath:skills/builtin/*.json} + {@code *.md}
 * {@link BuiltInSkillLoader} feeds it — that the snapshot stayed unchanged proves
 * the byte-exact prompt and metadata fidelity.
 *
 * <p>Deliberately rewrite the snapshot (only for an intended change of content):
 * {@code ./mvnw test -Dtest=BuiltInSkillsSnapshotTest -Dskills.snapshot.write=true}
 */
class BuiltInSkillsSnapshotTest {

    private static final String SNAPSHOT_RELATIVE = "src/test/resources/skills/builtin-snapshot.json";

    /** Single source of the skills — when restructuring, switch only here. */
    private static String canonicalJson() {
        BuiltInSkillLoader loader = new BuiltInSkillLoader(new ObjectMapper());
        return canonicalJson(loader.loadAll(), loader.loadAllDefinitions());
    }

    @Test
    void builtInSkills_matchCanonicalSnapshot() throws IOException {
        String actual = canonicalJson();
        Path snapshot = snapshotPath();

        if (Boolean.getBoolean("skills.snapshot.write")) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, actual, StandardCharsets.UTF_8);
        }

        assertThat(Files.exists(snapshot))
                .as("Snapshot fehlt: %s — einmalig mit -Dskills.snapshot.write=true erzeugen", snapshot)
                .isTrue();

        String expected = Files.readString(snapshot, StandardCharsets.UTF_8);
        assertThat(actual)
                .as("""
                        Built-in-Skills weichen vom Snapshot ab (Prompt-Fidelitaet verletzt). Haeufigste \
                        unbeabsichtigte Ursache: ein Editor hat an eine skills/builtin/<id>.md einen \
                        finalen Zeilenumbruch angehaengt — die Prompts enden bewusst ohne. Die \
                        .editorconfig im Repo-Wurzelverzeichnis haelt das fest \
                        (insert_final_newline = false). Gewollte Aenderung: \
                        ./mvnw test -Dtest=BuiltInSkillsSnapshotTest -Dskills.snapshot.write=true \
                        (und die version in der <id>.json erhoehen, siehe BuiltInSkillVersionGateTest)""")
                .isEqualTo(expected);
    }

    @Test
    void canonicalJson_isStableAcrossInvocations() {
        assertThat(canonicalJson()).isEqualTo(canonicalJson());
    }

    // ── Canonical serialization ─────────────────────────────

    /**
     * {@code skills} and {@code definitions} both come sorted by {@code sortOrder} from
     * the same loader run. The snapshot pins the <em>declared</em> {@code sortOrder} from
     * the JSON (not the list position), so that a shift of all values —
     * e.g. 1..19 → 10..28 — also stands out as a deviation.
     */
    static String canonicalJson(List<Skill> skills, List<BuiltInSkillDefinition> definitions) {
        if (skills.size() != definitions.size()) {
            throw new IllegalStateException("Skills und Definitionen passen nicht zusammen");
        }
        List<Object> root = new ArrayList<>();
        for (int i = 0; i < skills.size(); i++) {
            Skill skill = skills.get(i);
            BuiltInSkillDefinition def = definitions.get(i);
            if (!skill.getId().equals(def.id())) {
                throw new IllegalStateException(
                        "Reihenfolge von Skills und Definitionen weicht ab: "
                                + skill.getId() + " != " + def.id());
            }
            root.add(toCanonicalMap(skill, def.sortOrder()));
        }
        try {
            return writer().writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Kanonische Serialisierung fehlgeschlagen", e);
        }
    }

    private static Map<String, Object> toCanonicalMap(Skill skill, int sortOrder) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", skill.getId());
        map.put("sortOrder", sortOrder);
        map.put("name", skill.getName());
        map.put("icon", skill.getIcon());
        map.put("description", skill.getDescription());
        map.put("category", skill.getCategory() != null ? skill.getCategory().name() : null);
        map.put("version", skill.getVersion());
        map.put("builtin", skill.isBuiltin());
        map.put("configSchema", skill.getConfigSchema());
        map.put("prompt", skill.getAiContext());

        List<Object> caps = new ArrayList<>();
        for (Capability c : skill.getCapabilities()) {
            Map<String, Object> cap = new LinkedHashMap<>();
            cap.put("name", c.getName());
            cap.put("description", c.getDescription());
            cap.put("handlerClass", c.getHandlerClass());
            cap.put("async", c.isAsync());
            cap.put("writes", c.isWrites());
            cap.put("inputSchema", c.getInputSchema());
            cap.put("outputSchema", c.getOutputSchema());
            cap.put("skillBackReference", c.getSkill() == skill ? skill.getId() : "MISSING");
            caps.add(cap);
        }
        map.put("capabilities", caps);
        return map;
    }

    private static ObjectWriter writer() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return new ObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writer(printer);
    }

    private static Path snapshotPath() {
        Path module = Files.isDirectory(Paths.get("src/test/resources"))
                ? Paths.get("")
                : Paths.get("ai-automation-backend");
        return module.resolve(SNAPSHOT_RELATIVE);
    }
}

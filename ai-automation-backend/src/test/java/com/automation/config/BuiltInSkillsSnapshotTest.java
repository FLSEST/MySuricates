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
 * Fidelity-Waechter fuer die Built-in-Skills (TODO 9fcb8ecf).
 *
 * <p>Serialisiert alle Built-in-Skills in eine kanonische JSON-Struktur (alle Map-Schluessel
 * rekursiv alphabetisch sortiert, damit {@code Map.of(...)} keine JVM-abhaengige Reihenfolge
 * einschleppt) und vergleicht sie byte-genau mit
 * {@code src/test/resources/skills/builtin-snapshot.json}.
 *
 * <p>Der Snapshot wurde aus den fruehreren Java-Definitionen ({@code config/BuiltIn*Skills})
 * erzeugt. Seit der Auslagerung nach {@code classpath:skills/builtin/*.json} + {@code *.md}
 * speist ihn {@link BuiltInSkillLoader} — dass der Snapshot dabei unveraendert blieb, belegt
 * die byte-genaue Prompt- und Metadaten-Fidelitaet.
 *
 * <p>Snapshot bewusst neu schreiben (nur bei einer gewollten inhaltlichen Aenderung):
 * {@code ./mvnw test -Dtest=BuiltInSkillsSnapshotTest -Dskills.snapshot.write=true}
 */
class BuiltInSkillsSnapshotTest {

    private static final String SNAPSHOT_RELATIVE = "src/test/resources/skills/builtin-snapshot.json";

    /** Einzige Quelle der Skills — bei einem Umbau nur hier umstellen. */
    private static List<Skill> builtInSkills() {
        return new BuiltInSkillLoader(new ObjectMapper()).loadAll();
    }

    @Test
    void builtInSkills_matchCanonicalSnapshot() throws IOException {
        String actual = canonicalJson(builtInSkills());
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
                .as("Built-in-Skills weichen vom Snapshot ab (Prompt-Fidelitaet verletzt)")
                .isEqualTo(expected);
    }

    @Test
    void canonicalJson_isStableAcrossInvocations() {
        assertThat(canonicalJson(builtInSkills())).isEqualTo(canonicalJson(builtInSkills()));
    }

    // ── Kanonische Serialisierung ─────────────────────────────

    static String canonicalJson(List<Skill> skills) {
        List<Object> root = new ArrayList<>();
        for (int i = 0; i < skills.size(); i++) {
            // sortOrder ist heute die Listenposition; nach der Auslagerung die Zahl aus der JSON.
            root.add(toCanonicalMap(skills.get(i), i + 1));
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

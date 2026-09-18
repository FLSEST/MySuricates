package com.automation.config;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Laedt die Built-in-Skills aus {@code classpath:skills/builtin/*.json} (Metadaten inkl.
 * {@code sortOrder}, {@code writes}) plus die Schwesterdatei {@code <id>.md} mit dem
 * Prompt-Text. Vorbild: {@link BuiltInTemplateLoader}.
 *
 * <p><b>Prompt-Fidelitaet:</b> Die {@code .md}-Datei wird byte-genau uebernommen — kein Trim,
 * kein angehaengter Zeilenumbruch. Die heutigen Prompts enden bewusst <em>ohne</em>
 * abschliessenden Zeilenumbruch; ein Editor, der einen anhaengt, aendert den Prompt.
 * {@code BuiltInSkillsSnapshotTest} schlaegt in dem Fall fehl.
 *
 * <p>Anders als beim Template-Loader sind Fehler hier hart: eine fehlende {@code .md},
 * ein doppelter {@code sortOrder} oder ein leeres Verzeichnis brechen den Start ab, statt
 * ein unvollstaendiges Skill-Set zu seeden.
 */
@Component
@Slf4j
public class BuiltInSkillLoader {

    private static final String DEFAULT_BASE_PATH = "skills/builtin";

    private final ObjectMapper objectMapper;
    private final String basePath;

    @Autowired
    public BuiltInSkillLoader(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_BASE_PATH);
    }

    /** Nur fuer Tests: abweichendes Ressourcen-Verzeichnis. */
    BuiltInSkillLoader(ObjectMapper objectMapper, String basePath) {
        this.objectMapper = objectMapper;
        this.basePath = basePath;
    }

    /** Alle Built-in-Skills als Entities, sortiert nach {@code sortOrder}. */
    public List<Skill> loadAll() {
        return loadAllDefinitions().stream().map(this::toEntity).toList();
    }

    /** Die rohen Metadaten ohne Prompt, sortiert nach {@code sortOrder}. */
    public List<BuiltInSkillDefinition> loadAllDefinitions() {
        List<BuiltInSkillDefinition> definitions = new ArrayList<>();

        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:" + basePath + "/*.json");
        } catch (IOException e) {
            throw new IllegalStateException("Built-in-Skills unter " + basePath + " nicht lesbar", e);
        }

        if (resources.length == 0) {
            throw new IllegalStateException("Keine Built-in-Skills unter classpath:" + basePath + "/*.json gefunden");
        }

        for (Resource resource : resources) {
            definitions.add(read(resource));
        }

        checkSortOrder(definitions);
        definitions.sort(Comparator.comparingInt(BuiltInSkillDefinition::sortOrder));
        log.debug("Loaded {} built-in skills from {}", definitions.size(), basePath);
        return definitions;
    }

    private BuiltInSkillDefinition read(Resource resource) {
        BuiltInSkillDefinition def;
        try (InputStream is = resource.getInputStream()) {
            def = objectMapper.readValue(is, BuiltInSkillDefinition.class);
        } catch (IOException e) {
            throw new IllegalStateException("Built-in-Skill " + resource.getFilename() + " nicht lesbar", e);
        }
        if (def.id() == null || def.id().isBlank()) {
            throw new IllegalStateException("Built-in-Skill " + resource.getFilename() + " hat keine id");
        }
        return def;
    }

    /** Prompt byte-genau aus {@code <id>.md}. */
    private String readPrompt(String id) {
        String path = basePath + "/" + id + ".md";
        ClassPathResource md = new ClassPathResource(path);
        if (!md.exists()) {
            throw new IllegalStateException("Prompt-Datei fehlt fuer Built-in-Skill " + id + ": " + id + ".md");
        }
        try (InputStream is = md.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Prompt-Datei " + id + ".md nicht lesbar", e);
        }
    }

    private static void checkSortOrder(List<BuiltInSkillDefinition> definitions) {
        Set<Integer> seen = new HashSet<>();
        for (BuiltInSkillDefinition def : definitions) {
            if (!seen.add(def.sortOrder())) {
                throw new IllegalStateException(
                        "Doppelter sortOrder " + def.sortOrder() + " bei Built-in-Skill " + def.id());
            }
        }
    }

    private Skill toEntity(BuiltInSkillDefinition def) {
        Skill skill = Skill.builder()
                .id(def.id())
                .name(def.name())
                .icon(def.icon())
                .description(def.description())
                .category(SkillCategory.valueOf(def.category()))
                .aiContext(readPrompt(def.id()))
                .builtin(true)
                .version(def.version() != null ? def.version() : "1.0.0")
                .build();

        List<Capability> capabilities = new ArrayList<>();
        if (def.capabilities() != null) {
            for (BuiltInSkillDefinition.CapabilityDefinition c : def.capabilities()) {
                Capability cap = Capability.builder()
                        .name(c.name())
                        .description(c.description())
                        .handlerClass(c.handlerClass())
                        .inputSchema(c.inputSchema() != null ? c.inputSchema() : new HashMap<>())
                        .outputSchema(c.outputSchema() != null ? c.outputSchema() : new HashMap<>())
                        .async(c.async())
                        .writes(c.writes())
                        .build();
                cap.setSkill(skill);
                capabilities.add(cap);
            }
        }
        skill.setCapabilities(capabilities);
        return skill;
    }
}

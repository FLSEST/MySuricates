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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads the built-in skills from {@code classpath:skills/builtin/*.json} (metadata incl.
 * {@code sortOrder}, {@code writes}) plus the sibling file {@code <id>.md} with the
 * prompt text. Modelled on {@link BuiltInTemplateLoader}.
 *
 * <p><b>Prompt fidelity:</b> The {@code .md} file is taken over byte-exact — no trim,
 * no appended line break. Today's prompts deliberately end <em>without</em> a
 * trailing line break; an editor that appends one changes the prompt.
 * {@code BuiltInSkillsSnapshotTest} fails in that case.
 *
 * <p>Unlike with the template loader, errors here are hard: a missing {@code .md}, a
 * file name that does not match the {@code id}, a missing {@code sortOrder}, a duplicate
 * {@code sortOrder}, a missing/unknown {@code category} or an empty directory
 * abort startup instead of seeding an incomplete skill set.
 */
@Component
@Slf4j
public class BuiltInSkillLoader {

    private static final String DEFAULT_BASE_PATH = "skills/builtin";

    private static final Set<String> CATEGORIES = Arrays.stream(SkillCategory.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());

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

    /** All built-in skills as entities, sorted by {@code sortOrder}. */
    public List<Skill> loadAll() {
        return loadAllDefinitions().stream().map(this::toEntity).toList();
    }

    /** The raw metadata without prompt, sorted by {@code sortOrder}. */
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
        String filename = resource.getFilename();
        BuiltInSkillDefinition def;
        try (InputStream is = resource.getInputStream()) {
            def = objectMapper.readValue(is, BuiltInSkillDefinition.class);
        } catch (IOException e) {
            throw new IllegalStateException("Built-in-Skill " + filename + " nicht lesbar", e);
        }

        if (def.id() == null || def.id().isBlank()) {
            throw new IllegalStateException("Built-in-Skill " + filename + " hat keine id");
        }
        // The prompt file is resolved via the id — file name and id must therefore match.
        if (!(def.id() + ".json").equals(filename)) {
            throw new IllegalStateException(
                    "Built-in-Skill " + filename + " heisst nicht wie seine id: erwartet " + def.id() + ".json");
        }
        if (def.sortOrder() == null) {
            throw new IllegalStateException("Built-in-Skill " + filename + " hat keinen sortOrder");
        }
        if (def.category() == null || def.category().isBlank()) {
            throw new IllegalStateException("Built-in-Skill " + filename + " hat keine category");
        }
        if (!CATEGORIES.contains(def.category())) {
            throw new IllegalStateException(
                    "Built-in-Skill " + filename + " hat unbekannte category: " + def.category());
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

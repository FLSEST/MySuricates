package com.automation.config;

import com.automation.entity.TemplateKind;
import com.automation.entity.WorkflowTemplate;
import com.automation.util.LocalizedJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads built-in workflow template definitions from JSON files
 * in classpath:templates/builtin/*.json (templates) and classpath:templates/blueprints/*.json
 * (blueprints: empty scaffolds that show how block types connect, TODO 268ae0db).
 *
 * Each JSON file maps to a single {@link BuiltInTemplateDefinition}
 * which is then converted to a {@link WorkflowTemplate} entity.
 */
@Component
@Slf4j
public class BuiltInTemplateLoader {

    /** Where each kind ships; same format, same loader, same gates. */
    static final Map<TemplateKind, String> PATTERNS = Map.of(
            TemplateKind.TEMPLATE, "classpath:templates/builtin/*.json",
            TemplateKind.BLUEPRINT, "classpath:templates/blueprints/*.json");

    private final ObjectMapper objectMapper;

    public BuiltInTemplateLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Loads all built-in templates from JSON resource files.
     *
     * @return list of WorkflowTemplate entities sorted by kind, then sortOrder, with builtIn=true
     */
    public List<WorkflowTemplate> loadAll() {
        List<WorkflowTemplate> templates = new ArrayList<>();
        for (TemplateKind kind : TemplateKind.values()) {
            templates.addAll(load(kind));
        }
        templates.sort(Comparator.comparing(WorkflowTemplate::getKind)
                .thenComparingInt(WorkflowTemplate::getSortOrder));
        log.debug("Loaded {} built-in templates from JSON files", templates.size());
        return templates;
    }

    /**
     * Ids of every template and blueprint file that ships, taken from the file names — a file's
     * name is its id ({@code BuiltInTemplateLoaderTest}). Read from the names on purpose, not from
     * the parsed content: a file that fails to parse still counts as shipped, so the seeder never
     * removes its row because of a typo in a release (TODO 7ddacd1d).
     *
     * @return the ids, or an empty set when the classpath cannot be scanned
     */
    public Set<String> shippedIds() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            for (String pattern : PATTERNS.values()) {
                for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                    String filename = resource.getFilename();
                    if (filename != null && filename.endsWith(".json")) {
                        ids.add(filename.substring(0, filename.length() - ".json".length()));
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan for built-in template files: {}", e.getMessage());
            return Set.of();
        }
        return ids;
    }

    private List<WorkflowTemplate> load(TemplateKind kind) {
        List<WorkflowTemplate> templates = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(PATTERNS.get(kind));

            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    // Bilingual files carry {de,en} maps inside blocks, which BlockDefinition can't
                    // take — the entity gets the English variant, the raw content goes to i18nSource.
                    JsonNode root = objectMapper.readTree(is);
                    BuiltInTemplateDefinition def = objectMapper.treeToValue(
                            LocalizedJson.resolveDefinition(root, LocalizedJson.DEFAULT_LOCALE),
                            BuiltInTemplateDefinition.class);
                    WorkflowTemplate template = toEntity(def);
                    template.setI18nSource(LocalizedJson.localizedSource(root));
                    template.setKind(kind);
                    templates.add(template);
                } catch (IOException | IllegalArgumentException e) {
                    log.error("Failed to load built-in template from {}: {}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan for built-in {} files: {}", kind, e.getMessage());
        }
        return templates;
    }

    private WorkflowTemplate toEntity(BuiltInTemplateDefinition def) {
        return WorkflowTemplate.builder()
                .id(def.id())
                .icon(def.icon())
                .name(def.name())
                .description(def.description())
                .category(def.category())
                .sortOrder(def.sortOrder())
                .version(def.version() != null ? def.version() : "1.0.0")
                .model(def.model())
                .instructions(def.instructions())
                .blocks(def.blocks())
                .builtIn(true)
                .build();
    }
}

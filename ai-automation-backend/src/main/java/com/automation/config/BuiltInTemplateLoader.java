package com.automation.config;

import com.automation.entity.WorkflowTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Loads built-in workflow template definitions from JSON files
 * in classpath:templates/builtin/*.json.
 *
 * Each JSON file maps to a single {@link BuiltInTemplateDefinition}
 * which is then converted to a {@link WorkflowTemplate} entity.
 */
@Component
@Slf4j
public class BuiltInTemplateLoader {

    private static final String TEMPLATE_PATTERN = "classpath:templates/builtin/*.json";

    private final ObjectMapper objectMapper;

    public BuiltInTemplateLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Loads all built-in templates from JSON resource files.
     *
     * @return list of WorkflowTemplate entities sorted by sortOrder, with builtIn=true
     */
    public List<WorkflowTemplate> loadAll() {
        List<WorkflowTemplate> templates = new ArrayList<>();

        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(TEMPLATE_PATTERN);

            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    BuiltInTemplateDefinition def = objectMapper.readValue(is, BuiltInTemplateDefinition.class);
                    templates.add(toEntity(def));
                } catch (IOException e) {
                    log.error("Failed to load built-in template from {}: {}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan for built-in template files: {}", e.getMessage());
        }

        templates.sort(Comparator.comparingInt(WorkflowTemplate::getSortOrder));
        log.debug("Loaded {} built-in templates from JSON files", templates.size());
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

package com.automation.service.pool;

import com.automation.dto.request.ExportToPoolRequest;
import com.automation.dto.response.ExportToPoolResponse;
import com.automation.entity.Workflow;
import com.automation.service.workflow.DefinitionIntegrity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes a workflow to disk as a pool definition.
 *
 * <p>Own class, because two things come together here that the {@link WorkflowPoolService}
 * would otherwise mix with reading the pool: the canonical field order of the pool JSON and
 * the cleanup of instance bindings ({@link PoolExportSanitizer}).
 *
 * <p>Rules: {@code docs/workflow-pool-kuratierung.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PoolExportWriter {

    private static final Set<String> FACTORY_INTERNAL_METADATA =
            Set.of("factoryGenerated", "factoryTopic", "factoryCategory");

    private final ObjectMapper objectMapper;

    /**
     * @param poolDir target directory; created if missing. An existing file name
     *                is not overwritten but numbered.
     */
    public ExportToPoolResponse write(Workflow workflow, ExportToPoolRequest request,
                                      PoolScope scope, Path poolDir) throws IOException {
        ExportToPoolRequest req = request != null
                ? request
                : new ExportToPoolRequest(null, null, null, null, null, null, null);

        String nameEn = req.nameEn() != null ? req.nameEn() : workflow.getName();
        Map<String, Object> poolJson = buildPoolJson(workflow, req, scope, nameEn);

        // Remove instance bindings before anything lands on disk. Convert to a generic
        // structure first: the blocks are otherwise POJOs the sanitizer does not look into.
        Map<String, Object> generic = objectMapper.convertValue(poolJson, new TypeReference<>() {});
        PoolExportSanitizer sanitizer = new PoolExportSanitizer();
        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbed = (Map<String, Object>) sanitizer.scrub(generic);
        // Last field, over everything above it — what the install checks again (TODO b18939a6)
        Map<String, Object> sanitized = new LinkedHashMap<>(scrubbed);
        sanitized.remove(DefinitionIntegrity.FIELD);
        sanitized.put(DefinitionIntegrity.FIELD, DefinitionIntegrity.compute(sanitized));
        if (!sanitizer.removedPaths().isEmpty()) {
            log.warn("Pool-Export von '{}' ({}): Instanz-Bindungen entfernt: {}",
                    workflow.getName(), workflow.getId(), sanitizer.removedPaths());
        }

        Files.createDirectories(poolDir);
        Path target = freeTarget(poolDir, slugify(nameEn));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), sanitized);

        String filename = target.getFileName().toString();
        log.info("Exported workflow '{}' ({}) to pool: {}",
                workflow.getName(), workflow.getId(), filename);
        return new ExportToPoolResponse(
                filename.replace(".json", ""), filename, sanitizer.removedPaths());
    }

    /** Canonical field order - so all pool files read the same. */
    private Map<String, Object> buildPoolJson(Workflow workflow, ExportToPoolRequest req,
                                              PoolScope scope, String nameEn) {
        String nameDe = req.nameDe() != null ? req.nameDe() : workflow.getName();
        String descEn = req.descriptionEn() != null ? req.descriptionEn() : workflow.getDescription();
        String descDe = req.descriptionDe() != null ? req.descriptionDe() : workflow.getDescription();
        List<String> tags = req.tags() != null ? req.tags() : List.of();

        LinkedHashMap<String, Object> poolJson = new LinkedHashMap<>();
        poolJson.put("name", Map.of("de", nameDe, "en", nameEn));
        if (descEn != null || descDe != null) {
            poolJson.put("description", Map.of(
                    "de", descDe != null ? descDe : "",
                    "en", descEn != null ? descEn : ""));
        }
        poolJson.put("icon", workflow.getIcon() != null ? workflow.getIcon() : "🔧");
        poolJson.put("category", resolveCategory(workflow, req));
        poolJson.put("scope", scope.jsonValue());
        if (!tags.isEmpty()) {
            poolJson.put("tags", tags);
        }
        poolJson.put("blocks", workflow.getBlocks());
        if (workflow.getInstructions() != null && !workflow.getInstructions().isBlank()) {
            poolJson.put("instructions", workflow.getInstructions());
        }
        if (workflow.getModel() != null) {
            poolJson.put("model", workflow.getModel());
        }
        if (workflow.getEffort() != null && !workflow.getEffort().isBlank()) {
            poolJson.put("effort", workflow.getEffort());
        }
        poolJson.put("version", workflow.getVersion() != null ? workflow.getVersion() : "1.0.0");
        poolJson.put("author", req.author() != null ? req.author() : "MySuricates");
        if (workflow.getDefaultContext() != null && !workflow.getDefaultContext().isEmpty()) {
            poolJson.put("defaultContext", workflow.getDefaultContext());
        }
        if (workflow.getMetadata() != null && !workflow.getMetadata().isEmpty()) {
            Map<String, Object> filtered = new LinkedHashMap<>(workflow.getMetadata());
            FACTORY_INTERNAL_METADATA.forEach(filtered::remove);
            if (!filtered.isEmpty()) {
                poolJson.put("metadata", filtered);
            }
        }
        return poolJson;
    }

    /** From the request, otherwise from the factory metadata, otherwise {@code CUSTOM}. */
    private String resolveCategory(Workflow workflow, ExportToPoolRequest req) {
        if (req.category() != null) {
            return req.category();
        }
        if (workflow.getMetadata() != null
                && workflow.getMetadata().get("factoryCategory") instanceof String s) {
            return s;
        }
        return "CUSTOM";
    }

    private Path freeTarget(Path poolDir, String slug) {
        Path target = poolDir.resolve(slug + ".json");
        int suffix = 2;
        while (Files.exists(target)) {
            target = poolDir.resolve(slug + "-" + suffix + ".json");
            suffix++;
        }
        return target;
    }

    private String slugify(String name) {
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            slug = "workflow-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return slug;
    }
}

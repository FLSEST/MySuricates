package com.automation.config;

import com.automation.entity.WorkflowTemplate;
import com.automation.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Brings the built-in templates and blueprints of this release into the database at startup: inserts
 * new ones, updates those whose shipped {@code version} is higher, and removes the built-in rows whose
 * id no longer ships — moved to the pool, renamed, or left over from before the ids were stable
 * (TODO 7ddacd1d). User templates ({@code builtIn = false}) are never touched.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowTemplateSeeder implements ApplicationRunner {

    private final WorkflowTemplateRepository templateRepository;
    private final BuiltInTemplateLoader templateLoader;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<WorkflowTemplate> existing = retireUnshipped(templateRepository.findByBuiltInTrue());
        List<WorkflowTemplate> allBuiltIn = templateLoader.loadAll();

        Map<String, WorkflowTemplate> existingById = existing.stream()
                .collect(Collectors.toMap(WorkflowTemplate::getId, Function.identity()));
        Set<String> existingNames = existing.stream()
                .map(t -> t.getName().getOrDefault("en", ""))
                .collect(Collectors.toSet());

        int seeded = 0;
        int updated = 0;

        for (WorkflowTemplate template : allBuiltIn) {
            String enName = template.getName().getOrDefault("en", "");
            WorkflowTemplate dbTemplate = existingById.get(template.getId());

            if (dbTemplate == null && !existingNames.contains(enName)) {
                // New template — insert
                templateRepository.save(template);
                seeded++;
            } else if (dbTemplate != null && isNewerVersion(template.getVersion(), dbTemplate.getVersion())) {
                // Existing template with older version — update
                dbTemplate.setBlocks(template.getBlocks());
                dbTemplate.setInstructions(template.getInstructions());
                dbTemplate.setI18nSource(template.getI18nSource());
                dbTemplate.setKind(template.getKind());
                dbTemplate.setModel(template.getModel());
                dbTemplate.setIcon(template.getIcon());
                dbTemplate.setName(template.getName());
                dbTemplate.setDescription(template.getDescription());
                dbTemplate.setCategory(template.getCategory());
                dbTemplate.setSortOrder(template.getSortOrder());
                dbTemplate.setVersion(template.getVersion());
                templateRepository.save(dbTemplate);
                updated++;
            }
        }

        if (seeded > 0 || updated > 0) {
            log.info("Built-in templates: seeded {}, updated {} ({} total)", seeded, updated, existing.size() + seeded);
        } else {
            log.debug("All {} built-in workflow templates up to date", existing.size());
        }
    }

    /**
     * Removes the built-in rows whose id no longer ships and returns the others. The database takes
     * care of what hangs on a removed row: its verifications go ({@code ON DELETE CASCADE}), workflows
     * made from it stay and only lose the back-reference ({@code workflows.template_id ON DELETE SET
     * NULL}). It runs before seeding: a left-over row with the English name of a shipped template kept
     * that template out ({@code existingNames}), so both happen in the same start.
     */
    private List<WorkflowTemplate> retireUnshipped(List<WorkflowTemplate> existing) {
        Set<String> shipped = templateLoader.shippedIds();
        if (shipped.isEmpty()) {
            // Nothing found is a broken classpath, not a release without templates — remove nothing.
            log.warn("No built-in template files found — built-in rows are left as they are");
            return existing;
        }
        List<WorkflowTemplate> retired = existing.stream().filter(t -> !shipped.contains(t.getId())).toList();
        if (retired.isEmpty()) {
            return existing;
        }
        templateRepository.deleteAll(retired);
        log.info("Built-in templates: removed {} that no longer ship: {}", retired.size(),
                retired.stream().map(WorkflowTemplate::getId).toList());
        return existing.stream().filter(t -> shipped.contains(t.getId())).toList();
    }

    /**
     * Compare semver version strings. Returns true if newVersion > oldVersion.
     */
    static boolean isNewerVersion(String newVersion, String oldVersion) {
        return SeedVersions.isNewer(newVersion, oldVersion);
    }
}

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

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowTemplateSeeder implements ApplicationRunner {

    private final WorkflowTemplateRepository templateRepository;
    private final BuiltInTemplateLoader templateLoader;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<WorkflowTemplate> existing = templateRepository.findByBuiltInTrue();
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
     * Compare semver version strings. Returns true if newVersion > oldVersion.
     */
    static boolean isNewerVersion(String newVersion, String oldVersion) {
        return SeedVersions.isNewer(newVersion, oldVersion);
    }
}

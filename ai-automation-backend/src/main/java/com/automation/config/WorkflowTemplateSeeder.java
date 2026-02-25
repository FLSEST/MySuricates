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

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowTemplateSeeder implements ApplicationRunner {

    private final WorkflowTemplateRepository templateRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<WorkflowTemplate> existing = templateRepository.findByBuiltInTrue();
        List<WorkflowTemplate> allBuiltIn = BuiltInTemplates.getAll();

        if (existing.size() >= allBuiltIn.size()) {
            log.debug("All {} built-in workflow templates already exist", existing.size());
            return;
        }

        if (existing.isEmpty()) {
            // Fresh install — seed all
            for (WorkflowTemplate template : allBuiltIn) {
                templateRepository.save(template);
            }
            log.info("Seeded {} built-in workflow templates", allBuiltIn.size());
        } else {
            // Partial state — seed missing ones
            List<String> existingIds = existing.stream().map(WorkflowTemplate::getId).toList();
            int seeded = 0;
            for (WorkflowTemplate template : allBuiltIn) {
                if (!existingIds.contains(template.getId())) {
                    templateRepository.save(template);
                    seeded++;
                }
            }
            log.info("Seeded {} new built-in workflow templates ({} already existed)", seeded, existing.size());
        }
    }
}

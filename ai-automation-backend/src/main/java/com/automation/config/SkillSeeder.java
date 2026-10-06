package com.automation.config;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillChangeSource;
import com.automation.repository.SkillRepository;
import com.automation.service.skill.SkillMutationGateway;
import com.automation.service.skill.SkillRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the shipped built-in skills from {@code classpath:skills/builtin/*.json}
 * (see {@link BuiltInSkillLoader}).
 *
 * <p>Version-based like {@link WorkflowTemplateSeeder}: missing skills are created,
 * existing ones are only updated on a higher resource version. User skills
 * ({@code builtin=false}) are never touched; built-ins that no longer exist in the
 * resources are kept and merely registered.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillSeeder implements ApplicationRunner {

    private final SkillRepository skillRepository;
    private final SkillRegistry skillRegistry;
    private final SkillMutationGateway mutationGateway;
    private final BuiltInSkillLoader skillLoader;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Skill> existing = skillRepository.findByBuiltin(true);
        Map<String, Skill> existingById = new LinkedHashMap<>();
        for (Skill skill : existing) {
            existingById.putIfAbsent(skill.getId(), skill);
        }

        Set<String> handled = new HashSet<>();
        int seeded = 0;
        int updated = 0;

        for (Skill fresh : skillLoader.loadAll()) {
            handled.add(fresh.getId());
            Skill dbSkill = existingById.get(fresh.getId());

            if (dbSkill == null) {
                skillRegistry.register(mutationGateway.save(fresh, SkillChangeSource.BUILTIN_SEED));
                seeded++;
            } else if (SeedVersions.isNewer(fresh.getVersion(), dbSkill.getVersion())) {
                applyResourceState(dbSkill, fresh);
                skillRegistry.register(mutationGateway.save(dbSkill, SkillChangeSource.BUILTIN_SEED));
                updated++;
            } else {
                skillRegistry.register(dbSkill);
            }
        }

        // Built-ins in the DB that do not (or no longer) exist in the resources: leave untouched,
        // but keep registering them so that their capabilities stay callable.
        for (Skill skill : existing) {
            if (!handled.contains(skill.getId())) {
                skillRegistry.register(skill);
            }
        }

        if (seeded > 0 || updated > 0) {
            log.info("Built-in skills: seeded {}, updated {} ({} already present)", seeded, updated, existing.size());
        } else {
            log.debug("All {} built-in skills up to date", existing.size());
        }
    }

    /** Transfers the resource state to the DB skill, including capabilities. */
    private void applyResourceState(Skill dbSkill, Skill fresh) {
        dbSkill.setName(fresh.getName());
        dbSkill.setIcon(fresh.getIcon());
        dbSkill.setDescription(fresh.getDescription());
        dbSkill.setCategory(fresh.getCategory());
        dbSkill.setAiContext(fresh.getAiContext());
        dbSkill.setVersion(fresh.getVersion());

        // orphanRemoval=true: die vorhandene Collection mutieren, nicht ersetzen.
        List<Capability> target = dbSkill.getCapabilities();
        target.clear();
        for (Capability c : fresh.getCapabilities()) {
            c.setSkill(dbSkill);
            target.add(c);
        }
    }
}

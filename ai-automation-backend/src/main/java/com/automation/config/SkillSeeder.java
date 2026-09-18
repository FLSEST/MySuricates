package com.automation.config;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.repository.SkillRepository;
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
 * Seedet die mitgelieferten Built-in-Skills aus {@code classpath:skills/builtin/*.json}
 * (siehe {@link BuiltInSkillLoader}).
 *
 * <p>Versionsbasiert wie {@link WorkflowTemplateSeeder}: fehlende Skills werden angelegt,
 * vorhandene nur bei hoeherer Ressourcen-Version aktualisiert. Nutzer-Skills
 * ({@code builtin=false}) werden nie angefasst; Built-ins, die es in den Ressourcen nicht
 * mehr gibt, bleiben erhalten und werden lediglich registriert.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillSeeder implements ApplicationRunner {

    private final SkillRepository skillRepository;
    private final SkillRegistry skillRegistry;
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
                skillRegistry.register(skillRepository.save(fresh));
                seeded++;
            } else if (SeedVersions.isNewer(fresh.getVersion(), dbSkill.getVersion())) {
                applyResourceState(dbSkill, fresh);
                skillRegistry.register(skillRepository.save(dbSkill));
                updated++;
            } else {
                skillRegistry.register(dbSkill);
            }
        }

        // Built-ins in der DB, die es in den Ressourcen nicht (mehr) gibt: unangetastet lassen,
        // aber weiterhin registrieren, damit ihre Capabilities aufrufbar bleiben.
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

    /** Uebertraegt den Ressourcen-Stand auf den DB-Skill, inklusive Capabilities. */
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

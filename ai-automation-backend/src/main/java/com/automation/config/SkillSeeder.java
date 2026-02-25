package com.automation.config;

import com.automation.entity.Skill;
import com.automation.repository.SkillRepository;
import com.automation.service.skill.SkillRegistry;
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
public class SkillSeeder implements ApplicationRunner {

    private final SkillRepository skillRepository;
    private final SkillRegistry skillRegistry;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Skill> existing = skillRepository.findByBuiltin(true);
        List<Skill> allBuiltIn = BuiltInSkills.getAll();

        if (existing.size() >= allBuiltIn.size()) {
            log.debug("All {} built-in skills already exist", existing.size());
            // Register existing DB skills in the in-memory registry
            for (Skill skill : existing) {
                skillRegistry.register(skill);
            }
            return;
        }

        if (existing.isEmpty()) {
            // Fresh install — seed all
            for (Skill skill : allBuiltIn) {
                Skill saved = skillRepository.save(skill);
                skillRegistry.register(saved);
            }
            log.info("Seeded {} built-in skills", allBuiltIn.size());
        } else {
            // Partial state — seed missing ones
            List<String> existingIds = existing.stream().map(Skill::getId).toList();
            int seeded = 0;
            for (Skill skill : allBuiltIn) {
                if (!existingIds.contains(skill.getId())) {
                    Skill saved = skillRepository.save(skill);
                    skillRegistry.register(saved);
                    seeded++;
                } else {
                    // Register existing skill in the registry
                    Skill existingSkill = existing.stream()
                            .filter(s -> s.getId().equals(skill.getId()))
                            .findFirst().orElse(skill);
                    skillRegistry.register(existingSkill);
                }
            }
            log.info("Seeded {} new built-in skills ({} already existed)", seeded, existing.size());
        }
    }
}

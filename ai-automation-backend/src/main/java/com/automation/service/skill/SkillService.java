package com.automation.service.skill;

import com.automation.dto.request.CreateSkillRequest;
import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.CapabilityRepository;
import com.automation.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SkillService {

    private final SkillRepository skillRepository;
    private final CapabilityRepository capabilityRepository;
    private final SkillRegistry skillRegistry;

    @Transactional(readOnly = true)
    public List<Skill> findAll() {
        return skillRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Skill findById(String id) {
        return skillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Skill", id));
    }

    @Transactional(readOnly = true)
    public List<Skill> findByCategory(SkillCategory category) {
        return skillRepository.findByCategory(category);
    }

    @Transactional(readOnly = true)
    public List<Capability> findCapabilities(String skillId) {
        findById(skillId); // Verify skill exists
        return capabilityRepository.findBySkillId(skillId);
    }

    @Transactional
    public Skill create(CreateSkillRequest request) {
        Skill skill = Skill.builder()
                .name(request.name())
                .description(request.description())
                .icon(request.icon())
                .category(request.category())
                .aiContext(request.aiContext())
                .configSchema(request.configSchema())
                .builtin(false)
                .build();

        Skill saved = skillRepository.save(skill);
        skillRegistry.register(saved);
        log.info("Created custom skill: {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(String id) {
        Skill skill = findById(id);
        if (skill.isBuiltin()) {
            throw new IllegalStateException("Cannot delete built-in skill: " + skill.getName());
        }
        skillRepository.delete(skill);
        skillRegistry.unregister(id);
        log.info("Deleted skill: {} ({})", skill.getName(), skill.getId());
    }
}

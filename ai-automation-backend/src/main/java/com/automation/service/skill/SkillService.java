package com.automation.service.skill;

import com.automation.dto.request.CreateSkillRequest;
import com.automation.dto.request.UpdateSkillRequest;
import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.SkillChangeSource;
import com.automation.entity.SkillVersion;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.CapabilityRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.SkillVersionRepository;
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
    private final SkillMutationGateway mutationGateway;
    private final SkillVersionRepository versionRepository;

    @Transactional(readOnly = true)
    public List<Skill> findAll() {
        List<Skill> skills = skillRepository.findAll();
        // Force-initialize lazy capabilities while still in transaction (OSIV is off)
        skills.forEach(s -> s.getCapabilities().size());
        return skills;
    }

    @Transactional(readOnly = true)
    public Skill findById(String id) {
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Skill", id));
        skill.getCapabilities().size();
        return skill;
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

        Skill saved = mutationGateway.save(skill, SkillChangeSource.USER);
        skillRegistry.register(saved);
        log.info("Created custom skill: {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    /**
     * Edits a custom skill. Built-ins stay read-only: the next bundled version would silently
     * overwrite the edit. A skill used by a sealed workflow is refused by the gateway.
     */
    @Transactional
    public Skill update(String id, UpdateSkillRequest request) {
        Skill skill = findById(id);
        if (skill.isBuiltin()) {
            throw new IllegalStateException("Cannot edit built-in skill: " + skill.getName());
        }
        skill.setName(request.name());
        skill.setDescription(request.description());
        skill.setIcon(request.icon());
        skill.setCategory(request.category());
        skill.setAiContext(request.aiContext());

        Skill saved = mutationGateway.save(skill, SkillChangeSource.USER);
        skillRegistry.unregister(id);
        skillRegistry.register(saved);
        log.info("Updated custom skill: {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    /** Change history, newest first — also for skills that have since been deleted. */
    @Transactional(readOnly = true)
    public List<SkillVersion> findVersions(String skillId) {
        return versionRepository.findBySkillIdOrderByVersionNumberDesc(skillId);
    }

    @Transactional
    public void delete(String id) {
        Skill skill = findById(id);
        if (skill.isBuiltin()) {
            throw new IllegalStateException("Cannot delete built-in skill: " + skill.getName());
        }
        mutationGateway.delete(skill, SkillChangeSource.USER);
        skillRegistry.unregister(id);
        log.info("Deleted skill: {} ({})", skill.getName(), skill.getId());
    }
}

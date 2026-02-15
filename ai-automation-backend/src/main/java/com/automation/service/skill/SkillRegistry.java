package com.automation.service.skill;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.skills.SkillProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SkillRegistry {

    private final List<SkillProvider> skillProviders;
    private final CapabilityRegistry capabilityRegistry;

    private final Map<String, Skill> registeredSkills = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (SkillProvider provider : skillProviders) {
            Skill skill = provider.provide();
            register(skill);
            log.info("Registered built-in skill: {} ({})", skill.getName(), skill.getId());
        }
    }

    public void register(Skill skill) {
        registeredSkills.put(skill.getId(), skill);

        if (skill.getCapabilities() != null) {
            for (Capability capability : skill.getCapabilities()) {
                capabilityRegistry.register(skill.getId(), capability);
            }
        }
    }

    public void unregister(String skillId) {
        Skill removed = registeredSkills.remove(skillId);
        if (removed != null && removed.getCapabilities() != null) {
            for (Capability capability : removed.getCapabilities()) {
                capabilityRegistry.unregister(skillId, capability.getName());
            }
        }
    }

    public Optional<Skill> findById(String skillId) {
        return Optional.ofNullable(registeredSkills.get(skillId));
    }

    public List<Skill> getAllRegistered() {
        return new ArrayList<>(registeredSkills.values());
    }

    public String getAggregatedAIContext(List<Skill> skills) {
        return skills.stream()
                .filter(s -> s.getAiContext() != null && !s.getAiContext().isBlank())
                .map(s -> "=== Skill: " + s.getName() + " ===\n" + s.getAiContext())
                .collect(Collectors.joining("\n\n"));
    }
}

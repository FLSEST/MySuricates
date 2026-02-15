package com.automation.service.skill;

import com.automation.entity.Capability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class CapabilityRegistry {

    // Key: "skillId:capabilityName"
    private final Map<String, Capability> capabilities = new ConcurrentHashMap<>();

    public void register(String skillId, Capability capability) {
        String key = buildKey(skillId, capability.getName());
        capabilities.put(key, capability);
        log.debug("Registered capability: {}", key);
    }

    public void unregister(String skillId, String capabilityName) {
        String key = buildKey(skillId, capabilityName);
        capabilities.remove(key);
    }

    public Optional<Capability> find(String skillId, String capabilityName) {
        return Optional.ofNullable(capabilities.get(buildKey(skillId, capabilityName)));
    }

    public List<Capability> findBySkill(String skillId) {
        return capabilities.entrySet().stream()
                .filter(e -> e.getKey().startsWith(skillId + ":"))
                .map(Map.Entry::getValue)
                .toList();
    }

    public List<Capability> findByName(String capabilityName) {
        return capabilities.entrySet().stream()
                .filter(e -> e.getKey().endsWith(":" + capabilityName))
                .map(Map.Entry::getValue)
                .toList();
    }

    private String buildKey(String skillId, String capabilityName) {
        return skillId + ":" + capabilityName;
    }
}

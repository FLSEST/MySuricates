package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.repository.SkillRepository;
import com.automation.service.skill.SkillContentHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Content hash per skill a workflow uses — its activated skills plus the per-block {@code skillIds}
 * (the executor resolves exactly these). The definition hash only covers the IDs; this map is what
 * a seal additionally pins so that a changed skill cannot alter a sealed workflow unnoticed.
 * A skill that no longer exists maps to {@link #MISSING}.
 */
@Component
@RequiredArgsConstructor
public class WorkflowSkillPins {

    static final String MISSING = "missing";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SkillRepository skillRepository;
    private final SkillContentHasher contentHasher;

    /** skill id → content hash, sorted by id. Loads the skills with their capabilities in one query. */
    public Map<String, String> pins(Workflow workflow) {
        TreeSet<String> ids = new TreeSet<>();
        if (workflow.getActivatedSkills() != null) {
            workflow.getActivatedSkills().stream().map(Skill::getId).filter(Objects::nonNull).forEach(ids::add);
        }
        if (workflow.getBlocks() != null) {
            for (BlockDefinition block : workflow.getBlocks()) {
                if (block.getSkillIds() != null) {
                    block.getSkillIds().stream().filter(Objects::nonNull).forEach(ids::add);
                }
            }
        }
        Map<String, String> pins = new TreeMap<>();
        if (ids.isEmpty()) {
            return pins;
        }
        Map<String, Skill> found = skillRepository.findAllByIdInWithCapabilities(ids).stream()
                .collect(Collectors.toMap(Skill::getId, s -> s, (a, b) -> a));
        for (String id : ids) {
            Skill skill = found.get(id);
            pins.put(id, skill != null ? contentHasher.hash(skill) : MISSING);
        }
        return pins;
    }

    /** Canonical JSON of a pin map, as stored in {@code workflows.sealed_skill_hashes}. */
    public static String toJson(Map<String, String> pins) {
        try {
            return JSON.writeValueAsString(new TreeMap<>(pins));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Skill pins not serialisable", e);
        }
    }

    public static Map<String, String> fromJson(String json) {
        try {
            return JSON.readValue(json, new TypeReference<TreeMap<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored skill pins are not readable: " + e.getOriginalMessage(), e);
        }
    }

    /** IDs whose pin differs between the sealed and the current state (added, removed or changed). */
    public static TreeSet<String> changed(Map<String, String> sealed, Map<String, String> current) {
        TreeSet<String> changed = new TreeSet<>();
        for (String id : sealed.keySet()) {
            if (!Objects.equals(sealed.get(id), current.get(id))) {
                changed.add(id);
            }
        }
        for (String id : current.keySet()) {
            if (!sealed.containsKey(id)) {
                changed.add(id);
            }
        }
        return changed;
    }
}

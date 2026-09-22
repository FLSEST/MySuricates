package com.automation.service.skill;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SHA-256 over the canonical JSON of what a skill contributes to a run: its name and {@code aiContext}
 * (written into the prompt), its config schema, and per capability name, description (part of the
 * block prompt), schemas, handler, async and writes flag. Labels — description, icon, category,
 * version, builtin — are not part of the hash, just as a workflow's name is not part of its
 * definition hash.
 *
 * <p>Canonical form as in {@code WorkflowDefinitionHasher}: sorted properties and map keys, empty
 * values omitted, capabilities sorted by name. A private mapper so Spring's Jackson customisation
 * can never change a hash. {@code SkillContentHasherTest} pins a known vector.
 */
@Component
public class SkillContentHasher {

    static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .serializationInclusion(JsonInclude.Include.NON_EMPTY)
            .build();

    record CapabilityContent(String name, String description, String handlerClass, Map<String, Object> inputSchema,
                             Map<String, Object> outputSchema, boolean async, boolean writes) {}

    record SkillContent(String id, String name, String aiContext, Map<String, Object> configSchema,
                        List<CapabilityContent> capabilities) {}

    public String hash(Skill skill) {
        return sha256(canonicalJson(skill));
    }

    /** Canonical JSON of the executable content — the input of {@link #hash(Skill)}. */
    public String canonicalJson(Skill skill) {
        return write(content(skill));
    }

    /** Canonical JSON of the whole skill, labels included — what a version row stores. */
    public String snapshotJson(Skill skill) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("content", content(skill));
        snapshot.put("description", skill.getDescription());
        snapshot.put("icon", skill.getIcon());
        snapshot.put("category", skill.getCategory());
        snapshot.put("version", skill.getVersion());
        snapshot.put("builtin", skill.isBuiltin());
        return write(snapshot);
    }

    /** SHA-256 over the canonical JSON of any value (maps are written with sorted keys). */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static SkillContent content(Skill skill) {
        List<CapabilityContent> capabilities = skill.getCapabilities() == null ? List.of()
                : skill.getCapabilities().stream()
                        .map(SkillContentHasher::content)
                        .sorted(Comparator.comparing(CapabilityContent::name, Comparator.nullsFirst(Comparator.naturalOrder())))
                        .toList();
        return new SkillContent(skill.getId(), skill.getName(), skill.getAiContext(), skill.getConfigSchema(), capabilities);
    }

    private static CapabilityContent content(Capability c) {
        return new CapabilityContent(c.getName(), c.getDescription(), c.getHandlerClass(), c.getInputSchema(),
                c.getOutputSchema(), c.isAsync(), c.isWrites());
    }

    private static String write(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Skill is not serialisable: " + e.getOriginalMessage(), e);
        }
    }
}

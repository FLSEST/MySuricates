package com.automation.config;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builder helpers shared by the {@code BuiltIn*Skills} category classes.
 */
final class BuiltInSkillSupport {

    private BuiltInSkillSupport() {}

    static Skill skill(String id, String name, String icon, String description,
                                SkillCategory category, String aiContext,
                                List<Capability> capabilities) {
        Skill s = Skill.builder()
                .id(id)
                .name(name)
                .icon(icon)
                .description(description)
                .category(category)
                .aiContext(aiContext)
                .builtin(true)
                .version("1.0.0")
                .build();
        for (Capability c : capabilities) {
            c.setSkill(s);
        }
        s.setCapabilities(new ArrayList<>(capabilities));
        return s;
    }

    static Capability cap(String name, String description, String handlerClass,
                                   Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        return cap(name, description, handlerClass, inputSchema, outputSchema, false);
    }

    static Capability cap(String name, String description, String handlerClass,
                                   Map<String, Object> inputSchema, Map<String, Object> outputSchema,
                                   boolean async) {
        return Capability.builder()
                .name(name)
                .description(description)
                .handlerClass(handlerClass)
                .inputSchema(inputSchema)
                .outputSchema(outputSchema)
                .async(async)
                .build();
    }

    /**
     * Capability variant that writes to the project filesystem. Workflows referencing
     * such a capability must explicitly opt in via {@code allow_writes: true} in the
     * skill-capability block config; otherwise the block fails fast.
     */
    static Capability capWrites(String name, String description, String handlerClass,
                                         Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        Capability c = cap(name, description, handlerClass, inputSchema, outputSchema, false);
        c.setWrites(true);
        return c;
    }
}

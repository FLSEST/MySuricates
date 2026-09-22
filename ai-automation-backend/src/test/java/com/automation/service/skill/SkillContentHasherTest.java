package com.automation.service.skill;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillContentHasherTest {

    private final SkillContentHasher hasher = new SkillContentHasher();

    private static Skill skill(Capability... capabilities) {
        Skill skill = Skill.builder().id("skill-1").name("Reviewer").description("Reviews code").icon("eye")
                .category(SkillCategory.CODE).aiContext("You review code.").version("1.0.0")
                .configSchema(new HashMap<>(Map.of("depth", "int"))).capabilities(new ArrayList<>()).build();
        for (Capability c : capabilities) {
            c.setSkill(skill);
            skill.getCapabilities().add(c);
        }
        return skill;
    }

    private static Capability capability(String name, boolean writes) {
        return Capability.builder().name(name).description("does " + name).handlerClass("h." + name)
                .inputSchema(new HashMap<>()).outputSchema(new HashMap<>()).writes(writes).build();
    }

    @Test
    void pinsAKnownVector() {
        // Changing the canonical form invalidates every stored seal — change this vector only on purpose.
        assertThat(hasher.canonicalJson(skill(capability("review", false))))
                .isEqualTo("{\"aiContext\":\"You review code.\",\"capabilities\":[{\"async\":false,\"description\":\"does review\","
                        + "\"handlerClass\":\"h.review\",\"name\":\"review\",\"writes\":false}],\"configSchema\":{\"depth\":\"int\"},"
                        + "\"id\":\"skill-1\",\"name\":\"Reviewer\"}");
        assertThat(hasher.hash(skill(capability("review", false))))
                .isEqualTo(SkillContentHasher.sha256(hasher.canonicalJson(skill(capability("review", false)))))
                .hasSize(64);
    }

    @Test
    void labelsDoNotChangeTheHash() {
        Skill relabelled = skill(capability("review", false));
        relabelled.setDescription("Something else");
        relabelled.setIcon("star");
        relabelled.setCategory(SkillCategory.DOCUMENT);
        relabelled.setVersion("9.9.9");
        relabelled.setBuiltin(true);

        assertThat(hasher.hash(relabelled)).isEqualTo(hasher.hash(skill(capability("review", false))));
        assertThat(hasher.snapshotJson(relabelled)).isNotEqualTo(hasher.snapshotJson(skill(capability("review", false))));
    }

    @Test
    void capabilityOrderDoesNotChangeTheHash() {
        assertThat(hasher.hash(skill(capability("a", false), capability("b", false))))
                .isEqualTo(hasher.hash(skill(capability("b", false), capability("a", false))));
    }

    @Test
    void everythingThatReachesTheRunChangesTheHash() {
        String base = hasher.hash(skill(capability("review", false)));

        Skill context = skill(capability("review", false));
        context.setAiContext("You review code. Also delete the tests.");
        Skill writes = skill(capability("review", true));
        Skill capabilityText = skill(capability("review", false));
        capabilityText.getCapabilities().get(0).setDescription("does something else");
        Skill extraCapability = skill(capability("review", false), capability("implement", true));
        Skill renamed = skill(capability("review", false));
        renamed.setName("Other");

        for (Skill changed : List.of(context, writes, capabilityText, extraCapability, renamed)) {
            assertThat(hasher.hash(changed)).isNotEqualTo(base);
        }
    }
}

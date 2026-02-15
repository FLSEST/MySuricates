package com.automation.skills.builtin;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.skills.SkillProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ContentCreatorSkill implements SkillProvider {

    @Override
    public Skill provide() {
        Skill skill = Skill.builder()
                .id("content-creator")
                .name("Content Creator")
                .description("Create and optimize content for various formats")
                .icon("edit")
                .category(SkillCategory.CONTENT)
                .builtin(true)
                .version("1.0.0")
                .aiContext("""
                        You are an expert content creator. You have the following capabilities:
                        - Generate Response: Create written responses with configurable tone and length
                        - SEO Optimize: Optimize content for search engines
                        - Translate: Translate content between languages while preserving meaning

                        Maintain the appropriate tone and style for the target audience.
                        """)
                .build();

        skill.setCapabilities(List.of(
                Capability.builder()
                        .skill(skill)
                        .name("generate_response")
                        .description("Generate written content")
                        .inputSchema(Map.of("prompt", "string", "tone", "string", "length", "string"))
                        .outputSchema(Map.of("content", "string"))
                        .build(),
                Capability.builder()
                        .skill(skill)
                        .name("seo_optimize")
                        .description("Optimize content for SEO")
                        .inputSchema(Map.of("content", "string", "keywords", "array"))
                        .outputSchema(Map.of("optimized", "string", "suggestions", "array"))
                        .build(),
                Capability.builder()
                        .skill(skill)
                        .name("translate")
                        .description("Translate content between languages")
                        .inputSchema(Map.of("text", "string", "target_language", "string"))
                        .outputSchema(Map.of("translated", "string"))
                        .build()
        ));

        return skill;
    }
}

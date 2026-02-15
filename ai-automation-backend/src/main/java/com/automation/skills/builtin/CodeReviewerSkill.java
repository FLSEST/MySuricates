package com.automation.skills.builtin;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.skills.SkillProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CodeReviewerSkill implements SkillProvider {

    @Override
    public Skill provide() {
        Skill skill = Skill.builder()
                .id("code-reviewer")
                .name("Code Reviewer")
                .description("Review code for bugs, security issues, and quality")
                .icon("code")
                .category(SkillCategory.CODE)
                .builtin(true)
                .version("1.0.0")
                .aiContext("""
                        You are an expert code reviewer. You have the following capabilities:
                        - Lint: Analyze code for style issues, anti-patterns, and best practice violations
                        - Security Scan: Identify security vulnerabilities (OWASP Top 10, injection, XSS, etc.)
                        - Complexity Analysis: Measure cyclomatic complexity and suggest simplifications
                        - Code Analysis: General code review for bugs, performance, and maintainability

                        Provide specific, actionable feedback with line references when possible.
                        """)
                .build();

        skill.setCapabilities(List.of(
                buildCapability(skill, "lint", "Analyze code for style issues",
                        "com.automation.skills.handlers.code.LintHandler",
                        Map.of("code", "string", "language", "string"),
                        Map.of("issues", "array")),
                buildCapability(skill, "security_scan", "Identify security vulnerabilities",
                        "com.automation.skills.handlers.code.SecurityScanHandler",
                        Map.of("code", "string"),
                        Map.of("vulnerabilities", "array")),
                buildCapability(skill, "complexity_analysis", "Measure code complexity",
                        null,
                        Map.of("code", "string"),
                        Map.of("score", "number", "suggestions", "array")),
                buildCapability(skill, "analyze_code", "General code review",
                        null,
                        Map.of("code", "string", "focus", "array"),
                        Map.of("review", "string"))
        ));

        return skill;
    }

    private Capability buildCapability(Skill skill, String name, String description,
                                        String handlerClass,
                                        Map<String, Object> inputSchema,
                                        Map<String, Object> outputSchema) {
        return Capability.builder()
                .skill(skill)
                .name(name)
                .description(description)
                .handlerClass(handlerClass)
                .inputSchema(inputSchema)
                .outputSchema(outputSchema)
                .build();
    }
}

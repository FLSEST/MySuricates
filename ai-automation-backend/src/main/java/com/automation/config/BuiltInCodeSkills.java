package com.automation.config;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.config.BuiltInSkillSupport.cap;
import static com.automation.config.BuiltInSkillSupport.capWrites;
import static com.automation.config.BuiltInSkillSupport.skill;

final class BuiltInCodeSkills {

    private BuiltInCodeSkills() {}

    static List<Skill> all() {
        List<Skill> skills = new ArrayList<>();

        // ── CODE ──────────────────────────────────

        skills.add(skill("builtin-code-review", "Code Review", "🔍",
                "Automated code review that identifies bugs, style issues, and suggests improvements.",
                SkillCategory.CODE,
                """
                You are a senior code reviewer. Analyze the provided code for:
                - Bugs and logic errors
                - Security vulnerabilities (SQL injection, XSS, etc.)
                - Performance issues and inefficiencies
                - Code style and readability
                - Adherence to best practices and design patterns

                Provide specific, actionable feedback with line references. Categorize issues by severity (critical, warning, info). Suggest concrete fixes with code examples where appropriate.""",
                List.of(
                        cap("review_code", "Perform a comprehensive code review on the provided source code",
                                null,
                                Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"), "language", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("issues", Map.of("type", "array"), "summary", Map.of("type", "string")))),
                        cap("suggest_fixes", "Generate fix suggestions for identified code issues",
                                null,
                                Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"), "issues", Map.of("type", "array"))),
                                Map.of("type", "object", "properties", Map.of("fixes", Map.of("type", "array")))),
                        cap("lint", "Analyze code for style issues, anti-patterns, and best practice violations",
                                "com.automation.skills.handlers.code.LintHandler",
                                Map.of("code", "string", "language", "string"),
                                Map.of("issues", "array")),
                        cap("security_scan", "Identify security vulnerabilities (OWASP Top 10, injection, XSS)",
                                "com.automation.skills.handlers.code.SecurityScanHandler",
                                Map.of("code", "string"),
                                Map.of("vulnerabilities", "array"))
                )));

        skills.add(skill("builtin-test-generator", "Test Generator", "🧪",
                "Automatically generates unit and integration tests for your code.",
                SkillCategory.CODE,
                """
                You are a testing expert. Generate comprehensive tests for the provided code:
                - Write unit tests covering all functions and edge cases
                - Include positive, negative, and boundary test cases
                - Generate integration tests for component interactions
                - Use appropriate mocking strategies for external dependencies
                - Follow the testing framework conventions of the target language (JUnit, pytest, Jest, etc.)

                Output well-structured, runnable test code with clear test names describing the expected behavior.""",
                List.of(
                        capWrites("generate_unit_tests", "Generate unit tests for the provided code",
                                null,
                                Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"), "framework", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("tests", Map.of("type", "string")))),
                        capWrites("generate_integration_tests", "Generate integration tests for component interactions",
                                null,
                                Map.of("type", "object", "properties", Map.of("components", Map.of("type", "array"))),
                                Map.of("type", "object", "properties", Map.of("tests", Map.of("type", "string"))))
                )));

        skills.add(skill("builtin-security-analyzer", "Security Analyzer", "🛡️",
                "Scans code and dependencies for security vulnerabilities and leaked secrets.",
                SkillCategory.CODE,
                """
                You are a security analyst. Perform thorough security analysis:
                - Scan for OWASP Top 10 vulnerabilities
                - Detect hardcoded secrets, API keys, and credentials
                - Audit dependency versions for known CVEs
                - Check for insecure configurations
                - Identify authentication and authorization weaknesses

                Report findings with severity levels (critical, high, medium, low), CVSS scores where applicable, and remediation steps.""",
                List.of(
                        cap("scan_vulnerabilities", "Scan source code for security vulnerabilities",
                                null,
                                Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("vulnerabilities", Map.of("type", "array")))),
                        cap("check_secrets", "Detect hardcoded secrets and credentials in code",
                                null,
                                Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("secrets", Map.of("type", "array")))),
                        cap("audit_dependencies", "Audit project dependencies for known vulnerabilities",
                                null,
                                Map.of("type", "object", "properties", Map.of("dependencies", Map.of("type", "object"))),
                                Map.of("type", "object", "properties", Map.of("advisories", Map.of("type", "array"))))
                )));

        return skills;
    }
}

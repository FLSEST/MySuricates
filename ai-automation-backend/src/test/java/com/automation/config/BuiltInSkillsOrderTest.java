package com.automation.config;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the BuiltInSkills category split (Task 5, clean-code-3d) and for the
 * move to {@code classpath:skills/builtin/} (TODO 9fcb8ecf): the exact id order and the exact
 * set of write-enabled capabilities must survive both refactorings byte-for-byte.
 */
class BuiltInSkillsOrderTest {

    private static List<Skill> builtInSkills() {
        return new BuiltInSkillLoader(new ObjectMapper()).loadAll();
    }

    @Test
    void getAll_returnsExactlyTheseIdsInThisOrder() {
        List<Skill> skills = builtInSkills();

        assertThat(skills.stream().map(Skill::getId)).containsExactly(
                "builtin-code-review",
                "builtin-test-generator",
                "builtin-security-analyzer",
                "builtin-grammar-style",
                "builtin-document-translator",
                "builtin-smart-summarizer",
                "builtin-document-analyzer",
                "builtin-data-analyzer",
                "builtin-report-generator",
                "builtin-blog-writer",
                "builtin-email-drafter",
                "builtin-social-media",
                "builtin-research-assistant",
                "builtin-fact-checker",
                "builtin-meeting-notes",
                "builtin-risk-assessor",
                "builtin-spec-architect",
                "builtin-feature-implementer",
                "builtin-marketing-copywriter"
        );
    }

    @Test
    void writesFlag_onlyOnExpectedCapabilities() {
        List<Skill> skills = builtInSkills();

        List<String> writeCapabilityNames = skills.stream()
                .flatMap(s -> s.getCapabilities().stream())
                .filter(Capability::isWrites)
                .map(Capability::getName)
                .toList();

        assertThat(writeCapabilityNames).containsExactlyInAnyOrder(
                "generate_unit_tests",
                "generate_integration_tests",
                "implement_feature",
                "write_tests",
                "apply_routes_glue"
        );
    }
}

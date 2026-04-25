package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillCategoryTest {

    @Test
    void should_haveAllSkillCategoryValuesInDeclaredOrder() {
        assertThat(SkillCategory.values())
                .containsExactly(
                        SkillCategory.DOCUMENT,
                        SkillCategory.CODE,
                        SkillCategory.DATA,
                        SkillCategory.CONTENT,
                        SkillCategory.RESEARCH,
                        SkillCategory.BUSINESS,
                        SkillCategory.PRODUCTIVITY
                );
    }

    @Test
    void should_haveSevenValues() {
        assertThat(SkillCategory.values()).hasSize(7);
    }

    @Test
    void should_resolveDocumentViaValueOf() {
        assertThat(SkillCategory.valueOf("DOCUMENT")).isEqualTo(SkillCategory.DOCUMENT);
    }

    @Test
    void should_resolveCodeViaValueOf() {
        assertThat(SkillCategory.valueOf("CODE")).isEqualTo(SkillCategory.CODE);
    }

    @Test
    void should_resolveDataViaValueOf() {
        assertThat(SkillCategory.valueOf("DATA")).isEqualTo(SkillCategory.DATA);
    }

    @Test
    void should_resolveContentViaValueOf() {
        assertThat(SkillCategory.valueOf("CONTENT")).isEqualTo(SkillCategory.CONTENT);
    }

    @Test
    void should_resolveResearchViaValueOf() {
        assertThat(SkillCategory.valueOf("RESEARCH")).isEqualTo(SkillCategory.RESEARCH);
    }

    @Test
    void should_resolveBusinessViaValueOf() {
        assertThat(SkillCategory.valueOf("BUSINESS")).isEqualTo(SkillCategory.BUSINESS);
    }

    @Test
    void should_resolveProductivityViaValueOf() {
        assertThat(SkillCategory.valueOf("PRODUCTIVITY")).isEqualTo(SkillCategory.PRODUCTIVITY);
    }

    @Test
    void should_throwException_when_valueOfReceivesInvalidName() {
        assertThatThrownBy(() -> SkillCategory.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_returnCorrectNameForEachValue() {
        assertThat(SkillCategory.DOCUMENT.name()).isEqualTo("DOCUMENT");
        assertThat(SkillCategory.CODE.name()).isEqualTo("CODE");
        assertThat(SkillCategory.DATA.name()).isEqualTo("DATA");
        assertThat(SkillCategory.CONTENT.name()).isEqualTo("CONTENT");
        assertThat(SkillCategory.RESEARCH.name()).isEqualTo("RESEARCH");
        assertThat(SkillCategory.BUSINESS.name()).isEqualTo("BUSINESS");
        assertThat(SkillCategory.PRODUCTIVITY.name()).isEqualTo("PRODUCTIVITY");
    }
}

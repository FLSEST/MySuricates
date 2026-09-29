package com.automation.mapper;

import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.entity.BlockDefinition;
import com.automation.entity.TemplateKind;
import com.automation.entity.WorkflowTemplate;
import com.automation.util.LocalizedJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Zweisprachige Vorlagen kommen in der gewünschten Sprache zurück, einsprachige unverändert. */
class TemplateResponseLocalizerTest {

    private static final String SOURCE;

    static {
        try {
            SOURCE = LocalizedJson.localizedSource(new ObjectMapper().readTree("""
                    {"i18n": ["de", "en"],
                     "instructions": {"de": "Arbeite gründlich.", "en": "Work carefully."},
                     "blocks": [{"type": "shell", "name": {"de": "messen", "en": "measure"},
                                 "config": {"command": "date"}}]}"""));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void bilingualTemplate_getsBlocksAndInstructionsInTheRequestedLanguage() {
        WorkflowTemplate template = template("t-1", SOURCE);
        WorkflowTemplateResponse response = response("t-1");

        WorkflowTemplateResponse de = TemplateResponseLocalizer.localize(template, response, "de-DE");
        WorkflowTemplateResponse en = TemplateResponseLocalizer.localize(template, response, "en");

        assertThat(de.blocks()).extracting(BlockDefinition::getName).containsExactly("messen");
        assertThat(de.instructions()).isEqualTo("Arbeite gründlich.");
        assertThat(en.blocks()).extracting(BlockDefinition::getName).containsExactly("measure");
        assertThat(en.instructions()).isEqualTo("Work carefully.");
        assertThat(de.id()).isEqualTo("t-1");
        assertThat(de.name()).isEqualTo(response.name());
    }

    @Test
    void templateWithoutSource_passesUnchanged() {
        WorkflowTemplateResponse response = response("t-1");

        assertThat(TemplateResponseLocalizer.localize(template("t-1", null), response, "de")).isSameAs(response);
        assertThat(TemplateResponseLocalizer.localize(template("t-1", "  "), response, "de")).isSameAs(response);
    }

    @Test
    void list_localizesMatchingTemplatesAndKeepsUnknownResponses() {
        WorkflowTemplateResponse known = response("t-1");
        WorkflowTemplateResponse plain = response("t-2");
        WorkflowTemplateResponse orphan = response("t-3");

        List<WorkflowTemplateResponse> result = TemplateResponseLocalizer.localize(
                List.of(template("t-1", SOURCE), template("t-2", null)), List.of(known, plain, orphan), "de");

        assertThat(result).hasSize(3);
        assertThat(result.get(0).instructions()).isEqualTo("Arbeite gründlich.");
        assertThat(result.get(1)).isSameAs(plain);
        assertThat(result.get(2)).isSameAs(orphan);
    }

    @Test
    void list_toleratesDuplicateTemplateIds() {
        List<WorkflowTemplateResponse> result = TemplateResponseLocalizer.localize(
                List.of(template("t-1", SOURCE), template("t-1", null)), List.of(response("t-1")), "de");

        assertThat(result.get(0).instructions()).isEqualTo("Arbeite gründlich.");
    }

    private static WorkflowTemplate template(String id, String source) {
        return WorkflowTemplate.builder().id(id).i18nSource(source).build();
    }

    private static WorkflowTemplateResponse response(String id) {
        return new WorkflowTemplateResponse(id, Map.of("en", "Name"), Map.of("en", "Desc"), null, "AI",
                List.of(BlockDefinition.builder().type("shell").name("english-fallback").build()), "fallback",
                null, true, 0, "UNTESTED", List.of(), "1.0.0", null, null, TemplateKind.TEMPLATE, List.of(), false);
    }
}

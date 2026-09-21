package com.automation.mapper;

import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.entity.WorkflowTemplate;
import com.automation.util.LocalizedJson;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Puts a bilingual template's blocks and instructions into the requested language (TODO a129a661).
 * The mapped response carries the English variant; single-language templates pass unchanged.
 */
public final class TemplateResponseLocalizer {

    private TemplateResponseLocalizer() {
    }

    public static WorkflowTemplateResponse localize(WorkflowTemplate template, WorkflowTemplateResponse response,
                                                    String locale) {
        String source = template.getI18nSource();
        if (source == null || source.isBlank()) {
            return response;
        }
        return response.withBlocksAndInstructions(
                LocalizedJson.blocksFor(source, locale, response.blocks()),
                LocalizedJson.instructionsFor(source, locale, response.instructions()));
    }

    public static List<WorkflowTemplateResponse> localize(List<WorkflowTemplate> templates,
                                                          List<WorkflowTemplateResponse> responses, String locale) {
        Map<String, WorkflowTemplate> byId = templates.stream()
                .collect(Collectors.toMap(WorkflowTemplate::getId, Function.identity(), (a, b) -> a));
        return responses.stream()
                .map(r -> byId.containsKey(r.id()) ? localize(byId.get(r.id()), r, locale) : r)
                .toList();
    }
}

package com.automation.service.export.bpmn;

import com.automation.entity.Workflow;
import com.automation.service.export.ProcessFormatAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** BPMN 2.0 als erster ProcessFormatAdapter (Spec §3): deterministisch, 0 Tokens pro Export. */
@Component
@RequiredArgsConstructor
public class BpmnExportAdapter implements ProcessFormatAdapter {

    private final ObjectMapper objectMapper;

    @Override
    public String formatKey() {
        return "bpmn";
    }

    @Override
    public String fileExtension() {
        return "bpmn";
    }

    @Override
    public String contentType() {
        return "application/xml";
    }

    @Override
    public String export(Workflow workflow) {
        BpmnModel model = new BpmnModelBuilder(objectMapper).build(workflow);
        return new BpmnXmlRenderer().render(model);
    }
}

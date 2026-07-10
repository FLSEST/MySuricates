package com.automation.controller;

import com.automation.entity.Workflow;
import com.automation.service.export.ProcessFormatAdapter;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowBpmnExportControllerTest {

    @Mock
    private WorkflowService workflowService;

    @Mock
    private ProcessFormatAdapter bpmnExportAdapter;

    @InjectMocks
    private WorkflowController controller;

    @Test
    void liefertBpmnAlsXmlAttachment() {
        Workflow wf = Workflow.builder().id("wf-1").name("Mein Workflow!").build();
        when(workflowService.findByIdEager("wf-1")).thenReturn(wf);
        when(bpmnExportAdapter.export(wf)).thenReturn("<definitions/>");
        when(bpmnExportAdapter.fileExtension()).thenReturn("bpmn");

        ResponseEntity<String> response = controller.exportWorkflowAsBpmn("wf-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"Mein_Workflow_.bpmn\"");
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/xml");
        assertThat(response.getBody()).isEqualTo("<definitions/>");
    }
}

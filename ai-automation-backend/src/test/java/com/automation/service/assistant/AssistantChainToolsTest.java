package com.automation.service.assistant;

import com.automation.dto.request.CreateChainRequest;
import com.automation.entity.WorkflowChain;
import com.automation.service.chain.ChainService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantChainToolsTest {

    @Mock private ChainService chainService;
    private AssistantChainTools tools;

    @BeforeEach
    void setUp() {
        tools = new AssistantChainTools(chainService, new ObjectMapper());
    }

    @Test
    void createChain_exactSuccessLine_andDefaults() {
        when(chainService.create(any())).thenReturn(WorkflowChain.builder().id("c-1").name("Nightly").build());

        String r = tools.createChain("{\"name\":\"Nightly\",\"entries\":[{\"workflowId\":\"wf-1\"},{\"workflowId\":\"wf-2\",\"sortOrder\":5,\"stopOnFailure\":false,\"label\":\"second\",\"inputContext\":{\"a\":1}}]}");

        assertThat(r).isEqualTo("✓ Chain created: \"Nightly\" with 2 workflows (ID: c-1)");
        ArgumentCaptor<CreateChainRequest> req = ArgumentCaptor.forClass(CreateChainRequest.class);
        verify(chainService).create(req.capture());
        assertThat(req.getValue().icon()).isEqualTo("⛓️");
        assertThat(req.getValue().stopOnFailure()).isTrue();
        assertThat(req.getValue().entries().get(0).sortOrder()).isZero();
        assertThat(req.getValue().entries().get(0).stopOnFailure()).isNull();
        assertThat(req.getValue().entries().get(0).label()).isNull();
        assertThat(req.getValue().entries().get(1).sortOrder()).isEqualTo(5);
        assertThat(req.getValue().entries().get(1).stopOnFailure()).isFalse();
        assertThat(req.getValue().entries().get(1).inputContext()).isEqualTo(Map.of("a", 1));
    }

    @Test
    void createChain_missingName_errorLine() {
        assertThat(tools.createChain("{\"entries\":[{\"workflowId\":\"x\"}]}"))
                .isEqualTo("⚠ create_chain: name is a required field.");
        verify(chainService, never()).create(any());
    }

    @Test
    void createChain_emptyOrMissingEntries_errorLine() {
        assertThat(tools.createChain("{\"name\":\"n\"}")).isEqualTo("⚠ create_chain: entries must not be empty.");
        assertThat(tools.createChain("{\"name\":\"n\",\"entries\":[]}")).isEqualTo("⚠ create_chain: entries must not be empty.");
    }

    @Test
    void createChain_serviceThrows_errorLine() {
        when(chainService.create(any())).thenThrow(new RuntimeException("dup"));

        assertThat(tools.createChain("{\"name\":\"n\",\"entries\":[{\"workflowId\":\"x\"}]}"))
                .isEqualTo("⚠ Could not create chain: dup");
    }
}

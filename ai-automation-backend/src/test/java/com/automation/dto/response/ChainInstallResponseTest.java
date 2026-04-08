package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainInstallResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var ids = List.of("wf-1", "wf-2");
        var response = new ChainInstallResponse("chain-42", "My Chain", ids);

        assertThat(response.chainId()).isEqualTo("chain-42");
        assertThat(response.chainName()).isEqualTo("My Chain");
        assertThat(response.workflowIds()).containsExactly("wf-1", "wf-2");
    }

    @Test
    void should_acceptEmptyWorkflowIds_when_constructed() {
        var response = new ChainInstallResponse("c-1", "Empty Chain", List.of());

        assertThat(response.chainId()).isEqualTo("c-1");
        assertThat(response.chainName()).isEqualTo("Empty Chain");
        assertThat(response.workflowIds()).isEmpty();
    }

    @Test
    void should_acceptNullChainId_when_constructed() {
        var response = new ChainInstallResponse(null, "Name", List.of("wf-1"));

        assertThat(response.chainId()).isNull();
        assertThat(response.chainName()).isEqualTo("Name");
        assertThat(response.workflowIds()).containsExactly("wf-1");
    }

    @Test
    void should_acceptNullChainName_when_constructed() {
        var response = new ChainInstallResponse("c-1", null, List.of("wf-1"));

        assertThat(response.chainId()).isEqualTo("c-1");
        assertThat(response.chainName()).isNull();
    }

    @Test
    void should_acceptNullWorkflowIds_when_constructed() {
        var response = new ChainInstallResponse("c-1", "Name", null);

        assertThat(response.chainId()).isEqualTo("c-1");
        assertThat(response.chainName()).isEqualTo("Name");
        assertThat(response.workflowIds()).isNull();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var response = new ChainInstallResponse(null, null, null);

        assertThat(response.chainId()).isNull();
        assertThat(response.chainName()).isNull();
        assertThat(response.workflowIds()).isNull();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new ChainInstallResponse("c-1", "Chain", List.of("wf-1", "wf-2"));
        var b = new ChainInstallResponse("c-1", "Chain", List.of("wf-1", "wf-2"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentChainId() {
        var a = new ChainInstallResponse("c-1", "Chain", List.of("wf-1"));
        var b = new ChainInstallResponse("c-2", "Chain", List.of("wf-1"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentChainName() {
        var a = new ChainInstallResponse("c-1", "Chain A", List.of("wf-1"));
        var b = new ChainInstallResponse("c-1", "Chain B", List.of("wf-1"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentWorkflowIds() {
        var a = new ChainInstallResponse("c-1", "Chain", List.of("wf-1"));
        var b = new ChainInstallResponse("c-1", "Chain", List.of("wf-2"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothNullFields() {
        var a = new ChainInstallResponse(null, null, null);
        var b = new ChainInstallResponse(null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new ChainInstallResponse("c-42", "My Chain", List.of("wf-1", "wf-2"));
        var str = response.toString();

        assertThat(str).contains("c-42");
        assertThat(str).contains("My Chain");
        assertThat(str).contains("wf-1");
        assertThat(str).contains("wf-2");
    }
}

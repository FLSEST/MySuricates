package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApproveTodosRequestTest {

    // --- Getter / Record semantics ---

    @Test
    void should_returnTrue_when_constructedWithApprovedTrue() {
        var request = new ApproveTodosRequest(true);

        assertThat(request.approved()).isTrue();
    }

    @Test
    void should_returnFalse_when_constructedWithApprovedFalse() {
        var request = new ApproveTodosRequest(false);

        assertThat(request.approved()).isFalse();
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_bothApprovedTrue() {
        var a = new ApproveTodosRequest(true);
        var b = new ApproveTodosRequest(true);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothApprovedFalse() {
        var a = new ApproveTodosRequest(false);
        var b = new ApproveTodosRequest(false);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentApprovedValues() {
        var a = new ApproveTodosRequest(true);
        var b = new ApproveTodosRequest(false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new ApproveTodosRequest(true);

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqual_when_comparedToDifferentType() {
        var request = new ApproveTodosRequest(true);

        assertThat(request).isNotEqualTo("not a request");
    }

    // --- toString ---

    @Test
    void should_containApprovedTrue_inToString() {
        var request = new ApproveTodosRequest(true);

        assertThat(request.toString()).contains("approved=true");
    }

    @Test
    void should_containApprovedFalse_inToString() {
        var request = new ApproveTodosRequest(false);

        assertThat(request.toString()).contains("approved=false");
    }
}

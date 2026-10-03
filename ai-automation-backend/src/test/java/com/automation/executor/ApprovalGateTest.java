package com.automation.executor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalGateTest {

    private final ApprovalGate gate = new ApprovalGate();

    @Test
    void request_setsCurrentQuestion_andResolveCompletesAndClears() throws Exception {
        ApprovalRequest request = ApprovalRequest.builder().question("Weiter?").build();

        CompletableFuture<String> future = gate.request(request);
        assertThat(gate.currentRequest().get()).isSameAs(request);
        assertThat(future).isNotDone();

        gate.resolve("Ja");

        assertThat(future.get()).isEqualTo("Ja");
        assertThat(gate.currentRequest().get()).isNull();
    }

    @Test
    void resolveWithoutPendingRequest_isHarmless() {
        gate.resolve("Ja");

        assertThat(gate.currentRequest().get()).isNull();
    }

    @Test
    void secondResolve_doesNotTouchTheAlreadyCompletedFuture() throws Exception {
        CompletableFuture<String> future = gate.request(ApprovalRequest.builder().build());
        gate.resolve("first");
        gate.resolve("second");

        assertThat(future.get()).isEqualTo("first");
    }

    @Test
    void cancel_failsPendingFutureWithCancellation_andClearsQuestion() {
        CompletableFuture<String> future = gate.request(ApprovalRequest.builder().question("q").build());

        gate.cancel();

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::join).isInstanceOf(CancellationException.class)
                .hasMessageContaining("Execution cancelled");
        assertThat(gate.currentRequest().get()).isNull();
    }

    @Test
    void cancelWithoutPendingRequest_isHarmless() {
        gate.cancel();

        assertThat(gate.currentRequest().get()).isNull();
    }

    @Test
    void newRequest_replacesTheOldFuture_soResolveAnswersOnlyTheNewOne() throws Exception {
        CompletableFuture<String> old = gate.request(ApprovalRequest.builder().question("alt").build());
        CompletableFuture<String> current = gate.request(ApprovalRequest.builder().question("neu").build());

        gate.resolve("x");

        assertThat(current.get()).isEqualTo("x");
        assertThat(old).isNotDone();
    }

    @Test
    void memory_remembersAnswersPerQuestion() {
        assertThat(gate.remembered("q")).isNull();

        gate.remember("q", "a");
        gate.remember("q", "b");

        assertThat(gate.remembered("q")).isEqualTo("b");
        assertThat(gate.remembered("other")).isNull();
    }

    // ── Warten anzeigen (TODO 4eea7a87) ──

    @Test
    void request_meldetWarten_undAwaitDasEnde() throws Exception {
        java.util.List<Boolean> events = new java.util.ArrayList<>();
        gate.onWait(events::add);

        CompletableFuture<String> future = gate.request(ApprovalRequest.builder().question("Weiter?").build());
        assertThat(events).containsExactly(true);
        gate.resolve("Ja");

        assertThat(gate.await(future, 1, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("Ja");
        assertThat(events).containsExactly(true, false);
    }

    @Test
    void awaitMeldetDasEndeAuchBeimTimeout() {
        java.util.List<Boolean> events = new java.util.ArrayList<>();
        gate.onWait(events::add);
        CompletableFuture<String> future = gate.request(ApprovalRequest.builder().build());

        assertThatThrownBy(() -> gate.await(future, 1, java.util.concurrent.TimeUnit.MILLISECONDS))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(events).containsExactly(true, false);
    }

    @Test
    void ohneZuhoererLaeuftDasWartenWieBisher() throws Exception {
        gate.onWait(null);
        CompletableFuture<String> future = gate.request(ApprovalRequest.builder().build());
        gate.resolve("Ja");

        assertThat(gate.await(future, 1, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("Ja");
    }
}

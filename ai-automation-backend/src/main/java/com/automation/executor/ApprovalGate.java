package com.automation.executor;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Der Wartezustand eines Laufs an einem Approval: die offene Zusage, die Frage, auf die gerade
 * gewartet wird, und die Antworten, die der Lauf sich gemerkt hat.
 *
 * <p>Reiner Umzug aus {@link ExecutionContext} (TODO 7473fdcb) — dieselbe Mechanik, nur an einem
 * Ort statt über drei Felder und fünf Methoden verteilt. Dem Vorbild von
 * {@link ExecutionContextTaint} folgend: ein Zustand, eine Klasse.
 */
class ApprovalGate {

    private final AtomicReference<CompletableFuture<String>> pending = new AtomicReference<>();
    private final AtomicReference<ApprovalRequest> currentRequest = new AtomicReference<>();
    private final Map<String, String> memory = new ConcurrentHashMap<>();
    /**
     * Wer den wartenden Schritt anzeigt (TODO 4eea7a87): {@code true}, sobald eine Frage offen ist,
     * {@code false}, wenn das Warten endet — beides im Thread des Laufs, nie in dem, der antwortet.
     */
    private volatile Consumer<Boolean> waitListener;

    void onWait(Consumer<Boolean> listener) {
        this.waitListener = listener;
    }

    AtomicReference<ApprovalRequest> currentRequest() {
        return currentRequest;
    }

    CompletableFuture<String> request(ApprovalRequest request) {
        CompletableFuture<String> future = new CompletableFuture<>();
        currentRequest.set(request);
        pending.set(future);
        notifyWaiting(true);
        return future;
    }

    /** Wartet auf die Antwort; danach zeigt der Schritt wieder, dass er läuft — auch bei Timeout oder Abbruch. */
    String await(CompletableFuture<String> future, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return future.get(timeout, unit);
        } finally {
            notifyWaiting(false);
        }
    }

    private void notifyWaiting(boolean waiting) {
        Consumer<Boolean> listener = waitListener;
        if (listener != null) listener.accept(waiting);
    }

    void resolve(String answer) {
        CompletableFuture<String> future = pending.getAndSet(null);
        currentRequest.set(null);
        if (future != null) {
            future.complete(answer);
        }
    }

    /** Bricht eine offene Zusage ab — der wartende Block läuft in die CancellationException. */
    void cancel() {
        CompletableFuture<String> future = pending.getAndSet(null);
        if (future != null) {
            future.completeExceptionally(new CancellationException("Execution cancelled"));
        }
        currentRequest.set(null);
    }

    void remember(String question, String answer) {
        memory.put(question, answer);
    }

    String remembered(String question) {
        return memory.get(question);
    }
}

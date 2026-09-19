package com.automation.executor;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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

    AtomicReference<ApprovalRequest> currentRequest() {
        return currentRequest;
    }

    CompletableFuture<String> request(ApprovalRequest request) {
        CompletableFuture<String> future = new CompletableFuture<>();
        currentRequest.set(request);
        pending.set(future);
        return future;
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

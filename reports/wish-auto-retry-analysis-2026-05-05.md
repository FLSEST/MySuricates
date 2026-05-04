# Auto-Retry für transiente Wish-Fehler — Analyse 2026-05-05

TODO: `f8f526bd-f17c-4bc2-9bac-6c7749345cb9`

## Status quo in `WishService.java`

### Was schon da ist

1. **Manuelle Retry via UI** (`RETRY` UserAction, Z. 221–232): Setzt `FAILED|CANCELLED` zurück auf `RECEIVED`, ruft `fulfillAsync` erneut. Inkrementiert `iterationCount`.
2. **Inline-Retry beim Evaluate** (Z. 338–343): Wenn das LLM JSON nicht parsbar liefert, ein Retry mit verkürztem Prompt. Ein einziger Versuch, kein Backoff.
3. **Refine-Loop** (Z. 270–308): Wenn Evaluate `shouldRetry: true` zurückgibt, läuft die Decompose/Execute/Evaluate-Schleife weiter (max-iterations konfiguriert).

### Was fehlt

- **Kein automatisches Retry bei Exceptions im Catch-all** (Z. 309–319): Jede unerwartete Exception → direkt `FAILED`, egal ob transient oder permanent.
- **Keine Klassifizierung** der Fehlerursache (Timeout, Connection, OOM, Cancellation, Validierungsfehler, …).
- **Kein Backoff**: bei manuellem Retry sofort, ohne Cooldown.

## Fehlerkategorien (Vorschlag)

| Kategorie | Beispiele | Auto-Retry sinnvoll? |
|---|---|---|
| **Transient — externer Dienst** | HTTP-Timeout/5xx zur Anthropic-/OpenAI-API, Netzfehler, Container-Start fehlt | ✅ ja, mit Backoff |
| **Transient — System** | `InterruptedException` (vom Cancellation-Pfad), `RejectedExecutionException` bei Threadpool-Sättigung | ✅ ja |
| **Transient — CLI/Container** | `claude -p` Exit-Code != 0 wegen temporärem Auth-Refresh, Docker-Daemon kurz nicht erreichbar | ✅ ja, eingeschränkt |
| **Semi-permanent — LLM-Output** | JSON-Parse-Failure, leere/abgeschnittene Antwort | ⚠️ ein Retry mit verändertem Prompt (haben wir bereits inline) |
| **Permanent — Konfiguration** | Workflow-ID nicht gefunden, ungültiger Provider, fehlende Credentials | ❌ niemals |
| **Permanent — User-Cancellation** | `isCancelled()` true | ❌ niemals |
| **Budget** | Token-Limit überschritten, Iteration-Cap erreicht, total-timeout | ❌ niemals (User entscheidet manuell) |

Erkennungsstrategie:
- Exception-Typ-Whitelist für transient (siehe Tabelle).
- HTTP-Status-Codes parsen (5xx vs 4xx) — erfordert dass `AiProvider.reason()` strukturierte Fehler wirft, nicht nur `RuntimeException(message)`. Aktuell ist das nicht der Fall → vorab `AiProviderException` mit `transient: boolean` einführen.

## Retry-Strategie

```text
maxAttempts = 3 (settable, default 3)
backoff:    exp(2^n) * baseDelay (e.g. 5s, 10s, 20s)
jitter:     ±20% gegen Thundering-Herd
```

Counter pro Wish in `wish.metadata` persistieren (`autoRetryCount`), damit auch über Backend-Restart hinweg sicher.

## Integration in `WishService.fulfillWish()`

Der Catch-all bei Z. 309 wird zu:

```java
} catch (Exception e) {
    if (isTransient(e) && wish.autoRetryCount() < maxAttempts) {
        long delay = computeBackoff(wish.autoRetryCount());
        log.warn("Wish {}: transient error, auto-retry #{} in {}ms",
                 wishId, wish.autoRetryCount() + 1, delay);
        scheduleRetry(wishId, delay);  // via TaskScheduler
        return;  // wish bleibt RECEIVED, kein FAILED-Status
    }
    // permanenter Fehler oder Retries aufgebraucht
    setFailed(wish, e);
}
```

Wichtig (Memory-Regel „kein Slow-Op in @Transactional"): `scheduleRetry` nutzt den Spring `TaskScheduler`, nicht `Thread.sleep` in einem aktiven `@Transactional`-Kontext. Der Retry-Lauf ruft `fulfillAsync(wishId)` neu auf und passiert in einem eigenen Thread.

## UI-/UX-Implikation

- Wish-Status während Auto-Retry sichtbar machen: ein neuer Substatus `RETRYING` (oder Flag `autoRetrying` im DTO) mit Countdown bis zum nächsten Versuch.
- Nach `maxAttempts` ohne Erfolg → klassisches `FAILED`, aber Error-Message benennt konkret „Auto-Retry erschöpft, letzte Ursache: …".
- User kann während Auto-Retry-Wartezeit manuell „Cancel" drücken → setzt `CANCELLED`, retry-Schedule wird abgebrochen.

## Settings (laut Memory: keine hart codierten Konstanten)

`ExecutionSettingsService` erweitern um:
- `wish-auto-retry-enabled` (bool, default true)
- `wish-auto-retry-max-attempts` (1–10, default 3)
- `wish-auto-retry-base-delay-seconds` (1–60, default 5)

Settings-Card im Frontend, i18n DE+EN.

## Testbarkeit

- Mock `AiProvider`, das beim ersten Aufruf `IOException` wirft, beim zweiten Erfolg liefert → assert: Wish kommt zu COMPLETED, autoRetryCount==1.
- Mock dass jeden Aufruf wirft → assert: nach maxAttempts FAILED, autoRetryCount==maxAttempts.
- Cancel während Retry-Wartezeit → assert: Schedule abgebrochen, Status CANCELLED.

## Konkreter nächster Schritt

Sobald der User die Strategie absegnet:

1. `AiProviderException(boolean transient, …)` einführen, in allen `AiProvider`-Implementierungen werfen (statt `RuntimeException`).
2. `WishService` um die transient-Erkennung + `TaskScheduler`-Retry ergänzen.
3. Settings + UI.
4. Tests.

Aufwand: ~½ Tag. Nicht eigenmächtig umsetzen — Verhaltens-Änderung, die User absegnen sollte (Refine-Loop interagiert subtil mit Auto-Retry — Doppel-Retry-Pfade kann irritieren).

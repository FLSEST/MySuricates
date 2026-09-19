package com.automation.dto.response;

import com.automation.entity.RunStatus;
import com.automation.entity.StepStatus;
import com.automation.entity.TriggerSource;

import java.time.Instant;
import java.util.Map;

/**
 * Eine Run-Zeile für Listen — wie {@link WorkflowRunResponse}, aber ohne die Schritte.
 *
 * <p><b>Warum ein eigener Typ.</b> Die Listen-Route benutzte dasselbe DTO wie die Detail-Route und
 * trug damit jeden {@code step_results.output} (TEXT) in die Antwort: bei 60 Runs mit je drei
 * 20-KB-Schritten <b>3 061 816 Bytes</b>, gemessen von {@code RequestCostRatchetTest} am
 * 2026-09-18 (TODO a05c9504). Produktiv ist es größer — Quality-Gate-Ausgaben liegen im hohen KB-
 * bis MB-Bereich, bei 50 Runs je Seite also zweistellige MB pro Seitenaufruf.
 *
 * <p>Dasselbe DTO mit teils gefüllten, teils leeren Schritten wäre die stillere, aber
 * schlechtere Lösung gewesen: Dann bedeutet {@code steps} in der Liste etwas anderes als im
 * Detail, und der nächste Leser erfährt es nur, wenn er es ausprobiert. Ein eigener Typ sagt es.
 *
 * <p>Was die Liste vom letzten Schritt braucht, ist erhoben und nicht geraten: {@code Runs.tsx}
 * zeigt die <b>Anzahl</b> der Schritte und die Ausgabe des <b>letzten</b> Schritts, gekürzt auf
 * 300&nbsp;px mit Volltext im Tooltip. Die übrigen fünf Verbraucher der Route rühren die Schritte
 * nicht an.
 *
 * <p>Einen Fehlertext je Schritt gibt es nicht: {@code Runs.tsx} liest {@code lastStep.error},
 * aber {@link StepResultResponse} hat kein solches Feld — der Wert war immer {@code undefined}.
 * Der Fehler eines Runs steht in {@link #error}, und dabei bleibt es; ein Feld zu erfinden, das
 * nichts füllt, hätte die Liste nur wieder undurchsichtig gemacht.
 *
 * @param stepCount       Anzahl der Schritte — die Liste zeigt sie, lädt sie aber nicht einzeln
 * @param lastStepStatus  Status des letzten Schritts, {@code null} wenn es keinen gibt
 * @param lastStepPreview Ausgabe des letzten Schritts, gekürzt auf {@value #PREVIEW_LENGTH}
 *                        Zeichen; der Volltext steht in der Detail-Route
 */
public record WorkflowRunListItemResponse(
        String id,
        String workflowId,
        String workflowName,
        String projectName,
        String projectPath,
        RunStatus status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        Integer userRating,
        Map<String, Object> error,
        Map<String, Object> metadata,
        Map<String, Object> inputContext,
        String triggeredByScheduleId,
        String definitionHash,
        String definitionVersionId,
        TriggerSource triggerSource,
        int stepCount,
        StepStatus lastStepStatus,
        String lastStepPreview
) {
    /**
     * Länge der Vorschau. 500 Zeichen, weil die Liste den Wert ohnehin auf 300&nbsp;px kürzt und
     * der Tooltip damit noch einen Satz mehr zeigt als die Zeile — mehr wäre wieder das Problem,
     * das dieses DTO löst.
     */
    public static final int PREVIEW_LENGTH = 500;
}

package com.automation.service.dialog;

import com.automation.executor.spec.BlockSpecs;

import java.util.List;

/**
 * The prompt that turns a Claude Code session into a workflow suggestion for
 * {@link DialogConversionService}. The block schema comes from {@link BlockSpecs} — the old rules
 * named the type forEach, which the save refuses (TODO cf8db6f8) — and
 * {@code PromptBlockSchemaGateTest} checks every type and config key the text names.
 */
public final class DialogConversionPrompt {

    /** The block types a suggestion may use. */
    static final List<String> BLOCK_TYPES = List.of("shell", "simple", "retry", "foreach");

    private DialogConversionPrompt() {
    }

    public static String build(String projectPath, int turns, String transcript) {
        return """
                Analysiere diese Claude Code Session und extrahiere einen wiederverwendbaren MySuricates-Workflow.

                SESSION METADATEN:
                - Projekt: %s
                - Turns: %d

                SESSION-INHALT:
                %s

                Identifiziere das KERN-MUSTER — die Abfolge von Befehlen und Entscheidungen, die sich lohnt zu automatisieren.

                Ignoriere: exploratives Lesen von Dateien, fehlgeschlagene Versuche, Debugging-Sackgassen.
                Behalte: erfolgreiche Befehle, Datei-Änderungen mit Ergebnis, logischer Ablauf.

                Antworte NUR mit einem gültigen JSON-Objekt:
                {
                  "suggestedName": "prägnanter Workflow-Name",
                  "suggestedDescription": "Kurze Beschreibung was dieser Workflow tut",
                  "confidence": 0.85,
                  "patternType": "scripting|file_editing|research|mixed",
                  "blocks": [
                    {"type": "shell", "config": {"command": "...", "container_image": "debian:bookworm-slim", "on_failure": "stop"}},
                    {"type": "simple", "config": {"command": "Analysiere die Ausgabe und erstelle einen Report", "on_failure": "stop"}}
                  ]
                }

                Block-Regeln (Typen und Config-Schlüssel genau wie in der Block-Referenz unten):
                - "shell" für deterministische Bash-Befehle (immer container_image angeben)
                - "simple" für KI-Reasoning-Aufgaben und Textgenerierung
                - "retry" für Befehle, die transient fehlschlagen können (max_retries: 3; execution_mode "shell" für Bash-Befehle)
                - "foreach" NUR wenn die Session klar über eine Liste iteriert
                - Verwandte Befehle zusammenfassen wenn sie eine logische Einheit bilden
                - confidence: 0.9 = klares Scripting-Muster, 0.6 = gemischt, 0.4 = unklar
                - Nur gültiges JSON, keine Erklärungen

                Block-Referenz:

                %s""".formatted(projectPath, turns, transcript, BlockSpecs.reference(BLOCK_TYPES));
    }
}

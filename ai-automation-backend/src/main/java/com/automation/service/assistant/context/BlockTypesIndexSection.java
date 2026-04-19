package com.automation.service.assistant.context;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BlockTypesIndexSection implements AssistantContextSection {

    public record BlockTypeEntry(String type, String summary) {}

    public static final List<BlockTypeEntry> BLOCK_TYPES = List.of(
            new BlockTypeEntry("simple", "KI-Prompt mit einer Antwort (Claude/Codex), nutzt Workflow-Modell"),
            new BlockTypeEntry("shell", "Shell-Kommando ausführen (kein API-Zugriff, kein DB-Zugriff)"),
            new BlockTypeEntry("agentic", "Mehrstufiger Agent mit REASON+ACT-Phase, langlaufend, mit Token-Budget"),
            new BlockTypeEntry("conditional", "Verzweigung: führt branch_true oder branch_false abhängig von Bedingung aus"),
            new BlockTypeEntry("foreach", "Iteriert über Liste, führt Sub-Blocks pro Element aus"),
            new BlockTypeEntry("while", "Schleife mit Abbruch-Bedingung und max_iterations"),
            new BlockTypeEntry("switch", "Multi-Way-Verzweigung anhand eines Match-Werts"),
            new BlockTypeEntry("retry", "Wiederholt einen Sub-Block bei Fehler mit Backoff"),
            new BlockTypeEntry("approval", "Pausiert bis User-Approval (UI-Confirm, optional Choices)"),
            new BlockTypeEntry("sub-workflow", "Ruft anderen Workflow auf, übergibt context als Inputs"),
            new BlockTypeEntry("skill-capability", "Führt eine Capability eines Skills aus (deklarativ)"),
            new BlockTypeEntry("human-input", "Fordert Wert(e) vom User an, pausiert bis Antwort"),
            new BlockTypeEntry("integration", "Aktion eines Integration-Providers (z.B. github:create_issue)"),
            new BlockTypeEntry("document", "OCR/Dokumenten-Verarbeitung (PDF, Bilder)")
    );

    @Override
    public void append(StringBuilder sb, boolean compact) {
        if (compact) {
            sb.append("**Block-Typen:** ").append(BLOCK_TYPES.size()).append(" verfügbar\n");
            return;
        }
        sb.append("**Block-Typen (").append(BLOCK_TYPES.size()).append("):**\n");
        BLOCK_TYPES.forEach(e -> sb
                .append("  - [").append(e.type()).append("] ")
                .append(e.summary()).append("\n"));
        sb.append("  Detail/Schema via <fetch type=\"block-spec\" id=\"<block-type>\"/>\n\n");
    }
}

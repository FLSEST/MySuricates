package com.automation.service;

import java.util.List;

/**
 * Fallback quick actions per assistant page, used until suggestions were generated for the page and
 * language, or when generation fails. German and English (5157fac2).
 */
final class PageSuggestionDefaults {

    private PageSuggestionDefaults() {}

    static List<String> forPage(String page, String lang) {
        return "en".equals(lang) ? english(page) : german(page);
    }

    private static List<String> english(String page) {
        return switch (page) {
            case "run"       -> List.of("Explain this run", "Why did it fail?", "What can be improved?");
            case "workflow"  -> List.of("Analyze this workflow", "Show recent runs", "Is there room for improvement?");
            case "workflows" -> List.of("Which workflows have problems?", "What is running right now?");
            case "runs"      -> List.of("What went wrong today?", "How was the success rate?");
            case "todos"     -> List.of("What is urgent?", "Prioritize my todos");
            case "project"   -> List.of("What is coming up in this project?", "Show open todos");
            case "projects"  -> List.of("How are my projects doing?", "What needs attention?");
            case "schedules" -> List.of("What is coming up today?", "Are any schedules overdue?");
            case "activity"  -> List.of("What happened today?", "Show important events");
            case "insights"  -> List.of("How was the week?", "What can be improved?");
            case "wishes"    -> List.of("What should I automate next?");
            case "factory"   -> List.of("Help me create a workflow");
            case "chains"    -> List.of("How are my chains doing?");
            default          -> List.of("What ran today?", "Are there any problems?", "What is coming up?");
        };
    }

    private static List<String> german(String page) {
        return switch (page) {
            case "run"       -> List.of("Erkläre diesen Run", "Warum ist das fehlgeschlagen?", "Was kann verbessert werden?");
            case "workflow"  -> List.of("Analysiere diesen Workflow", "Zeige letzte Runs", "Gibt es Verbesserungspotenzial?");
            case "workflows" -> List.of("Welche Workflows haben Probleme?", "Was läuft gerade?");
            case "runs"      -> List.of("Was lief heute schief?", "Wie war die Erfolgsrate?");
            case "todos"     -> List.of("Was ist dringend?", "Priorisiere meine Todos");
            case "project"   -> List.of("Was steht in diesem Projekt an?", "Zeige offene Todos");
            case "projects"  -> List.of("Wie laufen meine Projekte?", "Was braucht Aufmerksamkeit?");
            case "schedules" -> List.of("Was steht heute an?", "Gibt es überfällige Schedules?");
            case "activity"  -> List.of("Was ist heute passiert?", "Zeige wichtige Ereignisse");
            case "insights"  -> List.of("Wie war die Woche?", "Was kann verbessert werden?");
            case "wishes"    -> List.of("Was soll ich als nächstes automatisieren?");
            case "factory"   -> List.of("Hilf mir einen Workflow zu erstellen");
            case "chains"    -> List.of("Wie laufen meine Chains?");
            default          -> List.of("Was lief heute?", "Gibt es Probleme?", "Was steht an?");
        };
    }
}

package com.automation.config;

import com.automation.entity.BlockDefinition;
import com.automation.entity.WorkflowTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static definitions of all built-in workflow templates.
 * These are seeded into the database on first startup.
 */
public final class BuiltInTemplates {

    private BuiltInTemplates() {}

    public static List<WorkflowTemplate> getAll() {
        List<WorkflowTemplate> templates = new ArrayList<>();
        int order = 0;

        templates.add(template("code-quality", "\uD83E\uDDEA",
                Map.of("de", "Code-Qualit\u00e4t", "en", "Code Quality"),
                Map.of("de", "Git-Status, Lint, Test mit Wiederholung und Build", "en", "Git status, lint, test with retry, and build"),
                "CODE", order++,
                List.of(
                        block("shell", Map.of("command", "git status")),
                        block("shell", Map.of("command", "npm run lint")),
                        block("retry", Map.of("command", "npm test", "max_retries", 3, "delay", 5, "execution_mode", "shell")),
                        block("shell", Map.of("command", "npm run build"))
                ),
                "Fuehre die Code-Qualitaetspruefungen der Reihe nach aus. Bei Fehlern gib klare Fehlermeldungen aus.",
                null));

        templates.add(template("document-review", "\uD83D\uDCC4",
                Map.of("de", "Dokument-Pr\u00fcfung", "en", "Document Review"),
                Map.of("de", "Grammatikpr\u00fcfung, Faktencheck und Zusammenfassung", "en", "Grammar check, fact check, and summarize"),
                "CONTENT", order++,
                List.of(
                        block("skill-capability", Map.of("capability", "grammar_check", "input", "{{ document }}")),
                        block("skill-capability", Map.of("capability", "fact_check", "input", "{{ previous_output }}")),
                        block("skill-capability", Map.of("capability", "summarize", "input", "{{ previous_output }}"))
                ),
                "Pruefe das Dokument gruendlich. Achte auf Grammatik, Fakten und erstelle eine praegnante Zusammenfassung.",
                null));

        templates.add(template("deploy-pipeline", "\uD83D\uDE80",
                Map.of("de", "Deploy-Pipeline", "en", "Deploy Pipeline"),
                Map.of("de", "Bedingter Test, Build und Deployment", "en", "Conditional test, build, and deploy"),
                "CODE", order++,
                List.of(
                        block("conditional", Map.of("condition", "test -f package.json", "then_command", "npm test", "else_command", "echo \"No tests\"", "execution_mode", "shell")),
                        block("shell", Map.of("command", "npm run build")),
                        block("shell", Map.of("command", "npm run deploy"))
                ),
                "Fuehre die Deploy-Pipeline aus. Stoppe bei fehlgeschlagenen Tests.",
                null));

        templates.add(template("research-assistant", "\uD83D\uDD0D",
                Map.of("de", "Recherche-Assistent", "en", "Research Assistant"),
                Map.of("de", "Websuche, Kernpunkte extrahieren und Quellen zitieren", "en", "Web search, extract key points, and cite sources"),
                "RESEARCH", order++,
                List.of(
                        block("skill-capability", Map.of("capability", "web_search", "input", "{{ query }}")),
                        block("skill-capability", Map.of("capability", "extract_key_points", "input", "{{ previous_output }}")),
                        block("skill-capability", Map.of("capability", "cite_sources", "input", "{{ previous_output }}"))
                ),
                "Recherchiere gruendlich und stelle sicher dass alle Quellen korrekt zitiert werden.",
                null));

        templates.add(template("data-analysis", "\uD83D\uDCCA",
                Map.of("de", "Datenanalyse", "en", "Data Analysis"),
                Map.of("de", "Statistiken, Anomalie-Erkennung und Visualisierungen", "en", "Statistics, anomaly detection, and visualizations"),
                "DATA", order++,
                List.of(
                        block("skill-capability", Map.of("capability", "analyze_statistics", "input", "{{ dataset }}")),
                        block("skill-capability", Map.of("capability", "detect_anomalies", "input", "{{ previous_output }}")),
                        block("skill-capability", Map.of("capability", "visualizations", "input", "{{ previous_output }}"))
                ),
                "Analysiere die Daten systematisch. Identifiziere Muster und Anomalien.",
                null));

        templates.add(template("file-improve", "\u2728",
                Map.of("de", "Datei-Analyse & Verbesserung", "en", "File Analysis & Improvement"),
                Map.of("de", "Projekt erkunden, Datei analysieren und verbesserte Version erstellen", "en", "Explore project, analyze a file, and create an improved version"),
                "CONTENT", order++,
                List.of(
                        block("simple", Map.of("command", "ls -la .")),
                        block("simple", Map.of("command", "Schau dir die Dateien in deinem Projekt an. Lies die Datei {{ filepath }} und analysiere ihren Inhalt. Liste konkret auf: 1) Staerken 2) Schwaechen 3) Fehlende Aspekte")),
                        block("simple", Map.of("command", "Erstelle eine priorisierte Liste von Verbesserungsvorschlaegen. Sei konkret - keine vagen Hinweise, sondern umsetzbare Aenderungen.")),
                        block("simple", Map.of("command", "Wende die Verbesserungen an und gib die vollstaendig ueberarbeitete Version aus. Markiere die geaenderten Stellen."))
                ),
                "Analysiere die Datei gruendlich und erstelle konkrete, umsetzbare Verbesserungsvorschlaege.",
                null));

        templates.add(template("research-enhance", "\uD83D\uDD2C",
                Map.of("de", "Recherche & Anreicherung", "en", "Research & Enrichment"),
                Map.of("de", "Projektdateien scannen, L\u00fccken finden und mit Recherche anreichern", "en", "Scan project files, find content gaps, and enrich with research"),
                "RESEARCH", order++,
                List.of(
                        block("simple", Map.of("command", "find . -type f \\( -name \"*.md\" -o -name \"*.txt\" -o -name \"*.rst\" \\) | head -20")),
                        block("simple", Map.of("command", "Lies die relevanten Dateien in deinem Projekt. Extrahiere die Kernaussagen und identifiziere inhaltliche Luecken oder Punkte die mehr Tiefe brauchen.")),
                        block("simple", Map.of("command", "Recherchiere zu den identifizierten Luecken. Finde aktuelle Fakten, Statistiken und Quellen die den Inhalt bereichern wuerden.")),
                        block("simple", Map.of("command", "Erstelle eine angereicherte Version der Hauptdatei. Integriere die recherchierten Informationen und fuege Quellenangaben hinzu."))
                ),
                "Scanne das Projekt rekursiv, finde inhaltliche Luecken und reichere die Texte mit Recherche an.",
                null));

        templates.add(template("iterative-refine", "\uD83D\uDD04",
                Map.of("de", "Iterative Verbesserung", "en", "Iterative Refinement"),
                Map.of("de", "Text in einer Schleife verbessern bis die Qualit\u00e4t stimmt", "en", "Improve text in a loop until quality threshold is met"),
                "CONTENT", order++,
                List.of(
                        block("simple", Map.of("command", "Lies die Datei {{ filepath }} und gib den Inhalt aus.")),
                        block("while", Map.of(
                                "condition_command", "Bewerte die Qualitaet des Textes auf einer Skala von 1-10 (Klarheit, Vollstaendigkeit, Struktur). Antworte NUR mit \"true\" wenn unter 8, oder \"false\" wenn 8 oder hoeher.",
                                "command", "Verbessere den Text. Fokussiere auf die groessten Schwaechen. Gib die vollstaendig ueberarbeitete Version aus.",
                                "max_iterations", 3
                        )),
                        block("simple", Map.of("command", "Erstelle eine Zusammenfassung: Was wurde verbessert? Wie ist die finale Qualitaetsbewertung?"))
                ),
                "Verbessere den Text iterativ bis die Qualitaet mindestens 8/10 erreicht.",
                null));

        templates.add(template("project-overview", "\uD83D\uDDC2\uFE0F",
                Map.of("de", "Projekt-\u00dcberblick & Synthese", "en", "Project Overview & Synthesis"),
                Map.of("de", "Alle Dateien scannen, Struktur verstehen und Projekt-Zusammenfassung erstellen", "en", "Scan all files, understand structure, and create a project summary"),
                "CODE", order++,
                List.of(
                        block("simple", Map.of("command", "find . -type f -not -path \"*/node_modules/*\" -not -path \"*/.git/*\" -not -name \"*.lock\" | head -50")),
                        block("simple", Map.of("command", "Verschaffe dir einen Ueberblick ueber das Projekt. Lies die wichtigsten Dateien (README, Konfigurationsdateien, Hauptdateien). Beschreibe: 1) Zweck des Projekts 2) Struktur 3) Verwendete Technologien")),
                        block("simple", Map.of("command", "Analysiere die Projektqualitaet: Was ist gut umgesetzt? Wo gibt es Verbesserungspotenzial? Fehlt Dokumentation?")),
                        block("simple", Map.of("command", "Erstelle eine strukturierte Zusammenfassung des Projekts mit konkreten Empfehlungen fuer naechste Schritte."))
                ),
                "Erstelle eine umfassende Projektanalyse mit konkreten Empfehlungen.",
                null));

        templates.add(template("fact-check-pipeline", "\u2705",
                Map.of("de", "Faktencheck-Pipeline", "en", "Fact-Check Pipeline"),
                Map.of("de", "Behauptungen extrahieren, Fakten pr\u00fcfen und Korrekturbericht erstellen", "en", "Extract claims, verify facts, and generate a correction report"),
                "RESEARCH", order++,
                List.of(
                        block("simple", Map.of("command", "Lies die Datei {{ filepath }} und gib den Inhalt aus.")),
                        block("simple", Map.of("command", "Extrahiere alle faktischen Behauptungen, Zahlen und Statistiken. Nummeriere jede Behauptung.")),
                        block("simple", Map.of("command", "Pruefe jede Behauptung. Markiere als KORREKT, UNKLAR oder FALSCH mit Begruendung.")),
                        block("conditional", Map.of(
                                "condition", "Basierend auf folgendem Pruefergebnis:\n{{ previous_output }}\n\nWurden Behauptungen als FALSCH oder UNKLAR markiert? Antworte nur mit true oder false.",
                                "then_command", "Erstelle einen Korrekturbericht mit den richtigen Informationen fuer jede fehlerhafte Behauptung.",
                                "else_command", "echo \"Alle Fakten geprueft - keine Fehler gefunden.\""
                        ))
                ),
                "Pruefe alle Fakten sorgfaeltig und erstelle bei Fehlern einen detaillierten Korrekturbericht.",
                null));

        templates.add(template("translation-pipeline", "\uD83C\uDF10",
                Map.of("de", "\u00dcbersetzungs-Pipeline", "en", "Translation Pipeline"),
                Map.of("de", "\u00dcbersetzen mit Qualit\u00e4tskontrolle und Fachbegriff-Pr\u00fcfung", "en", "Translate with quality check and terminology validation"),
                "CONTENT", order++,
                List.of(
                        block("simple", Map.of("command", "Lies die Datei {{ filepath }} und gib den Inhalt aus.")),
                        block("simple", Map.of("command", "Uebersetze den Text ins {{ target_language }}. Behalte Formatierung, Fachbegriffe und Struktur bei.")),
                        block("simple", Map.of("command", "Pruefe die Uebersetzung auf: 1) Sprachliche Natuerlichkeit 2) Fachbegriff-Konsistenz 3) Vollstaendigkeit. Liste alle Probleme auf.")),
                        block("simple", Map.of("command", "Korrigiere alle gefundenen Probleme und gib die finale Uebersetzung aus."))
                ),
                "Uebersetze den Text hochwertig mit Qualitaetskontrolle und konsistenten Fachbegriffen.",
                null));

        templates.add(template("code-refactor", "\u2699\uFE0F",
                Map.of("de", "Code-Analyse & Refactoring", "en", "Code Analysis & Refactoring"),
                Map.of("de", "Projekt-Code scannen, Probleme finden und verbesserte Version erstellen", "en", "Scan project code, find issues, and create improved version"),
                "CODE", order++,
                List.of(
                        block("simple", Map.of("command", "find . -type f \\( -name \"*.ts\" -o -name \"*.js\" -o -name \"*.py\" -o -name \"*.java\" \\) -not -path \"*/node_modules/*\" | head -20")),
                        block("simple", Map.of("command", "Schau dir die Code-Dateien im Projekt an. Lies die Datei {{ filepath }} und analysiere: 1) Bugs und logische Fehler 2) Sicherheitsprobleme 3) Performance 4) Code-Qualitaet. Strukturiere nach Schweregrad.")),
                        block("simple", Map.of("command", "Erstelle fuer jedes Problem einen konkreten Verbesserungsvorschlag mit Code-Beispiel.")),
                        block("simple", Map.of("command", "Wende alle Verbesserungen an und gib die refactored Version aus. Kommentiere die wesentlichen Aenderungen."))
                ),
                "Analysiere den Code auf Bugs, Sicherheit und Qualitaet. Erstelle konkrete Verbesserungen.",
                null));

        templates.add(template("topic-deep-dive", "\uD83D\uDCA1",
                Map.of("de", "Themen-Deep-Dive", "en", "Topic Deep Dive"),
                Map.of("de", "Thema im Projektkontext recherchieren und strukturierten Report erstellen", "en", "Research a topic in project context and create a structured report"),
                "RESEARCH", order++,
                List.of(
                        block("simple", Map.of("command", "ls -la .")),
                        block("simple", Map.of("command", "Schau dir das Projekt an und recherchiere das Thema \"{{ topic }}\" im Kontext dieses Projekts. Identifiziere die wichtigsten Aspekte und aktuelle Entwicklungen.")),
                        block("simple", Map.of("command", "Erstelle eine detaillierte Gliederung fuer einen Report zu diesem Thema.")),
                        block("simple", Map.of("command", "Schreibe den vollstaendigen Report. Jeder Abschnitt soll fundiert und gut strukturiert sein. Fuege am Ende ein Quellenverzeichnis hinzu."))
                ),
                "Recherchiere das Thema im Projektkontext und erstelle einen fundierten, strukturierten Report.",
                null));

        // Advanced agentic templates
        templates.add(template("doc-to-website", "\uD83C\uDF10",
                Map.of("de", "Dokument \u2192 Lese-Webseite", "en", "Document \u2192 Reading Website"),
                Map.of("de", "Dokument in eine responsive HTML-Webseite mit Kapitelzusammenfassungen, Key Takeaways und Suche verwandeln",
                        "en", "Turn a document into a responsive HTML website with chapter summaries, key takeaways, and search"),
                "TRANSFORM", order++,
                List.of(
                        block("simple", Map.of("command", "Lies die Datei {{ filepath }}. Analysiere die Struktur: Wie viele Kapitel/Abschnitte gibt es? Was sind die Hauptthemen? Gib eine strukturierte Uebersicht aus.")),
                        block("agentic", Map.of(
                                "goal", "Erstelle eine vollstaendige statische Webseite in output/{{ title }}/. Die Webseite soll enthalten: (1) Eine Startseite mit Gesamtzusammenfassung, (2) Eine Seite pro Kapitel/Abschnitt mit Zusammenfassung und Key Takeaways, (3) Eine Zitate-Seite mit den wichtigsten Passagen, (4) Navigation zwischen allen Seiten, (5) Responsive Design im Stil '{{ style }}' mit TailwindCSS via CDN, (6) Eine einfache Suchfunktion mit JavaScript. Sprache: {{ language }}.",
                                "success_criteria", "Alle HTML-Dateien existieren in output/{{ title }}/ und sind vollstaendig.",
                                "max_iterations", 8
                        )),
                        block("simple", Map.of("command", "Pruefe alle erstellten HTML-Dateien in output/{{ title }}/. Liste alle Dateien auf, pruefe ob Links zwischen Seiten funktionieren, und gib eine Zusammenfassung aus: Anzahl Seiten, Gesamtgroesse, Qualitaetsbewertung."))
                ),
                "Erstelle eine vollstaendige, responsive Webseite aus dem Dokument. Verwende relative Pfade.",
                null));

        templates.add(template("idea-to-prototype", "\uD83D\uDE80",
                Map.of("de", "Idee \u2192 Funktionierender Prototyp", "en", "Idea \u2192 Working Prototype"),
                Map.of("de", "App-Idee in ein funktionierendes Projekt mit Code, Abh\u00e4ngigkeiten und Build-Konfiguration verwandeln",
                        "en", "Turn an app idea into a working project with code, dependencies, and build config"),
                "TRANSFORM", order++,
                List.of(
                        block("simple", Map.of("command", "Analysiere diese App-Idee: {{ idea }}. Erstelle eine detaillierte Feature-Liste und einen technischen Plan. Tech-Stack: {{ tech_stack }}. Definiere: Datenmodell, Komponenten-Struktur, benoetigte Abhaengigkeiten.")),
                        block("agentic", Map.of(
                                "goal", "Baue einen funktionierenden Prototyp fuer: {{ idea }}. Tech-Stack: {{ tech_stack }}. Erstelle das Projekt in prototype/. Schritte: (1) Projektstruktur anlegen, (2) package.json/Abhaengigkeiten erstellen, (3) npm install ausfuehren, (4) Alle Komponenten und Seiten implementieren, (5) npm run build ausfuehren und Fehler beheben. Kommentare auf {{ language }}.",
                                "success_criteria", "npm run build laeuft erfolgreich ohne Fehler.",
                                "max_iterations", 12
                        )),
                        block("simple", Map.of("command", "Erstelle eine README.md in prototype/ mit: Projektbeschreibung, Screenshots-Platzhalter, Installation, Starten, Technologie-Stack."))
                ),
                "Baue einen funktionierenden Prototyp mit allen Abhaengigkeiten und Build-Konfiguration.",
                null));

        templates.add(template("cv-to-portfolio", "\uD83D\uDCBC",
                Map.of("de", "Lebenslauf \u2192 Portfolio-Webseite", "en", "Resume \u2192 Portfolio Website"),
                Map.of("de", "Lebenslauf in eine professionelle Single-Page Portfolio-Seite mit Timeline, Skill-Bars und Animationen verwandeln",
                        "en", "Turn a resume into a professional single-page portfolio with timeline, skill bars, and animations"),
                "TRANSFORM", order++,
                List.of(
                        block("simple", Map.of("command", "Lies {{ filepath }} und extrahiere alle relevanten Informationen: Name, Beruf, Kontaktdaten, Berufserfahrung (Firma, Rolle, Zeitraum, Beschreibung), Ausbildung, Skills (mit Kategorien), Projekte, Sprachen. Strukturiere alles als JSON-Ueberblick.")),
                        block("agentic", Map.of(
                                "goal", "Erstelle eine professionelle Single-Page Portfolio-Webseite in portfolio/. Verwende die extrahierten Lebenslauf-Daten. Die Seite soll enthalten: (1) Hero-Bereich mit Name und Berufstitel, (2) Ueber-mich Abschnitt, (3) Berufserfahrung als vertikale Timeline, (4) Skills als animierte Progress-Bars nach Kategorien, (5) Projekte als Card-Grid, (6) Ausbildung, (7) Kontakt-Bereich. Design: Modernes Single-Page Layout mit Farbschema '{{ color_scheme }}', Smooth-Scroll Navigation, TailwindCSS via CDN, dezente CSS-Animationen. Sprache: {{ language }}.",
                                "success_criteria", "index.html existiert in portfolio/ und enthaelt alle Sektionen.",
                                "max_iterations", 6
                        )),
                        block("simple", Map.of("command", "Pruefe portfolio/index.html. Stelle sicher dass alle Sektionen vorhanden sind und das HTML valide ist. Gib eine Zusammenfassung aus."))
                ),
                "Erstelle eine professionelle Portfolio-Webseite aus dem Lebenslauf.",
                null));

        templates.add(template("csv-to-dashboard", "\uD83D\uDCCA",
                Map.of("de", "CSV \u2192 Interaktives Dashboard", "en", "CSV \u2192 Interactive Dashboard"),
                Map.of("de", "CSV-Daten in ein interaktives HTML-Dashboard mit Chart.js-Visualisierungen und KPIs verwandeln",
                        "en", "Turn CSV data into an interactive HTML dashboard with Chart.js visualizations and KPIs"),
                "DATA", order++,
                List.of(
                        block("simple", Map.of("command", "Lies {{ filepath }}. Analysiere die CSV-Struktur: Spaltennamen, Datentypen, Anzahl Zeilen, fehlende Werte. Identifiziere automatisch: (1) Numerische Spalten fuer KPIs (Summe, Durchschnitt, Min/Max), (2) Zeitliche Spalten fuer Trend-Charts, (3) Kategorische Spalten fuer Pie/Bar-Charts, (4) Korrelationen zwischen Spalten. Gib einen strukturierten Analyse-Plan aus.")),
                        block("agentic", Map.of(
                                "goal", "Erstelle ein interaktives HTML-Dashboard in dashboard/. Titel: '{{ title }}'. Basierend auf der CSV-Analyse: (1) KPI-Karten oben (grosse Zahlen mit Vergleichsindikatoren), (2) Trend-Line-Charts fuer zeitliche Daten, (3) Bar/Pie-Charts fuer kategorische Verteilungen, (4) Datentabelle mit Sortierung. Verwende Chart.js via CDN fuer Diagramme, TailwindCSS fuer Layout. Die CSV-Daten sollen als JavaScript-Variable eingebettet werden. Sprache: {{ language }}. Das Dashboard soll als einzelne index.html Datei funktionieren.",
                                "success_criteria", "index.html in dashboard/ enthaelt mindestens 3 verschiedene Chart-Typen und eine KPI-Uebersicht.",
                                "max_iterations", 8
                        )),
                        block("simple", Map.of("command", "Pruefe dashboard/index.html. Zaehle die Diagramme, pruefe ob Chart.js korrekt eingebunden ist, und gib eine Uebersicht des Dashboards aus."))
                ),
                "Erstelle ein interaktives Dashboard mit Chart.js aus den CSV-Daten.",
                null));

        templates.add(template("codebase-to-onboarding", "\uD83D\uDCDA",
                Map.of("de", "Codebase \u2192 Onboarding-Paket", "en", "Codebase \u2192 Onboarding Package"),
                Map.of("de", "Vollst\u00e4ndiges Developer-Onboarding-Paket mit Architektur-Guide, Setup-Anleitung und Code-Walkthrough generieren",
                        "en", "Generate a complete developer onboarding package with architecture guide, setup instructions, and code walkthrough"),
                "CODE", order++,
                List.of(
                        block("simple", Map.of("command", "Scanne das Projekt rekursiv. Liste auf: Verzeichnisstruktur (max 3 Ebenen), erkannte Programmiersprachen, Config-Dateien, Einstiegspunkte (main, index, app), Test-Dateien, CI/CD-Konfiguration. Gib eine strukturierte Projektanalyse aus.")),
                        block("agentic", Map.of(
                                "goal", "Erstelle ein vollstaendiges Developer-Onboarding-Paket fuer '{{ project_name }}' in docs/onboarding/. Erstelle diese Dateien: (1) README.md — Projektuebersicht, Zweck, Technologie-Stack, (2) SETUP.md — Schritt-fuer-Schritt Setup-Anleitung (Prerequisites, Installation, Konfiguration, Erster Start), (3) ARCHITECTURE.md — Architektur-Diagramm (ASCII), Verzeichnis-Erklaerung, Datenfluss, zentrale Konzepte, (4) CODE_WALKTHROUGH.md — Die 10 wichtigsten Dateien erklaert mit Code-Beispielen, (5) GLOSSARY.md — Projektspezifische Begriffe und Abkuerzungen, (6) FAQ.md — Haeufige Probleme und Loesungen. Sprache: {{ language }}. Zielgruppe: {{ audience }}.",
                                "success_criteria", "Alle 6 Markdown-Dateien existieren in docs/onboarding/ und sind inhaltlich vollstaendig.",
                                "max_iterations", 10
                        )),
                        block("simple", Map.of("command", "Lies alle Dateien in docs/onboarding/ und erstelle eine Qualitaetsbewertung: Vollstaendigkeit, Verstaendlichkeit, fehlende Themen."))
                ),
                "Erstelle ein umfassendes Onboarding-Paket fuer neue Entwickler.",
                null));

        templates.add(template("git-to-changelog", "\uD83D\uDCCB",
                Map.of("de", "Git-History \u2192 Changelog & Release Notes", "en", "Git History \u2192 Changelog & Release Notes"),
                Map.of("de", "CHANGELOG, Release Notes und Migrationsanleitung aus der Git-Commit-History generieren",
                        "en", "Generate CHANGELOG, release notes, and migration guide from Git commit history"),
                "CODE", order++,
                List.of(
                        block("shell", Map.of("command", "git log {{ since_tag }}..HEAD --format='%h | %s | %an | %ad' --date=short")),
                        block("simple", Map.of("command", "Analysiere die Git-Commits. Kategorisiere jeden Commit: feat (neue Features), fix (Bugfixes), refactor, docs, test, chore, breaking (Breaking Changes). Gruppiere zusammengehoerige Commits. Identifiziere die 3 wichtigsten Aenderungen.")),
                        block("simple", Map.of("command", "Erstelle CHANGELOG.md im Keep-a-Changelog Format fuer Version {{ new_version }}. Kategorien: Added, Changed, Fixed, Removed, Breaking Changes. Jeder Eintrag mit Commit-Hash.")),
                        block("conditional", Map.of("condition", "grep -qi 'breaking' CHANGELOG.md", "then_command", "Erstelle MIGRATION.md mit einer Schritt-fuer-Schritt Migrationsanleitung fuer alle Breaking Changes.", "else_command", "echo \"Keine Breaking Changes — Migration Guide nicht noetig.\"", "execution_mode", "shell")),
                        block("simple", Map.of("command", "Erstelle RELEASE_NOTES.md fuer '{{ product_name }}' {{ new_version }}. Schreibe benutzerfreundlich (nicht technisch). Struktur: Was ist neu (mit Beispielen), Was wurde verbessert, Was wurde behoben, Upgrade-Hinweise."))
                ),
                "Generiere Changelog, Release Notes und ggf. Migrationsanleitung aus der Git-History.",
                null));

        templates.add(template("notes-to-presentation", "\uD83C\uDFAC",
                Map.of("de", "Notizen \u2192 Slide-Deck", "en", "Notes \u2192 Slide Deck"),
                Map.of("de", "Notizen in eine vollst\u00e4ndige reveal.js Pr\u00e4sentation mit Speaker Notes und Animationen verwandeln",
                        "en", "Turn notes into a complete reveal.js presentation with speaker notes and animations"),
                "TRANSFORM", order++,
                List.of(
                        block("simple", Map.of("command", "Lies {{ filepath }}. Analysiere den Inhalt und erstelle eine Slide-Gliederung. Beruecksichtige die Ziel-Dauer von {{ duration_minutes }} Minuten (ca. 1-2 Minuten pro Slide). Gib aus: Anzahl geplanter Slides, Titel jedes Slides, geschaetzte Redezeit pro Slide.")),
                        block("agentic", Map.of(
                                "goal", "Erstelle eine reveal.js Praesentation in presentation/. Verwende reveal.js via CDN. Theme: '{{ theme }}'. Sprache: {{ language }}. Fuer jeden Slide: (1) Klarer Titel, (2) Maximal 3-5 Bullet Points oder eine Visualisierung, (3) Speaker Notes im <aside class='notes'> Tag mit ausfuehrlichem Redetext. Fuege hinzu: Titelslide, Agenda-Slide, Zusammenfassung-Slide, Danke-Slide. Nutze reveal.js Features: Fragment-Animationen, Code-Highlighting wo passend, vertikale Slides fuer Vertiefungen.",
                                "success_criteria", "index.html in presentation/ ist eine vollstaendige reveal.js Praesentation mit mindestens 8 Slides.",
                                "max_iterations", 6
                        )),
                        block("simple", Map.of("command", "Zaehle die Slides in presentation/index.html und pruefe ob Speaker Notes vorhanden sind. Gib eine Uebersicht aus: Slide-Titel, Anzahl, geschaetzte Gesamtdauer."))
                ),
                "Erstelle eine professionelle Praesentation mit Speaker Notes aus den Notizen.",
                null));

        templates.add(template("api-to-sdk", "\uD83D\uDD0C",
                Map.of("de", "API-Spec \u2192 Client-SDK", "en", "API Spec \u2192 Client SDK"),
                Map.of("de", "Typisiertes Client-SDK mit Error Handling und Beispielen aus einer OpenAPI/Swagger-Spec generieren",
                        "en", "Generate a typed client SDK with error handling and examples from an OpenAPI/Swagger spec"),
                "CODE", order++,
                List.of(
                        block("simple", Map.of("command", "Lies {{ filepath }}. Extrahiere alle API-Endpunkte: HTTP-Methode, Pfad, Parameter, Request Body, Response Schema, Authentifizierung. Gib eine strukturierte Uebersicht aller Endpunkte aus.")),
                        block("agentic", Map.of(
                                "goal", "Erstelle ein typisiertes Client-SDK in {{ target_language }} unter {{ package_name }}/. Das SDK soll enthalten: (1) Typen/Interfaces fuer alle Request/Response-Modelle, (2) Eine Client-Klasse mit Methoden fuer jeden Endpunkt, (3) Error Handling (HTTP-Fehler, Netzwerkfehler, Validierung), (4) Konfiguration (Base URL, Auth Token, Timeout), (5) package.json/setup.py/go.mod je nach Sprache, (6) README.md mit Installationsanleitung und Beispielen fuer jeden Endpunkt. Fuehre den Type-Check aus (tsc/mypy/go build) und behebe Fehler.",
                                "success_criteria", "Type-Check laeuft ohne Fehler.",
                                "max_iterations", 10
                        )),
                        block("simple", Map.of("command", "Liste alle generierten Dateien in {{ package_name }}/ auf. Gib eine Zusammenfassung: Anzahl Endpunkte, Anzahl Typen, Code-Zeilen, unterstuetzte Features."))
                ),
                "Generiere ein typisiertes Client-SDK mit Error Handling aus der API-Spezifikation.",
                null));

        templates.add(template("markdown-to-ebook", "\uD83D\uDCD6",
                Map.of("de", "Markdown \u2192 E-Book", "en", "Markdown \u2192 E-Book"),
                Map.of("de", "Markdown-Dateien zu einem formatierten HTML-E-Book mit Inhaltsverzeichnis und Print-CSS kombinieren",
                        "en", "Combine Markdown files into a formatted HTML e-book with table of contents and print CSS"),
                "TRANSFORM", order++,
                List.of(
                        block("simple", Map.of("command", "Finde alle Markdown-Dateien im Projekt (rekursiv, ausser node_modules und .git). Liste sie auf und analysiere: Dateiname, erste Zeile (vermutlicher Titel), Wortanzahl. Schlage eine sinnvolle Kapitel-Reihenfolge vor.")),
                        block("simple", Map.of("command", "Lies alle gefundenen Markdown-Dateien. Erstelle eine Zusammenfassung: (1) Gesamtstruktur (wieviele Kapitel, geschaetzte Seitenzahl), (2) Inhaltsuebersicht pro Kapitel (2-3 Saetze), (3) Thematische Kohaerenz — passen die Texte zusammen? Gibt es Luecken?")),
                        block("agentic", Map.of(
                                "goal", "Erstelle ein formatiertes E-Book in ebook/. Titel: '{{ title }}', Autor: '{{ author }}', Sprache: {{ language }}. Erstelle: (1) index.html — Hauptdatei mit allen Kapiteln, automatischem Inhaltsverzeichnis mit Ankern, Titelseite mit Autor und Titel, (2) style.css — Buch-typisches CSS: Serifen-Font, angenehme Zeilenbreite (max 70 Zeichen), Kapiteltrenner, Print-CSS mit Seitenumbruechen, (3) Fuege zwischen Kapiteln Trennseiten ein. Alle Markdown-Inhalte sollen korrekt in HTML konvertiert werden (Ueberschriften, Listen, Code-Blocks, Links, Bilder).",
                                "success_criteria", "index.html und style.css existieren in ebook/ und enthalten alle Kapitel.",
                                "max_iterations", 8
                        )),
                        block("simple", Map.of("command", "Pruefe ebook/index.html: Anzahl Kapitel, Inhaltsverzeichnis-Links funktionieren, Gesamtwortanzahl. Erstelle eine Zusammenfassung."))
                ),
                "Kombiniere die Markdown-Dateien zu einem formatierten HTML-E-Book mit Inhaltsverzeichnis.",
                null));

        templates.add(template("bug-to-fix", "\uD83D\uDC1B",
                Map.of("de", "Bug-Report \u2192 Fix + Tests", "en", "Bug Report \u2192 Fix + Tests"),
                Map.of("de", "Bug analysieren, fehlschlagenden Test schreiben, Fix implementieren und alle Tests verifizieren",
                        "en", "Analyze a bug, write a failing test, implement the fix, and verify all tests pass"),
                "CODE", order++,
                List.of(
                        block("simple", Map.of("command", "Lies {{ filepath }} und analysiere den Code. Suche nach dem beschriebenen Bug: {{ bug_description }}. Identifiziere die betroffene Funktion/Stelle und erklaere die Ursache.")),
                        block("agentic", Map.of(
                                "goal", "Behebe den Bug in {{ filepath }}. Bug: '{{ bug_description }}'. Vorgehen: (1) Verstehe den bestehenden Code und das Bug-Verhalten, (2) Schreibe zuerst einen Test der den Bug reproduziert (der Test muss fehlschlagen), (3) Implementiere den Fix, (4) Fuehre den Test erneut aus — er muss jetzt bestehen, (5) Fuehre alle existierenden Tests aus um sicherzustellen dass nichts kaputt geht.",
                                "success_criteria", "Alle Tests bestehen nach dem Fix.",
                                "max_iterations", 8
                        )),
                        block("simple", Map.of("command", "Erstelle einen Bug-Fix-Report: (1) Ursache des Bugs, (2) Was wurde geaendert (mit Diff-Ueberblick), (3) Welche Tests wurden hinzugefuegt, (4) Wie kann man verifizieren dass der Fix korrekt ist."))
                ),
                "Analysiere den Bug, schreibe einen reproduzierenden Test, behebe den Fehler und verifiziere.",
                null));

        templates.add(template("dependency-risk-radar", "\uD83E\uDDED",
                Map.of("de", "Dependency Risk Radar", "en", "Dependency Risk Radar"),
                Map.of("de", "Alle Repos scannen, kritische Paket-Updates priorisieren, Aktionsplan erstellen",
                        "en", "Scan all repos, prioritize critical package updates, create an action plan"),
                "CODE", order++,
                List.of(
                        block("shell", Map.of("command", "find . -maxdepth 2 \\( -name package.json -o -name pom.xml \\) -print | xargs -n1 dirname | sort -u")),
                        block("foreach", Map.of(
                                "variable_name", "repo",
                                "items_source", "command",
                                "items_command", "find . -maxdepth 2 \\( -name package.json -o -name pom.xml \\) -print | xargs -n1 dirname | sort -u",
                                "execution_mode", "shell",
                                "timeout_seconds", 600,
                                "command", "set -euo pipefail; cd \"{{ repo }}\"; if [ -f package.json ]; then echo \"=== {{ repo }} (npm) ===\"; npm outdated --json || true; elif [ -f pom.xml ]; then echo \"=== {{ repo }} (maven) ===\"; mvn -q versions:display-dependency-updates || true; else echo \"=== {{ repo }} (unknown) ===\"; fi"
                        )),
                        block("skill-capability", Map.of(
                                "capability", "analyze",
                                "input", "Aggregierte Dependency-Outputs aus mehreren Repos:\n\n{{ previous_output }}\n\nAufgabe: Fasse die Top-5 dringendsten Updates zusammen. Priorisiere Major/Critical. Nenne je Eintrag: Repo, Paket/Artefakt, current\u2192latest, Risiko (security/major/compat), kurzen Handlungsvorschlag.",
                                "params", Map.of("max_entries", 5)
                        )),
                        block("simple", Map.of("command", "Erstelle einen Aktionsplan aus der Analyse: Repo | Paket | current\u2192latest | Impact | Empfehlung. Halte dich kurz."))
                ),
                "Scanne alle Repos unterhalb des Projektpfads nach npm- oder Maven-Abhaengigkeiten, identifiziere kritische Updates und liefere einen priorisierten Aktionsplan.",
                null));

        templates.add(template("log-triage", "\uD83E\uDEB5",
                Map.of("de", "Log Triage (Cross-Repo)", "en", "Log Triage (Cross-Repo)"),
                Map.of("de", "Logs sammeln, Fehler gruppieren, Ursachen & Checks vorschlagen",
                        "en", "Collect logs, group errors, suggest causes and checks"),
                "CODE", order++,
                List.of(
                        block("shell", Map.of("command", "find . \\( -name \"*.log\" -o -name \"*.err\" \\) -not -path \"*/node_modules/*\" -not -path \"*/.git/*\" -mtime -1 -print -exec tail -200 {} \\;")),
                        block("skill-capability", Map.of(
                                "capability", "analyze",
                                "input", "Log-Auszug der letzten 24h aus mehreren Projekten:\n\n{{ previous_output }}\n\nGruppiere Fehler/Warnings, nenne vermutlich betroffene Services/Repos, moegliche Ursachen und empfohlene Checks. Markiere kritische Themen zuerst.",
                                "params", Map.of("max_groups", 5)
                        )),
                        block("simple", Map.of("command", "Fasse kurz zusammen: Anzahl gefundener Fehlergruppen, kritische Punkte, welche Repos/Services betroffen sind, empfohlene Sofortmassnahmen."))
                ),
                "Triagiert Logs der letzten 24h (logs/*.log), gruppiert Fehler und liefert Empfehlungen.",
                null));

        templates.add(template("test-matrix", "\u2705",
                Map.of("de", "Test-Matrix (Multi-Repo)", "en", "Test Matrix (Multi-Repo)"),
                Map.of("de", "Tests pro Stack (npm/mvn/gradle/pytest) ausf\u00fchren, Failures b\u00fcndeln, Matrix erstellen",
                        "en", "Run tests per stack (npm/mvn/gradle/pytest), consolidate failures, produce a matrix"),
                "CODE", order++,
                List.of(
                        block("shell", Map.of("command", "find . -maxdepth 2 \\( -name package.json -o -name pom.xml -o -name build.gradle -o -name build.gradle.kts -o -name pyproject.toml \\) -not -path \"*/node_modules/*\" -not -path \"*/.next/*\" -not -path \"*/dist/*\" -not -path \"*/build/*\" -print | xargs -n1 dirname | sort -u")),
                        block("foreach", Map.of(
                                "variable_name", "repo",
                                "items_source", "command",
                                "items_command", "find . -maxdepth 2 \\( -name package.json -o -name pom.xml -o -name build.gradle -o -name build.gradle.kts -o -name pyproject.toml \\) -not -path \"*/node_modules/*\" -not -path \"*/.next/*\" -not -path \"*/dist/*\" -not -path \"*/build/*\" -print | xargs -n1 dirname | sort -u",
                                "execution_mode", "shell",
                                "timeout_seconds", 900,
                                "command", "cd \"{{ repo }}\"; start=$(date +%s); echo \"=== {{ repo }} ===\"; if [ -f package.json ]; then has_test=$(node -e \"const p=require('./package.json'); process.exit(p.scripts&&p.scripts.test?0:1)\" 2>/dev/null && echo yes || echo no); if [ \"$has_test\" = \"no\" ]; then echo \"-- SKIP (no test script)\"; exit 0; fi; cmd=\"npm test -- --runInBand\"; elif [ -f pom.xml ]; then cmd=\"mvn -q test\"; elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then cmd=\"./gradlew test || gradle test\"; elif [ -f pyproject.toml ]; then cmd=\"pytest\"; else echo \"-- SKIP (unsupported stack)\"; exit 0; fi; echo \"$cmd\"; ($cmd) && status=0 || status=$?; dur=$(($(date +%s)-start)); if [ $status -ne 0 ]; then echo \"-- FAIL (exit $status, ${dur}s)\"; else echo \"-- OK (${dur}s)\"; fi; exit 0"
                        )),
                        block("skill-capability", Map.of(
                                "capability", "analyze",
                                "input", "Testergebnisse mehrerer Repos:\n\n{{ previous_output }}\n\nFasse fehlgeschlagene Repos/Tests zusammen. Nenne pro Failure: Repo, vermutete Ursache (aus Logauszug), empfohlene Fix-Richtung. Wenn alle gruen: kurzer OK-Report.",
                                "params", Map.of("summarize_failures", true)
                        )),
                        block("simple", Map.of("command", "Basierend auf den bisherigen Ergebnissen: Erstelle NUR eine Markdown-Tabelle als Test-Matrix mit den Spalten: Repo | Stack | Ergebnis (OK/FAIL/SKIP) | Dauer | Empfehlung. Lies KEINE Dateien und fuehre KEINE Befehle aus — nutze ausschliesslich die Daten aus {{ previous_output }}. Antworte direkt mit der Tabelle.", "timeout_seconds", 600))
                ),
                "Fuehrt tests build-abh\u00e4ngig (npm/mvn/gradle/pytest) \u00fcber alle Repos im Projektpfad aus, sammelt Logs bei Fehlschlag und erzeugt eine kurze Entscheidungs-Matrix.",
                null));

        templates.add(template("hunt-quality-pipeline", "\uD83D\uDD0E",
                Map.of("de", "Hunt-Qualit\u00e4ts-Pipeline", "en", "Hunt Quality Pipeline"),
                Map.of("de", "Schnitzeljagden verifizieren, Fakten pr\u00fcfen, Regeln checken und gezielt reparieren",
                        "en", "Verify treasure hunts, fact-check content, validate rules, and fix issues"),
                "QUALITY", order++,
                List.of(
                        block("shell", Map.of("command", "cd /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level && rm -f reports/verify_*.md && python3 scripts/qa_analyze_hunts.py hunts/Bahnorte/ 2>&1 | grep -E '(Risk Score|WARN|Template|dupliziert)' | head -40")),
                        block("simple", Map.of("command", "Analysiere das QA-Ergebnis: {{ previous_output }}. Identifiziere die {{ batch_size }} Hunts mit dem hoechsten Risiko. Gib NUR die Ort-Slugs als komma-separierte Liste zurueck, NICHTS anderes. Beispiel-Format: bork,diepholz,kemmlitz")),
                        block("agentic", Map.of(
                                "goal", "Verifiziere die folgenden Schnitzeljagden (Slug-Liste): {{ previous_output }}\n\nPROJEKT-PFAD: /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level\nJede Hunt liegt unter: /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level/src/Bahnorte/[slug]/manifest.json\nReports schreiben nach: /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level/reports/verify_[slug].md\n\nWICHTIG FUER JEDE AKTION: Jede ACTION wird von einem SEPARATEN Agenten ausgefuehrt der KEINEN Zugriff auf deine Konversation hat. Deshalb MUSS jede ACTION enthalten:\n- ABSOLUTE Dateipfade (z.B. /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level/src/Bahnorte/kemmlitz/manifest.json)\n- Die konkreten Slug-Namen\n- Was genau zu tun ist\nSchreibe NIEMALS vage Aktionen wie \"Lies alle Manifeste\".\n\nArbeite eine Hunt nach der anderen ab. Fuer JEDE Hunt:\n1. Lies /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level/src/Bahnorte/[slug]/manifest.json\n2. REGEL-CHECK: Mind. 8-12 Tasks? Mind. 3 Solution-Types? 3 Hints (5/8/12)? Einfuehrungstext? contentText 2-3 Saetze? Antworten nicht verraten? Spannungsbogen? Logische Route? Hunt-ID ASCII?\n3. FAKTEN-CHECK: Historische Fakten, Sehenswuerdigkeiten, Angaben korrekt?\n4. GPS-CHECK: Koordinaten im richtigen Ort? Radien 50-150m? Keine Duplikate?\n5. QUALITAETS-SCORE 1-10: Inhaltliche Tiefe, Story-Kohaerenz, Aufgaben-Vielfalt, Lokaler Bezug\n\nSchreibe Report nach: /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level/reports/verify_[slug].md",
                                "success_criteria", "Fuer jede Hunt existiert ein Verifizierungsbericht in reports/ mit Qualitaets-Score und konkreten Findings",
                                "max_iterations", 15,
                                "iteration_timeout_seconds", 600,
                                "total_timeout_seconds", 3600,
                                "max_total_tokens", 250000,
                                "max_repeated_actions", 3,
                                "require_approval", false
                        )),
                        block("agentic", Map.of(
                                "goal", "Behebe die Probleme in den folgenden Hunts. Hier ist das Ergebnis der Verifizierung:\n\n{{ previous_output }}\n\nPROJEKT-PFAD: /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level\n\nARBEITE NUR AN DEN OBEN GENANNTEN HUNTS. Lies fuer jeden Slug den Verifizierungsbericht:\n/home/flo/Entwicklung/IdeaProjects/treasure_hunt_level/reports/verify_[slug].md\n\nWICHTIG FUER JEDE AKTION: Jede ACTION wird von einem SEPARATEN Agenten ausgefuehrt der KEINEN Zugriff auf deine Konversation hat. Deshalb MUSS jede ACTION enthalten:\n- ABSOLUTE Dateipfade\n- Den konkreten Slug-Namen\n- Welche Probleme behoben werden sollen\n- Was genau zu tun ist\n\nARBEITE EINE HUNT NACH DER ANDEREN AB. Pro Hunt:\n1. Lies /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level/reports/verify_[slug].md\n2. Lies /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level/src/Bahnorte/[slug]/manifest.json\n3. Behebe NUR die gefundenen Probleme (nicht alles neu schreiben!)\n4. Speichere manifest.json\n5. Baue: cd /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level/src/Bahnorte/[slug] && zip -q ../../../hunts/Bahnorte/bahnhof_[slug].hunt manifest.json\n6. Validiere: cd /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level && python3 scripts/qa_analyze_hunts.py hunts/Bahnorte/bahnhof_[slug].hunt\n\nReparatur-Regeln:\n- Regel-Verstoesse: Fehlende Hints ergaenzen, Solution-Type-Mix anpassen\n- Fakten-Fehler: Falsche Behauptungen korrigieren\n- Template-Fragen: Durch ortsspezifische Fragen ersetzen\n- GPS-Probleme: Koordinaten korrigieren\n- Generischer Content: Nur Tasks mit Score < 5 komplett ueberarbeiten",
                                "success_criteria", "Alle reparierten Hunts haben einen besseren QA-Score als vorher und 0 Template-Fragen-Warnings",
                                "max_iterations", 20,
                                "iteration_timeout_seconds", 600,
                                "total_timeout_seconds", 3600,
                                "max_total_tokens", 300000,
                                "max_repeated_actions", 3,
                                "require_approval", false
                        )),
                        block("shell", Map.of("command", "cd /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level && python3 scripts/qa_analyze_hunts.py hunts/Bahnorte/ 2>&1 | tail -30")),
                        block("simple", Map.of("command", "Lies die Verifizierungsberichte unter /home/flo/Entwicklung/IdeaProjects/treasure_hunt_level/reports/verify_*.md und das finale QA-Ergebnis: {{ previous_output }}. Erstelle einen Gesamtbericht:\n1. Welche Hunts wurden geprueft\n2. Gefundene Probleme pro Kategorie (Regeln, Fakten, GPS, Qualitaet)\n3. Was wurde repariert\n4. Vorher/Nachher QA-Score-Vergleich\n5. Hunts die weitere manuelle Pruefung brauchen"))
                ),
                "Du bist ein Qualitaetspruefer fuer Schnitzeljagden. Deine Aufgabe ist es, Hunts zu VERIFIZIEREN und nur gezielt zu reparieren.\n\nREGELN (muessen alle eingehalten werden):\n- 8-12 Tasks pro Hunt (ideal: 10)\n- Mind. 3 verschiedene Solution-Types (geoLocation, multipleChoice, exact, flexible)\n- 3 Hints pro Task mit Kosten 5/8/12\n- Antworten NIEMALS in Frage oder Hints verraten\n- Einfuehrungstext mit Story-Setting und Guide-Charakter\n- contentText: mind. 2-3 Saetze (Atmosphaere, Kontext, Ueberleitung zur Frage)\n- Logische Route (kein Zickzack)\n- GPS-Koordinaten muessen im Ort liegen, Radius 50-150m\n- Hunt-ID: nur ASCII, Texte: echte Umlaute\n- Spannungsbogen: leichter Start, Steigerung, starkes Finale\n\nQUALITAETSKRITERIEN:\n- Echte lokale Fakten (keine generischen Texte)\n- Historisch korrekte Angaben (verifizieren!)\n- Ortsspezifische Fragen (nicht austauschbar)\n- Kohaerente Story mit rotem Faden\n- Keine Template-Fragen (\"Wie heisst dieser Ort?\", \"Gefaellt dir X?\")\n\nWICHTIG: Nicht alles neu schreiben! Nur das reparieren, was kaputt ist.",
                "claude-sonnet-4-6"));

        templates.add(template("folder-backup", "\uD83D\uDCBE",
                Map.of("de", "Ordner-Backup (tar.gz)", "en", "Folder Backup (tar.gz)"),
                Map.of("de", "Mehrere Ordner per Shell in komprimierte Archive sichern, inklusive Checksummen",
                        "en", "Backup multiple folders via shell into compressed archives with checksums"),
                "DATA", order++,
                List.of(
                        block("shell", Map.of("command", "mkdir -p backup")),
                        block("foreach", Map.of(
                                "variable_name", "item",
                                "items_source", "command",
                                "items_command", "find . -mindepth 1 -maxdepth 1 -type d ! -name backup -exec basename {} \\;",
                                "execution_mode", "shell",
                                "timeout_seconds", 900,
                                "command", "set -euo pipefail; src=\"{{ item }}\"; ts=\"$(date +%Y%m%d-%H%M%S)\"; tar -czf backup/\"${src}-${ts}.tgz\" \"$src\"; sha256sum backup/\"${src}-${ts}.tgz\""
                        )),
                        block("shell", Map.of("command", "ls -lh backup | sort -k5,5h")),
                        block("simple", Map.of("command", "Pruefe das Backup-Verzeichnis backup. Liste die erstellten Archive mit Groesse und Checksumme. Markiere, falls ein Ordner nicht gesichert wurde."))
                ),
                "Sichere alle Unterordner des aktuellen Projektverzeichnisses (ausser backup) in komprimierte Tar-Archive mit Zeitstempel nach backup/. Keine KI-Beteiligung, reine Shell-Ausfuehrung.",
                null));

        return templates;
    }

    private static WorkflowTemplate template(String id, String icon,
                                              Map<String, String> name, Map<String, String> description,
                                              String category, int sortOrder,
                                              List<BlockDefinition> blocks,
                                              String instructions, String model) {
        return WorkflowTemplate.builder()
                .id(id)
                .icon(icon)
                .name(name)
                .description(description)
                .category(category)
                .sortOrder(sortOrder)
                .blocks(blocks)
                .instructions(instructions)
                .model(model)
                .builtIn(true)
                .version("1.0.0")
                .build();
    }

    private static BlockDefinition block(String type, Map<String, Object> config) {
        return BlockDefinition.builder()
                .type(type)
                .config(new java.util.HashMap<>(config))
                .build();
    }
}

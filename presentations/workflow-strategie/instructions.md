# Präsentations-Auftrag: Workflow-Strategie

## Ziel
Erstelle eine vollständige, visuell beeindruckende reveal.js HTML-Präsentation.

## Thema
Warum es entscheidend ist, die RICHTIGEN Dinge in Workflows abzubilden, wiederausführbar zu machen und wo möglich zu persistieren — damit man nur dort von KI-Modellen abhängig ist, wo es wirklich nötig ist.

## Pflicht-Slides (mindestens 12 Slides)

1. **Titelslide** — Titel, Untertitel, Datum, modernes Design
2. **Das Problem** — Warum "alles mit KI" teuer, langsam und fragil ist
3. **Die Kosten-Pyramide** — Mermaid-Diagramm: Was kostet was? (API-Calls vs. Shell vs. Persistiert)
4. **Determinismus vs. KI** — Wann braucht man wirklich ein Modell? Entscheidungsmatrix
5. **Workflow-Anatomie** — Mermaid-Flowchart: Idealer Workflow mit Shell, Simple, Agentic Blöcken
6. **Persistierung als Schlüssel** — Ergebnisse speichern statt neu generieren. Beispiele.
7. **Wiederausführbarkeit** — Idempotente Workflows, reproduzierbare Ergebnisse
8. **Die richtige Block-Wahl** — Vergleichstabelle: Shell vs Simple vs Agentic — wann was?
9. **Kosten-Analyse** — Mermaid-Diagramm: Token-Verbrauch naiver vs. optimierter Workflow
10. **Risiko-Matrix** — Was passiert wenn das Modell sich ändert/ausfällt? Mermaid-Quadrant
11. **Best Practices** — 5-7 konkrete Regeln für gute Workflow-Architektur
12. **Vision** — Selbstverbessernde Plattform die aus Runs lernt und Workflows kristallisiert
13. **Zusammenfassung & Call-to-Action**

## Design-Anforderungen
- reveal.js 5.x via CDN (https://cdn.jsdelivr.net/npm/reveal.js@5.1.0/)
- Mermaid.js via CDN für Diagramme (https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js)
- Dunkles, professionelles Theme (eigenes CSS, NICHT das Standard-Theme)
- Farbpalette: Dunkelblau (#0a1628), Akzentfarben Cyan (#00d4ff) und Orange (#ff6b35)
- Smooth Animationen und Übergänge (fade, slide)
- Responsive, gute Lesbarkeit
- Fragment-Animationen für stufenweisen Aufbau
- Mindestens 4 Mermaid-Diagramme (Flowcharts, Pie, Quadrant, Sequence)
- Professionelle Typografie (Inter oder System-Font)
- Icons via Unicode-Emoji oder SVG inline
- Alles in EINER index.html Datei (self-contained)
- Mermaid-Diagramme müssen nach reveal.js-Init mit mermaid.run() gerendert werden

## Technische Hinweise
- Mermaid-Blöcke: <pre class="mermaid"> nicht <div class="mermaid">
- Mermaid init: Nach Reveal.initialize() → mermaid.initialize({startOnLoad:false, theme:'dark'}) → Reveal.on('ready', () => mermaid.run())
- Slide-Wechsel: Reveal.on('slidechanged', () => mermaid.run()) für lazy rendering

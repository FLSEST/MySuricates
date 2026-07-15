# Design: Workflow „Urlaubs-Scout"

**Datum:** 2026-07-15
**Status:** Freigegeben (Brainstorming mit Flo)
**Tracking-TODO:** `afbaed77-480a-4c24-8aff-dabdd527492f`

## Ziel

Wiederverwendbarer Workflow, der bei der Urlaubssuche unterstützt: Anreise (bevorzugt Bahn/Nightjet),
Ferienwohnungs-/Ferienpark-Angebote in mehreren Preisklassen, familientaugliche Ausflüge — gebündelt
auf **einer** HTML-Seite mit klickbaren Links zu den Angeboten.

**Erster Anwendungsfall:** Niederlande im Fenster 17.08.–28.08.2026, Nightjet ab Basel,
2 Erwachsene + 2 Kinder (beide 6–14), Dauer-Varianten 7 vs. 11 Nächte, 2–3 Regionen im Vergleich,
verschiedene Preisklassen (kein festes Budget-Limit).

## Projekt & Ablage

- **Neues MySuricates-Projekt „Urlaub"** — ID `a9a216d4-a10a-4c21-88c9-d71d7174ed32`,
  Typ `PERSONAL`, Pfad `~/dokumente/urlaub`.
- Der Projektordner ist Arbeitsverzeichnis der Claude-Recherche-Blöcke und Ablageort der Ergebnisse:
  `scout-<ziel-slug>-<jahr-monat>.html` (stabiler Name pro Suche, wird bei erneutem Lauf überschrieben)
  plus Archivkopie `archiv/scout-<slug>-{{ run_timestamp }}.html`.
- Workflow-JSON wird in `workflow-pool/urlaubs-scout.json` versioniert (Repo autotrigger).

## Entscheidung: Ansatz

Gewählt: **strukturierte Recherche-Pipeline** (statt einem agentic Deep-Research-Block oder
interaktivem Zwei-Phasen-Lauf). Begründung: Sparsamkeits-Leiter (3 fokussierte Simple-Calls statt
N agentic Iterationen), vorhersagbare Kosten, pro Block debugbar, deterministisches Rendering.

## Pipeline (5 Blöcke, Workflow-Modell: `claude-opus-4-6`)

| # | Typ | Name | Zweck | Timeout |
|---|---|---|---|---|
| 0 | human-input | `params` | Multi-Field-Formular, Defaults vorbelegt (s. u.); `use_default_on_timeout: true`, 10 min | — |
| 1 | simple | `transport` | Nightjet {{ origin }} → Ziel: Verbindungen, Kategorien (Sitz/Liege/Schlaf), Familienpreise, Buchungswege (nightjet.com, sbb.ch, bahn.de), Verfügbarkeitshinweise; Tageszug-Alternative in 2 Sätzen. Web-Recherche via WebSearch/WebFetch. Output: striktes JSON | 600 s |
| 2 | simple | `lodging` | 2–3 Regionen bestimmen (gemäß `regions_hint`, Anreise-Kompatibilität aus `{{ block.transport.output }}`); je Region 2–4 konkrete Unterkunfts-Angebote (Ferienwohnung/Ferienpark) in verschiedenen Preisklassen, je Dauer-Variante, mit Link/Preis/Kapazität. Output: striktes JSON | 600 s |
| 3 | simple | `activities` | Pro Region (aus `{{ previous_output }}`) 4–6 familientaugliche Ausflüge (Kinder-Alter aus `child_ages`), mit Links, grober Eintritt. Output: striktes JSON. `on_failure: continue` | 600 s |
| 4 | shell | `render` | Python (nur stdlib): JSONs aus `{{ block.transport.output }}`, `{{ block.lodging.output }}`, `{{ previous_output }}` defensiv parsen (Fences strippen), **eine** selbstständige HTML-Seite rendern, Deep-Links deterministisch generieren, Datei schreiben + `xdg-open` | 120 s |

Kein Setup-/Persist-Block nötig: `{{ block.<name>.output }}` macht alle Recherche-Outputs im
Render-Block verfügbar.

## Parameterformular (Block 0)

| Variable | Typ | Default (erste Suche) |
|---|---|---|
| `destination` | text | `Niederlande` |
| `origin` | text | `Basel` |
| `window_start` | text | `2026-08-17` |
| `window_end` | text | `2026-08-28` |
| `durations` | text | `7,11` (Nächte-Varianten, kommasepariert) |
| `adults` | text | `2` |
| `child_ages` | text | `10,13` (kommasepariert; steuert Bahn-Familienpreis-Recherche + Deep-Links) |
| `transport_pref` | text | `Nightjet ab Basel bevorzugt, Alternativen kurz erwähnen` |
| `regions_hint` | text | `flexibel — 2–3 Regionen vergleichen (z. B. Küste, Amsterdam-Umland)` |
| `notes` | text | `Ausflugsmöglichkeiten in der Nähe wichtig` |

Headless-Läufe (Schedule/API) laufen nach 10 min mit den Defaults weiter.

## JSON-Verträge (Kurzform)

- `transport`: `{ "connections": [{ "route", "depart", "arrive", "categories": [{ "name", "price_estimate" }], "family_note", "booking_links": [{ "label", "url" }] }], "alternative": "…", "notes": "…" }`
- `lodging`: `{ "regions": [{ "name", "why", "arrival_hint", "offers": [{ "title", "type", "tier": "günstig|mittel|komfort", "sleeps", "price_estimate", "duration_nights", "url", "note" }] }] }`
- `activities`: `{ "regions": [{ "name", "excursions": [{ "title", "category", "distance_hint", "price_hint", "url" }] }] }`

Die Prompts enthalten das jeweilige Schema wörtlich plus die Regeln: **nur valides JSON, kein
Markdown-Fence, keine Dateien schreiben, keine erfundenen URLs** (URLs nur aus tatsächlich
besuchten/gefundenen Quellen; lieber Feld weglassen als raten).

## HTML-Ergebnisseite

Abschnitte in dieser Reihenfolge:

1. **Kopf**: Suchparameter (Ziel, Fenster, Personen, Dauer-Varianten), Stand-Zeitstempel
2. **Anreise**: Verbindungstabelle, Familienpreis-Hinweise, Buchungslinks
3. **Preis-Übersicht**: Matrix Region × Dauer mit Preisspanne (günstig → komfort)
4. **Region-Karten**: Beschreibung/Warum, Unterkunfts-Angebote (Tier-Badge, Preis, Link), Ausflüge
5. **Deep-Link-Panel**: „Selbst weitersuchen" — Booking.com- und Airbnb-Suchlinks mit
   vorausgefüllten Daten (Checkin/Checkout je Dauer-Variante, 2 Erwachsene, Kinder mit Altern)
   pro Region; Nightjet-/SBB-/NS-International-Buchungsseiten. Deterministisch aus den
   Formular-Variablen generiert — kann nicht halluziniert sein oder veralten.
6. **Fußzeile**: Disclaimer „Preise sind Rechercheergebnisse — beim Buchen verifizieren",
   Run-ID, Workflow-Name

Selbstständiges Inline-CSS (kein CDN), hell/dunkel via `prefers-color-scheme`, druckbar.
Template liegt inline im Render-Block (self-contained Workflow, keine externe Template-Datei).

## Robustheit

- **Dual-Timeout-Falle**: `timeout_seconds` pro Block explizit gesetzt (Recherche 600 s, Render 120 s)
- **Simple-Block-Fallen**: explizite Keine-Dateien-Anweisung; Fences werden im Render trotzdem gestrippt
- **JSON-Fehler**: Render failt mit klarer Meldung, welcher Block kaputtes JSON lieferte
- **`activities` optional**: `on_failure: continue` — Seite wird auch ohne Ausflüge gerendert (mit Hinweis)
- **Shell-Hygiene**: `set -euo pipefail`, Ausgabe nur in den Projektordner, keine REST-/DB-Zugriffe
- **Keine externen Abhängigkeiten**: Python-stdlib only

## Test & Abnahme

1. Workflow via `POST /api/v1/workflows` auf Dev (9090) anlegen, JSON zusätzlich in `workflow-pool/` ablegen
2. Render-Block-Python vorab lokal gegen Beispiel-JSONs testen (shellEscape-Emulation)
3. Erster Lauf mit NL-Defaults: Block-Outputs einzeln prüfen (valides JSON, plausible Links)
4. HTML im Browser sichten, Links stichprobenartig öffnen
5. TODO `afbaed77` auf DONE, Commit (kein Push)

## Nicht-Ziele (YAGNI)

- Kein Preis-Monitoring/Schedule (kann später als Schedule auf denselben Workflow gelegt werden)
- Keine Buchungsautomatisierung, keine Login-/Scraping-Umgehungen
- Kein eigener Dashboard-Server (statische Datei + `xdg-open` reicht)

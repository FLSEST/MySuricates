# Konzepte zum Übernehmen

Diese Seiten erklären Mechanismen von MySuricates: wann sie helfen, was sie
kosten und wo ihre Grenzen liegen. Jede Seite verbindet ein Ablaufdiagramm mit
Codebelegen und einer datierten Betriebsmessung. Die Zahlen beschreiben eine
einzelne Entwicklungsinstallation; sie belegen keinen allgemeinen Nutzen.

| Mechanismus | Leitfrage |
|---|---|
| [Gates](gates.md) | Wie wird aus einer Ausgabe ein maschinenlesbares Urteil? |
| [Selbstverbesserung](selbstverbesserung.md) | Wie werden aus Befunden begrenzte Korrekturaufträge? |
| [Persistenz und Herkunft](persistenz-herkunft.md) | Welche Definition wurde durch welchen Anlass ausgeführt? |
| [Agentische Budgets](agentische-budgets.md) | Wie lässt sich eine offene KI-Aufgabe begrenzen? |
| [Zugänge](zugaenge.md) | Wie führen UI, Plugin, Zeitplan und Ereignis zum selben Ablauf? |
| [Messen vor Bewerten](messen-vor-bewerten.md) | Welche Arbeit erledigen Messwerkzeuge, welche die KI? |
| [Workflow-Memory](workflow-memory.md) | Wie gelangt verdichtetes Laufwissen in spätere Aufrufe? |
| [Härtung](haertung.md) | Was schützen Siegel, Zulassungsprüfung und Taint tatsächlich? |

## Messmethode und Grenzen

Der [Fakten-Snapshot](betriebsdaten.json) wurde am **19.09.2026 (UTC)** über die
REST-API erhoben. Er enthält ausschließlich ausgewählte Zähler und deren
Definitionen. Namen, Aufgabenbeschreibungen, Ausgaben, Instanz-IDs, private Pfade
und Zugangsdaten werden nicht übernommen. Die Abfragen erfolgen nacheinander;
laufende Arbeit kann den Bestand während der Erhebung verändern.

- `/workflows` liefert nicht gelöschte Workflows **in aktiven Projekten**. Deshalb
  sind die 263 sichtbaren Definitionen keine Gesamtzahl einschließlich archivierter
  Projekte. Ein `runCount` von null bedeutet **keinen gespeicherten Lauf**; nach
  Retention lässt sich daraus nicht ableiten, dass ein Workflow niemals lief.
- `/todos` wird über alle Seiten gelesen. Ein leeres `resolutionState` betrifft
  den optionalen manuellen Resolve-Pfad. Es misst weder die allgemeine
  Verifikationsabdeckung noch den Erfolg automatischer Fixes. Eine ausgefüllte
  `sessionId` garantiert kein noch vorhandenes, fortsetzbares Transkript.
- `/learning/dashboard?days=30` liefert unter anderem **Schrittzahlen der letzten
  30 Tage**. Gezählt werden erhaltene Datensätze mit `blockType`-Metadaten;
  Schleifen-Wrapper zählen einmal. Ein `foreach` kann KI aufrufen und darf nicht
  pauschal als deterministische Arbeit gezählt werden. `totalRuns` desselben
  Endpunkts umfasst dagegen **alle erhaltenen Runs**, unabhängig vom Fenster.
- `/learning/platform-stats` liefert aktuelle Konfigurationsbestände. Aktive
  Zeitpläne oder Memory-Einträge belegen keine Nutzung oder Wirksamkeit. Die sechs
  aktiven Memory-Einträge sind ein installationsweiter Zähler; geteilt durch die
  Workflows aktiver Projekte ergeben sie keine belastbare Abdeckungsquote.

Die früheren Zahlen aus Planungstickets werden nicht fortgeschrieben: Retention,
Archivierung und unterschiedliche Bezugsgrößen verhindern einen direkten
Zeitvergleich. Vor einer Veröffentlichung oder Neugenerierung erneut messen.

## Seiten erneut erzeugen

Voraussetzungen sind dieses Repository, Python 3, eine laufende MySuricates-Instanz
und ein eingerichteter KI-Provider. Der mitgelieferte Workflow nutzt PI mit dem
Modell des bestehenden Docs-Sync-Wegs. Der Provider ist am KI-Block gesetzt,
damit er auch nach einer Pool-Installation erhalten bleibt.

1. In einer Operator-Sitzung den API-Key über `MYSURICATES_API_KEY` bereitstellen
   und den anonymen Snapshot erheben:

   ```bash
   python3 scripts/concepts/collect_facts.py --output docs/konzepte/betriebsdaten.json
   ```

   Für eine andere Instanz `--api-base` verwenden. Dieses Skript gehört **vor** den
   Workflow-Start: Workflow-Blöcke rufen weder die eigene REST-API noch die DB auf.

2. [Konzeptseiten-Workflow](../../workflow-pool/mysuricates-concept-pages.json)
   installieren und manuell starten. `concept_root` auf den absoluten Pfad des
   gewünschten Checkouts setzen; `concept_facts` auf dessen Snapshot-Datei.
   `concept_output` bestimmt das Ausgabe-Basisverzeichnis, standardmäßig
   `/tmp/mysuricates-concept-pages`.

   Der Ablauf ist `shell → foreach → shell → shell`: Faktenpakete prüfen, genau
   ein KI-Aufruf je Mechanismus, feste Diagramme und Messwerttabellen ergänzen,
   abschließend deterministisch prüfen. Der Snapshot darf beim Start höchstens
   24 Stunden alt sein. Jeder Run hat ein eigenes Ausgabeverzeichnis.

   `concept_only` begrenzt den Lauf auf einzelne Seiten: eine Kommaliste von Slugs
   aus dem Katalog, etwa `zusammenstoepseln,anbieter-unabhaengig`. Leer (Standard)
   heißt alle Seiten. Die Schleife holt ihre Einträge mit `pages.py list`; ein
   unbekannter Slug bricht schon beim ersten Schritt ab.

3. Unter `<concept_output>/<run-id>/de/` die erzeugten Seiten fachlich lesen. Das Gate
   prüft Vollständigkeit, Abschnitte, Quellenlinks, Messwerte, Snapshot-Hash,
   unveränderte Diagramme und offensichtliche private Marker. Es ersetzt keine
   inhaltliche Prüfung und garantiert keine vollständige Anonymisierung.
   Erst nach erfolgreicher Prüfung die Seiten nach `docs/konzepte/` übernehmen.
   Fehlt einer Seite die Zeile in der Übersicht oben, liegt daneben eine `README.md`
   mit den zusätzlichen Zeilen (Bezeichnung und Leitfrage aus dem Katalog);
   bestehende Zeilen bleiben unverändert. Sie wird mit übernommen.

   Mit `concept_only` prüft das Gate nur die gewählten Seiten. Die übrigen behalten
   Hash und Messwerte ihres Snapshots. Wird für den gefilterten Lauf ein neuer
   Snapshot nach `docs/konzepte/` übernommen, passen die übrigen Seiten nicht mehr
   dazu; die Prüfung ohne Filter meldet das, bis alle Seiten neu erzeugt sind.

4. Die [begrenzte Variante des Docs-DE→EN-Sync](../../workflow-pool/mysuricates-concept-pages-en.json)
   starten. `translation_root` bezeichnet denselben absoluten Checkout-Pfad.
   Sie verwendet den Übersetzungsauftrag des bestehenden Delta-Syncs für alle
   Seiten plus den Index. Jede Datei wird einzeln übersetzt; anschließend
   setzt das Skript die englischen Diagrammlabels und korrigiert die durch die
   zusätzliche Verzeichnisebene geänderten Quellenlinks. Ziel: `docs/en/konzepte/`.
   Vorhandene Übersetzungen in diesem Verzeichnis werden ersetzt. Mit demselben
   `concept_only` wie im deutschen Lauf werden nur diese Seiten übersetzt; den Index
   übersetzt die KI dann nicht, das Skript ergänzt dort nur die fehlenden Zeilen
   aus dem Katalog.

Die Workflow-Definitionen sind auf dieses Repository angewiesen und deshalb im
Pool als `scope: repo` gekennzeichnet. Sie führen keine Commits oder Pushes aus.
Die deutsche Fassung bleibt die Quelle; englische Korrekturen laufen über denselben
Übersetzungsweg.

## Lokale Prüfung

```bash
python3 -m unittest discover -s scripts/tests -p 'test_concept_pages.py'
python3 scripts/concepts/pages.py check --root . --work docs/konzepte --facts docs/konzepte/betriebsdaten.json
python3 scripts/concepts/pages.py check --root . --work docs/en/konzepte --facts docs/konzepte/betriebsdaten.json --language en
# nur einzelne Seiten:
python3 scripts/concepts/pages.py check --root . --work docs/konzepte --facts docs/konzepte/betriebsdaten.json --only gates,haertung
```

Der [Katalog](../../scripts/concepts/catalog.json) ist die gemeinsame Quelle für
Themen, Codebelege, beide Sprachfassungen der Mermaid-Diagramme und die Zeilen der
Übersicht. Die Kommentare
je Seite enthalten den geprüften Quell-Commit und den SHA-256 des Fakten-Snapshots.

# Konzepte zum Übernehmen

Diese Seiten erklären Mechanismen von MySuricates: wann sie helfen, was sie
kosten und wo ihre Grenzen liegen. Jede Seite verbindet ein Ablaufdiagramm mit
Codebelegen.

Vier davon – Gates, Selbstverbesserung, Workflow-Memory und Agentische Budgets – sind gebaut, um je eine
Forschungsfrage zu untersuchen. Gebaut heißt: sie laufen. Es heißt nicht, dass die Frage beantwortet ist.

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

## Seiten erneut erzeugen

Voraussetzungen sind dieses Repository, Python 3, eine laufende MySuricates-Instanz
und ein eingerichteter KI-Provider. Der mitgelieferte Workflow nutzt PI mit dem
Modell des bestehenden Docs-Sync-Wegs. Der Provider ist am KI-Block gesetzt,
damit er auch nach einer Pool-Installation erhalten bleibt.

1. [Konzeptseiten-Workflow](../../workflow-pool/mysuricates-concept-pages.json)
   installieren und manuell starten. `concept_root` auf den absoluten Pfad des
   gewünschten Checkouts setzen. `concept_output` bestimmt das Ausgabe-Basisverzeichnis,
   standardmäßig `/tmp/mysuricates-concept-pages`.

   Der Ablauf ist `shell → foreach → shell → shell`: Belegpakete aus Katalog und
   Quellcode zusammenstellen, genau ein KI-Aufruf je Mechanismus, feste Diagramme
   und Quellenlinks ergänzen, abschließend deterministisch prüfen. Jeder Run hat
   ein eigenes Ausgabeverzeichnis.

   `concept_only` begrenzt den Lauf auf einzelne Seiten: eine Kommaliste von Slugs
   aus dem Katalog, etwa `zusammenstoepseln,anbieter-unabhaengig`. Leer (Standard)
   heißt alle Seiten. Die Schleife holt ihre Einträge mit `pages.py list`; ein
   unbekannter Slug bricht schon beim ersten Schritt ab.

2. Unter `<concept_output>/<run-id>/de/` die erzeugten Seiten fachlich lesen. Das Gate
   prüft Vollständigkeit, Abschnitte, Quellenlinks, Quell-Commit,
   unveränderte Diagramme und offensichtliche private Marker. Es ersetzt keine
   inhaltliche Prüfung und garantiert keine vollständige Anonymisierung.
   Erst nach erfolgreicher Prüfung die Seiten nach `docs/konzepte/` übernehmen.
   Fehlt einer Seite die Zeile in der Übersicht oben, liegt daneben eine `README.md`
   mit den zusätzlichen Zeilen (Bezeichnung und Leitfrage aus dem Katalog);
   bestehende Zeilen bleiben unverändert. Sie wird mit übernommen.

   Mit `concept_only` prüft das Gate nur die gewählten Seiten; die übrigen bleiben,
   wie sie sind.

3. Die [begrenzte Variante des Docs-DE→EN-Sync](../../workflow-pool/mysuricates-concept-pages-en.json)
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
python3 scripts/concepts/pages.py check --root . --work docs/konzepte
python3 scripts/concepts/pages.py check --root . --work docs/en/konzepte --language en
# nur einzelne Seiten:
python3 scripts/concepts/pages.py check --root . --work docs/konzepte --only gates,haertung
```

Der [Katalog](../../scripts/concepts/catalog.json) ist die gemeinsame Quelle für
Themen, Codebelege, beide Sprachfassungen der Mermaid-Diagramme und die Zeilen der
Übersicht. Der Kommentar je Seite enthält den geprüften Quell-Commit.

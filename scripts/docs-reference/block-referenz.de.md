# Block-Referenz

> **Scope:** Alle Config-Schlüssel jedes Block-Typs, ab „Gemeinsame Felder“ erzeugt aus
> `ai-automation-frontend/src/data/blockConfigSchema.json` (`python3 scripts/docs-reference.py --write`,
> siehe [Entwicklung → Erzeugte Referenzen](entwicklung.md#erzeugte-referenzen)). Aus derselben Datei
> nimmt der Block-Editor seine Vorschläge und der Analyzer seine Prüfung. Wozu ein Typ taugt, ein Beispiel
> und die Fallen stehen im [Workflow-Guide, Kapitel 2](WORKFLOW-GUIDE.md#2-block-typ-referenz).

Ein Block ist `{ "type": …, "name": …, "config": { … } }`; die Tabellen beschreiben die Schlüssel in
`config`. **Pflicht** „ja“ heißt: muss gesetzt sein; „einer von“: genau einer dieser Schlüssel, ein leerer
Text gilt als nicht gesetzt. **Werte** nennt die erlaubten Werte mit ihrer Bedeutung, dazu Untergrenzen
(„≥ 1“). „Gelesen nur mit …“ heißt: Der Executor liest den Schlüssel nur, wenn die genannten anderen
Schlüssel so gesetzt sind. Unbekannte Schlüssel ignoriert das Backend; Editor und Analyzer melden sie.

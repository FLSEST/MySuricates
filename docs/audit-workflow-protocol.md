# Audit-Workflow-Protokoll

Das Audit Center führt selbst keine fachlichen Prüfungen aus. Regeln, Datensammlung,
Bewertung und Report-Erzeugung bleiben vollständig in normalen Workflows. Die Plattform
stellt nur ein opt-in Protokoll für versionierte Reports, stabile Findings und TODO-Links bereit.

## Workflow aktivieren

```json
{
  "metadata": {
    "audit": {
      "enabled": true,
      "key": "project.security",
      "category": "security",
      "reportBlock": "report",
      "schemaVersion": 1
    }
  }
}
```

Der in `reportBlock` genannte Workflow-Block braucht denselben `name`. Audit-Keys und
Kategorien sind frei wählbar; das Backend kennt keine projektspezifischen Keys oder Regeln.
Ein Audit-Workflow verwendet nicht gleichzeitig `metadata.autoTodo`: TODOs werden aus den
strukturierten Findings verwaltet.

## Ergebnisformat v1

Der Report-Block gibt normalen Markdown aus und schließt mit genau einem versteckten
JSON-Envelope:

```text
<!-- mysuricates:audit-result:v1
{"snapshotComplete":true,"findings":[
  {
    "ruleKey":"architecture.god-class",
    "subject":"src/main/java/example/WorkflowExecutor.java",
    "severity":"HIGH",
    "title":"Klasse hat zu viele Verantwortlichkeiten",
    "description":"Konkrete Bewertung und empfohlene Behebung",
    "evidence":"WorkflowExecutor.java:120-510",
    "tags":["backend","architecture"]
  }
]}
-->
```

`findings` ist immer der vollständige aktuelle Snapshot, nicht nur das Delta. `ruleKey` und
`subject` bilden die stabile Identität eines Findings und müssen zwischen Runs gleich bleiben.
Titel und Beschreibung dürfen sich weiterentwickeln. Ein leerer Snapshot wird als
`"findings": []` ausgegeben.

Überlässt der Prompt das `subject` dem Modell, driftet es: Zwischen zwei Läufen wurde aus
`docs/deployment.md` `host-reaching-endpoints`, und der Befund kam als Wiedergänger ohne seine
Disposition zurück. Ein Prompt legt das `subject` deshalb je `ruleKey` fest — einen festen Wert
für Regeln, die das Repository oder ein Modul als Ganzes betreffen (Dateien und Zahlen gehören
dann in `description`/`evidence`), sonst den Pfad relativ zur Repo-Wurzel, je Datei ein Finding.
Vorbild ist die ruleKey-Liste im Evidenz-Prompt von „Public Readiness“ (`subject "<wert>"` bzw.
`subject = Pfad` je Eintrag); `AuditRuleKeyCatalogTest` hält fest, dass dort kein Eintrag die
Angabe vergisst (TODO `3a9e275b` — Evidenz-Prompt: subject je ruleKey festschreiben).

Ein Doppelpunkt im `subject` hat Bedeutung: Steht davor eine Datei oder Klasse
(`pom.xml:docker-java`, `Foo.java:42`), zählt nur der Teil vor dem `:`. Steht davor ein klein
geschriebenes Art-Wort (`package:com.example.web`), zählt `<art>:<wert>` ganz. Zwei Befunde
desselben Reports mit gleicher Identität übernimmt die Ingestion nur einmal und meldet den Rest
im Activity-Log.

### Optional: `expectedFindings`

Ein Audit, dessen Messblock die Soll-Zahl kennt (etwa `ERWARTETE FINDINGS` in der
Test-Abdeckung), kann sie als `"expectedFindings": <N>` neben `snapshotComplete` in den Envelope
schreiben. Stimmt sie nicht mit der Zahl der gelieferten Findings überein, wird der **ganze
Report verworfen**: Er wird als INVALID historisiert, es entstehen keine Findings, und vor allem
wird keines auf RESOLVED gesetzt.

Das ist der Punkt: Ein gekürzter Envelope mit `snapshotComplete: true` hat zuvor alle nicht
gelieferten Findings aufgelöst — genau das Fehlerbild, das der Snapshot-Modus verhindern soll.
Der Gate-Block im Workflow meldet das zwar als RED, aber die Ingestion lief davon unbeeindruckt
weiter; eine Meldung ohne Konsequenz ist keine Absicherung. Fehlt das Feld, bleibt das Verhalten
unverändert (TODO `e39379a9`).

### ruleKey-Katalog

Gibt ein Prompt seinem Modell feste `ruleKey`s vor, stehen sie in
`ai-automation-backend/src/main/resources/audit/rule-keys.json` unter ihrem `auditKey`. Die
Ingestion normalisiert dann Schreibweisen-Drift (Kleinschreibung, `_` → `-`, Plural vor `-count`:
`god-classes-count` → `god-class-count`). Ein Key, der auch danach nicht im Katalog steht, wird
**verworfen — und mit ihm der ganze Report** (TODO `f57945e2`): Er wird als INVALID
historisiert, es entstehen keine Findings, und keines wird auf RESOLVED gesetzt. Eine Zeile im
Activity-Log nennt die unbekannten Keys und die erlaubten. Ein `auditKey` ohne Katalogeintrag
wird nicht geprüft; sein Prompt vergibt die Keys frei.

Warum so hart: Ein Envelope, dessen Keys einheitlich gedriftet sind, trägt die *richtige Anzahl*
Findings und passiert damit den zählenden Gate-Block. Übernimmt die Ingestion ihn trotzdem, legt
sie lauter neue Identitäten an und schließt die komplette Vorgängergeneration als RESOLVED — man
bekommt ebenso viele Wiedergänger und fängt bei den echten Befunden von vorn an. Die frühere
Markierung am Finding (`unknownRuleKey`, V75) war die Zwischenstufe, solange es keine härtere
Konsequenz gab; sie wird nicht mehr neu gesetzt.

### Belegprüfung: `evidence` muss eine Messung dieses Laufs nennen

Der Prompt verlangt zu jedem Finding eine Belegzeile — geprüft hat das lange niemand, und ein
Finding mit erfundenem oder aus dem letzten Report recyceltem Beleg wurde unverändert zu einem
TODO (TODO `a94b56a0`). Seit V77 hält `EvidenceVerification` beim Ingest jedes `evidence` gegen
das Faktenblatt des Runs: gesammelt werden die Namen aller `FAKT <name>:`- und
`TREFFER <name>:`-Zeilen, und wer keinen davon nennt, wird als `unverifiedEvidence` markiert.

Ein so markiertes Finding wird **gespeichert und bleibt sichtbar** — verworfen wird nichts, damit
ein Fehlalarm der Prüfung keinen echten Befund verschluckt. Es erzeugt aber **kein verwaltetes
TODO** mehr, auch bei `severity: HIGH` nicht, und eine Zeile im Activity-Log nennt es beim Namen.
Wie `unknownRuleKey` wird die Markierung bei jedem Ingest neu gesetzt, auch zurück.

Die Grenze: Geprüft wird die **Herkunft** des Belegs, nicht seine Tragfähigkeit. Wer eine echte
FAKT-Zeile zitiert und daraus etwas über eine ganz andere Stelle folgert, kommt weiterhin durch.
Ein Run ohne Faktenblatt wird nicht geprüft — kein Faktenblatt ist kein Verdacht.

## Faktenblatt-Muster: die Shell misst, die KI interpretiert

Ein Audit-Workflow lässt die KI **nicht** selbst zählen. Ein Shell-Block vor dem
Report-Block misst deterministisch und gibt ein Faktenblatt aus:

```text
## FAKTENBLATT
FAKT files_over_300: 19
FAKT backend_tests_failures: 0
NICHT GEMESSEN: step_results_bytes (Shell-Blöcke dürfen die SQLite-DB nicht lesen)
TREFFER repository_in_loop: mapper/WishMapper.java:36 workflowRepository.find (Schleife ab Zeile 34)

## PFLICHTFELDER
PFLICHT-FAKTEN: database_bytes source_files_scanned transactional_slow_call_count
```

Regeln:

- `FAKT <name>: <wert>` ist ein Messwert, `NICHT GEMESSEN: <name> (<grund>)` eine bewusst
  offene Lücke, `TREFFER <name>: <datei>:<zeile> …` ein Kandidat mit Fundstelle. Ein
  Fehlerpfad im Messblock erzeugt immer eine NICHT-GEMESSEN-Zeile — nie eine stille Null:
  „0 Zyklen gefunden" und „Zyklensuche fehlgeschlagen" dürfen nicht gleich aussehen.
- Der Report-Prompt enthält die Messregel „Bewerte ausschließlich das Faktenblatt; miss und
  zähle nichts selbst", nennt zu jeder Dimension die zuständigen FAKT-Zeilen und verlangt,
  dass jede Zahl im Report ihre FAKT-Zeile nennt. Rohlogs kommen nur gekürzt (letzte 80
  Zeilen je Messblock) mit — zur Einordnung eines Fehlschlags, nicht als Zählmaterial.
- Der Gate-Block prüft deterministisch, ob die Pflicht-FAKT-Zeilen vorliegen, und setzt
  sonst `gate_status: RED` mit `gate_category: audit-invalid`. Ohne diese Prüfung könnte
  ein KI-Report grün sein, obwohl der Messblock nichts geliefert hat.
- Faktenblatt-Blöcke lesen Block-Ausgaben nur als `set -- {{ block.<name>.output }}` und
  setzen `"shell_escape_variables": true` (Hygiene-Gate `TemplateHygieneGateTest`). Einfache
  Werte ohne Zeilenumbruch — `BASE={{ project_path }}`, `BASE={{ execution_path }}` — stehen
  ebenfalls **unquoted**: mit `shell_escape_variables` setzt der Resolver die Single-Quotes
  selbst, eigene Quotes (`BASE="{{ project_path }}"`) landen sonst im Wert. Sie rufen weder
  die REST-API auf noch öffnen sie die SQLite-Datenbank.

Umgestellte Audits im `workflow-pool/`:

| Workflow | Messung | Selbsttest |
|---|---|---|
| Architektur-Review | Block `arch_metrics` → `scripts/public-readiness/arch-metrics.sh` (jdeps, Zeilen, Churn) | `scripts/tests/test-arch-metrics.sh` |
| Architektur-Review (Frontend) | Block `frontend_metrics` → `scripts/public-readiness/frontend-metrics.sh` (Quelltext-Importe, Zeilen, Modul-Zyklen) | `scripts/tests/test-frontend-metrics.sh` |
| Health-Check | Block `facts` (Surefire-Summen, Exit-Codes, Lint/tsc, Bundle, Code- und Doku-Größen) | `scripts/tests/test-audit-facts.sh` |
| Performance-Audit | Block `facts` (Health-Sonde, Heap-Zahlen aus `/api/v1/system/jvm-memory`, DB- und Bundle-Dateien, Logzeilen, Quelltext-Muster) | `scripts/tests/test-audit-facts.sh` |
| Test-Abdeckung | Block `metrics` mit Soll-Größe `ERWARTETE FINDINGS`, Gate zählt den Envelope dagegen | `scripts/tests/test-coverage-snapshot.sh` |
| Doku-Zehrprobe | Block `dossier` → `scripts/public-readiness/docs_orphan_scan.py` (Erreichbarkeit, Verweise, Pflegestand, Themen-Überlappung) | `scripts/tests/test_docs_orphan_scan.py` |

### Wann `expectedFindings` **nicht** passt

Die Doku-Zehrprobe verzichtet bewusst darauf, und der Grund gilt für jedes Audit, dessen
Findings ein Urteil sind statt einer Messung: Bei der Test-Abdeckung *ist* die Sollzahl eine
Messung — jede ungetestete Klasse muss gemeldet werden, Weglassen wäre ein Fehler. Bei der
Zehrprobe misst die Shell nur *Kandidaten*; welcher davon ein Befund ist, entscheidet der
Report. Zählte das Gate die Kandidaten gegen den Envelope, müsste die KI jeden melden und
dürfte nie sagen „dieses Dokument gehört so ins Repository" — die Rollenteilung wäre dahin.

Statt der Zahl prüft das Gate dort die **Anwesenheit der Pflicht-FAKT-Zeilen**
(`PFLICHT-FAKTEN:` im Faktenblatt). Das fängt den Fall ab, gegen den `expectedFindings`
schützt — einen Schnappschuss auf ausgefallener Messung —, ohne das Urteil zu erzwingen.
Der Report-Prompt trägt zusätzlich die Regel, einen unverändert gebliebenen Befund des
Vorlaufs erneut zu melden: sonst schlösse ein bloßer Sinneswandel ihn als RESOLVED.

## Lebenszyklus

- Fehlgeschlagene Runs, fehlende Report-Blöcke und ungültige Envelopes werden als
  `INVALID` historisiert. Sie verändern keine Findings.
- Ein valider vollständiger Snapshot aktualisiert vorhandene Findings und legt neue an.
- Ein zuvor offenes Finding, das im nächsten validen Snapshot fehlt, wird `RESOLVED`.
- Ein erneut auftretendes `RESOLVED`-Finding wird als Regression wieder geöffnet.
- `CRITICAL` und `HIGH` erzeugen automatisch verwaltete TODOs. `MEDIUM` kann im Audit
  Center bestätigt werden; `LOW` bleibt zunächst ein Finding. Fällt ein offenes Finding unter
  `HIGH`, wird sein verwaltetes TODO gelöst und archiviert (außer es steht auf `IN_PROGRESS`).
- Ein auf `DONE` gesetztes verwaltetes TODO versetzt das Finding in
  `AWAITING_VERIFICATION`. Erst der nächste valide Audit-Snapshot bestätigt die Behebung.
- Manuell verknüpfte TODOs werden niemals automatisch im Status verändert.

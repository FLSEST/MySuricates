# Audit-Workflow-Protokoll

Das Audit Center fuehrt selbst keine fachlichen Pruefungen aus. Regeln, Datensammlung,
Bewertung und Report-Erzeugung bleiben vollstaendig in normalen Workflows. Die Plattform
stellt nur ein opt-in Protokoll fuer versionierte Reports, stabile Findings und TODO-Links bereit.

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
Kategorien sind frei waehlbar; das Backend kennt keine projektspezifischen Keys oder Regeln.
Ein Audit-Workflow verwendet nicht gleichzeitig `metadata.autoTodo`: TODOs werden aus den
strukturierten Findings verwaltet.

## Ergebnisformat v1

Der Report-Block gibt normalen Markdown aus und schliesst mit genau einem versteckten
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

`findings` ist immer der vollstaendige aktuelle Snapshot, nicht nur das Delta. `ruleKey` und
`subject` bilden die stabile Identitaet eines Findings und muessen zwischen Runs gleich bleiben.
Titel und Beschreibung duerfen sich weiterentwickeln. Ein leerer Snapshot wird als
`"findings": []` ausgegeben.

## Faktenblatt-Muster: die Shell misst, die KI interpretiert

Ein Audit-Workflow laesst die KI **nicht** selbst zaehlen. Ein Shell-Block vor dem
Report-Block misst deterministisch und gibt ein Faktenblatt aus:

```text
## FAKTENBLATT
FAKT files_over_300: 19
FAKT backend_tests_failures: 0
NICHT GEMESSEN: step_results_bytes (Shell-Bloecke duerfen die SQLite-DB nicht lesen)
TREFFER repository_in_loop: mapper/WishMapper.java:36 workflowRepository.find (Schleife ab Zeile 34)

## PFLICHTFELDER
PFLICHT-FAKTEN: database_bytes source_files_scanned transactional_slow_call_count
```

Regeln:

- `FAKT <name>: <wert>` ist ein Messwert, `NICHT GEMESSEN: <name> (<grund>)` eine bewusst
  offene Luecke, `TREFFER <name>: <datei>:<zeile> …` ein Kandidat mit Fundstelle. Ein
  Fehlerpfad im Messblock erzeugt immer eine NICHT-GEMESSEN-Zeile — nie eine stille Null:
  „0 Zyklen gefunden" und „Zyklensuche fehlgeschlagen" duerfen nicht gleich aussehen.
- Der Report-Prompt enthaelt die Messregel „Bewerte ausschliesslich das Faktenblatt; miss und
  zaehle nichts selbst", nennt zu jeder Dimension die zustaendigen FAKT-Zeilen und verlangt,
  dass jede Zahl im Report ihre FAKT-Zeile nennt. Rohlogs kommen nur gekuerzt (letzte 80
  Zeilen je Messblock) mit — zur Einordnung eines Fehlschlags, nicht als Zaehlmaterial.
- Der Gate-Block prueft deterministisch, ob die Pflicht-FAKT-Zeilen vorliegen, und setzt
  sonst `gate_status: RED` mit `gate_category: audit-invalid`. Ohne diese Pruefung koennte
  ein KI-Report gruen sein, obwohl der Messblock nichts geliefert hat.
- Faktenblatt-Bloecke lesen Block-Ausgaben nur als `set -- {{ block.<name>.output }}` und
  setzen `"shell_escape_variables": true` (Hygiene-Gate `TemplateHygieneGateTest`). Einfache
  Werte ohne Zeilenumbruch — `BASE={{ project_path }}`, `BASE={{ execution_path }}` — stehen
  ebenfalls **unquoted**: mit `shell_escape_variables` setzt der Resolver die Single-Quotes
  selbst, eigene Quotes (`BASE="{{ project_path }}"`) landen sonst im Wert. Sie rufen weder
  die REST-API auf noch oeffnen sie die SQLite-Datenbank.

Umgestellte Audits im `workflow-pool/`:

| Workflow | Messung | Selbsttest |
|---|---|---|
| Architektur-Review | Block `arch_metrics` → `scripts/public-readiness/arch-metrics.sh` (jdeps, Zeilen, Churn) | `scripts/tests/test-arch-metrics.sh` |
| Health-Check | Block `facts` (Surefire-Summen, Exit-Codes, Lint/tsc, Bundle, Code- und Doku-Groessen) | `scripts/tests/test-audit-facts.sh` |
| Performance-Audit | Block `facts` (Actuator, DB- und Bundle-Dateien, Logzeilen, Quelltext-Muster) | `scripts/tests/test-audit-facts.sh` |
| Test-Abdeckung | Block `metrics` mit Soll-Groesse `ERWARTETE FINDINGS`, Gate zaehlt den Envelope dagegen | `scripts/tests/test-coverage-snapshot.sh` |

## Lebenszyklus

- Fehlgeschlagene Runs, fehlende Report-Bloecke und ungueltige Envelopes werden als
  `INVALID` historisiert. Sie veraendern keine Findings.
- Ein valider vollstaendiger Snapshot aktualisiert vorhandene Findings und legt neue an.
- Ein zuvor offenes Finding, das im naechsten validen Snapshot fehlt, wird `RESOLVED`.
- Ein erneut auftretendes `RESOLVED`-Finding wird als Regression wieder geoeffnet.
- `CRITICAL` und `HIGH` erzeugen automatisch verwaltete TODOs. `MEDIUM` kann im Audit
  Center bestaetigt werden; `LOW` bleibt zunaechst ein Finding.
- Ein auf `DONE` gesetztes verwaltetes TODO versetzt das Finding in
  `AWAITING_VERIFICATION`. Erst der naechste valide Audit-Snapshot bestaetigt die Behebung.
- Manuell verknuepfte TODOs werden niemals automatisch im Status veraendert.

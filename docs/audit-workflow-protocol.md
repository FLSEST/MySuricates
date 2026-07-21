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

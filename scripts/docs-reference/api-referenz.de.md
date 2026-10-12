# API-Referenz

> **Scope:** Die REST-API von MySuricates, ab „Fehlercodes“ erzeugt aus dem Code
> (`python3 scripts/docs-reference.py --write`, siehe [Entwicklung → Erzeugte Referenzen](entwicklung.md#erzeugte-referenzen)).
> Die kuratierte Kurzreferenz für externe Agents mit Beispielen ist [AGENTS.md](../AGENTS.md).

Je Bereich (erstes Pfadsegment nach `/api/v1`) eine Tabelle: Methode und Pfad, als Zweck der erste Satz
des Javadoc der Handler-Methode – ohne Javadoc bleibt die Zelle leer – und das Modul aus `@RequiresModule`
(„Kern“ = immer an). Was das Javadoc danach sagt – Statuscodes, Grenzen, Nebenwirkungen –, steht unter der
Tabelle als „Hinweise“. Die Javadocs sind meist englisch. Request- und Response-Felder stehen in den DTOs unter
`ai-automation-backend/src/main/java/com/automation/dto/`, das Verhalten im Controller, den die Zeile
„Quelle“ nennt.

## Allgemein

- **Basis-URL:** `http://localhost:9090/api/v1`, Content-Type `application/json`
- **Authentifizierung:** Header `X-API-Key: <key>` (wenn `automation.security.enabled=true`) – der Key
  darf alles. Eingeschränkt ist nur der Lauf-Token (`X-Run-Token`, gilt nur während eines Laufs, dessen
  Workflow `metadata.permissions: ["read"]` setzt): nur `GET` auf eine feste Liste, siehe
  [WORKFLOW-GUIDE → Plattformdaten lesen mit dem Lauf-Token](WORKFLOW-GUIDE.md#plattformdaten-lesen-mit-dem-lauf-token).
- **Proxy:** Aufrufe mit API-Key per `curl` immer mit `--noproxy '*'` – bei gesetztem `http_proxy`
  schickt curl auch Anfragen an `localhost` über den Proxy, samt Key. Das Doku-Gate
  `doc-facts-check.sh` prüft das an jedem Beispiel in `docs/` und `AGENTS.md`.
- **Module:** Ist das Modul eines Endpunkts ausgeschaltet, antwortet er mit `404` und Code `MODULE_DISABLED`.
- **Paginierung:** Seitenweise Listen nehmen `page` (ab 0) und `size` und liefern ein Seitenobjekt mit
  `content` und `totalElements`.

### Fehlerformat

```json
{ "code": "INTERNAL_ERROR", "error": "Fehlermeldung", "timestamp": "2025-01-15T10:30:00Z" }
```

`details` kommt nur bei Bedarf mit (`@JsonInclude(NON_NULL)`), Validierungsfehler (`VALIDATION_FAILED`)
zusätzlich mit `fieldErrors` (Feld → Meldung).

`params` (Objekt aus Zeichenketten) kommt nur mit, wenn Code und Parameter die Meldung vollständig
beschreiben – etwa `{"code":"WORKFLOW_SEALED","params":{"name":"Nightly"}}`, auch leer (`{}`) bei festem
Text. Die Oberfläche zeigt dann ihre eigene Übersetzung des Codes; `error` bleibt der englische Text für
Logs und API-Clients. Ohne `params` ist `error` Freitext der Wurfstelle und wird so angezeigt.

Wo ein Code für viele Meldungen steht (`INVALID_ARGUMENT`, `STATE_CONFLICT`, …), kommt zusätzlich
`messageKey` mit – ein Schlüssel des Backend-Enums `ErrorMessage`, etwa
`{"code":"INVALID_ARGUMENT","messageKey":"INVALID_CRON","params":{"expression":"…"}}`. Die Oberfläche
übersetzt dann den Schlüssel statt des Codes.

## WebSocket

STOMP über SockJS auf `ws://localhost:9090/ws`. Der Handshake braucht ein kurzlebiges Einmal-Ticket aus
`POST /api/v1/ws-ticket` (API-Key im Header, Antwort `{ "ticket": "kxJ9…", "expiresInSeconds": 30 }`) –
genau einmal einlösbar, für jeden Verbindungsversuch (auch jeden Reconnect) ein frisches. Ein
`apiKey`-Query-Parameter wird abgelehnt.

| Topic | Inhalt |
|---|---|
| `/topic/executions/{runId}/logs` | Workflow-Ausführungs-Logs (Steps, Status, Approval-Prompts) |
| `/topic/chains/{chainRunId}/status` | Chain-Ausführungs-Status-Updates |
| `/topic/assistant/{requestId}/status` | Assistent-Chat-Fortschritt (Phase, Detail, Elapsed) |
| `/topic/assistant/{sessionId}/events` | Assistent-Push-Events (erstellte Items, Suggestions) |
| `/topic/wishes/{wishId}/status` | Wish-Iterations-Status |
| `/topic/activity-log` | Globale Activity-Log-Events |
| `/topic/dashboard/runs` | Nach dem Speichern eines beendeten Runs `{"type":"run_completed","runId":…,"workflowId":…,"status":…}` – nur IDs und Status |

Execution-Logs sind JSON-Objekte, unterschieden über `type`; Step-Nachrichten haben keins. Weitere Typen:
`status`, `approval_required`/`approval_resolved`, `human_input_required`/`human_input_resolved`,
`agentic_*`, `foreach_item_started`/`foreach_item_completed`,
`container_starting`/`container_started`/`container_skipped`, `command` und `live_output` – Ausgabe
laufender Blöcke, gebündelt höchstens alle 250 ms, mit `seq` lückenlos je `bufferId` (Lücken =
übersprungene Zeilen; Nachladen über `GET /api/v1/executions/{id}/live-log`).

Das Terminal (`ws://localhost:9090/ws/terminal/{runId}?ticket=...`) ist ein roher WebSocket ohne
SockJS/STOMP, ebenfalls mit einem frischen Ticket, höchstens 3 Sessions je Container.

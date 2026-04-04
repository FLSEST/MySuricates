# Assistant Research Audit

Datum: 2026-04-04

## Kurzfazit

Der Assistent ist heute kein schlechter Chat, aber noch kein starker Plattform-Agent. Er kann analysieren, erklären und gelegentlich handeln, fühlt sich in der Praxis aber oft zu passiv, zu fragil und zu wenig lernend an. Der größte Bruch ist nicht ein einzelner Bug, sondern die Differenz zwischen Anspruch und Verhalten:

- Die Vision verspricht einen problemlösenden, selbstverbessernden Assistenten.
- Die aktuelle Architektur liefert primär Chat-Orchestrierung mit Kontext-Injektion, Fetches, Tools und Seiten-Suggestions.
- Die reale Nutzung zeigt vor allem Run-Analyse, Präsentations-Feedback und Rückfragen zu fehlerhaften Ergebnissen, aber nur wenig echte Proaktivität oder sichtbares Agentenverhalten.

Unterm Strich ist der Assistent aktuell am stärksten als "kontextsensitiver Debug-/Analyse-Chat". Am schwächsten ist er bei Kontinuität, aktiver Handlung, sichtbarem Fortschritt und systematischem Lernen aus den Gesprächen.

## Quellen

- Vision: [docs/vision.md](/home/flo/Entwicklung/tools/autotrigger/docs/vision.md)
- Architektur: [docs/architektur.md](/home/flo/Entwicklung/tools/autotrigger/docs/architektur.md)
- Assistant-System: [docs/assistant-system.md](/home/flo/Entwicklung/tools/autotrigger/docs/assistant-system.md)
- Backend:
  - [AssistantService.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/service/AssistantService.java)
  - [AssistantSessionService.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/service/AssistantSessionService.java)
  - [PageSuggestionService.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/service/PageSuggestionService.java)
  - [AssistantContextBuilder.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/service/assistant/AssistantContextBuilder.java)
  - [AssistantToolExecutor.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/service/assistant/AssistantToolExecutor.java)
  - [AssistantFetchService.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/service/assistant/AssistantFetchService.java)
- Frontend:
  - [AssistantPanel.tsx](/home/flo/Entwicklung/tools/autotrigger/ai-automation-frontend/src/components/AssistantPanel.tsx)
  - [assistantStore.ts](/home/flo/Entwicklung/tools/autotrigger/ai-automation-frontend/src/stores/assistantStore.ts)
  - [useAssistant.ts](/home/flo/Entwicklung/tools/autotrigger/ai-automation-frontend/src/hooks/useAssistant.ts)
  - [useAssistantWebSocket.ts](/home/flo/Entwicklung/tools/autotrigger/ai-automation-frontend/src/hooks/useAssistantWebSocket.ts)
- Datenbank: `assistant_sessions`, `page_suggestions`, `todos` in `ai-automation-backend/data/automation.db`
- Screenshots unter `/home/flo/Bilder/tmp_automater/assistent/`

## Relevante Ist-Daten

### Nutzungsmuster aus der DB

- `assistant_sessions`: `16`
- `page_suggestions`: `8`
- Sessions nach Seite:
  - `run`: `9`
  - `dashboard`: `2`
  - `runs`: `2`
  - `workflows`: `2`
  - `workflow`: `1`
- Persistierte Nachrichten:
  - `116` User-Nachrichten
  - `116` Assistant-Nachrichten
  - `0` persistierte Assistant-Nachrichten mit `actions`
  - `0` persistierte Error-Flags in den gespeicherten Nachrichten
- Nachrichtengröße:
  - Durchschnitt: `184.8`
  - Median: `83`
  - P95: `501`

### Wiederkehrende Muster in den Sessions

- Der generische Fehlertext "`Entschuldigung, ich konnte deine Anfrage gerade nicht verarbeiten...`" taucht in mindestens `4` Sessions auf.
- Formulierungen wie "`Ich lade...`" tauchen trotz explizitem Verbot im System-Prompt in mindestens `7` Sessions auf.
- "`projectId`" bzw. Projekt-ID-bezogene Aussagen tauchen in Sessions auf, obwohl der Prompt explizit sagt, dass der Assistent vorhandene IDs selbst nutzen soll.
- Reale Antwortzeiten in den Sessions liegen häufig im Bereich `47s` bis `147s`, teils sogar bei einfachen Folgeaktionen.

### Hinweise aus den Screenshots

- Der Assistent wirkt zu reaktiv und schlägt zu wenige nächste Schritte vor.
- Sichtbarkeit und Vertrauen sind schwach: fehlende oder unklare Zeit-/Statussignale, Hänger, wenig sichtbarer Arbeitsfortschritt.
- Es gibt Fälle, in denen der Assistent nach Informationen fragt, die systemisch verfügbar sein sollten.
- Die UI zeigt Chat und Thinking-Status, aber kein belastbares Bild dessen, was der Assistent konkret gerade tut oder getan hat.

## Findings

## 1. Verlässlichkeit und Gesprächskontinuität sind noch nicht gut

### Was nicht gut ist

- Gespräche fühlen sich fragil an.
- Bei Fehlern oder Hängern kippt der Assistent schnell auf eine generische Fallback-Antwort.
- Wenn ein Request hängt oder abbricht, ist für den Nutzer nicht klar, ob die Nachricht angekommen ist, ob gearbeitet wird oder ob alles verloren ist.

### Warum das so ist

- [AssistantService.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/service/AssistantService.java) arbeitet synchron über einen Host-CLI-Call mit `120` Sekunden Timeout.
- Der Fehlerpfad liefert immer denselben generischen Text; es gibt keinen differenzierten Fehlervertrag für Timeout, Provider-Fehler, Prompt-Verletzung oder Tool-Failures.
- [AssistantController.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/controller/AssistantController.java) persistiert die Session erst nach dem kompletten Chat-Call. Wenn der Aufruf vorher hängt oder scheitert, fehlt der serverseitige Gesprächsfortschritt.
- Im Frontend gibt es Queueing und Cancel, aber kein belastbares Recovery-Modell über Request-Grenzen hinweg.

### Wie man es verbessert

- User-Nachricht serverseitig sofort beim Request-Eingang persistieren, nicht erst nach fertiger Antwort.
- Assistant-Antwort und Status-Updates als zweite Phase speichern, damit aus einem hängenden Request kein "unsichtbares Loch" wird.
- Fehlervertrag aufsplitten in `timeout`, `provider_unavailable`, `tool_failed`, `guardrail_rejected`, `cancelled`.
- Resumierbare Request-IDs einführen: Ein offener Assistant-Request muss vom Frontend wiedergefunden und als `stuck`, `failed` oder `recoverable` angezeigt werden können.

### TODO-Abgleich

- Schon offen und passend:
  - `6ce8cd66-b8dd-4c61-a8df-626ca4c19003` `Assistent: Nachrichten gehen verloren wenn CLI-Aufruf hängt oder Session endet`
- Kein neues Delta-TODO nötig.

## 2. Der Assistent ist noch zu wenig proaktiv und zu wenig sichtbar agentisch

### Was nicht gut ist

- Der Assistent erklärt häufig, was er tun würde, statt klar sichtbare Aktionen anzubieten oder auszuführen.
- In den gespeicherten Sessions tauchen praktisch keine `actions` auf.
- Die Screenshots zeigen das gleiche Bild: zu wenig Optionen, zu wenig Vorschläge, zu wenig wahrnehmbare Initiative.

### Warum das so ist

- Das Response-Format unterstützt zwar Actions, aber in den realen Sessions werden sie praktisch nicht genutzt.
- [AssistantChatBroadcaster.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/service/assistant/AssistantChatBroadcaster.java) unterstützt Session-Events, aber es existiert aktuell kein realer Producer für `broadcastEvent(...)`.
- [useAssistantWebSocket.ts](/home/flo/Entwicklung/tools/autotrigger/ai-automation-frontend/src/hooks/useAssistantWebSocket.ts) empfängt `events`, aber [AssistantPanel.tsx](/home/flo/Entwicklung/tools/autotrigger/ai-automation-frontend/src/components/AssistantPanel.tsx) rendert diese Event-Liste nicht.
- Dadurch bleibt das agentische Verhalten unsichtbar, selbst wenn intern mehr passiert.

### Wie man es verbessert

- Assistant-Handlungen in drei Formen unterscheiden:
  - direkte Ausführung
  - bestätigungspflichtige Aktion
  - navigierbare nächste Schritte
- Eine echte Event-Timeline im Panel anzeigen: `Kontext geladen`, `Run analysiert`, `Workflow aktualisiert`, `Tool ausgeführt`, `Fetch Runde 1`.
- Actions als first-class Ergebnis behandeln und aktiv auf gute Fälle optimieren, statt sie nur optional am Rand zu erlauben.
- Für wiederkehrende Fragen auf Run-/Workflow-Seiten heuristisch immer mindestens 2 bis 3 Next-Best-Actions erzeugen.

### TODO-Abgleich

- Teilweise abgedeckt:
  - `f02728ce-e7cf-4840-8d93-2b70475b6871` `Assistent Phase 3: Direkte Aktionen + Memory-Integration`
- Echte Lücke:
  - Es gibt noch kein passendes TODO für sichtbare Assistant-Events und Event-Timeline im UI.

## 3. Der Kontextvertrag zwischen Frontend und Backend ist zu dünn

### Was nicht gut ist

- Der Assistent fragt nach Informationen, die eigentlich aus Route und Plattformzustand ableitbar sein sollten.
- Das sichtbarste Beispiel ist die Projekt-ID-/Projektzuordnung, obwohl der Nutzer sich bereits in einem konkreten Kontext bewegt.

### Warum das so ist

- Das Frontend berechnet in [AssistantPanel.tsx](/home/flo/Entwicklung/tools/autotrigger/ai-automation-frontend/src/components/AssistantPanel.tsx) für Run-Seiten bereits `workflowId`.
- [AssistantChatRequest.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/dto/AssistantChatRequest.java) erlaubt im `PageContext` aber nur `page`, `entityId`, `projectId`.
- `workflowId` geht also auf dem Weg zum Backend verloren.
- Der Backend-Context muss fehlende Beziehungen daher implizit rekonstruieren oder arbeitet ohne sie.

### Wie man es verbessert

- `PageContext` zu einem echten Kontextvertrag ausbauen:
  - `page`
  - `entityId`
  - `projectId`
  - `workflowId`
  - optional `runId`
  - optional `pathname`
- Backend soll aus diesem Vertrag deterministisch Run-, Workflow- und Projektbeziehungen auflösen, bevor das Modell gefragt wird.
- Die Regel "`never ask for data you already have`" darf nicht nur ein Prompt-Satz sein, sondern muss durch vollständigen Kontext gestützt werden.

### TODO-Abgleich

- Nur teilweise abgedeckt:
  - `c2f2e904-e993-4900-a239-930c42d2c257` `Assistent: Step-Output auf Run-Seite anzeigen können`
  - `f02728ce-e7cf-4840-8d93-2b70475b6871` `Assistent Phase 3: Direkte Aktionen + Memory-Integration`
- Echte Lücke:
  - Kein TODO adressiert den End-to-End-Context-Contract explizit.

## 4. Der Lern- und Analysewert der Assistant-Sessions ist heute zu gering

### Was nicht gut ist

- Die Vision spricht von einer selbstverbessernden Plattform.
- Für den Assistenten selbst werden aber nur begrenzt nutzbare Verlaufsdaten gespeichert.

### Warum das so ist

- [AssistantSessionService.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/service/AssistantSessionService.java) kappt Assistant-Antworten auf `500` Zeichen.
- Es werden keine `actions` persistiert.
- Es werden keine Fetches, Tool-Ausführungen, Statusphasen oder Compliance-Verletzungen gespeichert.
- Die Session-Daten sind dadurch gut genug für eine grobe Historie, aber zu arm für ernsthafte Qualitätsanalyse, Ranking oder Lernschleifen.

### Wie man es verbessert

- Assistant-Session-Speicherung in zwei Ebenen trennen:
  - kompakte UI-History
  - vollständiges Audit-/Learning-Journal
- Im Journal speichern:
  - volle Assistant-Antwort
  - `actions`
  - Fetch-Runden
  - Tool-Aufrufe und Ergebnisse
  - Statusphasen
  - Provider/Modell
  - Guardrail-Verstöße
- Aus diesen Daten regelmäßig Qualitäts-Insights erzeugen:
  - Welche Rückfragen waren unnötig?
  - Wo traten generische Fehler auf?
  - Welche Action-Typen wurden nie angeboten?

### TODO-Abgleich

- Kein passendes offenes TODO vorhanden.
- Das ist ein echtes Delta.

## 5. Suggestions sind aktuell eher Seiten-Prompts als echte Next-Best-Actions

### Was nicht gut ist

- Suggestions sind hilfreich, aber noch nicht stark genug, um den Assistenten proaktiv wirken zu lassen.
- Sie sind seitenbezogen, aber kaum entitätsbezogen.
- Die Screenshots beschreiben genau dieses Defizit: mehr Ideen, mehr Aktionen, häufiger, proaktiver.

### Warum das so ist

- [PageSuggestionService.java](/home/flo/Entwicklung/tools/autotrigger/ai-automation-backend/src/main/java/com/automation/service/PageSuggestionService.java) arbeitet mit:
  - `10` Minuten Throttle
  - max. `4` Suggestions
  - wenigen Session-Snippets
  - wenigen Memories
- Es gibt keine Priorisierung auf aktuelle Entität, aktuellen Fehlerfall oder aktuelle Nutzerintention.
- Die Vorschläge sind damit eher gute Seiteneinstiege als operative "next best actions".

### Wie man es verbessert

- Suggestions von page-aware auf entity-aware anheben.
- Heuristiken und Modell kombinieren:
  - deterministische Vorschläge aus aktuellem Run-/Workflow-/Todo-Zustand
  - ergänzende Modellvorschläge für Exploration
- Refresh nicht nur zeitbasiert, sondern auch ereignisbasiert bei relevanten Statusänderungen.
- Erfolgsfeedback auf Suggestions einführen: angeklickt, ignoriert, hilfreich, führte zu Aktion.

### TODO-Abgleich

- Teilweise verwandt:
  - `5044dcd1-8e5c-421f-9625-3a5fc449a22f` `Assistent: Modell-Toggle + Provider-Auswahl + kontextuelle Banner`
- Kein TODO adressiert die Suggestions-Qualität als eigenes Lern-/Ranking-Thema.
- Das ist ein echtes Delta.

## 6. Prompting allein reicht nicht, Compliance muss erzwungen werden

### Was nicht gut ist

- Der System-Prompt ist klar und ambitioniert.
- Das reale Verhalten weicht trotzdem mehrfach davon ab.

### Warum das so ist

- Der Prompt verlangt:
  - keine unnötigen Rückfragen
  - keine Formulierungen wie "`Ich lade...`"
  - sofortige Fetches statt Meta-Kommunikation
- In den realen Sessions tauchen genau diese verbotenen Muster dennoch auf.
- Es gibt keinen Validator oder Retry-Mechanismus, der Antworten mit verbotenen Mustern erkennt und korrigiert.

### Wie man es verbessert

- Nach dem Modell-Output einen deterministischen Assistant-Validator ausführen:
  - verbotene Phrasen
  - fehlende Actions bei klarer Handlungsaufforderung
  - Rückfragen trotz vorhandenem Kontext
- Bei Verstoß:
  - einmal automatischer Re-Prompt
  - danach Markierung als Guardrail-Verstoß
- Prompt-Qualität wird so von "Best Effort" zu einer erzwungenen Produktregel.

### TODO-Abgleich

- Schon offen und passend:
  - `9f022277-15ec-45c3-ad2e-f6a9b10913de` `Assistent: Safety-Guardrails einbauen — darf nichts kaputt machen`
- Kein neues Delta-TODO nötig.

## TODO-Mapping

| Finding | Status |
|---|---|
| Verlässlichkeit / Nachrichtenverlust / Hänger | Bereits gut durch offenes TODO abgedeckt |
| Zu wenig direkte Aktionen / sichtbare Agentik | Teilweise abgedeckt, Delta für Event-Timeline offen |
| Dünner Frontend→Backend-Context-Contract | Teilweise abgedeckt, Delta offen |
| Zu schwache Session-/Learning-Daten | Nicht abgedeckt, Delta offen |
| Suggestions zu statisch / zu wenig next-best-action | Nicht sauber abgedeckt, Delta offen |
| Prompt-Regeln ohne technische Erzwingung | Durch Guardrail-TODO abgedeckt |

## Empfohlene Reihenfolge

### Sofort

1. Nachrichtenverlust/Hänger sauber schließen
2. Context-Contract erweitern
3. Guardrails für verbotene Assistant-Muster einbauen

### Danach

4. Event-Timeline und sichtbare Agentik im Panel
5. Entity-aware Suggestions und echte Next-Best-Actions

### Danach über Zeit

6. Vollständiges Assistant-Journal für Lernen, Audits und Qualitätsmetriken

## Delta-TODO-Kandidaten

Diese Punkte sind nach heutigem Backlog echte Deltas:

1. `Assistant Context Contract erweitern — workflowId/runId/pathname End-to-End`
2. `Assistant Event-Timeline implementieren — echte Push-Events im Backend erzeugen und im Panel rendern`
3. `Assistant Session Journal ausbauen — volle Antworten, Actions, Fetches, Tools und Phasen für Lernen speichern`
4. `Assistant Suggestions auf Entity-Awareness + Ranking umbauen`

## Langfristige Richtung

Damit der Assistent über Zeit besser wird, reicht "besser prompten" nicht aus. Es braucht vier systemische Eigenschaften:

- **Verlässlich**: keine verlorenen Nachrichten, klare Fehlerzustände, sauberer Recovery-Pfad
- **Kontextstabil**: vollständiger Seiten-/Entitätskontext statt impliziter Rekonstruktion
- **Handlungsfähig**: sichtbare, nachvollziehbare Aktionen statt rein erklärender Texte
- **Lernfähig**: vollständige Journaldaten, aus denen Qualitäts- und Produktverbesserungen ableitbar sind

Erst wenn diese vier Schichten sauber zusammenspielen, wird aus dem heutigen Assistant-Chat ein echter Plattform-Agent.

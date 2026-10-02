# Glossar

Projektspezifische Begriffe, Abkuerzungen und Konzepte in alphabetischer Reihenfolge.

---

## A

**Agent-Container**
Docker-Container mit sprachspezifischer Laufzeitumgebung (Python, Node.js, Go). Werden vom Backend dynamisch ueber die Docker API erstellt und verwaltet -- nicht ueber Docker Compose.

**Agentischer Block (Agentic Block)**
Block-Typ der eine KI-gesteuerte Goal→Reason→Act→Observe Schleife implementiert. Claude CLI analysiert ein Ziel, schlaegt Aktionen vor und fuehrt sie iterativ aus, bis das Ziel erreicht ist oder ein Sicherheitslimit greift.

**Approval Gate**
Manueller Freigabe-Mechanismus innerhalb eines Workflows. Pausiert die Ausfuehrung und wartet auf menschliche Bestaetigung (approve/reject). Implementiert ueber `CompletableFuture<String>`.

**API-Key**
Authentifizierungstoken fuer die REST API. Wird im Header `X-API-Key` uebergeben. Gespeichert in der `app_settings`-Tabelle und im Frontend localStorage.

## B

**BlockDefinition**
JSON-Objekt das einen einzelnen Schritt in einem Workflow beschreibt. Besteht aus `type` (Block-Typ) und `config` (typ-spezifische Konfiguration). Wird als JSON-Array in der `blocks`-Spalte der Workflow-Tabelle gespeichert.

**BlockExecutor**
Interface das jeder Block-Typ implementieren muss. Definiert `execute(BlockDefinition, ExecutionContext)` und `supports(String type)`.

**Built-in Skills**
19 vordefinierte Skills die beim Start automatisch geseedet werden (z.B. `builtin-code-review`, `builtin-test-generator`). Definiert als Ressourcen unter `src/main/resources/skills/builtin/<id>.json` (Metadaten inkl. `sortOrder`) + `<id>.md` (Prompt), geladen von `BuiltInSkillLoader`.

**Built-in Templates**
54 vordefinierte Workflow-Vorlagen die beim Start geseedet werden. Definiert als JSON unter `src/main/resources/templates/builtin/`, eingelesen von `BuiltInTemplateLoader` (Schema: `BuiltInTemplateDefinition`), geseedet von `WorkflowTemplateSeeder`. Koennen nicht geloescht werden.

## C

**Capability**
Einzelne Faehigkeit eines Skills (z.B. "lint", "OCR"). Registriert im `CapabilityRegistry` und referenzierbar in Block-Definitionen ueber `requiredCapabilities`.

**Claude CLI**
Kommandozeilen-Tool von Anthropic fuer KI-Interaktionen. Workflow-Bloecke rufen es auf dem Host-System auf (nicht im Container), ueber `ProcessBuilder` in `HostClaudeService`. Im Projekt-Container laufen nur die Ad-hoc-Befehle waehrend eines Runs (`claude` per `docker exec`, `RunControlService.executeAdHocCommand`) und das Container-Terminal (`bash -l`).

**Container**
Docker-Container der einem Projekt zugeordnet ist (1:1 Beziehung). Verwaltet Dateisystem-Mounts, Ressourcenlimits und Lebenszyklus.

## D

**DTO (Data Transfer Object)**
Objekte fuer die Kommunikation zwischen Controller und Client. Getrennt in `request/` (eingehend, mit Validierung) und `response/` (ausgehend, Projektion).

## E

**ExecutionContext**
Laufzeit-Kontext der durch alle Block-Executors einer Workflow-Ausfuehrung gereicht wird. Enthaelt Container-ID, Variablen, vorherige Ausgabe, Pause/Cancel-Status und Approval-State.

## F

**Flyway**
Datenbank-Migrations-Tool. Migrationen liegen unter `src/main/resources/db/migration/` mit dem Schema `V{N}__beschreibung.sql`. Aktuell 97 Migrationen (V1-V97).

## G

**Gate (Quality Gate, `GateStatus`)**
Ergebnis einer Qualitaetspruefung am Ende eines Workflow-Laufs: `GREEN`, `YELLOW`, `RED` (`entity/GateStatus.java`). Der `GateStatusResolver` liest den Wert aus dem Run-Metadatum `gate_status` oder leitet ihn aus dem Run-Status ab. `AutoTodoService` erzeugt daraus TODOs: RED mit Prioritaet HIGH, YELLOW mit MEDIUM, GREEN schliesst offene TODOs.

Nicht zu verwechseln mit dem → **Approval Gate**: das ist der manuelle Freigabe-Schritt im Workflow und hat mit `GateStatus` nichts zu tun.

## H

**HostClaudeService**
Service-Klasse die Claude CLI auf dem Host-System startet. Schreibt Prompts ueber stdin (nicht als CLI-Argument) um `E2BIG`-Fehler auf Linux zu vermeiden.

**History Compression**
Mechanismus im `AgenticBlockExecutor` der nach 7 Konversationsrunden aeltere Eintraege zusammenfasst, um das Kontextfenster effizient zu nutzen.

## I

**i18n (Internationalisierung)**
Zweisprachige Unterstuetzung (Deutsch + Englisch). Frontend: `i18n/de.ts` und `i18n/en.ts`. Workflow-Templates speichern Namen/Beschreibungen als JSON-Maps: `{"de":"...","en":"..."}`.

## L

**Lazy Loading**
Alle Frontend-Seiten werden per `React.lazy()` geladen (Code-Splitting). Reduziert die initiale Bundle-Groesse.

## M

**MapStruct**
Code-Generator fuer Entity-DTO Mapping. Generiert zur Compile-Zeit typsichere Mapper-Klassen (z.B. `WorkflowMapper`).

**Metadata**
Freies JSON-Feld auf Entities (Workflow, WorkflowRun, StepResult). Gespeichert ueber `JsonMapConverter`. Wird fuer zusaetzliche Informationen ohne Schema-Aenderung genutzt.

## P

**previousOutput**
Ausgabe des zuletzt ausgefuehrten Blocks. Verfuegbar im naechsten Block ueber `{{ previous_output }}` Template-Variable.

**Project**
Oberste organisatorische Einheit. Hat einen Typ (CODE, RESEARCH, CONTENT, BUSINESS, PERSONAL), einen Pfad und optional einen Container.

**PWA (Progressive Web App)**
Die Frontend-Anwendung ist als PWA konfiguriert (Vite PWA Plugin). Ermoeglicht Installation auf dem Desktop/Mobilgeraet.

## R

**Run (WorkflowRun)**
Eine einzelne Ausfuehrung eines Workflows. Entsteht direkt als RUNNING (zwischendurch PAUSED moeglich) und endet als COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED oder INTERRUPTED. Enthaelt eine Liste von `StepResult`s.

## S

**Seeder**
Spring-Komponenten die beim Anwendungsstart automatisch Daten einfuegen. `SkillSeeder` fuer Skills, `WorkflowTemplateSeeder` fuer Templates.

**Skill**
Wiederverwendbarer Automatisierungsbaustein mit Name, Kategorie, AI-Kontext und Capabilities. 19 Built-in + benutzerdefinierte Skills.

**SkillRegistry**
In-Memory `ConcurrentHashMap` die alle registrierten Skills haelt. Wird beim Start vom Seeder befuellt. Bietet schnellen Zugriff und aggregierten AI-Kontext.

**StepResult**
Ergebnis eines einzelnen Block-Schritts innerhalb einer Ausfuehrung. Enthaelt Status, Ausgabe, Fehler, Zeitstempel und Metadaten.

**STOMP**
WebSocket-Subprotokoll fuer Messaging. Verwendet fuer Echtzeit-Updates zwischen Backend und Frontend.

## T

**TanStack Query**
Server-State Management Library (ehemals React Query). Verwaltet Caching, Refetching und Mutations fuer alle API-Daten. Konfiguriert mit 30s staleTime.

**Template-Variablen**
Platzhalter in Block-Konfigurationen: `{{ variable_name }}` oder `{{variable_name}}`. Werden zur Laufzeit vom `ExecutionContext.resolveTemplate()` aufgeloest.

**Todo**
Persistente Aufgabe in der Datenbank. Kann manuell oder automatisch (von KI-Agenten) erstellt werden. Felder: title, description, status, priority, source, tags.

## V

**Virtual Threads (Java 21)**
Leichtgewichtige Threads fuer asynchrone Ausfuehrung. Jede Workflow-Ausfuehrung laeuft in einem eigenen Virtual Thread, gesteuert durch einen `ExecutorService`.

## W

**WAL (Write-Ahead Logging)**
SQLite-Journaling-Modus fuer bessere Concurrent-Read Performance. Konfiguriert in der JDBC-URL: `?journal_mode=WAL&busy_timeout=10000`.

**WebSocket**
Bidirektionale Echtzeit-Kommunikation. `/ws` fuer Workflow-Updates, `/ws/terminal` fuer Container-Terminal. Frontend nutzt `@stomp/stompjs` + `useWebSocket` Hook.

**Workflow**
Sequenz von Bloecken die zusammen eine Automatisierungsaufgabe beschreiben. Gehoert zu einem Projekt, kann Skills aktivieren und hat optionale Instruktionen + Modellauswahl.

**Workflow-Konvertierung**
Mechanismus der erfolgreiche agentische Laeufe in deterministische Workflows umwandelt. Nutzt regelbasierte Analyse + optionale KI-Unterstuetzung. Implementiert in `WorkflowConversionService`.

## Z

**Zustand**
Minimalistisches State-Management fuer React. Verwendet fuer Client-seitigen State (Settings, Theme, Sidebar, Toasts). Mit `persist` Middleware fuer localStorage-Persistierung.

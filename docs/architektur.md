# Architektur

MySuricates ist eine selbst betriebene Workflow-Plattform: ein Spring-Boot-Backend mit
Web-Oberflaeche, in dem Ablaeufe aus Bloecken bestehen — teils deterministisch (Shell,
Verzweigung, Schleife), teils KI-Aufrufe. Diese Seite beschreibt den inneren Aufbau. Fuer den
Einstieg siehe [README.de.md](../README.de.md), zum Bauen von Workflows
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md).

> Kuerzel in Klammern wie `(182bd224)` oder `TODO 63ecc124` sind Herkunftsnachweise:
> Commits dieses Repositories bzw. Vorgaenge in der begleitenden MySuricates-Instanz.
> Sie belegen, wann und warum etwas entstanden ist — von aussen nachschlagen laesst
> sich davon nur der Commit. Die Seite beschreibt den heutigen Stand; wie er entstanden
> ist, steht in Git. Tabellen und Felder: [datenmodell.md](datenmodell.md).

> Die Abläufe dieser Seite gibt es auch als Diagramme: [docs/diagramme.md](diagramme.md)
> — Engine, Steuerung und Status, Memory.

## Systemübersicht

```
┌─────────────────────────────────────────────────────────┐
│                      Browser                            │
│  React 19 PWA (Vite Dev-Server :3000 / statisch)        │
└──────────┬──────────────────────┬───────────────────────┘
           │ REST /api/v1/*       │ WebSocket /ws
           │ (Vite Proxy)         │ (STOMP/SockJS)
           ▼                      ▼
┌─────────────────────────────────────────────────────────┐
│         Spring Boot Backend Dev (:9090)                 │
│                                                         │
│  ┌─────────┐  ┌──────────┐  ┌────────────┐             │
│  │Controller│→ │ Service  │→ │ Repository │             │
│  └─────────┘  └──────────┘  └──────┬─────┘             │
│       │                            │                    │
│       │  ┌──────────────┐    ┌─────▼─────┐             │
│       └→ │ WebSocket    │    │  SQLite   │             │
│          │ (STOMP)      │    │ (WAL)     │             │
│          └──────────────┘    └───────────┘             │
│                                                         │
│  ┌──────────────────────────────────────┐               │
│  │         Docker Client API            │               │
│  └──────────────┬───────────────────────┘               │
└─────────────────┼───────────────────────────────────────┘
                  │ /var/run/docker.sock
                  ▼
┌─────────────────────────────────────────────────────────┐
│                  Docker Engine                          │
│  ┌──────────────┐  ┌──────────────┐                     │
│  │agent-node    │  │agent-python  │                     │
│  │(Node 22)     │  │(Python 3.12) │                     │
│  └──────────────┘  └──────────────┘                     │
│  ┌──────────────┐  ┌──────────────┐                     │
│  │agent-devops  │  │agent-java    │                     │
│  │(Ubuntu 24.04)│  │(Temurin 21)  │                     │
│  └──────────────┘  └──────────────┘                     │
└─────────────────────────────────────────────────────────┘

Stable (:9091) und Test (:8090) laufen als separate Docker-Container.
```

## Drei Umgebungen

Neben der Dev-Instanz aus dem Quellcode (Frontend 3000, Backend 9090) laufen bei Bedarf **Stable** (9091)
und **Test** (8090) als Docker-Container; beide starten nicht von selbst. Einrichtung, Start und
Definition-Sync: [deployment.md → Drei-Umgebungs-Setup](deployment.md#drei-umgebungs-setup).

## Module-System (Startkonfiguration)

Neben dem immer aktiven Kern (Workflows, Runs, Projekte, TODOs mit TodoTrigger, Schedules, Activity/DLQ, Gates, Pool, Templates, Docs, Settings) gibt es **optionale Module**. Sie werden **beim Start** festgelegt und sind im Default **nicht per REST umschaltbar**: Wer ein Modul einschalten will, braucht Schreibzugriff auf die Konfiguration des Hosts und einen Neustart. Ein geleakter API-Key kann damit keine Netzwerk-, Zugangsdaten- oder Host-Zugriffs-Funktion reaktivieren. Wer das auf einer nicht öffentlich erreichbaren Instanz bequemer haben will, erlaubt das Schalten in der Oberfläche mit `automation.modules.ui-editable: true` (siehe unten).

**Konfiguration** — Preset plus Einzel-Override, Override schlägt Preset:

```yaml
automation:
  modules:
    preset: core        # core (Default) | research | full
    webhooks: true      # einzelnes Modul zusätzlich an
    ui-editable: false  # true = Funktionsmodule zusätzlich in den Settings schaltbar (Default false)
  hardening:
    sealing: true       # Härtungs-Flags sind vom Preset unabhängig
```

Env-Form: `AUTOMATION_MODULES_PRESET`, `AUTOMATION_MODULES_<NAME>`, `AUTOMATION_HARDENING_<NAME>` (Bindestriche als `_`, z. B. `AUTOMATION_MODULES_REMOTE_ACCESS=true`). Stable- und Test-Profil setzen `full`; `scripts/setup.sh` schreibt das gewählte Preset nach `ai-automation-backend/config/application.yml`. Vorlagen „minimal“ und „maximal“: `ai-automation-backend/config-examples/`, erklärt in [deployment.md](deployment.md#konfigurationen-minimal-und-maximal). `scripts/setup.sh --lang de|en` wählt nur die Anzeigesprache der Modultexte im Terminal (Default: `$LANG`-Präfix, sonst `en`) — das Ergebnis (welche Module aktiv sind) ändert sich dadurch nicht.

**Katalog** (einzige Quelle: `modules/ModuleCatalog` – dort stehen auch Standardwerte, Abhängigkeiten und Beschreibungen):

| Stufe | Module | Reifegrad |
|---|---|---|
| `standard` (alle Presets) | `dashboard-report`, `goals` (Ziele mit Projekten, Arbeitspaketen, Meilensteinen, TODO-Planung, Workflows am Ziel, Ziel-Wächter) | stabil |
| `research` | `chains`, `assistant`, `learning` (Analyse-Endpunkte; Dashboard-Daten bleiben Kern), `memory`, `audit`, `bpmn` | chains stabil, Rest experimentell |
| `advanced` (nach außen) | `integrations` (inkl. TODO-Tracker-Export, Kalender-Adapter), `http-api` (allgemeine HTTP-API-Verbindung, braucht `integrations`), `webhooks`, `remote-access` (Tailscale), `terminal` (Run-Terminal per WebSocket), `staging` | stabil / staging experimentell |
| `advanced` (wenig genutzt) | `conversion`, `dialog-import` (benötigt conversion), `onboarding-profiles`, `skills`, `debug`, `factory` (inkl. Auto-Repair), `wishes` (benötigt chains) | Altlast / experimentell |
| `hardening` | `provenance` (Default an), `sealing`, `require-sealed-for-unattended`, `require-signed-seal`, `seal-immutable`, `webhook-signatures`, `private-network-guard` (Default an), `private-network-guard-http-api`, `private-network-guard-own-services` | experimentell |

Der Katalog wird zusätzlich als reine Bash-Arrays nach `scripts/lib/module-catalog.generated.sh` exportiert,
damit die Skripte ohne Backend auskommen — erzeugt, nicht gepflegt, siehe
[entwicklung.md → Einstieg in den Code](entwicklung.md#einstieg-in-den-code).

**Durchsetzung**

- `@RequiresModule` an Controller-Klassen oder einzelnen Handlern; `ModuleGuardInterceptor` (als `MappedInterceptor`-Bean, damit `@WebMvcTest`-Slices keine Registry brauchen) antwortet für deaktivierte Module mit **404 `MODULE_DISABLED`** — auch am öffentlichen Webhook-Trigger.
- Hintergrundpfade prüfen `ModuleRegistry` direkt: `integration`-/`skill-capability`-Blöcke schlagen mit klarer Meldung fehl, `/ws/terminal` lehnt den Handshake ab (`ModuleHandshakeGuard`), Auto-Memory, Audit-Ingestion, Staging-Health-Checks und Chain-Schedules laufen nicht, Auto-Repair hängt per `@EventListener(condition = ModuleConditions.FACTORY)` am Modul, die gecachten Einstellungen des Assistenten folgen einer Änderung (`AppSettingChangedEvent`) per `ModuleConditions.ASSISTANT` nur bei eingeschaltetem Modul — `AssistantSettingsListener` liest sie beim Einschalten zur Laufzeit nach.
- Unbekannte Modulnamen, ungültige Werte und unerfüllte Abhängigkeiten (`requires`) **brechen den Start ab** — nichts schaltet sich selbst ein.
- `ControllerModuleGateTest` erzwingt, dass jeder Controller einem Modul zugeordnet oder bewusst als Kern gelistet ist.
- Block-Typen eines Moduls stehen in einer Liste, `executor/BlockTypeModules` (`skill-capability` → `skills`, `integration` → `integrations`, `dashboard-report`). `/system/capabilities` bietet einen solchen Typ nur an, wenn sein Modul an ist, und `GET /workflow-templates` nennt je Vorlage `requiredModules`. `BlockTypeModuleGateTest` hält Liste und Executor-Prüfungen in beide Richtungen deckungsgleich: Jeder Eintrag braucht einen Executor, der verweigert, und jeder Executor, der ein Modul prüft, braucht einen Eintrag.
- `GET /api/v1/system/capabilities` liefert `preset`, `modulesUiEditable` und `modules[]` (mit `configured`/`overridden`); das Frontend blendet damit Navigation, Routen (Seite „Modul deaktiviert“ mit Snippet) und Widgets aus. **Settings → Module & Härtung** zeigt alles mit YAML-/Env-Snippet.

**Schalten in der Oberfläche (optional)** — Mit `automation.modules.ui-editable: true` (Env `AUTOMATION_MODULES_UI_EDITABLE`) bekommt jedes Funktionsmodul in den Settings einen Schalter:

- Die Änderung wirkt **sofort** (`ModuleRegistry` ist zur Laufzeit veränderbar; alle Prüfungen laufen pro Aufruf, der Terminal-WebSocket prüft je Handshake, `TailscaleService` je Aufruf) und bleibt über Neustarts erhalten (`app_settings` → `modules.ui-overrides`).
- UI-Werte gehen der Konfigurationsdatei vor; ein Wert gleich dem Config-Wert wird nicht gespeichert, „Auf Konfiguration zurücksetzen“ entfernt ihn. Abhängigkeiten (`requires`) werden wie beim Start geprüft (409 mit Hinweis, was vorher nötig ist). Passen gespeicherte Werte nach einer Config-Änderung nicht mehr, werden sie beim Start übersprungen statt den Start abzubrechen.
- Jede Änderung erzeugt einen WARN-`SECURITY`-Eintrag im Activity-Log. Ist der Schalter aus, antworten `PUT/DELETE /api/v1/system/modules/…` mit **403 `MODULES_NOT_EDITABLE`**, gespeicherte UI-Werte werden ignoriert.
- **Härtungs-Schalter bleiben immer Startkonfiguration** (400): Sie schützen gegen genau den Aufrufer, der den API-Key hat.
- Abwägung: Mit `ui-editable` kann jeder mit API-Key Webhooks (öffentlicher Endpunkt), Integrationen (Tokens), Fernzugriff oder das Run-Terminal (Host-Zugriff) einschalten — für öffentlich erreichbare Instanzen aus lassen. Laufende Runs, die einen Block eines gerade abgeschalteten Moduls erreichen, schlagen mit der bekannten Meldung fehl.

## Workflow-Härtung (optional)

Schalter unter `automation.hardening.*` — derselbe Mechanismus wie bei den Modulen (nur Startkonfiguration, vom Preset unabhängig, **Settings → Module & Härtung** zeigt sie lesend). Die Herkunft von Änderungen ist kein Schalter, sondern immer an.

| Schalter | Default | Braucht | Wirkung |
|---|---|---|---|
| `provenance` | an | — | Jeder Run speichert `definition_hash`, `definition_version_id` und `trigger_source` |
| `sealing` | aus | `provenance` | Workflows versiegeln (`POST /api/v1/workflows/{id}/seal`, `…/unseal`) |
| `require-sealed-for-unattended` | aus | `sealing` | Unbeaufsichtigte Runs starten nur versiegelte Workflows |
| `require-signed-seal` | aus | `sealing` | Versiegelte Workflows brauchen eine gültige Signatur |
| `seal-immutable` | aus | `sealing` | Entsiegeln nicht über REST — nur über die Startkonfiguration |
| `webhook-signatures` | aus | Modul `webhooks` | Signatur-Secret pro Webhook (HMAC-SHA256) |
| `private-network-guard` | an | — | iCal-Feed und RSS/Atom-Feed rufen nur öffentliche Adressen ab (siehe [Integrationen](integrations.md#abrufe-ins-lokale-netz)) |
| `private-network-guard-http-api` | aus | — | Dieselbe Sperre für http-api (eigenes Netz sonst erreichbar) |
| `private-network-guard-own-services` | aus | — | Dieselbe Sperre für eigene Dienste: CalDAV, Gitea, GitLab, ntfy, Webhook-Benachrichtigungen |

Die drei `private-network-guard`-Schalter steuern den **Outbound-Guard** (`OutboundTargetGuard`): wohin die Instanz selbst Verbindungen aufbaut. Ausgehende HTTP-Clients gibt es nur über `OutboundHttp`, das ihn je Zielart anwendet; welcher Provider welchen Schalter nutzt, folgt aus seiner Zielart — siehe [Integrationen → Zielart eines Providers](integrations.md#zielart-eines-providers-outbound-guard).

**Definitions-Hash** — `WorkflowDefinitionHasher` hasht den `WorkflowSnapshot` (blocks, instructions, model, provider, effort, metadata, defaultContext, sortierte Skill-IDs) als kanonisches JSON (sortierte Schlüssel, leere Werte weggelassen) mit einem eigenen `ObjectMapper`, damit Jackson-Customizing den Hash nie verändert. Name, Beschreibung, Icon und Tags sind Etiketten und fließen nicht ein. Versionszeilen ohne Hash bekommen ihn beim Start (`WorkflowVersionHashBackfillService`). Kontextvariablen: `{{ workflow_definition_hash }}`, `{{ trigger_source }}`.

**Prüfsumme importierter Definitionen (immer an)** — Der Definitions-Hash entsteht erst aus dem gespeicherten Stand; ob eine Datei auf dem Weg in die Instanz verändert wurde, sagt er nicht. Deshalb tragen Pool-Dateien und Workflow-Exporte ein Feld `"integrity": "sha256:<hex>"` (Feldname nach npm, Format nach OCI), das `DefinitionIntegrity` mit derselben kanonischen Form (eigener Mapper des `WorkflowDefinitionHasher`, sortierte Schlüssel, leere Werte weggelassen) über den Inhalt ohne das Feld selbst berechnet — beim Pool über die ganze Datei, beim Workflow-Export über alles, was der Import übernimmt, außer dem Namen. Pool-Install und `POST /workflows/import` rechnen nach: Abweichung → **400** (`POOL_INTEGRITY_MISMATCH` bzw. `IMPORT_INTEGRITY_MISMATCH`, mit angegebenem und berechnetem Wert), es wird nichts geschrieben; ohne Feld wird mit Warnung übernommen (Log, Install-Antwort `"integrity": "MISSING"`, Hinweis im Import-Dialog). `PoolIntegrityGateTest` hält die getrackten Pool-Dateien und ihre Prüfsummen gleich. **Grenze:** Das erkennt Übertragungsfehler und versehentliche Änderungen, **keine** absichtliche Fälschung — wer die Datei ändert, rechnet die Prüfsumme einfach neu. Für den ausgelieferten Pool bürgt die Versionierung im Repository; Details: [Kuratierung → Prüfsumme](workflow-pool-kuratierung.md#prüfsumme-integrity).

**Run-Zulassung** — Jeder Run-Auslöser ruft `WorkflowExecutor.execute(…, TriggerSource)` und geht damit durch `RunAdmission`, bevor eine Run-Zeile existiert. Regeln in dieser Reihenfolge: (1) ein versiegelter Workflow muss seinem Siegel entsprechen — Definition und Inhalt seiner Skills —, (2) mit `require-sealed-for-unattended` braucht ein unbeaufsichtigter Run einen versiegelten Workflow, (3) ein unbeaufsichtigter Run (Zeitplan, Webhook, TODO-/Completion-Trigger, Auto-Repair, Wish, Auto-Resume, Chain oder Sub-Workflow mit solcher oder unbekannter Herkunft) einer Definition, die auf die eigene Plattform zugreift, wird immer abgelehnt — **403 `UNATTENDED_PLATFORM_ACCESS`**, wenn der Analyzer dort `self-api-call` oder `direct-db-access` mit `HIGH` meldet (`PlatformAccessCheck`, umgesetzt von `WorkflowAnalyzer`, nur die Mustersuche dieser beiden Regeln, ohne KI und ohne Datenbank; TODO e7e93fba). Speichern bleibt erlaubt (der Analyzer meldet nur), ein Mensch kann den Workflow weiter starten (`MANUAL`, `RETRY`, `RESUME`, `TODO_RESOLUTION`, `ASSISTANT`); `MEDIUM`-Befunde (Aufruf nur im KI-Auftrag, `sqlite3` auf eine Projektdatei) halten nichts auf. Ein abgelehnter Run hinterlässt **keine Run-Zeile und keinen DLQ-Eintrag**, nur einen `SECURITY`-Eintrag im Activity-Log; REST-Aufrufer bekommen den Fehlercode.

**Welche Workflows Regel (3) trifft** — Regel (3) kennt keinen Schalter. Welche Definitionen sie betrifft, zeigt der Bestandslauf des Analyzers:

```bash
curl -s --noproxy '*' -H "X-API-Key: $KEY" "http://localhost:9090/api/v1/workflows/analyze?minSeverity=HIGH"
```

Steht in `byRule` `self-api-call` oder `direct-db-access`, starten diese Workflows nicht per Zeitplan, Webhook, Trigger oder Kette (`403 UNATTENDED_PLATFORM_ACCESS`, SECURITY-Eintrag im Activity-Log). Abhilfe: Daten über Kontextvariablen, TODOs über die todo-Blöcke, fremde Dienste über den Integrations-Block; von Hand starten sie weiter.

**Herkunft von Änderungen (immer an)** — Alle Schreiber von Definitionsfeldern gehen durch `WorkflowService` → `WorkflowMutationGateway`. Jede Änderung schreibt eine Versionszeile mit dem Zustand **nach** der Änderung (Nummer = MAX+1) und einem `changeSource`, den der aufrufende Server-Code bestimmt, nie der Request (`user`, `assistant`, `factory`, `wish`, `dialog`, `conversion`, `chain-template`, `pool-install`, `example-project`, `goal-guard`, `import`, `bpmn-import`, `restore`). Passt der Hash der neuesten Zeile nicht zum aktuellen Stand (Altbestand, Änderung am Service vorbei), wird dieser Stand zuerst als `untracked` gesichert. Reine Etiketten-Änderungen erzeugen keine Version. `WorkflowWriterAllowlistTest` legt fest, wer `WorkflowRepository.save` sonst noch aufrufen darf. Dieselbe Stelle lehnt unbekannte Blocktypen ab: `BlockTypeValidator` prüft jeden Blocktyp — verschachtelte in `switch`-`cases[].blocks` und `default` eingeschlossen — gegen die Executor-Registry (`BlockTypeRegistry`, gespeist aus allen `BlockExecutor`-Beans) und wirft **400 `INVALID_ARGUMENT`** mit dem unbekannten Typ und der Liste der erlaubten Typen. Die Prüfung läuft in der Transaktion des Aufrufers, der Speichervorgang wird also zurückgerollt.

**Versiegeln** — `seal` hält den aktuellen Hash in `sealed_hash` fest (mit `sealed_at` und einem WARN-`SECURITY`-Eintrag). Versiegelte Workflows lehnen Definitionsänderungen und Löschen mit **409 `WORKFLOW_SEALED`** ab; Umbenennen bleibt möglich. Reparieren (`POST …/repair`) wird ebenso sofort abgelehnt, automatisches Auto-Repair überspringt versiegelte Workflows vor jedem KI-Aufruf, und die Oberfläche sperrt „Optimieren“ und „Reparieren“. Weicht die Definition beim Start vom Siegel ab (etwa nach einer Änderung direkt in der Datenbank) oder lässt sie sich nicht prüfen, startet der Run nicht: **409 `WORKFLOW_TAMPERED`**, ERROR-`SECURITY`-Eintrag mit erwartetem und tatsächlichem Hash. Beide Prüfungen gelten unabhängig vom Schalter — `sealing` regelt nur Setzen und Lösen, Ausschalten ist kein stilles Entsiegeln.

**Skills unter dem Siegel** — Der Definitions-Hash enthält nur die Skill-**IDs**; was ein Skill in den Run trägt (Name und `aiContext` im Prompt, Config-Schema, je Capability Name, Beschreibung, Schemas, Handler, `async`/`writes`), steckt nicht darin. Deshalb hält `seal` zusätzlich je genutztem Skill — aktivierte Skills und die `skillIds` der Blöcke — einen Inhalts-Hash fest (`SkillContentHasher`, kanonisches JSON wie beim Definitions-Hash, Etiketten wie Beschreibung, Icon, Kategorie und Version zählen nicht) und speichert die Zuordnung als JSON in `workflows.sealed_skill_hashes` (`WorkflowSkillPins`). `RunAdmission` vergleicht sie vor jedem Run: Ein geänderter, hinzugekommener oder gelöschter Skill ergibt **409 `WORKFLOW_TAMPERED`** mit den betroffenen Skill-IDs; lässt sich der Stand nicht prüfen, wird ebenfalls abgelehnt. Die Skill-Hashes fließen nicht in den Definitions-Hash ein, damit Siegel und die Versions-Zuordnung der Runs nur von der Definition abhängen. Ein Siegel ohne Skill-Hashes (`sealed_skill_hashes = NULL`) läuft weiter (mit einer Log-Warnung), prüft aber keine Skills — erneutes `seal` trägt die Hashes nach, allerdings nur, solange die Definition noch zum Siegel passt.

**Schreibpfad für Skills (immer an)** — Alle Schreiber von Skills gehen durch `SkillMutationGateway` (Gegenstück zu `WorkflowMutationGateway`): Anlegen und Bearbeiten (`user`, `PUT /api/v1/skills/{id}`; Built-ins bleiben schreibgeschützt, sonst überschriebe das nächste Release die Änderung still), Import (`import`) und Built-in-Aktualisierung beim Start (`builtin-seed`). Jede Änderung schreibt eine Zeile in `skill_versions` (Operation `CREATE`/`UPDATE`/`DELETE`, kanonischer Snapshot, Inhalts-Hash, serverseitige Herkunft; ohne Fremdschlüssel, die Historie überlebt das Löschen) und einen Activity-Log-Eintrag — ein Import ist ein WARN-`SECURITY`-Ereignis. `GET /api/v1/skills/{id}/versions` listet die Historie, auch für gelöschte Skills. Nutzt ein versiegelter Workflow den Skill, lehnt das Gateway Ändern und Löschen durch eine Person mit **409 `SKILL_PINNED_BY_SEAL`** ab. Built-in-Updates aus den Ressourcen lassen sich nicht ablehnen; ändert sich dabei der Inhalt unter einem versiegelten Workflow, schreibt das Gateway einen WARN-`SECURITY`-Eintrag mit den betroffenen Workflows, und deren Runs werden bis zum erneuten Siegeln abgelehnt. `SkillWriterAllowlistTest` legt fest, dass nur das Gateway `skillRepository.save/delete` aufruft.

**Signatur und Geschichte des Siegels** — `sealed_hash` liegt in derselben SQLite-Datei wie die Definition, die es schützt; wer die Datei schreibt, ändert beides und kommt durch. `WorkflowSealSigner` signiert deshalb jedes Siegel per HMAC-SHA256 über Workflow-ID, Definitions-Hash und Skill-Pins — mit einem Schlüssel **außerhalb** der Datenbank (`automation.seal.key-file`, Env `AUTOMATION_SEAL_KEY`, sonst 0600-Datei beim ersten Start; nie in `app_settings`). Eine vorhandene Signatur wird immer geprüft (`SIGNATURE_INVALID`); ob ein Siegel **ohne** Signatur laufen darf, entscheidet `automation.hardening.require-signed-seal` (`SIGNATURE_MISSING`), damit unsignierte Siegel nicht schlagartig ihre Runs verlieren. Ohne lesbaren Schlüssel startet die Instanz trotzdem, Siegel bleiben dann unsigniert. `automation.hardening.seal-immutable` nimmt `POST …/unseal` aus der REST-API (**403 `SEAL_IMMUTABLE`**): Lösen geht dann nur über die Startkonfiguration, ein geleakter API-Key (oder ein Agent, der ihn hat) reicht nicht mehr. Jede Versiegelung und Entsiegelung steht mit Zeitpunkt in `workflow_seal_events` (`GET /api/v1/workflows/{id}/seal-events`) — das Activity-Log wird nach 30 Tagen aufgeräumt und `sealed_at` fällt beim Entsiegeln weg. Die Einträge sind über alle Workflows hinweg verkettet (`prev_event_hash`), eine nachträglich geänderte oder gelöschte Zeile meldet die API als `chainIntact: false`. Ablehnungen (Run wie Entsiegelung) schreiben ihren SECURITY-Eintrag über `ActivityLogService.logRefusal` nach dem Ende der Aufrufer-Transaktion, sonst nimmt der Rollback sie mit.

**Siegel sichtbar** — `WorkflowSealVerifier` ist die eine Prüfung, die `RunAdmission` vor dem Start und `GET /api/v1/workflows/seal-status` ohne Run ausführen (`INTACT`, `VIOLATED` mit Grund `DEFINITION_CHANGED`/`SKILLS_CHANGED`/`UNVERIFIABLE` und den betroffenen Skills, `NOT_SEALED`). Die Antwort enthält auch die in den letzten 7 Tagen abgelehnten Starts aus dem Activity-Log, denn ein abgelehnter Start legt keine Run-Zeile an. Der Endpunkt ist Kern, nicht Teil des `sealing`-Moduls: Ein Siegel bleibt wirksam, wenn der Schalter aus ist, also muss es sichtbar bleiben. `GET /api/v1/skills/seal-pins` ordnet jedem Skill die versiegelten Workflows zu, die ihn nutzen. Jeder Run hält beim Start fest, ob er versiegelt und geprüft lief (`workflow_runs.sealed`, `sealed_skill_pins`); das wird nie aus dem heutigen Zustand des Workflows abgeleitet. Die Oberfläche zeigt beide Zustände, nicht nur den versiegelten — an Workflow, Run, Chain-Eintrag und Skill; Runs ohne erfassten Siegelstand zeigen „Siegelstand nicht erfasst“, nie einen aus dem heutigen Zustand geratenen Wert. Der SECURITY-Eintrag eines abgelehnten Starts wird bei laufender Transaktion des Aufrufers (Webhook, Zeitplan, Chain) erst nach deren Ende geschrieben, auf einem eigenen virtuellen Thread — sonst rollte die Ablehnung ihn mit zurück, und `REQUIRES_NEW` würde sich bei Pool-Größe 1 selbst blockieren.

**Unbeaufsichtigte Runs** — `TriggerSource` teilt die Auslöser ein:

| Beaufsichtigt | Unbeaufsichtigt | Erbt vom Auslöser |
|---|---|---|
| `MANUAL`, `RETRY`, `RESUME`, `TODO_RESOLUTION`, `ASSISTANT` | `SCHEDULE`, `WEBHOOK`, `TODO_TRIGGER`, `COMPLETION_TRIGGER`, `AUTO_REPAIR`, `AUTO_RESUME`, `WISH` | `CHAIN`, `SUB_WORKFLOW` |

Chains und Sub-Workflows geben die Herkunft ihres Auslösers im reservierten Kontextschlüssel `__parent_trigger_source` weiter: `ChainExecutor` setzt ihn für jeden Eintrag selbst (Eingaben können ihn nicht vorgeben), `SubWorkflowBlockExecutor` reicht die effektive Herkunft des Eltern-Runs durch, und Webhook-Payloads dürfen keine `__`-Schlüssel enthalten. Fehlt der Schlüssel oder ist er unlesbar, gilt der Run als unbeaufsichtigt. Abgelehnt wird mit **403 `UNATTENDED_REQUIRES_SEAL`**. Mit diesem Schalter sind Auto-Repair und Wishes faktisch aus: Sie laufen unbeaufsichtigt und müssten versiegelte Workflows ändern. `RESUME` heißt „eine Person hat fortgesetzt“; setzt die Plattform selbst fort (Opt-in `metadata.autoResume`), läuft der Run als `AUTO_RESUME` und braucht mit dem Schalter ein Siegel — eine Ablehnung lässt ihn `INTERRUPTED`.

**Vertrauensklassen der Kontextwerte** — Jede Kontextvariable eines Runs trägt eine von drei Klassen (`executor/VariableTrust`, Buchführung in `executor/ExecutionContextTaint`). Die Klasse entscheidet, ob der Wert in einer Shell-Kommandozeile roh eingesetzt oder in POSIX-Single-Quotes gepackt wird — und ob er den Run *taintet*, also alle Folgeausgaben mitescapt.

| Klasse | Quelle | In Shell-Blöcken | In KI-Prompts | Taintet den Run |
|---|---|---|---|---|
| **trusted** | Workflow-Defaults, Systemwerte (`project_path`, `workflow_run_id`, `workflow_definition_hash`), blockinterne Werte, Antworten aus `approval`/`human-input`, jedes `setVariable` | roh eingesetzt | roh | nein |
| **untrusted extern** | Webhook-Payload, wiederhergestellte `__untrusted_keys` (Resume, Sub-Workflow), aus getainteten Ausgaben abgeleitete Variablen (`step_N_output`, foreach-Items, `document`-Extrakte) | immer single-quoted | roh | ja, ab dem Setzen |
| **Plattform-Daten** | `previous_report`, `open_todos` — von `WorkflowContextDataService` in **jeden** Run injiziert | immer single-quoted | roh | auf zwei Wegen: (1) sobald der Platzhalter wirklich in eine Shell-Kommandozeile substituiert wurde; (2) sobald die Ausgabe eines KI-Blocks verbucht wird, in dessen Prompt der Platzhalter stand — ab dieser gefärbten Ausgabe ist der Lauf klebrig getaintet |

- **Woher die Plattform-Daten kommen:** Jede Plattform-Variable liefert eine eigene `PlatformVariableSource`-Bean – `PreviousReportVariable`, `OpenTodosVariable`, `RequestMetricsVariable`, `GoalVariables`, `AuditFindingsVariable`, in dieser `@Order`. `WorkflowContextDataService` sammelt sie nur ein und gibt jeder einen eigenen Fehlerpfad: Wirft eine Quelle, stehen ihre Variablen als `NICHT GEMESSEN`-Lücke da, die anderen bleiben unberührt (TODO 441a4c46). Eine neue Variable ist eine neue Bean; in `ContextDeltaCalculator.SYSTEM_VAR_KEYS` gehört sie trotzdem, sonst landet sie im Context-Delta jedes Steps. Jede Quelle nennt mit `allNames()` alle Namen, die sie setzen kann; diese Namen gehören der Plattform (TODO 2a0695ff): `ExecutionContextFactory` übernimmt keinen davon aus einem Eingabe-Kontext, und ein Sub-Workflow bekommt die des Parent-Runs nicht mit – sonst lägen sie im gespeicherten `input_context` des Kindes und kämen dort, wo die eigene Quelle nicht greift (`goal_*` ohne `goal_id`, `audit_findings` ohne `metadata.audit`), als vertrauenswürdige Variable an. Umgekehrt setzt der Einsammler nur, was eine Quelle für den Lauf angemeldet hat (`names()`); einen anderen Namen aus `load()` verwirft er und meldet ihn im Activity-Log.
- **Taint-Ausbreitung:** Ist ein Run getaintet, escapt `recordOutput` jede weitere Blockausgabe (`previous_output`, `{{ block.<name>.output }}`) und `setDerivedVariable` markiert jede daraus abgeleitete Variable als untrusted. Klebrig wird der Taint, sobald eine Ausgabe verbucht wurde oder Plattform-Daten in eine Kommandozeile gelangt sind — ab dann hebt ihn auch ein späteres `setVariable` auf die auslösende Variable nicht mehr auf, weil ein Block Fremdbytes ohne Platzhalter in seine Ausgabe holen kann (Datei lesen, API aufrufen). Davor, also bei externem Input ohne jede verbuchte Ausgabe, entfernt ein `setVariable` auf genau diese Variable den Taint noch. Persistiert wird er in den drei reservierten Kontextschlüsseln `__untrusted_keys`, `__untrusted_outputs` und `__platform_in_shell` (Letzteres das Nutzungs-Flag der Plattform-Daten, `true` sobald es gesetzt ist), damit er Resume und Sub-Workflow-Übergabe überlebt — auch dann, wenn nach der Substitution noch keine Ausgabe verbucht wurde. Ein sauberer Run exportiert nichts; sein Kontext trägt keinen der drei Schlüssel.
- **Warum Plattform-Daten erst bei Nutzung tainten:** Sie stecken in jedem Run. Würden sie beim Injizieren tainten, wäre jeder Run getaintet und die Ausgabe-Platzhalter in den mitgelieferten Templates bekämen eine andere Bedeutung. Deshalb meldet `TemplateResolver.resolveShell`/`resolveShellSafe` zurück, welche Platzhalter tatsächlich in eine Kommandozeile eingesetzt wurden; erst diese Meldung schaltet den Taint scharf (`ExecutionContextTaint.substitutedIntoShell`). Ein Run, der `{{ previous_report }}` gar nicht verwendet, bleibt untainted.
- **KI-Ausgaben nach Plattform-Daten-Prompt sind gefärbt:** Standen Plattform-Daten im Prompt eines KI-Blocks, gilt die Ausgabe genau dieses Blocks als untrusted — ein folgender Shell-Block bekommt `{{ previous_output }}`/`{{ block.<name>.output }}` also single-quoted. Umgesetzt über denselben Rückmeldekanal: `TemplateResolver.resolvePrompt` meldet die aufgelösten Platzhalter, `ExecutionContextTaint.substitutedIntoPrompt` merkt sich Plattform-Daten, und die nächste über `recordOutput` verbuchte Ausgabe konsumiert dieses Flag. Der **Prompt allein** färbt den Run nicht: Solange keine Ausgabe verbucht wurde, ist `hasUntrustedInput()` false und `exportTaint()` leer. Mit der verbuchten Ausgabe ist der Lauf dann aber getaintet, und zwar klebrig wie in jedem anderen Fall — `recordOutput` escapt ab da **jede** weitere Blockausgabe, `setDerivedVariable` markiert jede daraus abgeleitete Variable (`step_N_output`, foreach-Items, `document`-Extrakte) als untrusted, und `exportTaint()` reicht beides an Sub-Workflows und an ein Resume weiter. Ein späterer KI-Block mit sauberem Prompt bekommt also keine saubere Ausgabe zurück — das gilt nur, wenn vorher nichts Gefärbtes verbucht wurde. Auf dem Prompt-Pfad liegen genau die KI-Aufrufe: `simple`, `skill-capability`, `agentic` (`goal`/`success_criteria`) und die Nicht-Shell-Zweige von `foreach`, `while`, `conditional`, `retry` und `switch`; Nicht-Prompt-Auflösungen (Mounts, Dokumentpfade, TODO-Felder, Integrations-Parameter, `switch`-Ausdruck) bleiben auf `resolveTemplate` und melden weiterhin nichts.
- **Bewusst trusted:** Antworten aus `approval`- und `human-input`-Blöcken kommen über die API-Key-geschützte REST-Schnittstelle von der Person, der die Instanz gehört — sie werden nicht escaped. Ebenso überschreibt `setVariable` (etwa eine Workflow-Variable, die einen Webhook-Wert gleichen Namens überdeckt) die Untrusted-Markierung absichtlich: Der Wert kommt dann aus der Definition, nicht vom Aufrufer. `replaceValue` (Workspace-Pfad-Remapping) tauscht dagegen nur den Wert und lässt die Klasse stehen.
- **Bekannte Grenzen:**
  1. In KI-Prompts wird nichts escaped — ein Prompt ist keine Kommandozeile, Prompt-Injection wehrt das Trust-Modell nicht ab.
  2. Prompt-Injection über die KI-Antwort selbst bleibt möglich: Gequotet wird die Ausgabe, ihr *Inhalt* stammt weiterhin aus einem Modell, das Plattform-Daten gelesen hat. Das Quoting verhindert Shell-Injection, keine inhaltliche Manipulation.
  3. Ein Shell-Block, der einen getainteten Wert selbst in eine Datei schreibt und diese später wieder ausführt, liegt außerhalb dessen, was das Quoting sehen kann.

  Den Weg Plattform-Daten → KI-Prompt → Shell-Block schließt die Färbung der KI-Ausgabe (TODO 22e6bec8). **Empfehlung: In jedem Shell-Block, der die Ausgabe eines KI-Blocks weiterverarbeitet, `"shell_escape_variables": true` setzen** — dann verhält sich der Block in gefärbten wie in sauberen Läufen gleich. Für die ausgelieferten Definitionen erzwingt das `PlatformPromptQuotingGateTest`.

**Webhook-Signaturen** — `POST /api/v1/webhooks/{id}/secret` erzeugt ein Secret (32 Zufallsbytes, hex), zeigt es genau einmal und speichert es AES-GCM-verschlüsselt (`IntegrationEncryptionService`, Schlüssel außerhalb der Datenbank) in `webhook_triggers.signing_secret_enc`; `DELETE …/secret` entfernt es. Hat ein Webhook ein Secret, prüft `WebhookSignatureVerifier` vor dem JSON-Parsen den Header `X-Signature: sha256=<hex>` (alternativ `X-Hub-Signature-256`, das Format von GitHub und Gitea) als HMAC-SHA256 über die rohen Request-Bytes, Vergleich in konstanter Zeit — sonst **401 `WEBHOOK_SIGNATURE_INVALID`**. Wie beim Siegel bleibt ein gespeichertes Secret wirksam, wenn der Schalter später aus ist. Responses verraten nur `signatureRequired`.

## Backend-Architektur

### Schichtenmodell

```
Controller (REST API)
    ↓ DTO (Request/Response Records)
Service (Geschäftslogik)
    ↓ Entity (JPA)
Repository (Spring Data JPA)
    ↓ SQL
SQLite (Datei: data/automation.db)
```

Die erlaubten Kanten zwischen den Schichten hält `LayerRuleTest` fest, siehe [Architektur-Gates](#architektur-gates).

### Datenbank-Wartung (SQLite, pool-size=1)

- **Run-Retention** (`RunCleanupService.cleanupTerminalRuns`, täglich 03:15): löscht abgeschlossene Runs (COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED) samt `step_results`, die älter als `run-retention-days` sind — älteste zuerst, höchstens `run-retention-max-per-night` pro Nacht, jeder Run in einer eigenen kurzen Transaktion über `RunDeletionService` (räumt DLQ-, TODO- und Wish-Verweise mit). Runs mit Audit-Report bleiben (Query-Ausschluss plus Guard im Service). Runtime-Settings in `ExecutionSettingsService`: `run-retention-days` [0,730] (0 = aus, Default 90), `run-retention-max-per-night` [10,5000] (Default 200). INTERRUPTED-Runs haben eine eigene Regel (`cleanupInterruptedRuns`, 03:00).
- **Run-Löschung per API abschaltbar** (`RunDeletionPolicy`): `automation.executions.allow-delete: false` (Startkonfiguration, Default `true`) lässt `DELETE /api/v1/executions/{id}` mit `403 RUN_DELETION_DISABLED` scheitern und blendet die Löschknöpfe aus (`runDeletionAllowed` in `/system/capabilities`). Bewusst ohne Laufzeitschalter. Die beiden Aufräumjobs oben sind Betrieb und laufen weiter; `run-retention-days` bleibt ein Runtime-Setting, lässt sich bei abgeschalteter Löschung zur Laufzeit aber nur noch **verlängern** (`0` = aus zählt als längste); eine kürzere Retention scheitert mit `403 RUN_DELETION_DISABLED`, damit ein geleakter Key die Runs nicht über Nacht löschen lassen kann.
- **Backup** (`DatabaseBackupService`, täglich 02:00, `automation.backup.retain-count`): läuft bewusst vor der Retention.
- **VACUUM**: `PRAGMA auto_vacuum = INCREMENTAL` ist das **einzige** Statement im Hikari-Init-SQL — sqlite-jdbc führt von einem mehrteiligen Init-String nur das erste aus (`foreign_keys`/`busy_timeout` stehen deshalb in der JDBC-URL, Guard: `DataSourceConfigGuardTest`). Das Pragma greift nur bei leerer DB oder nach einem vollen `VACUUM`. Der Startup-Check `checkAutoVacuumMode` schreibt eine ActivityLog-WARNING, wenn die Datei noch auf NONE steht — dann einmal `POST /api/v1/system/vacuum` ausführen, danach gibt das nächtliche `incremental_vacuum` (03:00) Platz frei. Direkter `sqlite3`-Zugriff ist tabu (Hibernate-Cache, Single-Writer).

### Packages

Die Pakete unter `com.automation` folgen den Schichten: `controller/`, `service/` (nach Fachgebiet in
Unterpakete gegliedert), `repository/`, `entity/`, `dto/` und `mapper/`; dazu unter anderem `executor/`
(Engine und Block-Executoren), `security/`, `websocket/`, `modules/`, `metrics/`, `config/`, `event/` und
`util/`. Welche Klasse wo
liegt, zeigt das Verzeichnis selbst. Wechselseitige Abhängigkeiten zwischen Paketen und verbotene
Schichtkanten fangen die [Architektur-Gates](#architektur-gates) ab; einige Blatt-Pakete
(`service/runoutput/`, `service/cli/claude/`, `service/assistant/format/`) gibt es nur, damit kein Zyklus
entsteht.

### Workflow-Ausführung

Die Workflow-Engine führt Blöcke sequenziell aus. Die KI-CLIs (Claude, Codex, Pi) laufen **immer auf dem Host** im Projektverzeichnis, nicht im Projekt-Container; Shell-Blöcke ebenso, außer ein Schritt nennt `container_image`.

**Ablauf:**
1. `WorkflowExecutor` lässt den Start von `RunAdmission` prüfen (abgewiesen: kein Run), legt den `WorkflowRun` direkt mit Status RUNNING an und antwortet sofort; die Blöcke laufen auf einem virtuellen Thread, sobald ein Slot frei ist (`automation.execution.max-concurrent`)
2. Vor dem ersten Block sucht oder startet der Run den Projekt-Container, sofern Docker eingerichtet ist und der Run nicht isoliert läuft (scheitert der Start, geht es ohne Container weiter), und schreibt Skills, Projektkontext und aktive Workflow-Memory nach `.claude/rules/`
3. Für jede `BlockDefinition` wird der passende `BlockExecutor` ausgewählt; `BlockStepRunner` legt den Step an und führt ihn aus
4. KI-Aufrufe gehen über den `CommandExecutorService` an den Anbieter aus Block oder Workflow, sonst an den Systemstandard (`AiProviderRegistry`), und starten dessen CLI auf dem Host (`claude -p`, `codex exec`, `pi -p`; Prompt über stdin)
5. Shell-Befehle laufen per `/bin/bash -c` im Projektverzeichnis auf dem Host, mit `container_image` in einem Einmal-Container (`docker run --rm`); den Projekt-Container nutzen nur Ad-hoc-Befehle während eines Runs und das Terminal
6. Ergebnisse werden als `StepResult` gespeichert
7. Live-Logs gehen über WebSocket (STOMP, `/topic/executions/{runId}/logs`) an das Frontend
8. Nach Abschluss wertet `RunCompletionHandler` Gate-Status und TODO-Marker aus allen Block-Ausgaben aus (die Kopien, die `compress`, `compress-ai` und `sub-workflow` weiterreichen, zählen nicht noch einmal), verbucht Template-Verification, Activity-Log und DLQ
9. Nach dem letzten Speichern feuert `RunFinalizer` das `WorkflowRunCompletedEvent` → u. a. `AutoTodoService` reagiert

**Steuerbarkeit ab Schritt 1:** Noch vor dem Submit registriert der `WorkflowExecutor` einen provisorischen `ExecutionContext` in der `WorkflowExecutionRegistry`. Ein Cancel greift damit auch, solange der Run noch auf einen Semaphore-Slot oder den Container-Start wartet — der Ausführungs-Thread übernimmt beim Tausch gegen den echten Context dessen Cancel-/Pause-Flag. Folge: `getActiveRunCount()` zählt auch wartende Runs, sodass `GhostRunWatchdogService` den Permit eines startenden Runs nicht als Leak freigibt. Pause, Resume, Cancel, Freigaben und Ad-hoc-Befehle laufen über `RunControlService`: Er arbeitet nur auf der Registry und startet selbst nichts — neue Runs und `resume-from-step` bleiben im `WorkflowExecutor` und damit hinter `RunAdmission`.

**Resume schneidet nach Blöcken, nicht nach Step-Indizes** (`ResumePlan`): `POST /executions/{id}/resume-from-step` setzt hinter dem letzten COMPLETED-Step an. Maßgeblich ist dessen **Position** in der Blockliste, nicht sein `stepIndex` — Wrapper-Blöcke (foreach, while, retry, conditional, switch) ziehen pro Item/Iteration einen weiteren Index, sodass der Index eines Blocks seiner Position vorauseilt. Grundlage ist die Zusage von `BlockStepRunner`: genau ein persistierter Step pro Top-Level-Block, in Blockreihenfolge (innere Items werden nie gespeichert). Die Steps des fortgesetzten Abschnitts werden über `ExecutionContext.startStepIndicesAt` oberhalb aller bereits persistierten Indizes weitergezählt, bleiben also eindeutig und sortierbar. Ein Schnitt nach Index würde nach einem Wrapper Blöcke überspringen, die nie gelaufen sind (TODO `1be423b4`).

**Automatisch fortsetzen (Opt-in, TODO 90d10ba1)** — `RunControlService.cleanupZombieRuns` (Start) und der Standby-Zweig von `GhostRunWatchdogService` schreiben beim Markieren in die Run-Metadaten, woraus (`interruptedFrom`: `RUNNING`/`PAUSED`) und wodurch (`interruptedBy`: `RESTART`/`STANDBY`) unterbrochen wurde, und veröffentlichen danach `RunInterruption.Event` mit den markierten Runs — die Reihenfolge ergibt sich aus diesem Aufruf, nicht aus der Listener-Reihenfolge. `RunAutoResumer` sieht damit nur Runs aus dem laufenden JVM-Lebenslauf und setzt davon die fort, die `INTERRUPTED` und vorher `RUNNING` sind, weder Ketten-Eintrag noch Sub-Workflow sind (Quelle `CHAIN`/`SUB_WORKFLOW`, `chain_run_id` oder `__parent_trigger_source` im Kontext), deren Workflow existiert und `metadata.autoResume.enabled` gesetzt hat und die noch keinen Versuch hatten. Der Zähler `autoResumeAttempts` wird in einer eigenen kurzen Transaktion **vor** dem Start gespeichert, dann läuft `WorkflowExecutor.resumeFromStep(runId, TriggerSource.AUTO_RESUME)` durch `RunAdmission`. Die Runs laufen nacheinander auf einem eigenen Thread an, mit `automation.execution.auto-resume.delay-seconds` vor jedem (Laufzeit-Einstellung `run-auto-resume-delay-seconds`, Schalter `run-auto-resume-enabled`); weder Start noch Watchdog-Tick warten darauf. Jede Fortsetzung, Ablehnung und jeder Fehler steht im Activity-Log (Quelle `RunAutoResumer`). Chains (`ChainZombieRunCleaner`) bleiben außen vor. Gegen den Zeitplan (TODO 070ccc3d): Der unterbrochene Run steht während der Wartezeit auf `INTERRUPTED`; die SKIP-Prüfung (`RUNNING`/`PAUSED`) allein sähe ihn nicht, und ein Nachhol-Run würde daneben starten. `ScheduleConflictCheck` fragt deshalb vor der Statusprüfung `PendingAutoResumes.forWorkflow`: ein in diesem Lebenslauf unterbrochener, geeigneter Run des Workflows, den `RunAutoResumer` noch nicht erledigt hat, oder der gerade laufende Versuch. Gelesen wird der gespeicherte Stand, nicht die Warteschlange — ein Run zählt ab seiner Markierung, auch bevor das Event ankommt; übergangene Runs (nicht geeignet, Schalter aus, abgelehnt) blockieren nichts. Die Reihenfolge „erst anstehend, dann `RUNNING`“ lässt keine Lücke, weil der fortgesetzte Run `RUNNING` ist, bevor sein Versuch als erledigt gilt.

**Block-Position je Step** (`metadata.blockIndex`): `BlockStepRunner.startStep` schreibt die Position des Blocks in der Top-Level-Liste in die Step-Metadaten, `runStep` hält sie gegen Executor-Metadaten fest. Das Ablauf-Diagramm der Run-Seite ordnet Steps darüber seinen Kästen (`b_<Position>`) zu (`latestStepByBlock` in `stepMetadata.ts`); Runs ohne den Schlüssel fallen auf die Position in der index-sortierten Liste zurück. Nach einem Resume steht der abgebrochene Versuch eines Blocks neben dem neuen — beide mit demselben `blockIndex`, der höhere `stepIndex` ist der aktuelle (TODO `dcba60da`).

**Container-Start/-Stop ohne Transaktion:** `ProjectService.startContainer`/`stopContainer` sind bewusst **nicht** `@Transactional`: der Docker-Aufruf liegt zwischen zwei kurzen `TransactionTemplate`-Bursts (STARTING → Docker außerhalb jeder Transaktion → RUNNING/ERROR), weil `ContainerService` dabei den Projekt-Monitor hält — eine umschließende Transaktion würde die einzige Connection über den ganzen 5–15-s-Docker-Start pinnen und einen parallelen Workflow-Run (der den Monitor zuerst nimmt) in den 120-s-Connection-Timeout laufen lassen (Lock-Order-Inversion Monitor ↔ Connection). Weil der STARTING-Burst bereits vor dem Docker-Start committet, holt `ProjectStatusReconciler` beim `ApplicationReadyEvent` alle Projekte ab, die ein Absturz in diesem Fenster in STARTING zurückgelassen hat (RUNNING-Container-Zeile ⇒ RUNNING, sonst STOPPED) — das Projekt-Gegenstück zu `RunControlService.cleanupZombieRuns`. `ProjectService.delete` folgt demselben Muster in umgekehrter Reihenfolge: zuerst der Archivierungs-Burst (`archivedAt` + ActivityLog), danach der Docker-Stop außerhalb jeder Transaktion — auch wenn nur die Container-Zeile RUNNING ist, weil ein Workflow-Lauf den Container selbst startet, ohne das Projekt auf RUNNING zu setzen — schlägt er fehl, bleibt das Projekt archiviert (ERROR-Log), und nur der verwaiste Container bleibt zurück, den `DockerOrphanReconciler`/Idle-Cleanup einsammeln.

Dasselbe Muster gilt für `WorkflowMemoryService.extractKnowledge`/`extractAndActivate` (Security-Finding 4, analog zu `ebd508c5`): der KI-Summarization-Call (`ClaudeSummarizer`/`CodexSummarizer`, Sekunden bis Minuten) läuft außerhalb jeder Transaktion, zwischen einem lesenden `TransactionTemplate`-Burst (Runs laden, lazy `steps`-Collection in eine neue Liste materialisieren — OSIV ist aus —, Run-IDs sammeln) und einem schreibenden Burst (`WorkflowMemory` speichern bzw. `deactivateAllForWorkflow` + Aktivierung). `activate`/`update`/`delete` bleiben kurze `@Transactional`-Methoden.

**Integration trennen ohne Transaktion:** `IntegrationOAuthService.disconnect`/`deleteCredentials` folgen demselben Burst-Muster: kurzer `TransactionTemplate`-Burst (Token-Felder leeren bzw. Zeile löschen) → Token-Revocation beim Provider außerhalb jeder Transaktion. Der HTTP-Aufruf hat 10 s Timeout und würde in einer `@Transactional`-Methode die einzige Connection so lange halten; „persist first" verhindert zusätzlich den Zustand „beim Provider widerrufen, lokal aber noch `CONNECTED`".

**Integration verbinden ohne Transaktion:** `saveCredentials` und `handleCallback` rufen Provider-HTTP (PAT-Validierung `fetchAccountInfo`/`fetchGrantedScopes` bzw. Code-Tausch `exchangeCodeForTokens` + Account-Info, je 10 s Timeout). Die Ablauflogik steckt in `IntegrationOAuthConnectFlow` (eigene Klasse wegen der Ratchet-Decke): kurzer lesender `TransactionTemplate`-Burst (Client-ID/-Secret entschlüsseln) → Provider-HTTP außerhalb jeder Transaktion → kurzer schreibender Burst (Zeile laden-oder-anlegen, Tokens/Status setzen, speichern). `IntegrationOAuthService` delegiert nur; `IntegrationOAuthConnectIntegrationTest` beweist per `TransactionSynchronizationManager.isActualTransactionActive()` im gemockten Client/Provider, dass während der HTTP-Aufrufe keine Transaktion offen ist. Nebenwirkung des Musters: Schlägt die PAT-Validierung fehl, wird die Zeile trotzdem mit Credentials und Status `DISCONNECTED` gespeichert.

**Pool-Watchdog sieht virtuelle Threads:** `ConnectionPoolWatchdogService` loggt eine Pool-Erschöpfung samt gefiltertem Thread-Dump erst, wenn zwei aufeinanderfolgende Stichproben (15 s Abstand) Wartende zeigen – eine einzelne löst sich fast immer bis zur nächsten auf; die ERROR-Meldung nach 60 s zählt ab der ersten Stichprobe. `Thread.getAllStackTraces()` kennt nur Plattform-Threads — Workflow-Runs laufen aber auf `virtual-*`-Threads, ein solcher Dump würde nur den Wartenden zeigen, nie den Halter der einzigen Connection. `ThreadDumpCollector` nutzt deshalb `HotSpotDiagnosticMXBean.dumpThreads(…, TEXT_PLAIN)` (JDK 21, keine Abhängigkeit): Dump in eine Temp-Datei unter `java.io.tmpdir` (sofort wieder gelöscht), parsen, auf Frames mit `com.automation`/Hibernate/SQLite/Hikari filtern, wahrscheinliche Halter (`org.sqlite.`-/Hikari-Proxy-Frames) zuerst, Wartende (`HikariPool.getConnection`) zuletzt, maximal 20 Threads à 30 Frames. Schlägt der HotSpot-Dump fehl, fällt der Collector auf `getAllStackTraces()` zurück (im Log als `source=…` erkennbar).

**Container-Zeile:** `ContainerService.createAndStartContainer` aktualisiert eine vorhandene `containers`-Zeile (Upsert), statt sie zu löschen und neu anzulegen — die Cascade `Project.container` höbe die Löschung beim Flush wieder auf, und der folgende INSERT würde an `UNIQUE(containers.project_id)` scheitern. Der Projekt-Lock bleibt dauerhaft in der Map (Entfernen unter Konkurrenz erzeugt einen zweiten Monitor), und `lastActivityAt` wird per JPQL-Update (`ContainerRepository.touchActivity`) statt per detached Merge gesetzt.

### Block-Typen

Welche Blocktypen es gibt, bestimmt die Executor-Registry: `BlockTypeRegistry` sammelt
`supportedTypes()` aller `BlockExecutor`-Beans, und `BlockTypeListsGuardTest` hält die übrigen Listen
(Frontend, Assistent) gleich. Konfiguration und Verhalten je Typ stehen in
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (Abschnitt „Block-Typ-Referenz“). Platzhalter (`{{ variable }}`,
`${varName}`) löst der `ExecutionContext` zur Laufzeit auf; `{{ previous_output }}` enthält die Ausgabe des
vorherigen Blocks.

### Agentic Block (Goal→Reason→Act→Observe)

Die Schleife REASON → ACT → OBSERVE mit ihren Sicherheitsgrenzen (Iterationen, Timeouts,
Token-Budget, wiederholte Aktionen, Freigabe) und ihren Fallen beschreibt
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (Abschnitt „Agentic Block Deep Dive“), warum die Budgets so gesetzt
sind [konzepte/agentische-budgets.md](konzepte/agentische-budgets.md). Im Code: `AgenticBlockExecutor` mit
`AgenticBlockConfig`, `AgenticSafetyGuard`, `AgenticTokenTracker` und `AgenticPlan`.

### Sub-Workflow Block

Verhalten und Konfiguration: [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (Abschnitt `sub-workflow`). Im Code:

- Start, Wächter (zirkuläre Referenzen über `__parent_workflow_ids`, Verschachtelungstiefe) und Warten liegen in `SubWorkflowLauncher`; denselben Weg nimmt `foreach` mit `workflow_id` je Item (TODO a5c9fbbf). `workflow_id` darf ein Platzhalter aus einer vertrauenswürdigen Kontextvariable sein, nie aus Webhook-Daten oder Blockausgaben
- Ein abgebrochener Eltern-Lauf bricht den Kind-Lauf über `RunControlService.cancelExecution` ab
- Gewartet wird über `RunCompletionWaiter`, dieselbe Schleife wie bei Ketten-Einträgen (`ChainRunWaiter`): Ende ist jeder Status außer `PENDING`/`RUNNING`/`PAUSED` – ein pausierter Kind-Lauf wird weiter abgewartet (TODO 802a516e)
- `@Lazy`-Injection auf `WorkflowExecutor` nur dort, wo ein Aufrufer selbst Runs startet (Sub-Workflow, Chains, Trigger); reine Status-Abfragen (aktive Runs, Container-IDs, Approvals) gehen direkt an `WorkflowExecutionRegistry`, das Steuern eines laufenden Runs an `RunControlService`, der den `WorkflowExecutor` nicht braucht

## Gate Status System

Ein Quality Gate ist das Urteil, das ein prüfender Workflow über sein Ergebnis fällt (GREEN, YELLOW,
RED), geschrieben als Marker `gate_status:`/`gate_category:`/`gate_description:` in eine Blockausgabe.
Regeln — welches Gate gewinnt, ausbleibendes Urteil (`gate-missing`), unlesbares (`gate-invalid`),
`checked_command` — stehen in [WORKFLOW-GUIDE.md → Quality-Gate-Marker](WORKFLOW-GUIDE.md#quality-gate-marker),
der Gedanke dahinter in [konzepte/gates.md](konzepte/gates.md). Ein Gate ändert den Run-Status nicht; es
ist ein Befund über das Ergebnis, kein Fehlschlag der Ausführung.

Im Code: `GateStatusOutputParser` liest die Marker, `RunCompletionHandler` wertet nach dem Lauf alle
Blockausgaben aus und schreibt das Urteil in `WorkflowRun.metadata`, `GateExpectation` erkennt, ob die
Definition ein Urteil bestellt, und `GateStatusResolver` leitet eines aus dem Run-Status ab, wenn kein
Marker kam. `AutoTodoService` legt bei eingeschaltetem Auto-TODO aus RED (im Modus `auto` auch YELLOW) ein
TODO an; nach einem Reparatur-Lauf schließt ein GREEN das auslösende TODO
(`TodoFeedbackListener`, siehe [Todo Triggers](#todo-triggers--self-improvement-loop)).

## Workflow Chains

Chains führen mehrere Workflows nacheinander aus, auch projektübergreifend; jeder Eintrag bekommt
`{{ previous_chain_output }}` des letzten abgeschlossenen Workflows. Eigenschaften, Struktur und Grenzen:
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (Abschnitt „Chains“). Im Code führt `ChainExecutor` die Einträge auf
virtuellen Threads aus und fragt ihren Status ab; der Status geht per WebSocket an
`/topic/chains/{chainRunId}/status`.

### Chain Templates

Vorgefertigte Chain-Definitionen, die sich aus bestehenden Workflow-Templates zusammensetzen. Jeder Eintrag muss ein ausgeliefertes Built-in-Template nennen (`ChainTemplateServiceTest`), eine Kette über Pool-Einträge gibt es nicht:
- `ChainTemplateService` — verwaltet Built-in Chain Templates (`BuiltInChainTemplate` Records)
- `ChainTemplateController` — `/api/v1/chain-templates` mit List, Get, Install-Endpunkten
- Install: Erstellt eine neue Chain (ggf. mit zugehörigen Workflows) in einem Zielprojekt

## Scheduler: verpasste Termine (Standby und Kaltstart)

Der Scheduler ist ein 60-s-Tick im Backend (`SchedulerTick`); ohne laufendes Backend startet nichts. Für Termine, die während einer Lücke fällig wurden, gilt **eine** Regel, unabhängig davon, ob die JVM eingefroren war (Standby, erkannt als Tick-Lücke > `automation.scheduler.resume-grace-minutes`) oder der Prozess gar nicht lief (Kaltstart, erkannt am ersten Tick; Referenz ist die Startzeit des Backends, Termine danach sind normale Fälligkeit):

- **Ohne Opt-in** wird der Termin übersprungen und der Zeitplan auf sein nächstes Vorkommen gesetzt (`ScheduleSkipPolicy`, Activity-Log „Schedule skipped (system suspend/resume)" bzw. „(backend was stopped)").
- **Mit Opt-in** — pro Zeitplan `catchUpOnResume` oder global `scheduler-catch-up-on-resume` (Settings-UI) — wandert der Termin in die `CatchUpQueue`. Sie startet die Einträge **nacheinander** in der Reihenfolge der ursprünglichen Fälligkeit: der nächste erst, wenn der vorherige Nachhol-Run einen Endzustand erreicht hat (`ScheduledWorkflowExecutor.isFinished`). Wartende Einträge behalten ihren alten `nextRunAt`, werden vom normalen Tick aber ausgeklammert. Ein Eintrag, den der Executor ablehnt (Ziel läuft bereits, Zeitplan inzwischen deaktiviert), blockiert die Warteschlange nicht.
- Eine aktive `skip-until`-Pause gewinnt: dann wird auch mit Opt-in nichts nachgeholt.
- Die Warteschlange ist bewusst nicht persistent: nach einem Neustart sind dieselben Termine wieder fällig und werden vom Kaltstart-Pfad erneut eingereiht — nichts geht verloren, nichts läuft doppelt.
- Sichtbarkeit: eine WARN-Zeile im Activity-Log pro Ereignis („Cold start detected … N missed schedule(s): X skipped forward, Y queued for sequential catch-up", bei Fehlern „…, Z failed"), `GET /api/v1/schedules/catch-up` (laufender und wartende Einträge) und ein Banner auf der Schedules-Seite.
- `resume-grace-minutes: 0` schaltet beide Erkennungen ab (dann startet alles Fällige sofort).
- Der Tick startet erst, wenn Spring Boot `ACCEPTING_TRAFFIC` meldet — also nach der Start-Bereinigung, die liegengebliebene RUNNING/PAUSED-Runs auf INTERRUPTED setzt (`ApplicationReadyEvent`). Spring feuert `@Scheduled` schon bei `ContextRefreshedEvent`, also vor dieser Bereinigung: Ein dort gestarteter Nachhol-Run würde von ihr mitten im Lauf als INTERRUPTED markiert, und ein Altlauf im Zustand RUNNING ließe das Ziel belegt aussehen, sodass der Nachhol-Termin verfiele (TODO aa51d221). Übersprungene Ticks zählen nicht; der erste echte Tick ist der Kaltstart und kommt etwa eine Tick-Periode (60 s) nach dem Start, Nachhol-Runs starten entsprechend später.

Weitere Regeln des Ticks:

- **Ein Fehler betrifft nur seinen Zeitplan** (TODO 68a3d91e): Jeder Zeitplan eines Ticks wird für sich gestartet, übersprungen oder eingereiht (`SchedulerTick.runIsolated`). Scheitert einer — etwa an einem Schreibfehler —, steht „Schedule execution failed: …" bzw. „Schedule skip failed: …" im Activity-Log, und der Tick macht mit dem nächsten weiter.
- **Ungültiger Cron-Ausdruck** (TODO 565e853b): Anlegen und Ändern weisen ihn ab; steht trotzdem einer in der Datenbank (von Hand, über eine alte Version), deaktiviert `ScheduleSkipPolicy.endIfCronInvalid` den Zeitplan beim ersten Kontakt — fällig im Executor wie beim Überspringen (`skip-until`, Standby, Kaltstart) — ohne Start und meldet es genau einmal (Activity-Log ERROR „Schedule disabled (invalid cron expression '…'): …", `reason: invalid-cron`). Bis er fällig wird, lässt das Dashboard (`UpcomingRunsCalculator`) ihn aus, statt mit der Parse-Ausnahme zu scheitern.
- **Konflikt `SKIP`** (Standard, TODO ade3fef8): Hat das Ziel beim Fälligwerden noch einen Lauf in `RUNNING` oder `PAUSED`, entfällt der Termin — `nextRunAt` springt auf das nächste Vorkommen, `ONCE`/`DELAYED` verfallen, `lastRunAt` und `runCount` bleiben (`ScheduledWorkflowExecutor.skipOccurrence`). `OVERLAP` startet daneben.
- **`maxRuns`** zählt jeden Start, auch fehlgeschlagene oder abgewiesene, nicht aber per `SKIP` entfallene Termine. Wiedereinschalten setzt `runCount` nicht zurück.
- **Kein Termin mehr übrig** (TODO 1e801952, `ScheduleEndConditions`): Anlegen, Ändern und Einschalten weisen einen eingeschalteten `CRON`-Zeitplan mit `400` ab, dessen nächster Termin am oder nach dem Enddatum liegt oder dessen `maxRuns` ≤ `runCount` ist (übersetzt: `SCHEDULE_NEXT_RUN_AFTER_END_DATE`, `SCHEDULE_MAX_RUNS_REACHED`). Ein ausgeschalteter bleibt änderbar (das Formular schickt `maxRuns` immer mit), und `update` prüft nur, wenn `cronExpression`, `scheduledAt`, `endDate` oder `maxRuns` im Request stehen — eine reine Beschreibungsänderung an Altbestand gibt kein `400`; der Bündel-Import legt Zeitpläne über `SchedulerService.createDisabled` ausgeschaltet an, ein abgelaufenes Enddatum bricht ihn nicht ab. Die Skip-Policy (`skip-until`, Standby, Kaltstart) setzt `nextRunAt` nicht hinter das Enddatum, sondern beendet den Zeitplan, und `effectiveNextRun` zeigt keinen Termin am oder nach dem Enddatum an.
- **Cron ohne künftigen Termin, einmaliger Termin in der Vergangenheit** (TODO 1e801952): Ein gültiger Ausdruck, der nie wieder trifft (etwa 30. Februar), wird beim Anlegen, Einschalten und bei neuem Ausdruck mit `400` abgewiesen (`SCHEDULE_NO_NEXT_RUN`); findet `advance` nach einem Lauf keinen Termin mehr, beendet es den Zeitplan wie beim Enddatum und meldet das einmal (Activity-Log „Schedule ended (no further occurrence)", `reason: no-next-run`). Ein `ONCE`-Zeitplan mit `scheduledAt` in der Vergangenheit wird beim Anlegen, Einschalten und beim Verlegen des Zeitpunkts abgewiesen (`SCHEDULE_ONCE_IN_PAST`) statt sofort zu starten — mit 60 s Toleranz: ein Zeitpunkt in der laufenden Minute startet regulär beim nächsten Tick; `DELAYED` rechnet ab jetzt und bleibt ungeprüft.
- **Enddatum** (`endDate`, nur `CRON`, TODO 9a3e6c91): Ein Termin am oder nach dem Enddatum startet nicht — gemessen am Termin, nicht am Zeitpunkt des Ticks, damit der letzte Termin davor nicht davon abhängt, wohin der 60-s-Tick fällt. `ScheduledWorkflowExecutor.advance` setzt nach einem Lauf oder `SKIP` keinen nächsten Termin hinter das Enddatum, sondern deaktiviert den Zeitplan; ein schon gespeicherter Termin dahinter (Altbestand, nach vorn verlegtes Enddatum) beendet ihn beim Fälligwerden ohne Start (Activity-Log „Schedule ended (end date reached)").
- **`skip-until` in den Ansichten** (TODO a776fd2a): Der Tick spult einen Termin erst hinter das Fenster, wenn er fällig wird. Bis dahin rechnen die Lesepfade um — `ScheduleSkipPolicy.effectiveNextRun` (dieselbe Regel wie `skipScheduleUntil`, ohne zu schreiben): `ScheduleMapper` liefert als `nextRunAt` den ersten Termin nach der Pause plus `pausedUntil`, `UpcomingRunsCalculator` lässt Termine in der Pause aus Dashboard, Kalender, Cron-Vorschau und Assistent-Kontext weg, `GET /schedules/maintenance` nennt das Pausenende. Die Lesepfade parsen `skip-until` ohne Activity-Log-Eintrag; einen ungültigen Wert meldet der Tick.
- **Wartungsmodus** (`scheduler.maintenance-mode`, Pause-Schalter der Schedules-Seite): Der Tick läuft weiter, startet aber nichts; `nextRunAt` bleibt stehen. Beim Ausschalten startet jeder überfällige Zeitplan einmal sofort, alle gleichzeitig — weder Standby-/Kaltstart-Erkennung noch Nachhol-Warteschlange greifen, die Ticks während der Pause haben die Lücke schon verbraucht.
- **Wartungsmodus in den Ansichten** (TODO 7704b1e9): dieselbe Regel wie bei `skip-until`, keine zweite. `ScheduleSkipPolicy.activePause` liest beide Pausen einmal je Ansicht (`SchedulerPause`), `effectiveNextRun` liefert für einen Zeitplan, den der Wartungsmodus hält, keinen nächsten Lauf — wann der Schalter ausgeht, weiß niemand. `ScheduleMapper` meldet dann `nextRunAt: null` plus `maintenance: true`, `UpcomingRunsCalculator` lässt im Dashboard alle Termine weg (`maintenance: true`), Kalender und Assistent-Kontext zeigen keinen. Dashboard-Karte und Zeitplan-Liste sagen „Wartungsmodus – startet nichts".

## Completion Triggers

Ein Completion Trigger startet einen Ziel-Workflow, wenn ein Quell-Workflow abgeschlossen ist
(`CompletionTriggerService`, `/api/v1/completion-triggers`, Endpunkte in der
[API-Referenz](api-referenz.md)).

## Audit Center — Findings, Akzeptanz und Verfall

Audit-Workflows (`metadata.audit`) liefern im Report-Block einen Envelope mit dem vollständigen
Befund-Schnappschuss. `AuditIngestionService` (vom `AuditIngestionListener` auf `WorkflowRunCompletedEvent`
gerufen) speichert je Run einen `AuditReport` und gleicht die Findings per Fingerprint ab
(`AuditSnapshotApplier`). Format, Identität eines Befunds, Lebenszyklus, verwaltete TODOs,
Risiko-Akzeptanz und die Kontextvariable `audit_findings`:
[audit-workflow-protocol.md](audit-workflow-protocol.md).

## Todo Triggers — Self-Improvement Loop

Ein TodoTrigger startet einen Fix-Workflow, sobald ein passendes TODO entsteht, und schließt damit den
Self-Improvement-Loop. Einrichten, Filter, Kontextvariablen und die drei Schichten der Loop-Prevention:
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (Abschnitt „TodoTrigger — Self-Improvement-Loop“), der Gedanke
dahinter: [konzepte/selbstverbesserung.md](konzepte/selbstverbesserung.md).

- `TodoTriggerService` — `@TransactionalEventListener` auf `TodoCreatedEvent`, gleicht TODOs gegen die konfigurierten Trigger ab (Tag-Pattern mit UND-Semantik, Priorität, Projekt) und startet den Fix-Workflow mit den TODO-Daten als Kontextvariablen
- `TodoFeedbackListener` — `@EventListener` auf `WorkflowRunCompletedEvent`, schließt das TODO bei GREEN (wenn `autoClose=true`), loggt eine Eskalation bei erschöpften Fix-Versuchen
- **Abgrenzung — manuelle Todo-Workflow-Verknüpfung**: Der `TodoTrigger` startet Workflows regelbasiert und automatisch. Davon getrennt kann ein einzelnes Todo optional mit einem **Resolver-Workflow** verknüpft werden (`linkedWorkflowId`, eingestellt in der Todo-Ansicht) — dem Workflow, der genau dieses Todo erledigen soll. Die Verknüpfung ist nicht global und startet nichts von selbst: erst die manuelle Aktion „Lösen" startet den Lauf, das Todo wechselt dabei auf `IN_PROGRESS` mit `resolutionState=RUNNING`, wird bei Erfolg automatisch auf `DONE` gesetzt und springt bei einem fehlgeschlagenen Run auf den vorherigen Status zurück (`resolutionState=FAILED`).
- **Pause** (`automation.todo-trigger.skip-until`, ISO-Instant, leer = aus): legt den gesamten Mechanismus bis zu diesem Zeitpunkt still — Backlog-Poll **und** Event-Pfad. Greift ab dem ersten Tick nach dem Start, braucht also kein laufendes Backend zum Setzen. Zusammen mit `automation.scheduler.skip-until` (+ `catch-up-on-resume: false`) ergibt das eine vollständige Trigger-Pause aus wenigen Config-Zeilen, z. B. in der gitignorierten `ai-automation-backend/config/application.yml`. Bewusst ein Datum statt eines Flags: es läuft von selbst aus, während ein vergessenes `enabled=false` auf den einzelnen Triggern den Loop stumm stillstehen lässt (kein Fehler, kein roter Status — die TODOs bleiben nur liegen). Unparsebarer Wert ⇒ fail-open (ERROR-Log, Trigger feuern normal). Eine aktive Pause wird beim Start als WARNING geloggt.

## TODO-Batch — nachts abarbeiten, morgens freigeben

Eine Kette aus zwei Pool-Workflows (`scope: repo`, TODO cc0eb3ba) arbeitet offene TODOs des eigenen Projekts ab – nacheinander in **einem** isolierten Klon, mit einer Freigabe für den ganzen Batch.

- **Einordnen** (`mysuricates-todo-batch-einordnen`): schließt übergebene TODOs, deren Commit (Trailer `Todo: <uuid>`) in `main` angekommen ist; gibt verwaiste frei; wählt mit einem KI-Aufruf (opus, Effort high) höchstens `batch_max` TODOs aus **einem** Bereich und setzt `batch:selected`. Die Triage bekommt dazu die Dateien, die höchstens 15 Zeilen unter ihrer Obergrenze aus `SourceSizeRatchetTest` stehen (`near_limit`; Grenzdatei und Quellwurzel als `size_ratchet_file`/`size_ratchet_root` im `defaultContext`; ein Repo ohne Größen-Ratchet lässt sie leer oder weg = keine Liste) und wählt kein TODO, das eine davon wachsen ließe, ohne den Split mitzubringen. Ohne Kandidaten oder bei laufendem Batch entfällt der KI-Aufruf (`run_if`).
- **Abarbeiten** (`mysuricates-todo-batch-abarbeiten`): Ausgangslage grün? → je TODO umsetzen (foreach, ein Commit mit Trailer) → Zuordnung, Wächter (`forbidden_paths`, Zeilen-Obergrenze) und Gates (pre-push-Gate ohne Build, Backend-/Frontend-Suite nach Bereich), bei Rot lineare Nachprüfung → Review je TODO (Codex, nur lesend; ein Blocker nimmt das TODO heraus, ein Urteil außer `ok`/`findings` – etwa `not_reviewed` – lässt es nur „mit Hinweis“ gelten, Ampel gelb) → Gesamt-Review (nur Hinweise) → Bericht → Freigabe → Übergabe als `refs/heads/mysuricates/batch-<run8>` ins Quell-Repo. Gemergt wird von Hand; das nächste Einordnen schließt die TODOs. Nur TODOs, deren Start-Update (`todo-update`) bestätigt ist, werden abgearbeitet; übersprungene nennt der Bericht. Leere Commits kommen nicht in den Branch. Gelb wird die Ampel auch, wenn ein Gate erst im zweiten Versuch grün wurde oder der Batch verworfen wird. Fällt ein TODO am Gate heraus, nennt sein Vermerk die erste aussagekräftige Fehlerzeile; die Merge-Vorschau im Übergabe-Vermerk stammt vom Zeitpunkt der Übergabe, und eine wiederholte Übergabe desselben Laufs (Ref zeigt schon auf diesen Stand) gilt als erledigt.
- **Zustand am TODO:** `batch:selected` → IN_PROGRESS + `batch:started` → `batch:handed-over` → DONE; `batch:failed` / `batch:rejected` mit Vermerk schließen ein TODO von weiteren Batches aus, bis ein Mensch das Tag entfernt. Ein übergebenes TODO, dessen Branch weder gemergt noch vorhanden ist, geht als OPEN zurück. Von Hand gesetztes `batch:selected` bleibt stehen und zählt gegen `batch_max`. Bricht ein Lauf vor der Prüfung ab, gehen seine TODOs mit `batch:failed` zurück; kommt er gar nicht mehr zur Übergabe, gibt das nächste Einordnen gestartete TODOs frei, die seit 30 h unverändert sind – ein hängender Lauf sperrt keine weiteren Batches.
- **Kein Auto-TODO:** Beide Workflows setzen `metadata.autoTodo.enabled: false`. Die Kette führt ihre TODOs selbst über Tags und Vermerke; ein gelbes Gate (etwa nach „Verwerfen“, einer bewussten Entscheidung) färbt nur Ampel und Kachel.
- **Plattform-Bausteine:** `{{ todo_details }}`, `todo-update` mit `updates`, `run_if`, `timeoutMinutes` je Ketten-Eintrag (siehe WORKFLOW-GUIDE).
- **Installation:** `python3 scripts/todo-batch/install.py` (Zeitplan zunächst aus; `--enable-schedule` schaltet ihn an). Die Skripte liegen unter `scripts/todo-batch/`; jeder Lauf kopiert sie vom Basis-Commit nach `.git/todo-batch/bin/` und ruft nur die Kopie auf – was die KI im Baum ändert, wirkt nicht auf die Prüfung. Ihr Zustand liegt im Klon unter `.git/todo-batch/`. Geprüft wird nur Committetes: Reste im Arbeitsbaum werden vorher verworfen und im Bericht genannt. Ohne isolierten Klon verweigern die Skripte die Arbeit.

## Projekt-Cockpit & TODO-Hygiene

Da TODOs zu ~96 % maschinell erzeugt und über viele Projekte verstreut sind, gibt es eine projektübergreifende Übersicht plus einen Aufräum-Mechanismus.

- **Cockpit** (`GET /api/v1/todos/overview`, `TodoOverviewService`): liefert pro Projekt einen Rollup (Status-Counts, `staleCount`, `userActive` vs. `machineActive`, `needsYouCount`) plus eine globale „Braucht dich"-Liste (USER-Source ∨ CRITICAL ∨ überfällig). Alles aus schlanken GROUP-BY-Projektionen (keine TEXT-Blobs → pool-size=1-safe), Frontend-Seite `ProjectCockpit.tsx` (Route `/cockpit`).
- **ARCHIVED-Status**: Ablage neben BACKLOG für „aus den Augen". Aus der Default-Liste ausgeblendet (`TodoSpecifications.buildSpec` schließt ARCHIVED aus, wenn kein Status-Filter gesetzt ist), erreichbar über den Archiv-Tab / Edit-Modal.
- **Hygiene** (`TodoHygieneService`): `archiveStaleNow(projectId)` verschiebt verstaubte Auto-Todos (OPEN/BACKLOG, Source AI/WORKFLOW, Tag `auto:workflow`, `updatedAt` älter als Schwelle) nach ARCHIVED — per Bulk-UPDATE in kurzer Transaktion. Cockpit-Knopf „Verstaubtes archivieren" (`POST /todos/archive-stale?projectId=`) ist der primäre, human-in-the-loop Weg. Der `@Scheduled`-Job (täglich 03:30) ruft denselben Pfad, ist aber per Default **AUS** (`todo-hygiene-enabled`). Schwellen runtime-konfigurierbar via `ExecutionSettingsService`: `todo-hygiene-enabled` [0,1], `todo-stale-days` [7,365], `todo-hygiene-max-per-run` [10,2000].
- **Ghost-Watchdog** (`TodoGhostWatchdogService`): setzt verwaiste IN_PROGRESS-TODOs mit Maschinen-Source (AI/WORKFLOW) zurück auf OPEN, wenn die zugehörige CLI-Session ohne abschließenden Status-PATCH gestorben ist (`updatedAt` älter als Schwelle). Poll alle 5 min (`automation.todo-watchdog.poll-ms`), per Default **AN**; Human-TODOs (Source USER) werden nie angefasst. ActivityLog-WARNING pro Lauf. Runtime-Settings: `todo-watchdog-enabled` [0,1], `todo-watchdog-stale-hours` [1,720] (Default 24h).
- **Bulk-Status** (`POST /todos/bulk-status`): generischer Bulk-UPDATE für viele TODO-IDs auf einen Status.

## Projekt-Bündel

Ein Projekt lässt sich als **eine JSON-Datei** weitergeben und auf einem anderen Rechner — auch mit anderen
Ordnern — wieder anlegen (`ProjectBundleController`, Kern; Services in `service/project/bundle/`).
Endpunkte: [api-referenz.md](api-referenz.md) → `GET /projects/{id}/export`, `POST /projects/import/preview`,
`POST /projects/import`.

**Format** (`format: mysuricates-project-bundle`, `formatVersion: 1`, Datei `<projekt-slug>.mysuricates-bundle.json`):
Projekt-Stammdaten ohne Pfad, Container, Status und IDs; die Workflows des Projekts (ohne gelöschte) mit ihrer
Definition wie beim Einzel-Export, aber ohne ID, Projekt, Siegelfelder und Hash; die Chains, deren Einträge nur
Workflows dieses Projekts nennen; die Zeitpläne auf diese Workflows und Chains — ohne `enabled`.

**Referenzen ohne UUIDs.** Workflows und Chains tragen einen `key` (Slug des Namens, eindeutig mit `-2`, `-3` …).
Chain-Einträge und Zeitpläne nennen `workflowKey`/`chainKey`. `sub-workflow`-Blöcke — auch verschachtelt, überall
wo `NestedBlocks` hinsieht, Schlüssel `workflow_id` und der Altschlüssel `workflowId` — zeigen auf
`@@workflow:<key>@@`; ein Verweis auf einen Workflow außerhalb des Projekts bleibt als UUID stehen und erscheint
unter `warnings`. Skills reisen per Name (`activatedSkillNames`, in Blöcken `@@skill:<name>@@`); fehlt ein Skill
auf dem Zielrechner, wird der Workflow ohne ihn angelegt (Warnung).

**Pfad-Anpassung.** Beim Export werden die Strings der Blöcke (auch verschachtelter), `instructions`,
`metadata`, `defaultContext` sowie der `inputContext` von Chain-Einträgen und Zeitplänen durchsucht:

- Der Projektpfad wird in Blöcken und `instructions` zu `{{ project_path }}` — dort löst der Executor Platzhalter
  auf. Kontextwerte und Metadaten werden nicht noch einmal aufgelöst; dort wird er wie jeder andere Pfad ein Token.
  Ein Projektpfad mit nur einem Segment (`/tmp`) wird nie Platzhalter.
- Jeder andere absolute Pfad unter `/home/ /Users/ /tmp/ /opt/ /srv/ /mnt/ /var/ /data/ ~/` wird `@@path:N@@`;
  `metadata.workspace.source_path` gilt als Ganzes als Pfad. Ein Pfad endet an Leerzeichen, Anführungszeichen,
  `; | ( ) { } < > , :` und am Zeilenende. Beginnt ein Pfad mit einem erfassten Pfad plus `/`, wird er als
  Unterpfad dieses Tokens geschrieben (`@@path:1@@/tests/.`); der kürzeste gewinnt, die Grenze ist das `/`
  (`/tmp/app-venv` ist kein Unterpfad von `/tmp/app`).
- `paths[]` nennt je Token `original`, die Zahl der Fundstellen und die erste als Hinweis. Der Home-Pfad des
  Exportierenden bleibt in `original` stehen — er ist der Vorschlag beim Import; ein Bündel ist deshalb kein
  Pool-Artefakt.

**Import.** Die Vorschau validiert (Format, Version, Pflichtfelder, eindeutige Keys, jede Referenz löst im Bündel
auf — sonst 400 mit allen Problemen) und zeigt Blocktypen, Anbieter, Modelle, die Shell-Befehle (je Feld die ersten
200 Zeichen), Namenskonflikte und Warnungen. Der Import verlangt für jedes Token eine Zuordnung und schreibt in
**einer** Transaktion — nur Datenbankarbeit, nichts Langsames: Projekt → Workflows über `WorkflowService` mit
`ChangeSource.IMPORT` (Versionszeile, ungesiegelt) → Verweise auf die neuen IDs (zweiter Schritt über denselben
Weg) → Chains → Zeitpläne, **immer deaktiviert**. Gleichnamige Workflows im Zielprojekt werden übersprungen
(Verweise zeigen dann auf den vorhandenen) oder umbenannt (`Name (Import)`, `Name (Import 2)` …). Scheitert ein
Schritt, bleibt nichts zurück; der Aktivitätseintrag folgt erst nach dem Commit. Ist das Modul `chains` aus,
exportiert der Export keine Chains und der Import überspringt sie — beides mit Warnung.

**Sicherheit.** Ein Bündel bringt ausführbare Shell-Befehle mit. Die Oberfläche zeigt sie in der Vorschau und
bietet den Import erst danach an; die API verlangt die Vorschau nicht zusätzlich — sie ist per API-Key geschützt
wie jeder Schreibweg. Siegel werden nie übernommen, Zeitpläne nie aktiv angelegt; Webhooks und
Integrations-Zugangsdaten sind nicht Teil des Bündels.

**Grenzen v1.** Nicht enthalten: TODO- und Completion-Trigger, Webhooks, Memory, Läufe, TODOs, Skills selbst.
Kein `replace` beim Konflikt. Anfrage höchstens 5 MB.

## Ziele — TODOs projektübergreifend einordnen

Ein **Ziel** (`entity/Goal`, `/api/v1/goals`, TODO dc0566f8) sammelt TODOs aus mehreren Projekten, die auf ein Ergebnis einzahlen – etwa den Open-Source-Launch, der AutoPilot und die Website zugleich betrifft. Es ist bewusst schlank: keine Abhängigkeitsgraphen, Sprints oder Zeiterfassung. Alles rund um Ziele ist das Standardmodul `goals` (in jedem Preset an, `automation.modules.goals=false` schaltet es ab, TODO c60bf914): dann antworten `/goals/**` und `PATCH /todos/{id}/planning` mit 404 `MODULE_DISABLED`, TODO-Antworten tragen kein `planning`, `GET /todos?goalId=` ist ein 400 (ein stilles Ignorieren gäbe die ganze Liste), `milestonesDue` im Cockpit ist leer, zielgebundene Zeitpläne (Eingabekontext mit `goal_id`, etwa der Ziel-Wächter) werden übersprungen und rücken weiter, und ein Lauf mit `goal_id` bekommt keine `goal_*`-Variablen. Die Daten bleiben erhalten; nach dem Wiedereinschalten ist alles da. Im Frontend fehlen dann der Menüpunkt „Ziele“, die Zielkarten und fälligen Meilensteine im Cockpit sowie die Einordnung im TODO-Dialog; alle Ziel-Abfragen sind abgeschaltet (`useModuleEnabled('goals')`), `/goals` und `/goals/:goalId` zeigen den Modul-Hinweis (`ModuleRoute`).

- **Einordnung am TODO:** `goal_id` (höchstens ein Ziel), `goal_rank` (1 = zuerst, NULL = noch nicht eingeordnet), `complexity` (S/M/L/XL), `suggested_effort` (`low`…`xhigh`, dieselben Stufen wie `workflow.effort` und `claude --effort`), `rating_note`, `rated_by` (USER/AI), `rated_at` und `open_question`. Geschrieben über `PATCH /api/v1/todos/{id}/planning` (`TodoPlanningService`); `null` lässt ein Feld stehen, ein leerer Text leert es.
- **Reihenfolge:** Rang, dann Priorität, dann Alter (`GoalService.WORK_ORDER`). `PUT /goals/{id}/order` setzt Rang 1..n für die genannten TODOs, alle übrigen verlieren den Rang. Zuordnen ist `POST /goals/{id}/todos` (neu Zugeordnete stehen ohne Rang hinten).
- **Fortschritt:** ARCHIVED zählt nicht mit; offene Fragen zählen nur bei aktiven TODOs. Liste und Detail lesen schlanke Projektionen (`GoalStatusCounts`, `GoalTodoRow`, `GoalProjectRow`) ohne Beschreibungen – pool-size=1-sicher.
- **Projekte des Ziels** (`entity/GoalProject`, `GoalProjectService`, TODO c60bf914): Ein Ziel ist ausdrücklich einem oder mehreren Projekten zugeordnet, mit Reihenfolge (`goal_projects`, n:m). `POST /goals/{id}/projects` hängt eins an (idempotent), `PUT /goals/{id}/projects/order` ordnet, `DELETE /goals/{id}/projects/{pid}` entfernt – mit 409 und Zählern, solange im Ziel Arbeitspakete oder TODOs daran hängen, mit `?cascade=true` werden die Arbeitspakete gelöscht (Meilensteine verlieren die Verknüpfung) und die TODOs vom Ziel gelöst. Holt ein Schreibweg ein TODO aus einem neuen Projekt ins Ziel (`GoalMembershipService.assign`, `TodoPlanningService`, Projektwechsel in `TodoService.update`), kommt das Projekt automatisch dazu (`GoalProjectRepository.linkIfAbsent`, ein natives `INSERT OR IGNORE`). Der Projektwechsel läuft über `service/todo/TodoGoalConsistency`, die nur Repositories nutzt – `service.goal` hängt schon von `service.todo` ab. `GET /goals?projectId=` filtert das Cockpit; `GoalResponse.projects` und `GoalDetailResponse.projects` (mit Zählern) kommen aus je einer Abfrage; daneben leitet `byProject` die Projekte aus den TODOs ab.
- **Wer einordnet:** Im Gespräch der Plugin-Skill `/mysuricates:plan-goal`, der das Repo sieht (TODO 9ed0687d); unbeaufsichtigt später ein Workflow (TODO 473f47e0). Ein Mensch überschreibt jede KI-Einschätzung im TODO-Dialog.
- **Relationskarte** (`GET /goals/{id}/relations`, `GoalRelationsService`, TODO c6ea8f5c): Ein Ziel hat keine eigene Verknüpfung zu Workflows, nur über seine TODOs. Je TODO liefert der Endpunkt die **Herkunft** (`source`, erzeugender Lauf `workflow_run_id`, Referenz `workflow_run`/`audit_finding`/`health_check`, `audit_finding_todos`), die **Bearbeitung** (Fix-Workflow `linked_workflow_id` und jeder passende aktive TodoTrigger) und das **Ergebnis** (`last_fix_run_id`, `fix_attempts`, `resolution_state`). Den Trigger-Abgleich teilt er mit `TodoTriggerService` (`service/trigger/TodoTriggerMatcher`, rein). Alles gebündelt: eine schlanke Projektion der TODOs, dann eine Abfrage je Art. `include` holt bis zu 20 Kandidaten ohne Ziel mit (`GET /todos?goalId=none` findet sie).
- **„Könnte dazugehören“** (`GET /goals/{id}/candidates`, `GoalCandidateService`, TODO c60bf914): offene TODOs (OPEN, IN_PROGRESS, BACKLOG) ohne Ziel, standardmäßig nur aus den Projekten des Ziels (Unterabfrage auf `goal_projects`, `TodoSpecifications.inProjectsOfGoal`), mit `allProjects=true` aus allen; Seite wie `GET /todos` (Reihenfolge, gebündelte Namen). Ein Ziel ohne Projekte hat ohne `allProjects` keine Kandidaten.
- **Arbeitspakete** (`entity/GoalWorkPackage`, `GoalWorkPackageService`, TODO c60bf914 und 259f4f66): gliedern ein Ziel je Projekt (`/goals/{id}/work-packages`). Ein Paket gehört genau einem Projekt des Ziels (`project_id`), hat einen optionalen Zeitraum (`start_date`/`end_date` nur zusammen, Start ≤ Ende, sonst 400) und eine Reihenfolge je Projekt (`position`, `PUT /goals/{id}/work-packages/order`, nur innerhalb eines Projekts). TODOs werden über `PATCH /todos/{id}/planning` mit `workPackageId` zugeordnet (Alias `milestoneId`) – nur Pakete des eigenen Ziels und Projekts (400 beim Schreiben, Altdaten werden gelesen); wechselt oder verlässt das TODO sein Ziel, verliert es das Paket, ein Projektwechsel des TODOs löst ein Paket des alten Projekts. Das Projekt wechselt ein Paket nur, solange es leer ist. Der **Zustand wird beim Lesen berechnet** (`GoalDueState`, Basis ist das Ende): `REACHED` (abgeschlossen), sobald alle zugeordneten TODOs (ohne ARCHIVED, mindestens eins) erledigt sind, sonst `OVERDUE`, `DUE_SOON` (höchstens 3 Tage) oder `OPEN`; ohne Zeitraum nur `OPEN`/`REACHED`. `completed_at` hält das erste Erkennen fest und bleibt; kommt danach ein offenes TODO dazu, meldet die Antwort `reopened: true`. Ein reines Datum heißt beim Start „ab Beginn“, beim Ende „bis Ende dieses Tages“ (UTC). Die Paket-Liste holt Ziel und Pakete samt Projekt-Positionen in **einer** Abfrage (`GoalRepository.findWithWorkPackages`) und zählt aus den `GoalTodoRow`-Zeilen – zwei Statements (`RequestCostRatchetTest`).
- **Meilensteine** (`entity/GoalMilestone`, `GoalMilestoneService`, TODO c60bf914): Termine des ganzen Ziels (`/goals/{id}/milestones`), kein Projekt, Datum Pflicht, optional an Arbeitspakete desselben Ziels geknüpft (`goal_milestone_work_packages`, `workPackageIds`). Mit Paketen ist ein Meilenstein erreicht, wenn alle abgeschlossen sind (`MilestoneProgress`; `reached_at` = erstes Erkennen, danach `reopened`), ohne Pakete markiert ihn ein Mensch (`reached`; mit Paketen ist das ein 400); sonst entscheidet das Datum. Gelesen werden Meilensteine samt Verknüpfungen in **einer** Abfrage je Ziel (`GoalMilestoneRepository.findWithLinks`), ihr Zustand kommt aus den ohnehin berechneten Paket-Zuständen. Die Zielliste nennt je Ziel den nächsten nicht erreichten Meilenstein (`GoalResponse.nextMilestone`) – für alle Ziele aus einer Abfrage, die verknüpfte und abgeschlossene Pakete als Unterabfragen zählt (`findSummaries`). Die Zielansicht (`GoalDetailAssembler`) liest Ziel samt Paketen, TODO-Zeilen, Zielprojekte und Meilensteine samt Verknüpfungen – vier Statements.
- **„Braucht dich“:** `GET /todos/overview` liefert zusätzlich `milestonesDue` – mit `kind` `MILESTONE` die Meilensteine aktiver Ziele mit `DUE_SOON` oder `OVERDUE` (nicht erreicht), mit `kind` `WORK_PACKAGE` die Arbeitspakete, deren Ende vorbei ist und die noch offene TODOs haben; je mit `goalId`/`goalTitle` für den Link, nach Datum. Das Cockpit zeigt sie vor den TODOs, jede Zeile führt zur Zielseite (`components/goal/MilestoneDueRow`).
- **Entschieden** (TODO 68a5e24e): Neben der offenen Frage hält ein TODO die Entscheidung fest, die sie beantwortet hat (`decision`, `decided_at`, `decided_by` USER/AI) – geschrieben über `PATCH /todos/{id}/planning` (`decision`; leert `openQuestion`, `""` nimmt sie zurück, neue Frage und Entscheidung zugleich → 400). Der Zustand `decisionState` (`QUESTION`/`DECIDED`/`NONE`) wird beim Lesen berechnet: eine spätere neue Frage lässt die Entscheidung stehen, der Zustand ist dann `QUESTION`. `decided` zählt die nicht archivierten TODOs mit `DECIDED` im Fortschritt des Ziels (aus dem ohnehin laufenden GROUP BY), je Zielprojekt und je Arbeitspaket (aus den ohnehin gelesenen `GoalTodoRow`-Zeilen) – kein zusätzliches Statement.
- **Workflows am Ziel** (`entity/GoalWorkflow`, `GoalWorkflowService`, `GoalWorkflowController`, TODO 52bc1e8c): ein Workflow hängt am ganzen Ziel oder an einem Arbeitspaket, Rolle `WORKER` („arbeitet daran“) oder `STATUS` („liefert den Stand“); `GET/POST/PATCH/DELETE /goals/{id}/workflows`. Die Liste liest Zuordnungen samt Workflow-Name, Projekt und Paket-Reihenfolge in einer Abfrage, weich gelöschte Workflows fallen heraus; ein eingeschalteter Ziel-Wächter steht als synthetischer erster Eintrag `guard` darin (nicht lösbar, 400). Gestartet wird über den vorhandenen Weg `POST /workflows/{id}/execute` mit `goal_id` (optional `work_package_id`, `goal_locale`) im Kontext – `TriggerSource` MANUAL, `goal_id` liefert `goal_status`/`goal_guard_*`; der Stand kommt aus `GET /dashboard/workflow-results`. Die Zielansicht trägt keine Workflows.
- **Ziel-Wächter** (`service/goal/guard/GoalGuardService`, `POST /goals/{id}/guard`): ein Workflow aus der mitgelieferten Vorlage `goal-guard` je Ziel, Name „Ziel-Wächter: <Titel>“, `defaultContext.goal_id` und `goal_locale`, angelegt über `WorkflowService` → `WorkflowMutationGateway` mit `ChangeSource` `goal-guard`; dazu ein CRON-Zeitplan (Vorgabe täglich 08:00, `inputContext.goal_id`, Konflikt `SKIP`). Projekt: das angegebene, sonst das erste nicht archivierte Zielprojekt, sonst das mit den meisten TODOs des Ziels, sonst das älteste aktive. `goals.guard_workflow_id` merkt sich den Workflow. Erneutes Einschalten aktiviert nur den Zeitplan (ein neuer `cron` ersetzt den alten), Ausschalten und Löschen des Ziels pausieren ihn – der Workflow bleibt. Ist der Workflow gelöscht, legt das nächste Einschalten einen neuen an. Braucht das Modul `dashboard-report`, sonst 409. `GoalResponse.guard` = `{enabled, workflowId, scheduleId, nextRunAt}` aus einer gebündelten Abfrage (`GoalGuardLookup`). Warum ein Workflow je Ziel: die Dashboard-Kachel wird je Workflow geführt (letzter Lauf mit `dashboard_widget`).
- **Kontextvariablen `goal_status` und `goal_guard_*`:** Trägt der zusammengeführte Kontext eines Laufs `goal_id`, injiziert `WorkflowContextDataService` über die Schnittstelle `GoalContextSource` (umgesetzt von `service.goal.GoalStatusContext`, damit `service.workflow` nicht von `service.goal` abhängt) den Stand des Ziels – Meilensteine und Arbeitspakete getrennt, als Daten markiert, saniert wie `open_todos`. Ohne `goal_id` gibt es sie nicht; eine unbekannte ID setzt jede davon auf die `NICHT GEMESSEN`-Lücke und warnt im Activity-Log. Details: [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) → „Ziel-Kontext“.
- **Oberfläche:** Die Zielseite — Gliederung, Liste, Zeitstrahl und Workflow-Zuordnung, Seitenleiste, Schalter „Wächter“, Entscheiden — liegt unter `components/goal/`; ihre reine Logik (`goalOutline.ts`, `timelineLayout.ts`, `goalRelationLayout.ts`, `panelGeometry.ts` …) ist dort getestet.

## Notification System

Benachrichtigungs-Konfigurationen (Webhook oder Desktop) für Workflow-Ereignisse verwaltet
`NotificationService` unter `/api/v1/notifications`. Ein Webhook-Ziel gilt als eigener Dienst: Ziele im
lokalen Netz lehnt erst der Härtungsschalter `private-network-guard-own-services` ab (Standard aus).

## Assistant System

Kontext-sensitiver Chat-Assistent der Oberfläche (Modul `assistant`): Chat, Sessions, Seitenvorschläge
und bestätigungspflichtige Werkzeuge. Aufbau, Grenzen und Kontext-Injektion:
[assistant-system.md](assistant-system.md).

## Workflow Memory

Workflows sammeln über Runs hinweg Wissen (Modul `memory`). Bedienung und API:
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (Abschnitt „Workflow Memory“), die Abläufe als Diagramme:
[workflow-memory-diagrams.md](workflow-memory-diagrams.md), Zweck und Grenzen:
[konzepte/workflow-memory.md](konzepte/workflow-memory.md).

- **Extrahieren:** `WorkflowMemoryService.extractKnowledge()` liest die gewählten oder die letzten 10 Runs, `ConversationPreprocessor` verdichtet ihre Schritte, ein `KnowledgeSummarizer` (`ClaudeSummarizer` oder `CodexSummarizer`, gewählt in `getSummarizer`) fasst zusammen. `POST …/memory/extract` antwortet erst nach dem KI-Aufruf, mit `201` und einer noch inaktiven Memory (ohne `provider`: `CLAUDE`). Der KI-Aufruf läuft außerhalb jeder Transaktion, zwischen einem kurzen Lese- und einem kurzen Schreibvorgang.
- **Summarizer:** Beide starten ihre CLI auf dem Host in einem eigenen Arbeitsverzeichnis unter `automation.summarization.base-dir` (`…/summarization/claude` bzw. `/codex`), dessen `CLAUDE.md` bzw. `AGENTS.md` `SummarizationSetupService` bei jedem Start aus `src/main/resources/summarization/` kopiert. Claude folgt `automation.host-claude.timeout-seconds`, Codex `automation.summarization.timeout-seconds` (zur Laufzeit `summarization-timeout-seconds`). Anders als bei Workflow-Blöcken ist Pi hier nicht angebunden.
- **Nutzen:** Vor dem ersten Block schreibt `writeActiveMemoryToProject` die aktive Memory nach `.claude/rules/project-memory.md` und löscht eine alte Datei, wenn keine aktiv ist; Codex und Pi bekommen sie über `RunContextFiles` an den System-Prompt. Je Workflow ist höchstens eine Memory aktiv.
- **Automatisch:** `AutoMemoryExtractionListener` (`@Async`, nur mit eingeschaltetem Modul) zählt nach jedem `COMPLETED`-Run die erfolgreichen Runs seit der letzten Extraktion und ruft bei Erreichen von `metadata.autoMemoryExtraction.interval` (Standard 5; `autoMemoryExtraction: true` genügt) `extractAndActivate` auf — immer mit Claude. Scheitert der CLI-Aufruf, entsteht keine Memory: Die bisher aktive bleibt aktiv, `project-memory.md` bleibt unverändert, der Fehler steht im Log.

## Agent Instructions System

Zweistufige Instruction-Injektion via Claude CLI `--append-system-prompt` / `--system-prompt`:

1. **Basis-Instructions** (`src/main/resources/agent-instructions.md`) — geladen beim Start durch `AgentInstructionsService`, gelten für alle Workflows
2. **Workflow-spezifische Instructions** (`workflow.instructions`) — pro Workflow, im UI editierbar (Monaco-Editor)

3. **Arbeitspräferenzen** (opt-in) — nur wenn ein Workflow sie über `metadata.userPreferences: true`
   anfordert. `UserPreferencesService` rendert dann genau drei Laufzeit-Settings in einen Abschnitt
   `## Arbeitspräferenzen`: `user-pref-language`, `user-pref-style`, `user-pref-stack`
   (Antwortsprache, Antwortstil, Tech-Stack). Mehr ist nicht möglich: Die Schlüsselliste steht im
   Code, ein weiterer Schlüssel ist eine bewusste Änderung. **Keine Namen, Kontaktdaten,
   Lebensläufe oder Berufsprofile.** Ist nichts gesetzt oder fehlt das Flag, bekommt der Prompt
   keinen solchen Abschnitt; ein Prüf- oder Messworkflow soll nur von den Fakten abhängen. Werte pflegbar über
   `PUT /api/v1/system/settings/{key}`.

Injektionspfade:
- **Block-Ausführung:** Instructions via `--append-system-prompt` auf Host Claude CLI (`HostClaudeService.executeAction()`)
- **Agentic REASON:** System-Prompt via `--system-prompt` an `HostClaudeService.reasonStructured()`, inkl. Workflow-Instructions in `## Workflow Instructions`

## Host Claude Service

Alle Claude-CLI-Aufrufe der Blöcke erfolgen auf dem Host (nicht im Container; nur Ad-hoc-Befehle während eines Runs laufen per `docker exec` im Projekt-Container):
- `HostClaudeService.executeAction()` — ACT-Phase, nutzt `--append-system-prompt`, `--model`-Flag, Projekt-Arbeitsverzeichnis
- `HostClaudeService.reasonStructured()` — REASON-Phase, nutzt `--system-prompt`, `--max-turns 3`; `--model` wird gesetzt, wenn der Workflow ein Modell konfiguriert (`ReasoningRequest.model`)
- Endet die CLI bei `reasonStructured()` mit Exit-Code ≠ 0 (Limit, fehlende Anmeldung, Absturz), wirft der Aufruf eine `IOException` mit Exit-Code und Meldung (`CliExit`, ebenso `HostPiService.reason()`; Codex wirft von sich aus). Ein Reasoning-Ergebnis ist damit immer eine Antwort, sonst speicherten Summarizer und Kontextgenerator die Fehlermeldung als Memory bzw. Projektkontext. `executeAction()` gibt den Exit-Code dagegen zurück, dort entscheidet der Block.
- Exit-Code 0 allein heißt nicht Erfolg: Meldet die Ergebniszeile von `stream-json` `is_error: true` (API-Fehler, Limit) oder einen Subtyp `error_*` (z. B. `error_max_turns`), gilt der Aufruf als gescheitert – `ClaudeCliOutcome` setzt dann Exit-Code 1 und ersetzt die Ausgabe durch eine Meldung mit Subtyp und Turn-Zahl (z. B. „Claude CLI reported an error (error_max_turns) after 3 turns“), nie durch den rohen NDJSON-Strom; `reasonStructured()` wirft sie unverändert hinter „Host Claude reasoning failed:“, ohne erfundenen Exit-Code. Das gilt für `HostClaudeService` und `CliSession` (Ad-hoc-Befehle im Container); pi liefert Klartext und Codex wirft selbst, dort gibt es kein solches Feld.
- Prompt wird via **stdin** an den Prozess geschrieben (`writePromptToStdin()`) — vermeidet Linux ARG_MAX-Limit
- `CLAUDE_CODE` und `CLAUDECODE` Umgebungsvariablen werden aus dem Prozess-Environment entfernt (verhindert Nested-Session-Fehler)
- Die eigenen Geheimnisse des Backends (`AUTOMATION_API_KEY`, `AUTOMATION_SSL_PASSWORD`) entfernt `util/ChildProcessEnv` aus der Umgebung jedes KI-CLI-, Shell-Block- und Integrationsprozesses – auch wenn das Backend selbst damit gestartet wurde (Docker). Ein Lauf mit fremden Eingaben soll die API nicht mit dem Instanz-Schlüssel bedienen können; neue Prozess-Startstellen erzwingt `ChildProcessEnvGateTest`. Die Schlüsseldatei im Home bleibt für Prozesse desselben Benutzers lesbar (offen: TODO 9525820a)
- `CommandExecutorService` bietet `executeOnHost(HostExecutionRequest)` / `executeStepOnHost(HostExecutionRequest)` / `writeSkillContextToProject()` — alle Overloads nutzen die `HostExecutionRequest`-Builder-API
- Skill-Kontext wird dateibasiert injiziert: `writeSkillContextToProject()` schreibt `.claude/rules/skills-context.md` ins Projektverzeichnis

## Provider Routing

Die KI-fähigen Block-Executors (simple, retry, conditional, foreach, while, switch, skill-capability, compress-ai, agentic) bestimmen den Anbieter mit `context.providerFor(block)`: `provider` am Block vor dem `provider` des Workflows, ohne beide der Systemstandard. Das Setzen von `"provider": "CODEX"` in einem Workflow-JSON gilt damit für jeden KI-Block ohne eigenen `provider`; `compress-ai` läuft nur mit `CLAUDE` (sonst scheitert der Schritt). Beim `integration`-Block bezeichnet `provider` den Integrations-Provider, nicht den KI-Anbieter.

`AiProviderRegistry.resolve()` fällt auf den konfigurierten Standard-Provider zurück (nicht hardcodiertes CLAUDE), wenn eine unbekannte Provider-ID übergeben wird.

Ob ein Anbieter bereit ist, meldet `GET /api/v1/system/ai-providers/status` (`service/ai/status/AiProviderStatusService`): Programm auf dem `PATH` und eine Anmeldung per Anmeldedatei oder Umgebungsvariable — dieselbe Regel wie der Installer (`scripts/lib/providers.sh`), aus `AiProvider#setup()` (`ProviderSetup`). Container-Instanzen lenken die Anmeldeverzeichnisse um (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `PI_CODING_AGENT_DIR`), die Prüfung folgt dem (`ProviderSetup.loginDir`/`loginDirEnv`). Kein CLI-Aufruf, gecacht (`automation.ai-provider-status.ttl-seconds`, Standard 60 s), `POST …/status/refresh` prüft sofort. Die Antwort nennt nur Namen und Befehle, nie einen Wert. Die Startseite zeigt den Status, solange es kein Projekt gibt oder der Standard-Anbieter nicht bereit ist (`AiProviderStartCard`), die Einstellungen immer (`AiProviderStatusCard`).

## Per-Block Skills

Blöcke können über das `skillIds`-Feld eigene Skills mitbringen, die additiv zu den Workflow-Level-Skills gelten:

- `BlockDefinition.skillIds` — optionale Liste von Skill-IDs, die nur für diesen Block aktiviert werden
- `BlockSkillContext` merged Workflow-Level-Skills + Block-Level-Skills vor jeder Block-Ausführung und stellt danach den Workflow-Stand wieder her
- Nach der Block-Ausführung wird der Baseline-Skill-Zustand wiederhergestellt
- Skill-Kontext wird dateibasiert über `.claude/rules/skills-context.md` im Projektverzeichnis bereitgestellt (`CommandExecutorService.writeSkillContextToProject()`); die Claude CLI liest `.claude/rules/` selbst, pi (`--no-context-files`) und Codex bekommen Projekt-Kontext, Skills und Workflow-Memory über `RunContextFiles` als System-Prompt-Anhang
- Frontend: Skill-Picker-Toggles in den Add/Edit-Block-Modals, Zap-Icon-Indikator auf `SortableBlock`

## Model Selection

- `workflow.model`-Feld: CLI-Alias (`opus`, `sonnet`, `haiku`, `fable` — immer das neueste Modell) oder konkrete ID (`claude-opus-5`); pi `<backend>/<model>`, codex Slug
- Vorschlagsliste: `GET /api/v1/system/ai-providers/{id}/models` — `ModelCatalogService` mit einer `ModelCatalogSource` je Provider (Claude: Aliase, sofern das Programm `claude` auf dem `PATH` liegt — sonst `available: false`; PI: `pi --offline --list-models`, CODEX: `~/.codex/models_cache.json` bzw. `codex debug models --bundled`), TTL-Cache (Setting `model-catalog-ttl-seconds`), `POST .../models/refresh`
- Wird via `--model`-Flag an die Claude CLI auf dem Host übergeben
- REASON und ACT eines Agentic-Laufs verwenden dasselbe Workflow-Modell (`ReasoningRequest.model` bzw. `ActionRequest.model`); ohne konfiguriertes Modell fällt der Provider auf seinen Default zurück

## Per-Call Token Limit

- `HostClaudeConfig.maxTokens` (Property `automation.host-claude.max-tokens`) — optionales Output-Token-Limit pro Aufruf
- Angewendet via `--max-tokens`-Flag in `reasonStructured()` und `executeAction()`
- Unterscheidet sich vom `max_total_tokens`-Budget des Agentic-Blocks (der kumulativen Input+Output über alle Iterationen trackt)

## Per-Block Claude CLI Timeout

- Alle Block-Typen unterstützen ein optionales `timeout_seconds`-Feld in der Block-Config
- Überschreibt den globalen `automation.host-claude.timeout-seconds`-Wert (Default: 300s) für diesen Block
- Ohne `timeout_seconds` in der Config → globaler Default gilt
- Für Dual-Mode-Blöcke (shell/claude) steuert `timeout_seconds` sowohl den Shell- als auch den Claude-CLI-Timeout
- Gilt für `reasonStructured()` und `executeAction()` in Agentic-Blöcken

## Workflow Conversion

Erfolgreiche Agentic-Runs können in deterministische Workflows konvertiert werden:
- `WorkflowConversionService` — analysiert den Run und schlägt einen strukturierten Workflow vor (regelbasiert + AI); den KI-Auftrag baut `WorkflowConversionPrompt`
- `ConversionController` — API-Endpunkte für Vorschau und Erstellung
- Frontend: Konversions-Dialog im Execution-View

### Dialog-to-Workflow Conversion

Claude-Code-Sessions können ebenfalls als Vorlage für Workflows dienen:
- `DialogSessionScanner` — scannt `~/.claude/projects/` nach JSONL-Session-Dateien
- `DialogConversionService` — analysiert eine Session mit Claude und generiert eine Workflow-Suggestion; den KI-Auftrag baut `DialogConversionPrompt`
- `ClaudeJsonlParser` — parst JSONL-Session-Dateien
- `DialogController` — `/api/v1/dialog` mit Session-List, Analyse und Workflow-Erstellung
- Pfad-Traversal-Schutz: nur Pfade unter `~/.claude/projects/` mit `.jsonl`-Endung sind erlaubt

## Workflow Templates

- Templates werden in der `workflow_templates`-DB-Tabelle gespeichert, nicht hardcodiert
- **54 Built-in Templates** werden beim Start durch `WorkflowTemplateSeeder` eingespielt (idempotent, fügt fehlende ein, aktualisiert bei Version-Bump). Built-in-Zeilen, deren Datei nicht mehr ausgeliefert wird (in den Pool verschoben, umbenannt, Altlasten mit Zufalls-ID), entfernt er vorher; maßgeblich sind die Dateinamen (`BuiltInTemplateLoader.shippedIds`), damit eine Datei, die nicht parst, ihre Zeile nicht verliert. Verifikationen fallen per `ON DELETE CASCADE` mit, Workflows aus solchen Vorlagen bleiben und verlieren nur den Rückverweis (`ON DELETE SET NULL`). Nutzer-Vorlagen bleiben unberührt
- Template-Definitionen liegen als einzelne JSON-Dateien in `src/main/resources/templates/builtin/` (eine Datei pro Template, geladen durch `BuiltInTemplateLoader`)
- Name und Beschreibung sind i18n JSON-Maps: `{"de":"...","en":"..."}` — gespeichert via `JsonStringMapConverter`
- Built-in Templates (`builtIn=true`) können nicht gelöscht werden (409). Per PUT geänderte Felder bleiben, bis ein Release eine höhere `version` mitbringt – dann überschreibt `WorkflowTemplateSeeder` sie beim Start. Ein PUT mit `blocks` oder `instructions` macht die Vorlage einsprachig (die zweite Sprachfassung entfällt)
- Nutzer können eigene Templates via API oder durch Speichern eines bestehenden Workflows anlegen
- `workflow.templateId` — optionale Back-Referenz auf das Quell-Template
- Frontend löst lokalisierte Template-Namen via `resolveTemplateName()` in `utils/templateUtils.ts` auf
- Jede Antwort trägt `requiredModules` (Module, die ihre Blöcke brauchen, verschachtelte eingeschlossen; `BlockTypeModules.requiredBy`) und `needsAi` (ob ein Lauf ein Modell rufen kann; `executor/ModelUse`, dieselbe Regel wie das KI-Badge im Frontend). Die Vorlagen-Auswahl blendet Vorlagen und Blaupausen mit ausgeschaltetem Modul aus und zeigt stattdessen einen Zähler („N weitere brauchen ein Modul – anzeigen“). Eingeblendet nennt die Karte das Modul und wie man es zuschaltet. Dazu kommen der Filter „Läuft ohne KI“ und übersetzte Kategorien
- CRUD-API unter `/api/v1/workflow-templates`

## Built-in Skills

- Skills werden in der `skills`-DB-Tabelle gespeichert, nicht in Frontend oder In-Memory-Beans hardcodiert
- **19 Built-in Skills** werden beim Start durch `SkillSeeder` eingespielt (idempotent)
- **Ablage:** `src/main/resources/skills/builtin/<id>.json` (Metadaten: `sortOrder`, `version`, `name`, `icon`, `description`, `category`, `capabilities` inkl. `writes`/`async`/`handlerClass`/Schemata) plus `<id>.md` mit dem Prompt-Text. Geladen von `BuiltInSkillLoader` (Vorbild `BuiltInTemplateLoader`)
- Die `.md` wird **byte-genau** übernommen (kein Trim, kein angehängter Zeilenumbruch). `BuiltInSkillsSnapshotTest` vergleicht alle Skills kanonisch mit `src/test/resources/skills/builtin-snapshot.json` und schlägt bei jeder unbeabsichtigten Änderung fehl; gewollte Änderungen mit `./mvnw test -Dtest=BuiltInSkillsSnapshotTest -Dskills.snapshot.write=true` neu schreiben
- **Versionsregel:** `SkillSeeder` seedet versionsbasiert (Semver-Vergleich in `SeedVersions`, wie `WorkflowTemplateSeeder`) — fehlende Skills werden angelegt, vorhandene nur bei höherer Ressourcen-`version` überschrieben (inkl. Capabilities). Eine inhaltliche Änderung an einem Built-in braucht also eine höhere `version` in der JSON, sonst erreicht sie bestehende Instanzen nicht. Nutzer-Skills (`builtin=false`) und in den Ressourcen entfallene Built-ins bleiben unangetastet
- **Gate zur Versionsregel:** `BuiltInSkillVersionGateTest` hasht je Skill die `<id>.json` (kanonisch) plus die `<id>.md` (byte-genau) gegen die eingecheckte Liste `src/test/resources/skills/builtin-versions.json` und schlägt fehl, wenn sich der Hash ohne höhere `version` geändert hat oder die Liste veraltet ist — nach einem gewollten Bump mit `./mvnw test -Dtest=BuiltInSkillVersionGateTest -Dskills.versions.write=true` neu schreiben und mitcommitten
- Loader-Fehler sind hart: fehlende `.md`, Dateiname ≠ `id`, fehlender oder doppelter `sortOrder`, fehlende/unbekannte `category` oder ein leeres Verzeichnis brechen den Start ab, statt ein unvollständiges Skill-Set zu seeden
- Built-in Skills haben stabile IDs (z.B. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` registriert alle Skills auch im In-Memory `SkillRegistry` für Capability-Lookups
- Capability-Handler-Klassen (z.B. `LintHandler`, `OCRHandler`, `SecurityScanHandler`, `FactCheckHandler`, `GrammarCheckHandler`) in `skills/handlers/` (Unterverzeichnisse `code/`, `document/`)
- Nutzer können eigene Skills via Import-API anlegen (`POST /api/v1/skills/import`)
- `Skill.java`-Entity nutzt `@PrePersist ensureId()` (statt `@GeneratedValue`) für explizite IDs bei Built-ins

## Template Verification

- `TemplateVerification`-Entity — trackt den Qualitätsstatus von Workflow-Templates
- `TemplateVerificationService` — verwaltet den Verifizierungs-Status pro Template+Projekt-Paar
- Workflows, die aus Templates erstellt wurden, können test-gestartet werden, um die Template-Korrektheit zu prüfen
- Verifizierungs-Ergebnisse sind in Template-CRUD-API-Responses sichtbar

## Workflow Factory

Automatisches Generieren und Verifizieren von Workflows aus einem Topic. Das Block-Schema in den Prompts von Factory, Wishes, Dialog-Import, Run-Konvertierung und Workflow-Optimierung kommt aus `BlockSpecs` (dieselben Texte wie `fetch block-spec` des Assistenten); `PromptBlockSchemaGateTest` prüft jeden dort genannten Blocktyp und Config-Schlüssel gegen die Executoren, damit kein Prompt Schlüssel nennt, die kein Executor liest (etwa `forEach`, `then_blocks`, `if_true`). Ein Beispiel für retry, foreach, while oder conditional mit Befehl muss außerdem `execution_mode` nennen: Ohne geht der Befehl als Prompt an die KI, und die KI baut nach, was das Beispiel zeigt.
- **Baufehler-Prüfung nach der Erzeugung** (`service/workflow/analyzer/GeneratedWorkflowCheck`, gemeinsam für Factory, Wünsche, Dialog-Import, Run-Konvertierung und Assistent): Nach der KI-Antwort läuft der `WorkflowAnalyzer` über die Blöcke. Bei HIGH-Befunden bekommt dieselbe KI (gleicher Provider, gleiches Modell, gleicher System-Prompt) **genau eine** Korrekturrunde mit ihrer Blockliste und den Befunden; ist die Antwort unbrauchbar, ändert sie die Struktur (andere Blockzahl, anderer Blocktyp an einer Position, andere Zahl verschachtelter Blöcke in einem Container — typisch: das Modell liefert nur den reparierten Block zurück) oder hat die korrigierte Fassung mehr HIGH-Befunde, gilt die ursprüngliche. Lässt die Korrektur `name`, `skillIds` oder `requiredCapabilities` eines Blocks weg, kommen sie aus dem Original. Bei MEDIUM/LOW gibt es keinen zusätzlichen Aufruf. Danach wird gespeichert; die Prüfung ändert nichts selbst und blockiert kein Speichern. Verbliebene Befunde ab MEDIUM stehen in `buildFindings` des Ergebnisses (deutsch; dieselben Zeilen englisch in `buildFindingsEn` — Factory-`/run`, Vorschläge von Dialog-Import und Run-Konvertierung — die UI zeigt sie als „Baufehler-Prüfung“ in ihrer Sprache) und landen beim Speichern als WARN im Activity-Log am Workflow. In der Factory läuft die Prüfung in `generateWorkflow`, also vor jedem Testlauf; regelbasierte Vorschläge (ohne KI) werden nur gemeldet. Der Assistent bekommt keine Korrekturrunde: `create_workflow`/`update_workflow` hängen die Befunde an ihre Ergebniszeile (in der Sprache der Oberfläche), und diese Zeile geht mit dem nächsten Zug gekürzt in den Verlauf des Modells ([assistant-system.md](assistant-system.md) → „Werkzeug-Ergebnisse im Verlauf“), damit es sie per `update_workflow` beheben kann. Jeder Befund (`AnalyzerFinding`) trägt seinen Text deutsch (`detail`) und englisch (`detailEn`); `AnalyzerFindingLanguagesTest` löst jede Regel aus und verlangt beide.
- `WorkflowFactoryService` — orchestriert den gesamten Factory-Prozess
- `FactoryVerificationService` — Verify/Fix-Schleife: auf der Test-Instanz ausführen, bei Fehlschlag Blöcke per KI reparieren, Ergebnis an Dev melden (auch für Auto-Repair und Wishes)
- `ClaudeWorkflowGenerator` / `CodexWorkflowGenerator` — AI-basierte Workflow-Generierung
- `WorkflowTextGenerator` — Textbasierte Workflow-Generierung
- `FactorySetupService` — Konfiguration und Setup der Factory-Umgebung
- `FactoryPromptBuilder` — Architekten-, Generator- und Auto-Fix-Prompt; die Codex-`AGENTS.md` verweist für die Blocktypen auf den Prompt
- `WorkflowFactoryController` — `/api/v1/factory` mit Endpunkten:
  - `POST /generate` — Workflow aus Topic generieren
  - `POST /run` — generierten Workflow auf Test-Instanz ausführen
  - `POST /verify/{id}` — Workflow verifizieren
  - `POST /promote/{id}` — Workflow in Produktion promoten
  - `GET /topics` — verfügbare Topics auflisten
- Nutzt die Test-Instanz (Port 8090) für sichere Ausführung
- Ablauf von `POST /run` („Erstellen & Testen“): generieren auf DEV (samt Baufehler-Prüfung, siehe oben) → `syncDefinitions()` → Testlauf auf der Test-Instanz (Polling) → bei `FAILED` und `autoFix` KI-Reparatur auf DEV aus der Fehlermeldung (neue Version), erneut syncen und testen, bis `maxRetries` → Testergebnis auf DEV vermerken → bei `autoPromote` und bestandenem Test Template. `POST /generate` („Workflow erstellen“) führt nur den ersten Schritt aus.
- **Baufehler-Prüfung nach jeder Auto-Fix-Reparatur** (`FactoryVerificationService.autoFixWorkflow`; genutzt von `/run`, von `verifyAndFix` für Wünsche und Auto-Reparatur und von der lokalen Auto-Reparatur): Bevor eine Reparatur gespeichert wird, legt `GeneratedWorkflowCheck.reviewRepair` dieselben Regeln an wie an die Korrekturrunde — ohne weiteren KI-Aufruf. Ändert die Reparatur die Struktur (andere Blockzahl, anderer Top-Level-Typ an einer Position, andere Zahl verschachtelter Blöcke in einem Container) oder hat sie mehr HIGH-Befunde als die aktuelle Fassung, wird sie mit WARN verworfen und der Workflow bleibt unverändert; weggelassene `name`, `skillIds` und `requiredCapabilities` kommen aus der aktuellen Fassung. In `/run`, `verifyAndFix` und der lokalen Auto-Reparatur zählt ein verworfener Versuch als Fehlversuch ohne erneuten Lauf des unveränderten Workflows; den Grund nennt `/run` in `retryErrors`, die Auto-Reparatur in ihrer Fehlermeldung im Activity-Log. Factory, Korrekturrunde, Wünsche, Dialog-Import und Run-Konvertierung lesen KI-erzeugte Blöcke über dieselbe Abbildung (`util/BlockJsonReader`), so dass `name`, `skillIds` und `requiredCapabilities` erhalten bleiben und `{{ block.<name>.output }}`-Bezüge auflösen.
- Das Ablaufdiagramm der Factory-Seite (`components/factory/FactoryFlowDiagram`) ist eine Erklärung, keine Live-Anzeige: `/run` ist ein blockierender Aufruf ohne Zwischenereignisse.

## Workflow Pool

Der Pool sind die mitgelieferten Workflow-Definitionen unter `workflow-pool/`, die sich in jede Instanz
installieren lassen. `WorkflowPoolService` liest sie, `WorkflowPoolController` (`/api/v1/pool`) listet,
installiert und exportiert. Was in den Pool gehört und wie er geprüft wird:
[workflow-pool-kuratierung.md](workflow-pool-kuratierung.md).

## Wishes (Wunsch-System)

Natürlichsprachige Wünsche werden automatisch in Workflows/Chains umgesetzt:
- `WishService` — erstellt Wish, verarbeitet Nutzer-Antworten (`respond`), kristallisiert zu Templates
- `WishFulfillmentLoop` — asynchroner Meta-Loop (decompose → execute → evaluate) inkl. Cancel-Flags und Gesamt-Timeout
- `WishEvaluationService` — Bewertungsphase (KI-Urteil → AWAITING_APPROVAL / REFINING / FAILED)
- `WishWatchdogService` — überwacht laufende Wishes auf Stale-/Timeout-Zustände
- `WishDecomposePromptBuilder` + `WishSystemPrompt` — Zerlegungs-Prompt; die Timeout-Vorgaben darin sind die Werte aus `automation.wish.*`, die `WishDecomposeService` beim Anlegen einsetzt
- Iterativer Prozess: Decomposition → Workflow-/Chain-Generierung → Ausführung → Feedback
- Unterstützt bis zu `max_iterations` Iterationen für Verbesserungen
- `crystallize()` — promotet erfolgreichen Wish als Template

## Integration System

Externe Dienste sind Provider: je ein `IntegrationProvider`-Bean, gefunden von `IntegrationRegistry`,
mit OAuth- bzw. Token-Fluss (`IntegrationOAuthService`), verschlüsselten Zugangsdaten
(`IntegrationEncryptionService`, AES-GCM) und Aktionen, die der `integration`-Block ausführt. Provider,
Zielarten und wie man einen neuen anbindet: [integrations.md](integrations.md).

## Dead Letter Queue

Fehlgeschlagene asynchrone Operationen werden in einer Dead-Letter-Queue gespeichert:
- `DeadLetterService` — legt für jeden FAILED-Run einen Eintrag an, per `@EventListener` auf `WorkflowRunCompletedEvent` (nicht per Aufruf aus `service.workflow` — der ergäbe einen Paketzyklus); Verwaltung von Dead-Letter-Entries (CRUD, Retry, Discard)
- `DeadLetterController` — `/api/v1/dlq`
- `DeadLetterEntry`-Entity mit Status: OPEN, RETRIED, DISCARDED

## Workflow Search

Aggregierte Suche über Workflows mit mehreren Such-Strategien:
- `SemanticWorkflowSearchService` — Substring/Fuzzy-Suche + semantisches Ranking via AI
- `SearchController` — `/api/v1/search`

## Docs System

Backend liest Markdown-Dokumentationsdateien und stellt sie als API bereit:
- `DocsController` — `/api/v1/docs`, liest `.md`-Dateien aus konfiguriertem `docs/`-Verzeichnis
- Pfad-Traversal-Schutz eingebaut
- Frontend zeigt Docs in Split-Layout mit Markdown-Rendering

## Frontend-Architektur

Aufbau und Regeln der Oberfläche: [frontend.md](frontend.md).

### Datenfluss

```
User-Interaktion
    ↓
React-Komponente
    ↓
Hook (useMutation / useQuery)
    ↓
API Client (fetch + X-API-Key Header)
    ↓
Vite Proxy (:3000 → :9090)
    ↓
Spring Boot Controller
    ↓
Service → Repository → SQLite
    ↓
Response (DTO) → React Query Cache → UI-Update
```

## WebSocket (Live-Logs + Status)

- **Protokoll:** STOMP über SockJS
- **Endpunkt:** `/ws` (SockJS Handshake, Authentifizierung per Ticket — siehe [WebSocket-Authentifizierung](#websocket-authentifizierung)); das Terminal nutzt `/ws/terminal/**`, höchstens drei Sitzungen je Container (`TerminalWebSocketHandler`)
- **Broker:** Simple Message Broker auf `/topic`
- **Topics:**
  - `/topic/executions/{runId}/logs` — Workflow-Ausführungs-Logs
  - `/topic/chains/{chainRunId}/status` — Chain-Ausführungs-Status
  - `/topic/activity-log` — Live-Activity-Log-Updates
  - `/topic/wishes/{wishId}/status` — Wish-Status-Updates
  - `/topic/assistant/{requestId}/status` — Assistenten-Chat-Status (pro Request)
  - `/topic/assistant/{sessionId}/events` — Assistenten-Push-Events (pro Session)
- **Nachrichten auf `/topic/executions/{runId}/logs`:** JSON-Objekte, unterschieden über `type` —
  Step-Nachrichten (ohne `type`: `stepIndex`, `status`, `input`, `output`, `durationMs`, `blockType`, `blockName`),
  `status`, `approval_*`, `human_input_*`, `agentic_*`, `foreach_item_*`, `container_*`, `command` und `live_output`
- **Live-Output laufender Blöcke:** Shell-, Container-, Pi- und Codex-Prozesse liefern ihre Ausgabe zeilenweise,
  Claude-`stream-json` wird zu kurzen Zeilen aufbereitet (Tool-Aufruf, gekürzter Text, Größe des Tool-Ergebnisses,
  Abschluss mit Turns/Kosten/Dauer).
  - **Mechanik:** `BlockStepRunner` bindet für die Dauer eines Steps (inkl. Validation-Retries und verschachtelter Blöcke)
    einen Sink per `ThreadLocal` (`service/live/LiveOutput`, bewusst nicht vererbbar — Sub-Workflows streamen in ihren
    eigenen Run). Die Prozess-Runner lesen ihn auf dem aufrufenden Thread und teilen die Pipe über
    `util/ProcessOutputTee` in Zeilen; der gesammelte Step-Output bleibt byte-identisch. Aufrufer außerhalb von Workflows
    (Assistent, Tagging, Suche) sind ungebunden und streamen nichts.
  - **Transport:** `RunLiveLogService` sammelt pro Run in einem Ring (Standard 1000 Zeilen) und sendet höchstens alle
    250 ms einen Batch `{ type: "live_output", runId, bufferId, lines: [{ seq, stepIndex, kind, text, ts }], skipped }`;
    vor der finalen Step-Nachricht wird synchron geflusht. `kind`: `line`, `text`, `tool`, `tool_result`, `tool_error`,
    `result`, `system`. Pro Batch gelten Zeilen- und Zeichen-Caps (neueste Zeilen gewinnen, Rest zählt als `skipped`).
    `setPreservePublishOrder(true)` hält die Reihenfolge pro Session.
  - **Replay:** `GET /api/v1/executions/{id}/live-log?afterSeq=-1` liefert den Puffer; das Frontend lädt ihn beim
    (Re-)Connect und dedupliziert über `bufferId` + `seq`. Puffer leben im Speicher und verfallen 10 Minuten nach
    Run-Ende (auch ohne Completion-Event über den Abgleich mit `WorkflowExecutionRegistry`), maximal 20 Runs.
  - **Aufbewahrung:** Beim `WorkflowRunCompletedEvent` schreibt `RunLiveLogArchive` den Puffer als eine Zeile in
    `run_live_logs` (gzip-JSON der Zeilen und Step-Namen, FK auf den Run mit `ON DELETE CASCADE` – Run-Retention und
    `DELETE /executions/{id}` nehmen ihn mit). Ob, entscheidet `workflows.live_log_persist` (`on`/`off`, NULL =
    Systemstandard `automation.live-log.persist-default`, Laufzeit-Schlüssel `live-log-persist-default`, Standard an).
    Der Schalter liegt außerhalb von `WorkflowSnapshot`: kein Hash, keine Version, auch bei Siegel änderbar. Das Replay
    liefert nach dem Verfall des Puffers die gespeicherte Zeile (`stored: true`, gleiche `bufferId`/`seq`). Ein
    fortgesetzter Run aus einem neuen Puffer wird angehängt und neu nummeriert. Runs ohne Completion-Event
    (Geister-Reaper, Zombie-Markierung beim Start) behalten keinen Mitschnitt.
  - **Konfiguration:** `automation.live-log.*` (`enabled`, `max-line-length`, `buffer-lines`, `flush-interval-ms`,
    `max-lines-per-flush`, `max-chars-per-flush`, `retention-minutes`, `max-buffered-runs`); nur beim Start gelesen,
    außer `persist-default`.
- **Terminal-WebSocket:** `/ws/terminal/**` für Echtzeit-Container-Zugriff (Raw WebSocket via `RawWebSocketConfig`, Handler: `TerminalWebSocketHandler`)

## Sicherheit

### API-Key-Authentifizierung

- **Header:** `X-API-Key`
- **Filter:** `ApiKeyAuthFilter` (vor Spring Security)
- **Vergleich:** Constant-Time (`MessageDigest.isEqual`)
- **Auto-Generierung:** Key wird beim ersten Start in `app_settings` gespeichert
- **Deaktivierbar:** `automation.security.enabled=false` (Entwicklung)

### Öffentliche Endpunkte (kein API-Key nötig)

Welche Pfade ohne Key erreichbar sind, steht einmal in `security/PublicPaths` (exakte Pfade, Muster, geschützte Bereiche `/api/**` und `/actuator/**`); die Liste mit dem Zweck jedes Pfads: [deployment.md → Was ohne API-Key erreichbar ist](deployment.md#was-ohne-api-key-erreichbar-ist). `SecurityConfig` baut daraus die Regeln, `ApiKeyAuthFilter` entscheidet mit denselben Matchern, ob ein fehlender Key als SECURITY-Warnung ins Activity-Log geht — nur dort, wo die Kette ihn auch abweist. Exakte Pfade treffen nur sich selbst (kein `/health/`, kein `/health/extra`); alles außerhalb der geschützten Bereiche (das ausgelieferte Frontend) ist frei.

### Rate Limiting

- **Filter:** `RateLimitFilter` (liest von `RateLimitConfig` Bean)
- **Algorithmus:** Sliding Window (1 Minute)
- **Standard:** 120 Requests/Minute pro Client-IP
- **Response-Header:** `X-RateLimit-Remaining`; bei 429 zusätzlich `Retry-After` — Sekunden bis das Minutenfenster des Eimers umschlägt (mindestens 1), derselbe Wert wie `retryAfterSeconds` im JSON-Body
- **Runtime-konfigurierbar:** Settings → Execution → API-Ratenbegrenzung (Toggle + RPM-Wert)
- **Env-Override:** `AUTOMATION_RATE_LIMIT_ENABLED` / `AUTOMATION_RATE_LIMIT_RPM`
- **Client-Backoff-Budget:** `automation.security.rate-limit.client-global-backoff-seconds` (2) und `client-default-retry-after-seconds` (10) — siehe unten
- **Webhook Rate Limiter:** `WebhookRateLimiter` — separater Per-IP-Limiter für `/api/v1/webhook/trigger/**`, immer aktiv
- **Client-IP (`ClientIpResolver`):** `X-Forwarded-For` zählt nur, wenn der direkte Peer in `automation.security.trusted-proxies` steht (Default `127.0.0.1,::1`), und wird dann **von rechts** gelesen: Client-ID ist der rechteste Eintrag, der kein Trusted Proxy ist (alle Header-Zeilen als eine Kette). Der linke Teil stammt vom Client selbst und würde sonst jedem Request einen eigenen Eimer geben.
- **Eimer-Speicher (`ClientBucketStore`):** beide Limiter fegen beim Zugriff abgelaufene Eimer aus (höchstens einmal pro Minute) und deckeln die Map bei 10 000 Clients — darüber fliegen die Eimer mit dem ältesten Fenster zuerst.
- **Webhook-Signaturen (optional):** HMAC-SHA256 pro Webhook, siehe [Workflow-Härtung](#workflow-härtung-optional)

**Server-Eimer global, Client-Sperre pro Pfad — die Asymmetrie ist Absicht.** Der Server zählt in
*einem* Eimer pro Client-IP über alle Endpunkte hinweg; ein 429 bedeutet also „du insgesamt bist zu
schnell". Der Client (`api/client.ts`) merkt sich die Sperre dagegen **pro Endpunkt-Muster** (der
Pfad ohne Query und mit durch `{id}` ersetzten UUIDs, damit `/executions/{id}` nicht pro Run einen
eigenen Schlüssel bekommt).

Dazu kommt eine **kurze Sperre über die übrigen Pfade: höchstens 2 s, gedeckelt durch
`Retry-After`** — schickt der Server `Retry-After: 0`, entsteht gar keine globale Sperre. Zwei Endpunkte sind davon ausgenommen (`GLOBAL_LOCK_EXEMPT`): `POST /ws-ticket` (eine
blockierte Ticket-Anfrage kostet den WebSocket einen Reconnect-Versuch und verdoppelt seinen
exponentiellen Abstand, aus 2 s wird also ein viel längerer Ausfall) und `GET /system/auth-status`
(der `ApiKeyGuard` rendert nichts, bevor der antwortet — die App bliebe leer statt den
Rate-Limit-Hinweis zu zeigen). Ihre eigene Pfad-Sperre gilt für sie weiterhin; ausgenommen sind sie
nur von *fremden* 429ern.

**Woher die zwei Zahlen kommen: von der Instanz, nicht aus dem Bundle.** Die 2 s der globalen
Kurzsperre und die 10 s, die der Client ohne brauchbaren `Retry-After` annimmt, stehen als
Konstanten (`GLOBAL_BACKOFF_SECONDS`, `DEFAULT_RETRY_AFTER_SECONDS`) nur als *Fallback* im
Client. Die geltenden Werte liefert `GET /api/v1/system/info` unter `rateLimit`
(`enabled`, `requestsPerMinute`, `globalBackoffSeconds`, `defaultRetryAfterSeconds`); `useSystemInfo`
reicht sie über `applyServerRateLimitConfig()` an den Client weiter — der Endpunkt wird beim Start
ohnehin geladen (Sidebar) und braucht keinen API-Key, also entsteht dafür kein eigener Request.
Gesetzt werden sie über `automation.security.rate-limit.client-global-backoff-seconds` bzw.
`client-default-retry-after-seconds`; fehlende oder nicht-positive Werte lässt der Client liegen und
bleibt bei seinen Konstanten. Hintergrund: Eine Instanz mit anderem `requests-per-minute` macht fest
verdrahtete Client-Zahlen zu Raterei (37b98c97).

Der `Retry-After`-Header kommt **nur vom `RateLimitFilter`**. Die beiden anderen 429-Quellen senden
ihn nicht: der Webhook-Limiter (`WebhookController` → `WebhookRateLimiter`, leere 429-Antwort) und
`TOKEN_BUDGET_EXCEEDED` (`ErrorCode`/`GlobalExceptionHandler`, ein Budget- statt Zeitproblem — hier
hilft Warten gar nicht). Für den Browser-Client heißt das: bei einem 429 auf `/api/v1` greift
praktisch immer der Header, der `defaultRetryAfterSeconds`-Wert nur bei diesen Sonderfällen oder
wenn ein Proxy den Header verschluckt.

Daraus folgt: Nach einem 429-Sturm lässt der Client nach spätestens 2 s wieder einzelne Muster los,
und jedes Muster sammelt sein eigenes 429 ein, bis seine `Retry-After`-Sperre steht. Die kurze
globale Sperre nimmt dem Sturm die Spitze, ohne die beiden Auswege zu verschließen. Erholung läuft
ohne Reload: Ein Timer auf der letzten Frist (`scheduleExpiry`) meldet das Ablaufen, und
`installRateLimitRecovery` invalidiert beim Übergang aktiv → inaktiv **einmal alle Queries im
Fehlerzustand** — `retry` ist für 429 aus und `refetchOnMount` greift nur beim Mount, dauerhaft
montierte Queries blieben sonst bis zum Reload rot. Erfasst wird dabei genau das, was wirklich auf
`error` steht. Queries mit `initialData` (`useSystemModules`, `useBlockTypes`) stehen auch nach
einem 429 weiter auf `success` und werden von der Erholung deshalb nie angefasst — gewollt: der
zuletzt bekannte Modul- bzw. Blocktyp-Stand soll stehen bleiben, statt Navigation und Widgets
flackern zu lassen (b13509f3). Ihren Serverstand holen sie beim nächsten Mount nach, weil
`initialDataUpdatedAt: 0` den geseedeten Stand sofort stale macht.

**Leeres `?ticket=` im Handshake ist der Client-Fallback.** Fällt `POST /ws-ticket` in eine Sperre
(oder scheitert sonst), setzt `useWebSocket` in `beforeConnect` `wsTicket = ''` und der Handshake
geht mit leerem `?ticket=` raus. Der Interceptor lehnt ihn ab, stompjs plant den nächsten Versuch
mit wachsendem Abstand — das ist der gewollte Weg, statt einer offenen Promise-Ablehnung, die den
Reconnect-Zyklus abreißen ließe. Ein leeres Ticket in den Server-Logs ist also kein Angriff, sondern
ein Client, dessen Ticket-Abruf gerade nicht durchkam.

### Request-Size-Limit

- **Filter:** `ActivityLogSizeLimitFilter`
- **Max:** 64 KB (konfigurierbar via `automation.activity-log.max-request-bytes`)
- Nur für `/api/**`-Pfade

### WebSocket-Authentifizierung

Weder SockJS noch ein rohes Browser-WebSocket kann beim Handshake eigene Header senden — die Authentifizierung muss also über die URL laufen. Der API-Key darf dort nicht stehen (Browser-History, Proxy-Logs), deshalb geht ein **kurzlebiges Einmal-Ticket** voraus:

1. Client ruft `POST /api/v1/ws-ticket` auf — **mit dem API-Key im Header**, wie jeder andere Endpunkt (unterliegt dem normalen API-Rate-Limit). Antwort: `{ "ticket": "<43 Zeichen>", "expiresInSeconds": 30 }`
2. Client hängt das Ticket als `?ticket=…` an die Handshake-URL (`/ws` und `/ws/terminal/{runId}`)
3. `WebSocketAuthInterceptor` löst das Ticket ein und lässt den Handshake durch — **genau einmal und nur vor Ablauf**. Ein `?apiKey=` wird abgelehnt (401, Handshake abgebrochen) und mit eigenem Hinweis protokolliert.

- **Ticket-Speicher:** `WebSocketTicketService` — rein in-memory (insertion-geordnete `LinkedHashMap` mit `synchronized`-Zugriff statt `ConcurrentHashMap`, kein DB-Zugriff im Handshake), 32 zufällige Bytes URL-safe Base64, abgelaufene Einträge werden bei jedem `issue()`/`consume()` weggeräumt (kein Scheduler). Zusätzlich ist der Speicher über `removeEldestEntry` hart auf **`MAX_OPEN_TICKETS` = 1000 offene Tickets** gedeckelt; darüber fällt per FIFO das älteste heraus (der betroffene Client holt beim nächsten Versuch einfach ein neues). Tickets überleben keinen Neustart — genau ihre gedachte Lebensdauer.
- **Laufzeit:** `automation.websocket.ticket-ttl-seconds` (Default 30, Env `AUTOMATION_WS_TICKET_TTL_SECONDS`)
- **Frontend:** `api.fetchWsTicket()`; `useWebSocket`/`useAssistantWebSocket` holen das Ticket in stompjs' `beforeConnect` (läuft vor jedem Verbindungsversuch inkl. Reconnect), das Run-Terminal vor `new WebSocket(...)`. Ein Wächter-Test verbietet `apiKey=` in `src/`.
- **Kein SockJS-Fallback:** Die Clients laufen mit `transports: ['websocket']`. SockJS legt für jeden Fallback-Transport (xhr-streaming, xhr-polling) eine eigene Session an und bräuchte dafür ein zweites Ticket — das erste ist nach dem WebSocket-Versuch verbraucht. **Die Instanz braucht also einen WebSocket-fähigen Pfad** (Proxy/Reverse-Proxy muss Upgrades durchlassen); einen HTTP-Fallback gibt es nicht.
- **Reconnect:** `reconnectDelay: 5000` mit `ReconnectionTimeMode.EXPONENTIAL` und `maxReconnectDelay: 60000` — jeder Versuch kostet einen `POST /ws-ticket` und soll bei totem Backend nicht gegen das API-Rate-Limit laufen.
- **Deaktiviert:** Bei `automation.security.enabled=false` oder ohne konfigurierten Key lässt der Interceptor jeden Handshake durch (wie der `ApiKeyAuthFilter`).

### CORS

- Eine Antwort für CORS-Filter, WebSocket-Handshakes und OAuth-Popup: `security/AllowedOrigins`
- Konfigurierte Liste `automation.cors.allowed-origins` (Default: `http(s)://localhost:3000` und `:5173`)
- Mit dem Modul `remote-access` zusätzlich die eigene Tailscale-IPv4 und der eigene MagicDNS-Name, jeder Port (`service/system/OwnTailnetAddresses`: `tailscale ip -4` / `tailscale status --json`, 60 s gemerkt)
- WebSocket: `AllowedOriginHandshakeInterceptor` prüft vor dem Ticket; Springs eigene Prüfung bekommt `*`, weil sie nur eine feste Startliste kennt
- Alle HTTP-Methoden (GET, POST, PUT, PATCH, DELETE, OPTIONS)
- Keine Credentials (Auth über `X-API-Key`, keine Cookies)

## Architektur-Gates

Zwei JUnit-Tests im Paket `com.automation.architecture` halten die gemessene Struktur fest, ohne neue Abhängigkeit. Ihre Grundmessung sind dieselben jdeps-Paketkanten wie in `scripts/public-readiness/arch-metrics.sh` (Abschnitt `## packages`); `LayerRuleTest` prüft zusätzlich eine zweite Sicht (siehe unten). `PackageCycleRatchetTest` pinnt die heute wechselseitig abhängigen Paketpaare als Allowlist — ein neues Paar bricht den Build, ein aufgelöstes wird nur als Hinweis gemeldet. Weil Paare allein nicht jeden Zyklus sehen (eine einzige neue Kante kann ein Paket in eine große Zyklus-Komponente ziehen, ohne ein neues Paar zu bilden), begrenzt er zusätzlich die starken Zusammenhangskomponenten, also die `cycle`-Zeilen von `arch-metrics.sh`: die größte auf `MAX_CYCLE_COMPONENT_SIZE`, und jede Komponente mit mehr als zwei Paketen ist samt Mitgliedern in `CYCLE_COMPONENTS` festgehalten — ein neu hineingezogenes Paket bricht den Build, und die Meldung nennt die Kanten, die es anbinden; schrumpft eine Komponente, schlägt der Test ebenfalls fehl, bis beide Werte im selben Commit gesenkt sind. `LayerRuleTest` verbietet die Kantenklassen `service.* → controller.*`, `controller.* → repository.*` und `repository.* → service.*` (Allowlist heute leer). Gemessen wird immer das Build-Verzeichnis, aus dem die Tests selbst geladen wurden (`target/` bzw. `target-ide/` im IDE-Profil), nie ein danebenliegender veralteter Stand; fehlt es oder fehlt jdeps, werden beide Tests mit klarer Meldung übersprungen statt still grün zu laufen.

Der blinde Fleck der Bytecode-Messung sind Abhängigkeiten, die nur als generisches Typargument auftauchen und im Bytecode gelöscht sind. `LayerRuleTest` prüft dieselben drei Regeln deshalb aus einer **zweiten Sicht**: `SourceImportEdges` liest die `import`-Zeilen des Quelltexts unter `src/main/java` (Spalte 0, damit Kommentare und String-Literale nicht mitzählen) und wertet sie gegen dieselben Regeln aus, mit eigener Allowlist (heute ebenfalls leer). Die Sichten decken sich nicht — die Bytecode-Sicht sieht Vererbung, statische Verweise und generierten Code, die Quelltext-Sicht die gelöschten Typargumente —, und der Test belegt zusätzlich, dass der Scan überhaupt Klassen gelesen hat: eine leere Messung meldete sonst jede Regel als eingehalten. Ein **Rest bleibt für beide unsichtbar**: ein voll qualifizierter Name innerhalb eines generischen Typarguments (`Page<com.automation.repository.Foo>` ohne Import) ist im Bytecode gelöscht und hat keine Import-Zeile. Für Projektionen, die vom Repository bis in den Controller reisen, heißt das: sie gehören nach `com.automation.dto.response`, nicht ins Repository-Paket.

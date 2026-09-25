# Architektur

MySuricates ist eine selbst betriebene Workflow-Plattform: ein Spring-Boot-Backend mit
Web-Oberflaeche, in dem Ablaeufe aus Bloecken bestehen — teils deterministisch (Shell,
Verzweigung, Schleife), teils KI-Aufrufe. Diese Seite beschreibt den inneren Aufbau. Fuer den
Einstieg siehe [README.de.md](../README.de.md), zum Bauen von Workflows
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md).

> Kuerzel in Klammern wie `(182bd224)` oder `TODO 63ecc124` sind Herkunftsnachweise:
> Commits dieses Repositories bzw. Vorgaenge in der begleitenden MySuricates-Instanz.
> Sie belegen, wann und warum etwas entstanden ist — von aussen nachschlagen laesst
> sich davon nur der Commit.

> Die Abläufe dieser Seite gibt es auch als Diagramme: [docs/diagramme.md](diagramme.md)
> — Engine, Scheduler, Skills, Memory, Request-Pfad, Frontend, Deployment.

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

| Umgebung | Port | Beschreibung |
|---|---|---|
| **Dev** | 3000 (Frontend) / 9090 (Backend) | Entwicklungsserver, aus Quellcode |
| **Stable** | 9091 | Docker-Container, Backend + eingebettetes Frontend |
| **Test** | 8090 | Docker-Container, dedizierte Test-Instanz |

Dev startet Stable und Test bei Boot automatisch, wenn ein Snapshot vorhanden ist (konfigurierbar via `automation.staging.auto-start` bzw. `automation.staging.test-auto-start`).

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

**Katalog** (einzige Quelle: `modules/ModuleCatalog`, 19 Feature-Module + 4 Härtungsschalter = 23 Schlüssel):

| Stufe | Module | Reifegrad |
|---|---|---|
| `standard` (alle Presets) | `dashboard-report` | stabil |
| `research` | `chains`, `assistant`, `learning` (Analyse-Endpunkte; Dashboard-Daten bleiben Kern), `memory`, `audit`, `bpmn` | chains stabil, Rest experimentell |
| `advanced` (nach außen) | `integrations` (inkl. TODO-Tracker-Export, Kalender-Adapter), `http-api` (allgemeine HTTP-API-Verbindung, braucht `integrations`), `webhooks`, `remote-access` (Tailscale), `terminal` (Run-Terminal per WebSocket), `staging` | stabil / staging experimentell |
| `advanced` (wenig genutzt) | `conversion`, `dialog-import` (benötigt conversion), `onboarding-profiles`, `skills`, `debug`, `factory` (inkl. Auto-Repair), `wishes` (benötigt chains) | Altlast / experimentell |
| `hardening` | `provenance` (Default an), `sealing`, `require-sealed-for-unattended`, `webhook-signatures` | experimentell |

Der Katalog wird zusätzlich als reine Bash-Arrays nach `scripts/lib/module-catalog.generated.sh`
exportiert — eingelesen von `scripts/lib/module-preset.sh`, das vor jedem Build läuft und deshalb
weder ein Backend noch `jq` voraussetzen darf. Die Datei wird nicht von Hand gepflegt: Nach einer
Änderung am Katalog oder an den Anzeigetexten (`ai-automation-frontend/src/data/modules.<lang>.json`)
neu erzeugen mit:

```bash
cd ai-automation-backend && ./mvnw test -Dtest=ModuleCatalogTextsTest -Dmodule.catalog.write=true
```

Ohne Regenerierung schlägt `ModuleCatalogTextsTest` rot fehl, sobald die generierte Datei nicht mehr
zum Katalog passt — wer ein Modul hinzufügt, erfährt es spätestens dort.

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

Vier Schalter unter `automation.hardening.*` — derselbe Mechanismus wie bei den Modulen (nur Startkonfiguration, vom Preset unabhängig, **Settings → Module & Härtung** zeigt sie lesend). Die Herkunft von Änderungen ist kein Schalter, sondern immer an.

| Schalter | Default | Braucht | Wirkung |
|---|---|---|---|
| `provenance` | an | — | Jeder Run speichert `definition_hash`, `definition_version_id` und `trigger_source` |
| `sealing` | aus | `provenance` | Workflows versiegeln (`POST /api/v1/workflows/{id}/seal`, `…/unseal`) |
| `require-sealed-for-unattended` | aus | `sealing` | Unbeaufsichtigte Runs starten nur versiegelte Workflows |
| `require-signed-seal` | aus | `sealing` | Versiegelte Workflows brauchen eine gültige Signatur |
| `seal-immutable` | aus | `sealing` | Entsiegeln nicht mehr über REST — nur über die Startkonfiguration |
| `webhook-signatures` | aus | Modul `webhooks` | Signatur-Secret pro Webhook (HMAC-SHA256) |

**Definitions-Hash** — `WorkflowDefinitionHasher` hasht den `WorkflowSnapshot` (blocks, instructions, model, provider, effort, metadata, defaultContext, sortierte Skill-IDs) als kanonisches JSON (sortierte Schlüssel, leere Werte weggelassen) mit einem eigenen `ObjectMapper`, damit Jackson-Customizing den Hash nie verändert. Name, Beschreibung, Icon und Tags sind Etiketten und fließen nicht ein. Alte Versionszeilen bekommen ihren Hash beim Start (`WorkflowVersionHashBackfillService`). Kontextvariablen: `{{ workflow_definition_hash }}`, `{{ trigger_source }}`.

**Run-Zulassung** — Jeder Run-Auslöser ruft `WorkflowExecutor.execute(…, TriggerSource)` und geht damit durch `RunAdmission`, bevor eine Run-Zeile existiert. Regeln in dieser Reihenfolge: (1) ein versiegelter Workflow muss seinem Siegel entsprechen — Definition und Inhalt seiner Skills —, (2) mit `require-sealed-for-unattended` braucht ein unbeaufsichtigter Run einen versiegelten Workflow. Ein abgelehnter Run hinterlässt **keine Run-Zeile und keinen DLQ-Eintrag**, nur einen `SECURITY`-Eintrag im Activity-Log; REST-Aufrufer bekommen den Fehlercode.

**Herkunft von Änderungen (immer an)** — Alle Schreiber von Definitionsfeldern gehen durch `WorkflowService` → `WorkflowMutationGateway`. Jede Änderung schreibt eine Versionszeile mit dem Zustand **nach** der Änderung (Nummer = MAX+1) und einem `changeSource`, den der aufrufende Server-Code bestimmt, nie der Request (`user`, `assistant`, `factory`, `wish`, `dialog`, `conversion`, `chain-template`, `pool-install`, `example-project`, `goal-guard`, `import`, `bpmn-import`, `restore`). Passt der Hash der neuesten Zeile nicht zum aktuellen Stand (Altbestand, Änderung am Service vorbei), wird dieser Stand zuerst als `untracked` gesichert. Reine Etiketten-Änderungen erzeugen keine Version. `WorkflowWriterAllowlistTest` legt fest, wer `WorkflowRepository.save` sonst noch aufrufen darf. Dieselbe Stelle lehnt unbekannte Blocktypen ab: `BlockTypeValidator` prüft jeden Blocktyp — verschachtelte in `switch`-`cases[].blocks` und `default` eingeschlossen — gegen die Executor-Registry (`BlockTypeRegistry`, gespeist aus allen `BlockExecutor`-Beans) und wirft **400 `INVALID_ARGUMENT`** mit dem unbekannten Typ und der Liste der erlaubten Typen. Die Prüfung läuft in der Transaktion des Aufrufers, der Speichervorgang wird also zurückgerollt.

**Versiegeln** — `seal` hält den aktuellen Hash in `sealed_hash` fest (mit `sealed_at` und einem WARN-`SECURITY`-Eintrag). Versiegelte Workflows lehnen Definitionsänderungen und Löschen mit **409 `WORKFLOW_SEALED`** ab; Umbenennen bleibt möglich. Reparieren (`POST …/repair`) wird ebenso sofort abgelehnt, automatisches Auto-Repair überspringt versiegelte Workflows vor jedem KI-Aufruf, und die Oberfläche sperrt „Optimieren“ und „Reparieren“. Weicht die Definition beim Start vom Siegel ab (etwa nach einer Änderung direkt in der Datenbank) oder lässt sie sich nicht prüfen, startet der Run nicht: **409 `WORKFLOW_TAMPERED`**, ERROR-`SECURITY`-Eintrag mit erwartetem und tatsächlichem Hash. Beide Prüfungen gelten unabhängig vom Schalter — `sealing` regelt nur Setzen und Lösen, Ausschalten ist kein stilles Entsiegeln.

**Skills unter dem Siegel** — Der Definitions-Hash enthält nur die Skill-**IDs**; was ein Skill in den Run trägt (Name und `aiContext` im Prompt, Config-Schema, je Capability Name, Beschreibung, Schemas, Handler, `async`/`writes`), steckt nicht darin. Deshalb hält `seal` zusätzlich je genutztem Skill — aktivierte Skills und die `skillIds` der Blöcke — einen Inhalts-Hash fest (`SkillContentHasher`, kanonisches JSON wie beim Definitions-Hash, Etiketten wie Beschreibung, Icon, Kategorie und Version zählen nicht) und speichert die Zuordnung als JSON in `workflows.sealed_skill_hashes` (`WorkflowSkillPins`). `RunAdmission` vergleicht sie vor jedem Run: Ein geänderter, hinzugekommener oder gelöschter Skill ergibt **409 `WORKFLOW_TAMPERED`** mit den betroffenen Skill-IDs; lässt sich der Stand nicht prüfen, wird ebenfalls abgelehnt. Der Definitions-Hash selbst bleibt unverändert, damit bestehende Siegel und die Versions-Zuordnung der Runs gültig bleiben. Siegel von vor V80 haben `sealed_skill_hashes = NULL`: Sie laufen weiter (mit einer Log-Warnung), prüfen aber keine Skills — erneutes `seal` trägt die Hashes nach, allerdings nur, solange die Definition noch zum Siegel passt.

**Schreibpfad für Skills (immer an)** — Alle Schreiber von Skills gehen durch `SkillMutationGateway` (Gegenstück zu `WorkflowMutationGateway`): Anlegen und Bearbeiten (`user`, `PUT /api/v1/skills/{id}`; Built-ins bleiben schreibgeschützt, sonst überschriebe das nächste Release die Änderung still), Import (`import`) und Built-in-Aktualisierung beim Start (`builtin-seed`). Jede Änderung schreibt eine Zeile in `skill_versions` (Operation `CREATE`/`UPDATE`/`DELETE`, kanonischer Snapshot, Inhalts-Hash, serverseitige Herkunft; ohne Fremdschlüssel, die Historie überlebt das Löschen) und einen Activity-Log-Eintrag — ein Import ist ein WARN-`SECURITY`-Ereignis. `GET /api/v1/skills/{id}/versions` listet die Historie, auch für gelöschte Skills. Nutzt ein versiegelter Workflow den Skill, lehnt das Gateway Ändern und Löschen durch eine Person mit **409 `SKILL_PINNED_BY_SEAL`** ab. Built-in-Updates aus den Ressourcen lassen sich nicht ablehnen; ändert sich dabei der Inhalt unter einem versiegelten Workflow, schreibt das Gateway einen WARN-`SECURITY`-Eintrag mit den betroffenen Workflows, und deren Runs werden bis zum erneuten Siegeln abgelehnt. `SkillWriterAllowlistTest` legt fest, dass nur das Gateway `skillRepository.save/delete` aufruft.

**Signatur und Geschichte des Siegels** — `sealed_hash` liegt in derselben SQLite-Datei wie die Definition, die es schützt; wer die Datei schreibt, ändert beides und kommt durch. `WorkflowSealSigner` signiert deshalb jedes Siegel per HMAC-SHA256 über Workflow-ID, Definitions-Hash und Skill-Pins — mit einem Schlüssel **außerhalb** der Datenbank (`automation.seal.key-file`, Env `AUTOMATION_SEAL_KEY`, sonst 0600-Datei beim ersten Start; nie in `app_settings`). Eine vorhandene Signatur wird immer geprüft (`SIGNATURE_INVALID`); ob ein Siegel **ohne** Signatur laufen darf, entscheidet `automation.hardening.require-signed-seal` (`SIGNATURE_MISSING`), damit Bestandssiegel nicht schlagartig ihre Runs verlieren. Ohne lesbaren Schlüssel startet die Instanz trotzdem, Siegel bleiben dann unsigniert. `automation.hardening.seal-immutable` nimmt `POST …/unseal` aus der REST-API (**403 `SEAL_IMMUTABLE`**): Lösen geht dann nur über die Startkonfiguration, ein geleakter API-Key (oder ein Agent, der ihn hat) reicht nicht mehr. Jede Versiegelung und Entsiegelung steht mit Zeitpunkt in `workflow_seal_events` (`GET /api/v1/workflows/{id}/seal-events`) — das Activity-Log wird nach 30 Tagen aufgeräumt und `sealed_at` fällt beim Entsiegeln weg. Die Einträge sind über alle Workflows hinweg verkettet (`prev_event_hash`), eine nachträglich geänderte oder gelöschte Zeile meldet die API als `chainIntact: false`. Ablehnungen (Run wie Entsiegelung) schreiben ihren SECURITY-Eintrag über `ActivityLogService.logRefusal` nach dem Ende der Aufrufer-Transaktion, sonst nimmt der Rollback sie mit.

**Siegel sichtbar** — `WorkflowSealVerifier` ist die eine Prüfung, die `RunAdmission` vor dem Start und `GET /api/v1/workflows/seal-status` ohne Run ausführen (`INTACT`, `VIOLATED` mit Grund `DEFINITION_CHANGED`/`SKILLS_CHANGED`/`UNVERIFIABLE` und den betroffenen Skills, `NOT_SEALED`). Die Antwort enthält auch die in den letzten 7 Tagen abgelehnten Starts aus dem Activity-Log, denn ein abgelehnter Start legt keine Run-Zeile an. Der Endpunkt ist Kern, nicht Teil des `sealing`-Moduls: Ein Siegel bleibt wirksam, wenn der Schalter aus ist, also muss es sichtbar bleiben. `GET /api/v1/skills/seal-pins` ordnet jedem Skill die versiegelten Workflows zu, die ihn nutzen. Jeder Run hält beim Start fest, ob er versiegelt und geprüft lief (`workflow_runs.sealed`, `sealed_skill_pins`); das wird nie aus dem heutigen Zustand des Workflows abgeleitet. Die Oberfläche zeigt beide Zustände, nicht nur den versiegelten: Badge neben dem Namen auf Detailseite und Listen-Panel (Schloss „Versiegelt“, offenes Schloss „Nicht versiegelt“, rot bei verletztem Siegel), Hinweisleiste auf der Detailseite, beschrifteter Knopf „Versiegeln“/„Entsiegeln“, Filter „Alle / Versiegelt / Nicht versiegelt“ mit Anzahl in der Liste, gefülltes Schloss oder graues offenes Schloss in Workflow-Zeilen, Run-Liste, Run-Detail und Chain-Einträgen (Runs von vor der Erfassung zeigen einen Strich „Siegelstand nicht erfasst“, nie einen aus dem heutigen Zustand geratenen Wert), Schloss auf Skill-Karten und als Hinweis „Starts abgelehnt“ über der Run-Liste. Der SECURITY-Eintrag eines abgelehnten Starts wird bei laufender Transaktion des Aufrufers (Webhook, Zeitplan, Chain) erst nach deren Ende geschrieben, auf einem eigenen virtuellen Thread — sonst rollte die Ablehnung ihn mit zurück, und `REQUIRES_NEW` würde sich bei Pool-Größe 1 selbst blockieren.

**Unbeaufsichtigte Runs** — `TriggerSource` teilt die Auslöser ein:

| Beaufsichtigt | Unbeaufsichtigt | Erbt vom Auslöser |
|---|---|---|
| `MANUAL`, `RETRY`, `RESUME`, `TODO_RESOLUTION`, `ASSISTANT` | `SCHEDULE`, `WEBHOOK`, `TODO_TRIGGER`, `COMPLETION_TRIGGER`, `AUTO_REPAIR`, `WISH` | `CHAIN`, `SUB_WORKFLOW` |

Chains und Sub-Workflows geben die Herkunft ihres Auslösers im reservierten Kontextschlüssel `__parent_trigger_source` weiter: `ChainExecutor` setzt ihn für jeden Eintrag selbst (Eingaben können ihn nicht vorgeben), `SubWorkflowBlockExecutor` reicht die effektive Herkunft des Eltern-Runs durch, und Webhook-Payloads dürfen keine `__`-Schlüssel enthalten. Fehlt der Schlüssel oder ist er unlesbar, gilt der Run als unbeaufsichtigt. Abgelehnt wird mit **403 `UNATTENDED_REQUIRES_SEAL`**. Mit diesem Schalter sind Auto-Repair und Wishes faktisch aus: Sie laufen unbeaufsichtigt und müssten versiegelte Workflows ändern.

**Vertrauensklassen der Kontextwerte** — Jede Kontextvariable eines Runs trägt eine von drei Klassen (`executor/VariableTrust`, Buchführung in `executor/ExecutionContextTaint`). Die Klasse entscheidet, ob der Wert in einer Shell-Kommandozeile roh eingesetzt oder in POSIX-Single-Quotes gepackt wird — und ob er den Run *taintet*, also alle Folgeausgaben mitescapt.

| Klasse | Quelle | In Shell-Blöcken | In KI-Prompts | Taintet den Run |
|---|---|---|---|---|
| **trusted** | Workflow-Defaults, Systemwerte (`project_path`, `workflow_run_id`, `workflow_definition_hash`), blockinterne Werte, Antworten aus `approval`/`human-input`, jedes `setVariable` | roh eingesetzt | roh | nein |
| **untrusted extern** | Webhook-Payload, wiederhergestellte `__untrusted_keys` (Resume, Sub-Workflow), aus getainteten Ausgaben abgeleitete Variablen (`step_N_output`, foreach-Items, `document`-Extrakte) | immer single-quoted | roh | ja, ab dem Setzen |
| **Plattform-Daten** | `previous_report`, `open_todos` — von `WorkflowContextDataService` in **jeden** Run injiziert | immer single-quoted | roh | auf zwei Wegen: (1) sobald der Platzhalter wirklich in eine Shell-Kommandozeile substituiert wurde; (2) sobald die Ausgabe eines KI-Blocks verbucht wird, in dessen Prompt der Platzhalter stand — ab dieser gefärbten Ausgabe ist der Lauf klebrig getaintet |

- **Taint-Ausbreitung:** Ist ein Run getaintet, escapt `recordOutput` jede weitere Blockausgabe (`previous_output`, `{{ block.<name>.output }}`) und `setDerivedVariable` markiert jede daraus abgeleitete Variable als untrusted. Klebrig wird der Taint, sobald eine Ausgabe verbucht wurde oder Plattform-Daten in eine Kommandozeile gelangt sind — ab dann hebt ihn auch ein späteres `setVariable` auf die auslösende Variable nicht mehr auf, weil ein Block Fremdbytes ohne Platzhalter in seine Ausgabe holen kann (Datei lesen, API aufrufen). Davor, also bei externem Input ohne jede verbuchte Ausgabe, entfernt ein `setVariable` auf genau diese Variable den Taint noch. Persistiert wird er in den drei reservierten Kontextschlüsseln `__untrusted_keys`, `__untrusted_outputs` und `__platform_in_shell` (Letzteres das Nutzungs-Flag der Plattform-Daten, `true` sobald es gesetzt ist), damit er Resume und Sub-Workflow-Übergabe überlebt — auch dann, wenn nach der Substitution noch keine Ausgabe verbucht wurde. Ein sauberer Run exportiert nichts und bleibt byte-identisch zum Verhalten vor der Taint-Einführung.
- **Warum Plattform-Daten erst bei Nutzung tainten:** Sie stecken in jedem Run. Würden sie beim Injizieren tainten, wäre jeder Run getaintet und die Ausgabe-Platzhalter in den mitgelieferten Templates bekämen eine andere Bedeutung. Deshalb meldet `TemplateResolver.resolveShell`/`resolveShellSafe` zurück, welche Platzhalter tatsächlich in eine Kommandozeile eingesetzt wurden; erst diese Meldung schaltet den Taint scharf (`ExecutionContextTaint.substitutedIntoShell`). Ein Run, der `{{ previous_report }}` gar nicht verwendet, bleibt untainted.
- **KI-Ausgaben nach Plattform-Daten-Prompt sind gefärbt:** Standen Plattform-Daten im Prompt eines KI-Blocks, gilt die Ausgabe genau dieses Blocks als untrusted — ein folgender Shell-Block bekommt `{{ previous_output }}`/`{{ block.<name>.output }}` also single-quoted. Umgesetzt über denselben Rückmeldekanal: `TemplateResolver.resolvePrompt` meldet die aufgelösten Platzhalter, `ExecutionContextTaint.substitutedIntoPrompt` merkt sich Plattform-Daten, und die nächste über `recordOutput` verbuchte Ausgabe konsumiert dieses Flag. Der **Prompt allein** färbt den Run nicht: Solange keine Ausgabe verbucht wurde, ist `hasUntrustedInput()` false und `exportTaint()` leer. Mit der verbuchten Ausgabe ist der Lauf dann aber getaintet, und zwar klebrig wie in jedem anderen Fall — `recordOutput` escapt ab da **jede** weitere Blockausgabe, `setDerivedVariable` markiert jede daraus abgeleitete Variable (`step_N_output`, foreach-Items, `document`-Extrakte) als untrusted, und `exportTaint()` reicht beides an Sub-Workflows und an ein Resume weiter. Ein späterer KI-Block mit sauberem Prompt bekommt also keine saubere Ausgabe zurück — das gilt nur, wenn vorher nichts Gefärbtes verbucht wurde. Auf dem Prompt-Pfad liegen genau die KI-Aufrufe: `simple`, `skill-capability`, `agentic` (`goal`/`success_criteria`) und die Nicht-Shell-Zweige von `foreach`, `while`, `conditional`, `retry` und `switch`; Nicht-Prompt-Auflösungen (Mounts, Dokumentpfade, TODO-Felder, Integrations-Parameter, `switch`-Ausdruck) bleiben auf `resolveTemplate` und melden weiterhin nichts.
- **Bewusst trusted:** Antworten aus `approval`- und `human-input`-Blöcken kommen über die API-Key-geschützte REST-Schnittstelle von der Person, der die Instanz gehört — sie werden nicht escaped. Ebenso überschreibt `setVariable` (etwa eine Workflow-Variable, die einen Webhook-Wert gleichen Namens überdeckt) die Untrusted-Markierung absichtlich: Der Wert kommt dann aus der Definition, nicht vom Aufrufer. `replaceValue` (Workspace-Pfad-Remapping) tauscht dagegen nur den Wert und lässt die Klasse stehen.
- **Bekannte Grenzen:**
  1. In KI-Prompts wird nichts escaped — ein Prompt ist keine Kommandozeile, Prompt-Injection wehrt das Trust-Modell nicht ab.
  2. Prompt-Injection über die KI-Antwort selbst bleibt möglich: Gequotet wird die Ausgabe, ihr *Inhalt* stammt weiterhin aus einem Modell, das Plattform-Daten gelesen hat. Das Quoting verhindert Shell-Injection, keine inhaltliche Manipulation.
  3. Ein Shell-Block, der einen getainteten Wert selbst in eine Datei schreibt und diese später wieder ausführt, liegt außerhalb dessen, was das Quoting sehen kann.

  Der Weg Plattform-Daten → KI-Prompt → Shell-Block war bis TODO 22e6bec8 die vierte Grenze; er ist seit der Färbung der KI-Ausgabe geschlossen. **Empfehlung bleibt: In jedem Shell-Block, der die Ausgabe eines KI-Blocks weiterverarbeitet, `"shell_escape_variables": true` setzen** — dann verhält sich der Block in gefärbten wie in sauberen Läufen gleich. Für die ausgelieferten Definitionen erzwingt das `PlatformPromptQuotingGateTest`.

**Webhook-Signaturen** — `POST /api/v1/webhooks/{id}/secret` erzeugt ein Secret (32 Zufallsbytes, hex), zeigt es genau einmal und speichert es AES-GCM-verschlüsselt (`IntegrationEncryptionService`, Schlüssel außerhalb der Datenbank) in `webhook_triggers.signing_secret_enc`; `DELETE …/secret` entfernt es. Hat ein Webhook ein Secret, prüft `WebhookSignatureVerifier` vor dem JSON-Parsen den Header `X-Signature: sha256=<hex>` (alternativ `X-Hub-Signature-256`, das Format von GitHub und Gitea) als HMAC-SHA256 über die rohen Request-Bytes, Vergleich in konstanter Zeit — sonst **401 `WEBHOOK_SIGNATURE_INVALID`**. Wie beim Siegel bleibt ein gespeichertes Secret wirksam, wenn der Schalter später aus ist. Responses verraten nur `signatureRequired`.

## Backend-Architektur

### Schichtenmodell

```
Controller (REST API, 51 Controller)
    ↓ DTO (Request/Response Records)
Service (Geschäftslogik)
    ↓ Entity (JPA)
Repository (Spring Data JPA)
    ↓ SQL
SQLite (Datei: data/automation.db)
```

### Datenbank-Wartung (SQLite, pool-size=1)

- **Run-Retention** (`RunCleanupService.cleanupTerminalRuns`, täglich 03:15): löscht abgeschlossene Runs (COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED) samt `step_results`, die älter als `run-retention-days` sind — älteste zuerst, höchstens `run-retention-max-per-night` pro Nacht, jeder Run in einer eigenen kurzen Transaktion über `RunDeletionService` (räumt DLQ-, TODO- und Wish-Verweise mit). Runs mit Audit-Report bleiben (Query-Ausschluss plus Guard im Service). Runtime-Settings in `ExecutionSettingsService`: `run-retention-days` [0,730] (0 = aus, Default 90), `run-retention-max-per-night` [10,5000] (Default 200). INTERRUPTED-Runs haben ihre eigene, ältere Regel (`cleanupInterruptedRuns`, 03:00).
- **Run-Löschung per API abschaltbar** (`RunDeletionPolicy`): `automation.executions.allow-delete: false` (Startkonfiguration, Default `true`) lässt `DELETE /api/v1/executions/{id}` mit `403 RUN_DELETION_DISABLED` scheitern und blendet die Löschknöpfe aus (`runDeletionAllowed` in `/system/capabilities`). Bewusst ohne Laufzeitschalter. Die beiden Aufräumjobs oben sind Betrieb und laufen weiter; `run-retention-days` bleibt ein Runtime-Setting, lässt sich bei abgeschalteter Löschung zur Laufzeit aber nur noch **verlängern** (`0` = aus zählt als längste); eine kürzere Retention scheitert mit `403 RUN_DELETION_DISABLED`, damit ein geleakter Key die Runs nicht über Nacht löschen lassen kann.
- **Backup** (`DatabaseBackupService`, täglich 02:00, `automation.backup.retain-count`): läuft bewusst vor der Retention.
- **VACUUM**: `PRAGMA auto_vacuum = INCREMENTAL` ist das **einzige** Statement im Hikari-Init-SQL — sqlite-jdbc führt von einem mehrteiligen Init-String nur das erste aus (`foreign_keys`/`busy_timeout` stehen deshalb in der JDBC-URL, Guard: `DataSourceConfigGuardTest`). Das Pragma greift nur bei leerer DB oder nach einem vollen `VACUUM`. Der Startup-Check `checkAutoVacuumMode` schreibt eine ActivityLog-WARNING, wenn die Datei noch auf NONE steht — dann einmal `POST /api/v1/system/vacuum` ausführen, danach gibt das nächtliche `incremental_vacuum` (03:00) Platz frei. Direkter `sqlite3`-Zugriff ist tabu (Hibernate-Cache, Single-Writer).

### Packages

```
com.automation
├── controller/          51 REST-Controller
├── service/
│   ├── project/         ProjectService, ProjectStatusReconciler, ContainerService (+ DockerContainerGateway, DockerOrphanReconciler), ProjectContextService, PathValidator, OnboardingProfileService (+ OnboardingProfileSeeder), ProjectOnboardingService
│   ├── workflow/        WorkflowExecutor (+ ExecutionContextFactory, BlockSkillContext, BlockStepRunner, RunCompletionHandler, RunFinalizer, WorkflowContextDataService), RunControlService, WorkflowService, ExecutionBroadcaster, TemplateVerificationService, WorkflowOptimizationService (+ WorkflowOptimizationPrompt), GhostRunWatchdogService, RunCleanupService, AgentInstructionsService, WorkflowTemplateService, BlockOutputValidator, WorkflowExecutionRegistry, IdleContainerReaper, WorkflowTaggingService, WorkflowVersionService, WorkflowContentChangedEvent
│   ├── skill/           SkillService, SkillImportExportService, SkillRegistry, CapabilityRegistry
│   ├── scheduler/       SchedulerService (+ SchedulerTick, SchedulerMaintenanceMode, UpcomingRunsCalculator, ScheduleSkipPolicy, SchedulerPause, ScheduleDefinitionParser, CatchUpQueue), ScheduledWorkflowExecutor, SuspendState
│   ├── webhook/         WebhookService, GitWebhookPayloadParser
│   ├── learning/        LearningService, AgenticLearningService, ExecutionInsightsService, WorkflowStatsService, WorkflowSimilarityService, BlockTypesBackfillService, MetadataExtractor, PlatformStatsService
│   ├── system/          ApiKeyService, DatabaseBackupService, ExecutionSettingsService (+ ExecutionSettingKeys; Setter je Thema in `settings/`: ExecutionCapacitySettings, AccessLimitSettings, HousekeepingSettings, FeatureSettings, RequestMetricsSettings), SystemIdentityService, TailscaleService, FileBrowserService, IntegrationSnippetService, SystemSettingsService (+ AppSettingChangedEvent: Dienste mit gecachter Einstellung lesen sie darauf neu, statt direkt gerufen zu werden)
│   ├── ai/              HostClaudeService, HostCodexService, HostPiService, AiProviderRegistry, AiProvider, AiProviderCapabilities, AiResponseMetadata, ClaudeAiProvider, CodexAiProvider, PiAiProvider, PiCatalogService
│   ├── cli/             CommandExecutorService (+ HostRequestAiMetadata), CliSessionManager, CliSession, ClaudeOutputParser, ClaudeJsonResponse, HostExecutionRequest, ShellCommandService, ContainerShellService, TaskCommandService
│   ├── chain/           ChainService, ChainExecutor (+ ChainRunWaiter, ChainZombieRunCleaner), ChainTemplateService, BuiltInChainTemplate
│   ├── memory/          WorkflowMemoryService, ClaudeSummarizer, CodexSummarizer, KnowledgeSummarizer, ConversationPreprocessor, SummarizationSetupService, AutoMemoryExtractionListener
│   ├── staging/         StagingService, TestingService, InstanceSyncHelper (+ InstanceHttpClient, SyncPreviewService), DockerContainerManager
│   ├── todo/            TodoService (+ TodoSpecifications, TodoTrackerExportService, TodoResolutionService, TodoResponseAssembler), AutoTodoService (+ AutoTodoTextBuilder, PendingTodoApprovalService, GranularTodoService), GateStatusResolver, ProjectPathResolver, TodoGhostWatchdogService, TodoHygieneService, TodoOverviewService, TodoResolutionListener, TodoSimilarityService
│   ├── runoutput/       GateStatusOutputParser, GateExpectation, TodoOutputParser — lesen Marker aus Blockausgaben (Blatt-Paket für workflow/ und executor/, damit workflow/ nicht auf todo/ zurückzeigt)
│   ├── conversion/      WorkflowConversionService (+ WorkflowConversionPrompt), WorkflowSuggestion, DialogWorkflowSuggestion
│   ├── dialog/          DialogSessionScanner, DialogConversionService (+ DialogConversionPrompt), ClaudeJsonlParser
│   ├── factory/         WorkflowFactoryService (+ FactoryPromptBuilder, FactoryResponseParser, FactoryTopicCatalog, FactoryGeneratorResolver), FactoryVerificationService, ClaudeWorkflowGenerator, CodexWorkflowGenerator, WorkflowTextGenerator, FactorySetupService
│   ├── pool/            WorkflowPoolService
│   ├── wish/            WishService (+ WishFulfillmentLoop, WishEvaluationService), WishWatchdogService, WishDecomposeService, WishDecomposePromptBuilder (+ WishSystemPrompt), WishExecutionService, WishEvaluatePromptBuilder, WishPromptBuilder, WishPromptHelper, WishJsonUtil, WishStatusHelper, WishStringUtil
│   ├── notification/    NotificationService
│   ├── trigger/         CompletionTriggerService, TodoTriggerService, TodoFeedbackListener
│   ├── health/          HealthAutoTodoService
│   ├── audit/           AuditResultParser, AuditIngestionService (+ AuditSnapshotApplier), AuditIngestionListener, AuditCenterService (+ AuditFindingTodoLinker)
│   ├── integration/     IntegrationRegistry, IntegrationOAuthService, IntegrationEncryptionService, IntegrationProvider, OAuthConfig, TaskExportCapable
│   ├── integration/providers/ 18 Provider in Unterverzeichnissen (caldav/, confluence/, feed/, gitea/, github/, gitlab/, google/, httpapi/, icalfeed/, jira/, ntfy/, openmeteo/, slack/, taskwarrior/, telegram/, todoist/, trello/; gemeinsam genutzt: ical/)
│   ├── assistant/       AssistantContextBuilder (+ PageContextRenderer, ProjectFileListing), AssistantChatCaller, AssistantChatBroadcaster, AssistantFetchService, AssistantToolExecutor (+ AssistantTodoTools, AssistantWorkflowTools, AssistantChainTools, AssistantToolPayloads), AssistantActionSuggestionService, AssistantErrorClassifier, AssistantFetchPolicy, AssistantMutationGate, AssistantActionMerger, AssistantResponseParser, PendingToolRegistry, ToolExecutionResult
│   ├── assistant/context/ AssistantContextSection, BlockTypesIndexSection, ChainsIndexSection, FailedWorkflowsContextSection, IntegrationsIndexSection, RunsContextSection, SchedulesContextSection, SkillsIndexSection, TodosContextSection, WishesContextSection
│   ├── assistant/format/  AssistantFormatUtils (Formatier-Helfer für Prompt-Bausteine; eigenes Blatt-Paket, damit context/ und fetch/ nicht auf assistant/ zurückzeigen)
│   ├── execution/       ExecutionQueryService (Lesen + DTO-Mapping), ExecutionCommandService (Retry, Resume, Notizen), WorkflowDebugService
│   ├── dlq/             DeadLetterService, DeadLetterRetryService
│   ├── document/        DocumentExtractionService, DocumentExtractionException
│   ├── export/bpmn/     BpmnModelBuilder (Kette/Einzel-Task/Schleife) + BpmnNodeFactory (Ids, Platzierung, Kanten, Command-Tasks), BpmnGatewayBuilder (conditional/switch: Split/Rows/Merge), BpmnEdgeRouter (rechtwinkliges Waypoint-Routing), DataFlowReferenceScanner (Platzhalter-Scan `block.x.output`/`previous_output`/`step_N_output`/compress-`source`) + DataFlowConfigStrings (Ebenen-Regel: direkte vs. verschachtelte Config-Werte) + BpmnBranchBlocks (Blöcke und Id-Pfade der switch-Zweige) + BpmnDataFlowBuilder (Datenobjekte und -assoziationen unter der Task-Reihe, Endpunkt ist der Task — bei Referenzen aus einem Zweig der Zweig-Task, nie das Gateway; Import überliest sie), BpmnBlockText (Labels, Doku, Config-Helfer), BpmnSegment (Teil-Graph), BpmnXmlRenderer, BpmnExportAdapter, BpmnModel, BpmnNames; Import (Roundtrip): BpmnImportAdapter (liest `mysuricates:blockConfig` der direkten `<process>`-Kinder zurück, lehnt BPMN ohne diese Erweiterung ab — Formatfilter, kein Herkunftsbeleg), BpmnDom (XXE-gehärtetes JDK-DOM-Parsen), BpmnImportException → 400; Interfaces `export/ProcessFormatAdapter` (Export) + `export/ProcessFormatImporter` (Import, Ergebnis `ImportedWorkflow`)
│   ├── search/          SemanticWorkflowSearchService
│   ├── watchdog/        ConnectionPoolWatchdogService (+ ThreadDumpCollector)
│   ├── repair/          AutoRepairService
│   ├── tray/            SystemTrayService
│   ├── activitylog/     ActivityLogService, ActivityLogLifecycleListener
│   ├── AssistantService.java        Chat-Assistent (Orchestrierung; CLI-Aufruf in assistant/AssistantChatCaller)
│   ├── AssistantSessionService.java Session-Persistierung
│   ├── AssistantSettingsListener.java gecachte Assistenten-Einstellungen nachziehen (nur bei eingeschaltetem Modul)
│   └── PageSuggestionService.java   Kontext-sensitive UI-Vorschläge
├── entity/              JPA-Entities + Enums
├── dto/                 Assistenten-DTOs direkt im Paket (AssistantChatRequest/-Response, PendingTool, …)
│   ├── request/         Request-Records
│   └── response/        Response-Records
├── repository/          Spring Data Repositories
├── mapper/              MapStruct-Mapper (Entity ↔ DTO)
├── executor/            Workflow-Ausführungs-Engine
│   └── blocks/          20 Block-Executors + Agentic-Hilfsklassen (AgenticBlockConfig, AgenticPlan, AgenticSafetyGuard, AgenticTokenTracker, AgenticPromptBuilder, AgenticEventBroadcaster, AgenticApprovalGate, AgenticActionRunner, AgenticStopRules)
├── concurrency/         DynamicSemaphore
├── config/              Konfigurationsklassen, Seeder (Skill, Template)
├── security/            ApiKeyAuthFilter, RateLimitFilter (+ RateLimitConfig), ActivityLogSizeLimitFilter, WebhookRateLimiter, WebSocketAuthInterceptor, WebSocketTicketService
├── skills/handlers/     CapabilityHandler, LintHandler, SecurityScanHandler, FactCheckHandler, GrammarCheckHandler, OCRHandler
├── exception/           GlobalExceptionHandler, ErrorCode + Exception-Klassen
├── websocket/           LogWebSocketHandler, TerminalWebSocketHandler, TerminalSession (+ TerminalStdinStream), Message-DTOs
├── event/               WorkflowRunCompletedEvent, AssistantSessionUpdatedEvent, TodoCreatedEvent
└── util/                JPA-Converter (JSON, BlockDefinitions), RuntimeClasspathGuard (Startsperre gegen `target/classes`)
```

### Alle 51 REST-Controller

| Controller | Base-Path | Beschreibung |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | CRUD, Archivierung, Container-Lifecycle, Kontext-Generierung |
| `WorkflowController` | `/api/v1/workflows` | CRUD, Ausführung, Runs, Export/Import, Skills |
| `WorkflowVersionController` | `/api/v1/workflows` | `/{id}/versions` — Versionsliste, einzelne Version, Restore |
| `WorkflowAssistController` | `/api/v1/workflows` | `/{id}/optimize`, `/{id}/similar`, `/{id}/repair` (Repair nur mit Modul `factory`) |
| `WorkflowAnalyzerController` | `/api/v1/workflows` | `/{id}/analyze` und `/analyze` — deterministische Baufehler-Prüfung einer Definition bzw. des Bestands. Meldet nur, greift in keinen Schreibweg ein (182bd224) |
| `WorkflowImportController` | `/api/v1/workflows/import` | BPMN-Import (`POST /bpmn?projectId=…`, Body = BPMN-XML) → neuer Workflow (201); BPMN ohne `mysuricates`-Erweiterung/kaputtes XML/zu groß → 400. Der Marker sagt nur, dass sich Blöcke lesen lassen, nicht woher die Datei stammt |
| `ExecutionController` | `/api/v1/executions` | Liste, Detail, Pause, Resume, Resume-from-Step, Cancel, Retry, Approve, Approval-Check, Todos-Approve, Notes, Command |
| `SkillController` | `/api/v1/skills` | CRUD, Capabilities, Export/Import |
| `ScheduleController` | `/api/v1/schedules` | CRUD, Enable/Disable, Runs, Calendar, Preview, Remaining-Stats, Chain-Schedules, Maintenance |
| `WebhookController` | `/api/v1/webhooks` | CRUD, Enable/Disable; Trigger via `/api/v1/webhook/trigger/{token}` (öffentlich, nur Token-geschützt — Payload-Felder werden als `__untrusted_keys` markiert und in Shell-Blöcken immer escaped; Block-Ausgaben solcher Runs erben den Taint über `__untrusted_outputs` (auch in Sub-Workflows und beim Resume), siehe `ExecutionContext.recordOutput`/`resolveShellTemplate`) |
| `ChainController` | `/api/v1/chains` | CRUD, Ausführung, Runs, Cancel |
| `ChainTemplateController` | `/api/v1/chain-templates` | Liste, Abrufen, Installieren als Chain |
| `WorkflowTemplateController` | `/api/v1/workflow-templates` | CRUD, Aus-Workflow erstellen |
| `WorkflowMemoryController` | `/api/v1/workflows/{id}/memory` | CRUD, Extrahieren, Aktivieren |
| `WorkflowFactoryController` | `/api/v1/factory` | Generate, Run, Verify, Promote, Topics |
| `WorkflowPoolController` | `/api/v1/pool` | Liste, Abrufen, Installieren, Export |
| `ConversionController` | `/api/v1/conversions` | Suggest, Create-Workflow aus Run |
| `DialogController` | `/api/v1/dialog` | Sessions, Projekte, Claude-Session-Analyse, Workflow-Erstellung |
| `LearningController` | `/api/v1/learning` | Dashboard, Stats, Step-Analytics, Suggestions, Similar, Agentic-History, Agentic-Analytics, Tool-Patterns, Execution-Insights, Run-Insights, Rate |
| `TodoController` | `/api/v1/todos` | CRUD, Status-Patch, Resolve, Export, Count, Overview (Cockpit), Bulk-Status, Archive-Stale, Similar |
| `ActivityLogController` | `/api/v1/activity-log` | Liste, Erstellen |
| `AssistantController` | `/api/v1/assistant` | Chat (`POST /chat`), Sessions, Pending-Tool-Bestätigung/Ablehnung |
| `PageSuggestionController` | `/api/v1/assistant` | Suggestions per Page (Get/Refresh) |
| `DeadLetterController` | `/api/v1/dlq` | Dead-Letter-Queue: Liste, Retry, Löschen |
| `SearchController` | `/api/v1/search` | Aggregierte Suche über Workflows (Substring/Fuzzy + Semantic Ranking) |
| `WorkflowDebugController` | `/api/v1/debug` | Debug-Seite: Workflows mit fehlgeschlagenem letztem Run |
| `CompletionTriggerController` | `/api/v1/completion-triggers` | CRUD, Enable/Disable, Filter by Workflow |
| `TodoTriggerController` | `/api/v1/todo-triggers` | CRUD, Enable/Disable, Filter by Target-Workflow |
| `NotificationController` | `/api/v1/notifications` | CRUD, Enable/Disable |
| `WishController` | `/api/v1/wishes` | CRUD, Cancel, Respond, Crystallize |
| `StagingController` | `/api/v1/staging` | Status, Start, Stop, Promote, Promote-Cancel, Sync-Preview, Sync-Definitions |
| `TestingController` | `/api/v1/testing` | Status, Start, Stop, Reset, Sync-Preview, Sync-Definitions, Report-Result, Pull-Changes |
| `SystemController` | `/api/v1/system` | Health, Auth-Status, Info, JVM-Memory, Settings, Execution-Config, AI-Providers, AI-Provider-Katalog, Capabilities, Backup, Backups, Remote-Access, Vacuum, Browse, Open-Path, Repair, Client-Errors, Integration-Snippet |
| `IntegrationController` | `/api/v1/integrations` | OAuth-Flow, Credentials, Actions, Provider-Liste, Task-Export-Ziele |
| `OnboardingProfileController` | `/api/v1/onboarding-profiles` | Onboarding-Profile CRUD, Reset, History |
| `ReportsController` | `/api/v1/reports` | Report-Generierung und -Abruf |
| `AuditController` | `/api/v1/audits`, `/api/v1/audit-*` | Audit-Übersicht, Report-Historie, Findings und TODO-Verknüpfungen |
| `DocsController` | `/api/v1/docs` | Markdown-Dokumentation lesen |
| `SystemSettingsController` | `/api/v1/system` | Laufzeit-Settings: App-Settings (`GET`/`PUT /settings/{key}`), Execution-Config |
| `SystemAiProviderController` | `/api/v1/system` | AI-Provider- und Capability-Auskunft: Provider-Liste, Capabilities, Pi-Katalog, Modelle je Provider (+ Refresh) |
| `SystemModulesController` | `/api/v1/system/modules` | Runtime-Modul-Umschalter für die Settings-UI (nur bei `automation.modules.ui-editable=true`, Härtung nie schaltbar) |
| `SystemAccessController` | `/api/v1/system` | Zugriffs-Oberfläche: Remote Access (Tailscale) Enable/Disable/Status, lokales Dateisystem (Browse, Open-Path, Integration-Snippet) |
| `SystemMaintenanceController` | `/api/v1/system` | Wartung: Auto-Repair, VACUUM, Datenbank-Backups (Liste, Erstellen, Download) |
| `DashboardLayoutController` | `/api/v1/dashboard/layouts` | Versionsverlauf des Dashboard-Layouts (Core-Feature, kein Modul-Gate) |
| `DashboardWorkflowController` | `/api/v1/dashboard/workflow-results` | Liefert die Ergebnisse aller Workflow-Widgets einer Ansicht in **einer** Anfrage (Marker `dashboard_widget`), damit das Dashboard nicht je Widget lädt |
| `DashboardReportAssistController` | `/api/v1/dashboard/report-assist` | Schlaegt im Editor per KI eine `dashboard-report`-Konfiguration aus einem vorhandenen Run vor (Modul `dashboard-report`; `POST`, weil der Aufruf Token kostet und kein Browser ihn vorladen darf) |
| `SystemRequestMetricsController` | `/api/v1/system/request-metrics` | Gemessene Kosten je Route: laufende Momentaufnahme (`GET`) und Tageswerte (`GET /daily`, optional je Route) |
| `WorkflowSealController` | `/api/v1/workflows/{id}` | Siegeln/Entsiegeln eines Workflows (nur bei `automation.hardening.sealing=true`; bestehende Siegel bleiben auch bei ausgeschaltetem Schalter wirksam) |
| `WorkflowSealStatusController` | `/api/v1/workflows/seal-status` | Siegelzustand aller versiegelten Workflows und abgelehnte Starts der letzten 7 Tage (nur lesend, Kern — auch bei ausgeschaltetem `sealing`) |
| `WebSocketTicketController` | `/api/v1/ws-ticket` | Vergibt kurzlebige Einmal-Tickets für den WebSocket-Handshake (kein Modul-Gate, gewöhnliches API-Rate-Limit gilt) |

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

**Steuerbarkeit ab Schritt 1:** Noch vor dem Submit registriert der `WorkflowExecutor` einen provisorischen `ExecutionContext` in der `WorkflowExecutionRegistry`. Ein Cancel greift damit auch, solange der Run noch auf einen Semaphore-Slot oder den Container-Start wartet — der Ausführungs-Thread übernimmt beim Tausch gegen den echten Context dessen Cancel-/Pause-Flag. Folge: `getActiveRunCount()` zählt auch wartende Runs, wodurch `GhostRunWatchdogService` den Permit eines startenden Runs nicht mehr als Leak freigibt. Pause, Resume, Cancel, Freigaben und Ad-hoc-Befehle laufen über `RunControlService` (aus dem `WorkflowExecutor` herausgelöst): Er arbeitet nur auf der Registry und startet selbst nichts — neue Runs und `resume-from-step` bleiben im `WorkflowExecutor` und damit hinter `RunAdmission`.

**Resume schneidet nach Blöcken, nicht nach Step-Indizes** (`ResumePlan`): `POST /executions/{id}/resume-from-step` setzt hinter dem letzten COMPLETED-Step an. Maßgeblich ist dessen **Position** in der Blockliste, nicht sein `stepIndex` — Wrapper-Blöcke (foreach, while, retry, conditional, switch) ziehen pro Item/Iteration einen weiteren Index, sodass der Index eines Blocks seiner Position vorauseilt. Grundlage ist die Zusage von `BlockStepRunner`: genau ein persistierter Step pro Top-Level-Block, in Blockreihenfolge (innere Items werden nie gespeichert). Die Steps des fortgesetzten Abschnitts werden über `ExecutionContext.startStepIndicesAt` oberhalb aller bereits persistierten Indizes weitergezählt, bleiben also eindeutig und sortierbar. Vorher wurde der frisch vergebene Index gegen den persistierten verglichen — nach einem Wrapper übersprang der Resume damit Blöcke, die nie gelaufen waren (TODO `1be423b4`).

**Block-Position je Step** (`metadata.blockIndex`): `BlockStepRunner.startStep` schreibt die Position des Blocks in der Top-Level-Liste in die Step-Metadaten, `runStep` hält sie gegen Executor-Metadaten fest. Das Ablauf-Diagramm der Run-Seite ordnet Steps darüber seinen Kästen (`b_<Position>`) zu (`latestStepByBlock` in `stepMetadata.ts`); Runs ohne den Schlüssel fallen auf die Position in der index-sortierten Liste zurück. Nach einem Resume steht der abgebrochene Versuch eines Blocks neben dem neuen — beide mit demselben `blockIndex`, der höhere `stepIndex` ist der aktuelle (TODO `dcba60da`).

**Container-Start/-Stop ohne Transaktion:** `ProjectService.startContainer`/`stopContainer` sind bewusst **nicht** `@Transactional`: der Docker-Aufruf liegt zwischen zwei kurzen `TransactionTemplate`-Bursts (STARTING → Docker außerhalb jeder Transaktion → RUNNING/ERROR), weil `ContainerService` dabei den Projekt-Monitor hält — eine umschließende Transaktion würde die einzige Connection über den ganzen 5–15-s-Docker-Start pinnen und einen parallelen Workflow-Run (der den Monitor zuerst nimmt) in den 120-s-Connection-Timeout laufen lassen (Lock-Order-Inversion Monitor ↔ Connection). Weil der STARTING-Burst bereits vor dem Docker-Start committet, holt `ProjectStatusReconciler` beim `ApplicationReadyEvent` alle Projekte ab, die ein Absturz in diesem Fenster in STARTING zurückgelassen hat (RUNNING-Container-Zeile ⇒ RUNNING, sonst STOPPED) — das Projekt-Gegenstück zu `RunControlService.cleanupZombieRuns`. `ProjectService.delete` folgt demselben Muster in umgekehrter Reihenfolge: zuerst der Archivierungs-Burst (`archivedAt` + ActivityLog), danach der Docker-Stop außerhalb jeder Transaktion — schlägt er fehl, bleibt das Projekt archiviert (ERROR-Log), und nur der verwaiste Container bleibt zurück, den `DockerOrphanReconciler`/Idle-Cleanup einsammeln.

Dasselbe Muster gilt für `WorkflowMemoryService.extractKnowledge`/`extractAndActivate` (Security-Finding 4, analog zu `ebd508c5`): der KI-Summarization-Call (`ClaudeSummarizer`/`CodexSummarizer`, Sekunden bis Minuten) läuft außerhalb jeder Transaktion, zwischen einem lesenden `TransactionTemplate`-Burst (Runs laden, lazy `steps`-Collection in eine neue Liste materialisieren — OSIV ist aus —, Run-IDs sammeln) und einem schreibenden Burst (`WorkflowMemory` speichern bzw. `deactivateAllForWorkflow` + Aktivierung). `activate`/`update`/`delete` bleiben kurze `@Transactional`-Methoden.

**Integration trennen ohne Transaktion:** `IntegrationOAuthService.disconnect`/`deleteCredentials` folgen demselben Burst-Muster: kurzer `TransactionTemplate`-Burst (Token-Felder leeren bzw. Zeile löschen) → Token-Revocation beim Provider außerhalb jeder Transaktion. Der HTTP-Aufruf hat 10 s Timeout und würde in einer `@Transactional`-Methode die einzige Connection so lange halten; „persist first" verhindert zusätzlich den Zustand „beim Provider widerrufen, lokal aber noch `CONNECTED`".

**Integration verbinden ohne Transaktion:** `saveCredentials` und `handleCallback` liefen bis 2026-09-13 als `@Transactional`-Methoden und riefen darin Provider-HTTP (PAT-Validierung `fetchAccountInfo`/`fetchGrantedScopes` bzw. Code-Tausch `exchangeCodeForTokens` + Account-Info, je 10 s Timeout). Die Ablauflogik steckt jetzt in `IntegrationOAuthConnectFlow` (eigene Klasse wegen der Ratchet-Decke): kurzer lesender `TransactionTemplate`-Burst (Client-ID/-Secret entschlüsseln) → Provider-HTTP außerhalb jeder Transaktion → kurzer schreibender Burst (Zeile laden-oder-anlegen, Tokens/Status setzen, speichern). `IntegrationOAuthService` delegiert nur noch; `IntegrationOAuthConnectIntegrationTest` beweist per `TransactionSynchronizationManager.isActualTransactionActive()` im gemockten Client/Provider, dass während der HTTP-Aufrufe keine Transaktion offen ist. Nebenwirkung des Musters: Schlägt die PAT-Validierung fehl, wird die Zeile trotzdem mit Credentials und Status `DISCONNECTED` gespeichert (wie zuvor, nur nicht mehr in derselben Transaktion wie der HTTP-Aufruf).

**Pool-Watchdog sieht virtuelle Threads:** `ConnectionPoolWatchdogService` loggt eine Pool-Erschöpfung samt gefiltertem Thread-Dump erst, wenn zwei aufeinanderfolgende Stichproben (15 s Abstand) Wartende zeigen – eine einzelne löste sich fast immer bis zur nächsten auf; die ERROR-Meldung nach 60 s zählt weiter ab der ersten Stichprobe. `Thread.getAllStackTraces()` kennt nur Plattform-Threads — Workflow-Runs laufen aber auf `virtual-*`-Threads, der Dump zeigte also nur den Wartenden, nie den Halter der einzigen Connection. `ThreadDumpCollector` nutzt stattdessen `HotSpotDiagnosticMXBean.dumpThreads(…, TEXT_PLAIN)` (JDK 21, keine Abhängigkeit): Dump in eine Temp-Datei unter `java.io.tmpdir` (sofort wieder gelöscht), parsen, auf Frames mit `com.automation`/Hibernate/SQLite/Hikari filtern, wahrscheinliche Halter (`org.sqlite.`-/Hikari-Proxy-Frames) zuerst, Wartende (`HikariPool.getConnection`) zuletzt, maximal 20 Threads à 30 Frames. Schlägt der HotSpot-Dump fehl, fällt der Collector auf `getAllStackTraces()` zurück (im Log als `source=…` erkennbar).

**Container-Zeile:** `ContainerService.createAndStartContainer` aktualisiert eine vorhandene `containers`-Zeile (Upsert) statt sie zu löschen und neu anzulegen — die Löschung wurde über die Cascade `Project.container` beim Flush wieder aufgehoben, sodass der folgende INSERT an `UNIQUE(containers.project_id)` scheiterte. Der Projekt-Lock bleibt dauerhaft in der Map (Entfernen unter Konkurrenz erzeugt einen zweiten Monitor), und `lastActivityAt` wird per JPQL-Update (`ContainerRepository.touchActivity`) statt per detached Merge gesetzt.

### Block-Typen

Vollständige Liste; die verbindliche Quelle ist die Registry `BlockTypesIndexSection.BLOCK_TYPES`, gegen die `BlockTypeListsGuardTest` die vorhandenen Executor-Klassen pinnt.

| Typ | Executor | Beschreibung |
|---|---|---|
| `simple` | `SimpleBlockExecutor` | Einzelner Claude-CLI-Aufruf mit Prompt |
| `shell` | `ShellBlockExecutor` | Shell-Skript, optional via Docker-Container |
| `retry` | `RetryBlockExecutor` | Prompt mit konfigurierbaren Wiederholungsversuchen |
| `conditional` | `ConditionalBlockExecutor` | If/Else-Verzweigung basierend auf Bedingung |
| `foreach` | `ForEachBlockExecutor` | Iteration über eine Liste von Elementen |
| `while` | `WhileBlockExecutor` | Wiederholung mit Abbruchbedingung und Max-Iterations |
| `switch` | `SwitchBlockExecutor` | Multi-Way-Branching per Expression mit Cases |
| `agentic` | `AgenticBlockExecutor` | Goal→Reason→Act→Observe Loop, bis zu 10 Iterationen |
| `approval` | `ApprovalBlockExecutor` | Hält Ausführung an, wartet auf Nutzer-Bestätigung |
| `sub-workflow` | `SubWorkflowBlockExecutor` | Führt einen anderen Workflow als Unterprozess aus |
| `skill-capability` | `SkillCapabilityBlockExecutor` | Ruft eine benannte Skill-Capability auf |
| `human-input` | `HumanInputBlockExecutor` | Sammelt Nutzereingabe via WebSocket, speichert als Kontext-Variable |
| `integration` | `IntegrationBlockExecutor` | Führt eine Aktion auf einem externen Integration-Provider aus |
| `document` | `DocumentBlockExecutor` | Extrahiert Text aus PDF/DOCX/XLSX/HTML/TXT via Apache Tika |
| `compress` | `CompressBlockExecutor` | Regelbasierte Komprimierung einer früheren Blockausgabe (Stufenkette, 0 Tokens) |
| `compress-ai` | `CompressAiBlockExecutor` | Werkzeuglose KI-Komprimierung einer früheren Blockausgabe mit Ein-/Ausgabegrenze |
| `todo-create` | `TodoCreateBlockExecutor` | Legt ein TODO an, direkt über den Service statt über die REST-API |
| `todo-update` | `TodoUpdateBlockExecutor` | Ändert ein bestehendes TODO |
| `todo-close` | `TodoCloseBlockExecutor` | Setzt ein TODO auf DONE |
| `dashboard-report` | `DashboardReportBlockExecutor` | Schreibt Zahlen und Status eines Laufs als Dashboard-Widget |

**Template-Variablen** (`{{ variable }}`, `${varName}`) werden zur Laufzeit im `ExecutionContext` aufgelöst. `{{ previous_output }}` enthält die Ausgabe des vorherigen Blocks.

### Agentic Block (Goal→Reason→Act→Observe)

Der Agentic-Block implementiert eine autonome Ausführungsschleife:

1. **REASON:** Claude analysiert Ziel und bisherige Beobachtungen → liefert strukturierte JSON-Aktion
2. **ACT:** Aktion wird ausgeführt (Shell oder Claude-CLI)
3. **OBSERVE:** Ausgabe wird gesammelt und der History hinzugefügt
4. Schleife läuft bis `done: true` oder Abbruchbedingungen

**Safety-Mechanismen:**

| Config | Default | Beschreibung |
|---|---|---|
| `max_iterations` | 10 | Maximale Iterations-Zahl |
| `iteration_timeout_seconds` | 300 | Wall-Clock-Timeout pro Iteration |
| `total_timeout_seconds` | 1800 | Gesamt-Block-Timeout (30 Min.) |
| `max_total_tokens` | 200000 | Token-Budget (Input+Output, alle Phasen) |
| `max_repeated_actions` | 3 | Abbruch nach N identischen Aktionen |
| `require_approval` | false | Approval-Gate vor jeder Aktion |

Zusätzlich: History-Kompression nach 7 Turns, Action-Kompression bei >100.000 Zeichen, Token-Tracking-Warnungen im Log. Alle 6 Safety-Felder werden im Frontend-"Block hinzufügen"-Dialog als Defaults vorbelegt. Hilfsklassen: `AgenticBlockConfig`, `AgenticSafetyGuard`, `AgenticTokenTracker`, `AgenticPlan`.

### Switch Block

Der Switch-Block evaluiert einen Ausdruck gegen mehrere Cases:
- Unterstützt `${varName}` und `{{ varName }}` für Variablen-Interpolation
- Cases werden in Reihenfolge geprüft — erster Treffer gewinnt
- Pipe-getrennte Alternativen: `"error|critical"` matched beide
- `default`-Case, wenn kein anderer Case zutrifft
- Jeder Case enthält eine Liste von Nested-Blocks (shell oder claude)
- Gibt `COMPLETED` mit `matchedCase: "none"` zurück, wenn kein Treffer und kein Default

### Sub-Workflow Block

- Führt einen anderen Workflow als verschachtelten Block aus
- Zirkuläre Referenzen werden via `__parent_workflow_ids` erkannt
- Maximale Verschachtelungstiefe: 5 Ebenen
- Polling-basierter Abschluss, 30-Minuten-Timeout
- `@Lazy`-Injection auf `WorkflowExecutor` nur dort, wo ein Aufrufer selbst Runs startet (Sub-Workflow, Chains, Trigger); reine Status-Abfragen (aktive Runs, Container-IDs, Approvals) gehen direkt an `WorkflowExecutionRegistry`, das Steuern eines laufenden Runs an `RunControlService`, der den `WorkflowExecutor` nicht braucht

### Human-Input Block

- Pausiert die Workflow-Ausführung und wartet auf Nutzereingabe
- Sendet `human_input_required`-Event via WebSocket an `/topic/executions/{runId}/logs`
- Nutzerantwort wird als Kontext-Variable gespeichert (`variable_name`)
- Unterstützt Text- und Select-Eingaben, Validierungsmuster, Timeout mit optionalem Default
- Nutzt den `ApprovalRequest`-Mechanismus des Approval-Blocks

## Gate Status System

Workflow-Blöcke können Quality-Gate-Marker in ihre Ausgabe schreiben:

```
gate_status: GREEN|YELLOW|RED
gate_category: <kategorie>
gate_description: <beschreibung>
```

**Verarbeitung:**
- `GateStatusOutputParser` — parst Marker aus Block-Ausgaben (tolerant gegenüber Markdown-Formatierung)
- `RunCompletionHandler` scannt alle Block-Ausgaben — höchste Schwere gewinnt (GREEN < YELLOW < RED)
- Gate-Status wird in `WorkflowRun.metadata` gespeichert (`gate_status`, `gate_category`, `gate_description`)

**Ausbleibendes Urteil ist kein Erfolg** (TODO 63ecc124). Gates bleiben freiwillig — aber ein
Workflow, der eines *bestellt*, muss eines liefern. `GateExpectation` sucht dafür in den
Block-Configs (verschachtelte eingeschlossen) nach demselben `gate_status`-Schlüssel, den der
Parser aus der Ausgabe liest: steht er in der Definition, erwartet der Lauf ein Urteil. Fehlt es
danach, setzt `RunCompletionHandler` **YELLOW** mit Kategorie `gate-missing` statt stillschweigend
GREEN — nur bei erfolgreichem Lauf, ein `FAILED` trägt sein Urteil schon über den Laufstatus.
Kein Workflow-Feld, keine Migration: Wer keinen Marker bestellt, merkt nichts davon.
Ein **unlesbarer** `gate_status` fällt ebenfalls nicht mehr stumm durch (`GateStatusResolver`):
Er wird geloggt und zu YELLOW `gate-invalid` — verschärfen darf er, mildern nie (ein `FAILED`
bleibt RED). Hintergrund: Ein Prüf-Workflow, dessen KI-Schritt still fehlschlägt, war zuvor von
einem Workflow ohne Gate nicht zu unterscheiden — beide wurden GREEN.

**Ein Gate ändert den Run-Status nicht.** Weder YELLOW noch RED: Das Gate ist ein Befund über
das Ergebnis, kein Fehlschlag der Ausführung. RED erzeugt über `AutoTodoService` ein TODO und
eine Warnung im Aktivitätslog, YELLOW nur die Warnung — der Lauf bleibt `COMPLETED`.

Den Run-Status bestimmt etwas Unabhängiges: `RunCompletionHandler` setzt
`COMPLETED_WITH_ERRORS`, wenn ein Schritt mit `checkedCommand`-Metadatum fehlgeschlagen ist,
sonst `COMPLETED`. Beides kann zusammentreffen — `RunCompletionHandlerTest` hält genau den Fall
fest: ein fehlgeschlagener `checked_command` ergibt `COMPLETED_WITH_ERRORS` **und**
`gate_status: RED`.

Die Richtung, die es wirklich gibt, ist die umgekehrte: `GateStatusResolver` leitet ein
Gate-Urteil aus dem Run-Status ab, wenn kein Marker geschrieben wurde (`FAILED` → RED,
`COMPLETED` → GREEN, `COMPLETED_WITH_ERRORS` → YELLOW).

Ein Shell-Block muss diese Marker nicht selbst schreiben: `checked_command`/`check_category` leiten
das Gate automatisch aus dem Exit-Code ab, mit Vorrang für einen eigenen (mindestens gleich
strengen) Marker des Skripts — Details und Beispiele:
[WORKFLOW-GUIDE.md → `checked_command` / `check_category`](WORKFLOW-GUIDE.md#checked_command--check_category--gate-automatisch-aus-dem-exit-code).

**AutoTodoService:**
- Reagiert auf `WorkflowRunCompletedEvent`
- RED-Gate → TODO mit `HIGH`-Priorität
- YELLOW-Gate → TODO mit `MEDIUM`-Priorität
- Source: `WORKFLOW`, verknüpft mit dem Run

## Workflow Chains

Chains gruppieren mehrere Workflows für sequenzielle Ausführung:

- **Projektübergreifend:** Eine Chain gehört keinem Projekt und kann Workflows aus verschiedenen Projekten referenzieren
- **Sequenziell:** `ChainExecutor` führt Entries in `sortOrder`-Reihenfolge aus (virtual threads, polling alle 2s, 180-Min.-Timeout)
- **Kontext-Weitergabe:** Jeder Entry bekommt Chain-Level-Kontext + Entry-Level-Kontext + `previous_chain_output` vom letzten abgeschlossenen Workflow
- **Stop-on-Failure:** Flag auf Chain- und Entry-Ebene — Chain stoppt nur wenn beide `true` sind
- **Cancellation:** Via `ConcurrentHashMap<String, AtomicBoolean>`
- **WebSocket:** Status-Broadcasts an `/topic/chains/{chainRunId}/status`

### Chain Templates

Vorgefertigte Chain-Definitionen, die sich aus bestehenden Workflow-Templates zusammensetzen. Jeder Eintrag muss ein ausgeliefertes Built-in-Template nennen (`ChainTemplateServiceTest`), eine Kette über Pool-Einträge gibt es nicht:
- `ChainTemplateService` — verwaltet Built-in Chain Templates (`BuiltInChainTemplate` Records)
- `ChainTemplateController` — `/api/v1/chain-templates` mit List, Get, Install-Endpunkten
- Install: Erstellt eine neue Chain (ggf. mit zugehörigen Workflows) in einem Zielprojekt

## Scheduler: verpasste Termine (Standby und Kaltstart)

Der Scheduler ist ein 60-s-Tick im Backend (`SchedulerTick`); ohne laufendes Backend startet nichts. Für Termine, die während einer Lücke fällig wurden, gilt seit 2026-09-14 **eine** Regel, unabhängig davon, ob die JVM eingefroren war (Standby, erkannt als Tick-Lücke > `automation.scheduler.resume-grace-minutes`) oder der Prozess gar nicht lief (Kaltstart, erkannt am ersten Tick; Referenz ist die Startzeit des Backends, Termine danach sind normale Fälligkeit):

- **Ohne Opt-in** wird der Termin übersprungen und der Zeitplan auf sein nächstes Vorkommen gesetzt (`ScheduleSkipPolicy`, Activity-Log „Schedule skipped (system suspend/resume)" bzw. „(backend was stopped)").
- **Mit Opt-in** — pro Zeitplan `catchUpOnResume` oder global `scheduler-catch-up-on-resume` (Settings-UI) — wandert der Termin in die `CatchUpQueue`. Sie startet die Einträge **nacheinander** in der Reihenfolge der ursprünglichen Fälligkeit: der nächste erst, wenn der vorherige Nachhol-Run einen Endzustand erreicht hat (`ScheduledWorkflowExecutor.isFinished`). Wartende Einträge behalten ihren alten `nextRunAt`, werden vom normalen Tick aber ausgeklammert. Ein Eintrag, den der Executor ablehnt (Ziel läuft bereits, Zeitplan inzwischen deaktiviert), blockiert die Warteschlange nicht.
- Eine aktive `skip-until`-Pause gewinnt: dann wird auch mit Opt-in nichts nachgeholt.
- Die Warteschlange ist bewusst nicht persistent: nach einem Neustart sind dieselben Termine wieder fällig und werden vom Kaltstart-Pfad erneut eingereiht — nichts geht verloren, nichts läuft doppelt.
- Sichtbarkeit: eine WARN-Zeile im Activity-Log pro Ereignis („Cold start detected … N missed schedule(s): X skipped forward, Y queued for sequential catch-up", bei Fehlern „…, Z failed"), `GET /api/v1/schedules/catch-up` (laufender und wartende Einträge) und ein Banner auf der Schedules-Seite.
- `resume-grace-minutes: 0` schaltet beide Erkennungen ab (Altverhalten: alles Fällige startet sofort).

Weitere Regeln des Ticks:

- **Ein Fehler betrifft nur seinen Zeitplan** (TODO 68a3d91e): Jeder Zeitplan eines Ticks wird für sich gestartet, übersprungen oder eingereiht (`SchedulerTick.runIsolated`). Scheitert einer — etwa an einem Cron-Ausdruck, der nicht mehr parst —, steht „Schedule execution failed: …" bzw. „Schedule skip failed: …" im Activity-Log, und der Tick macht mit dem nächsten weiter. Bis 2026-09-24 beendete die Ausnahme beim Überspringen (`skip-until`) die Schleife: Die übrigen fälligen Zeitpläne des Ticks wurden weder gestartet noch übersprungen.
- **Konflikt `SKIP`** (Standard): Hat das Ziel beim Fälligwerden noch einen Lauf in `RUNNING` oder `PAUSED`, entfällt der Termin — `nextRunAt` springt auf das nächste Vorkommen, `ONCE`/`DELAYED` verfallen, `lastRunAt` und `runCount` bleiben (`ScheduledWorkflowExecutor.skipOccurrence`). Bis 2026-09-24 blieb `nextRunAt` in der Vergangenheit, der Tick versuchte es jede Minute erneut und startete den Termin verspätet, sobald der andere Lauf endete (TODO ade3fef8). `OVERLAP` startet daneben.
- **`maxRuns`** zählt jeden Start, auch fehlgeschlagene oder abgewiesene, nicht aber per `SKIP` entfallene Termine. Wiedereinschalten setzt `runCount` nicht zurück.
- **Enddatum** (`endDate`, nur `CRON`, TODO 9a3e6c91): Ein Termin am oder nach dem Enddatum startet nicht — gemessen am Termin, nicht am Zeitpunkt des Ticks, damit der letzte Termin davor nicht davon abhängt, wohin der 60-s-Tick fällt. `ScheduledWorkflowExecutor.advance` setzt nach einem Lauf oder `SKIP` keinen nächsten Termin hinter das Enddatum, sondern deaktiviert den Zeitplan; ein schon gespeicherter Termin dahinter (Altbestand, nach vorn verlegtes Enddatum) beendet ihn beim Fälligwerden ohne Start (Activity-Log „Schedule ended (end date reached)"). Bis 2026-09-24 lief der erste Termin nach dem Enddatum noch einmal.
- **`skip-until` in den Ansichten** (TODO a776fd2a): Der Tick spult einen Termin erst hinter das Fenster, wenn er fällig wird. Bis dahin rechnen die Lesepfade um — `ScheduleSkipPolicy.effectiveNextRun` (dieselbe Regel wie `skipScheduleUntil`, ohne zu schreiben): `ScheduleMapper` liefert als `nextRunAt` den ersten Termin nach der Pause plus `pausedUntil`, `UpcomingRunsCalculator` lässt Termine in der Pause aus Dashboard, Kalender, Cron-Vorschau und Assistent-Kontext weg, `GET /schedules/maintenance` nennt das Pausenende. Die Lesepfade parsen `skip-until` ohne Activity-Log-Eintrag; einen ungültigen Wert meldet der Tick.
- **Wartungsmodus** (`scheduler.maintenance-mode`, Pause-Schalter der Schedules-Seite): Der Tick läuft weiter, startet aber nichts; `nextRunAt` bleibt stehen. Beim Ausschalten startet jeder überfällige Zeitplan einmal sofort, alle gleichzeitig — weder Standby-/Kaltstart-Erkennung noch Nachhol-Warteschlange greifen, die Ticks während der Pause haben die Lücke schon verbraucht.
- **Wartungsmodus in den Ansichten** (TODO 7704b1e9): dieselbe Regel wie bei `skip-until`, keine zweite. `ScheduleSkipPolicy.activePause` liest beide Pausen einmal je Ansicht (`SchedulerPause`), `effectiveNextRun` liefert für einen Zeitplan, den der Wartungsmodus hält, keinen nächsten Lauf — wann der Schalter ausgeht, weiß niemand. `ScheduleMapper` meldet dann `nextRunAt: null` plus `maintenance: true`, `UpcomingRunsCalculator` lässt im Dashboard alle Termine weg (`maintenance: true`), Kalender und Assistent-Kontext zeigen keinen. Dashboard-Karte und Zeitplan-Liste sagen „Wartungsmodus – startet nichts".

## Completion Triggers (V39)

Automatische Verkettung von Workflows auf Basis von Abschluss-Ereignissen:
- `CompletionTriggerService` — verwaltet Trigger-Definitionen
- `CompletionTriggerController` — `/api/v1/completion-triggers`
- Trigger verknüpfen einen Source-Workflow (Auslöser) mit einem Target-Workflow (wird nach Abschluss gestartet)
- API: List, Get, Create, Enable/Disable, Delete; Filter by Source- oder Target-Workflow-ID

## Audit Center — Findings, Akzeptanz und Verfall (V68/V69)

Audit-Workflows (Metadata `audit.enabled=true`, `key`, `category`, `reportBlock`) liefern im Report-Block einen Envelope `<!-- mysuricates:audit-result:v1 {…} -->`, den `AuditResultParser` zerlegt (bei mehreren den letzten, damit ein wiedergegebener Vorlauf nicht zählt; TODO 1cf996df). `AuditIngestionService.ingest` (auf `WorkflowRunCompletedEvent`) prüft und speichert pro Run einen `AuditReport` (VALID/INVALID); die Findings eines gültigen Reports gleicht `AuditSnapshotApplier` in derselben Transaktion per Fingerprint (SHA-256 über projectId/auditKey/ruleKey/**normalisiertes** subject) ab:

- **Normalisierung** (`AuditSubjectNormalizer`): steht vor dem ersten `:` ein klein geschriebenes Art-Wort (`package:…`, `keyword:…`, `safe-inventory:…`), bleibt `<art>:<wert>` ganz — es ist die Identität. Sonst fallen alles ab dem ersten `:` und ein klein geschriebenes Methoden-Suffix hinter einem Klassennamen fallen weg (`ContainerShellService.execute` → `ContainerShellService`, `application.yml:key-store-password` → `application.yml`); Pfade mit `/` und Dateinamen ohne Klassenpräfix bleiben ganz. Das modellgenerierte `subject` driftet sonst zwischen Runs und erzeugt Wiedergänger (altes Finding `RESOLVED`, neues `OPEN`, verwaltetes TODO fälschlich `DONE`). Das gespeicherte `subject` ist reines Anzeigeattribut: bei einem Treffer gewinnt der neueste Wert. `AuditFingerprintMigrationJob` (Startup, idempotent) rechnet Altbestände um und führt Wiedergänger auf das älteste Finding zusammen (Zähler addiert, TODO-Links und Vorkommen ziehen mit). Eine menschliche Disposition (`ACCEPTED_RISK`, `DISMISSED`) schlägt dabei immer — egal ob sie am Ziel oder am Wiedergänger hängt; Status und Disposition (`dispositionNote`/`-Evidence`/`-At`, `resolvedAt`) wandern zusammen, wiedereröffnet wird nur über `AuditAcceptancePolicy` (belegte Verschlechterung). Jede Zusammenführung wird vor dem Löschen mit beiden IDs, beiden Roh-Subjects und dem `ruleKey` protokolliert; ein fehlschlagender Scope wird geloggt und blockiert weder die übrigen noch den Start. Ergeben zwei Befunde **desselben** Reports dieselbe Identität, wird nur der erste übernommen; die übrigen meldet die Ingestion mit ihren Subjects im Activity-Log (früher verschwanden sie still, TODO 94fda6ef).

- **Neu** → `OPEN`; **wieder da** nach `RESOLVED` → `OPEN` + `regressionCount`; `AWAITING_VERIFICATION` → `OPEN`; **nicht mehr im Snapshot** → `RESOLVED` (nur OPEN/AWAITING_VERIFICATION). Terminale Status `ACCEPTED_RISK` und `DISMISSED` werden bei Abwesenheit nicht angefasst.
- **Verwaltete TODOs**: nur für `CRITICAL`/`HIGH`-Findings im Status `OPEN` (`ensureManagedTodo`, Tag `audit`, `auto:workflow`, `TodoCreatedEvent` → TodoTrigger). Akzeptierte/verworfene Risiken und Wiedervorlagen lösen keinen Fix-Loop aus. `reconcileManagedTodos` schließt das TODO, sobald alle verknüpften Findings terminal sind, und öffnet es beim Wiederauftauchen. Liegt keines der offenen verknüpften Findings mehr über der Schwelle (etwa nach geänderten Kriterien HIGH → MEDIUM), wird das TODO freigegeben: Verknüpfung gelöst, Status `ARCHIVED` (nicht `DONE` — behoben ist nichts); ein `IN_PROGRESS`-TODO bleibt unangetastet (TODO ba0dcb3a).
- **Gate-Status** kommt aus den Run-Metadaten des Workflows (`gate_status`), nicht aus den Findings; `AuditSummary.openFindingsBySeverity` zählt nur nicht-terminale Findings, `findingsByStatus` liefert Akzeptierte getrennt (Audit Center: „davon N akzeptiert").

**Risiko-Akzeptanz** (`PATCH /api/v1/audit-findings/{id}` mit `status=ACCEPTED_RISK`, `dispositionNote`) — Regeln in `AuditAcceptancePolicy`, Zahlen-Parser `EvidenceMetrics`:

| Regel | Verhalten |
|---|---|
| Beweis-Snapshot | Beim Akzeptieren friert `AuditCenterService.updateFinding` den aktuellen `evidence`-Text als `disposition_evidence` und den Zeitpunkt als `disposition_at` ein (V69). Beides bleibt bei späteren Statuswechseln zur Nachvollziehbarkeit erhalten; erneutes Akzeptieren setzt beides neu. |
| Verschlechterung | Taucht das Finding erneut auf, vergleicht die Ingestion messbare Werte der neuen Evidence mit dem Snapshot — `EvidenceMetrics.parse` erkennt `Zahl + Einheit` (`LOC`/`lines`/`Zeilen`, `deps`, `KB`/`MB`/`GB`, `ms`, `%`, `files`, `classes`, `methods`, `tests`, `warnings`, `errors`, `issues`, `vulnerabilities`; Synonyme werden zusammengeführt, pro Einheit zählt der größte Wert). Liegt ein gemeinsamer Schlüssel > 10 % über dem Snapshot, wird das Finding wieder `OPEN`, die Notiz um `Verschlechtert: 320 LOC → 400 LOC` ergänzt; der Snapshot bleibt. Ohne vergleichbare Zahlen (einseitig oder gar keine) keine Wiedereröffnung. |
| Verfall | Ist die Akzeptanz beim Wiederauftauchen älter als 180 Tage (`disposition_at`, Fallback `resolved_at` für Alt-Akzeptanzen), geht das Finding auf `AWAITING_VERIFICATION` mit Notiz `Wiedervorlage nach 180 Tagen` — ohne verwaltetes TODO. Das passiert genau einmal: danach gelten die normalen Regeln (nächster Ingest: da → `OPEN`, weg → `RESOLVED`); erst ein erneutes Akzeptieren startet eine neue Frist. Verschlechterung hat Vorrang vor Verfall. |

Das Audit Center zeigt pro Finding Akzeptanz-Datum, Wiedervorlage-Datum (+180 Tage), Dispositions-Notiz und den Snapshot (aufklappbar). Tests: `AuditAcceptancePolicyTest`, `EvidenceMetricsTest`, `AuditIngestionServiceTest`, `AuditControllerTest`, Frontend `AuditCenter.test.tsx`.

**Kontextvariable `audit_findings`** (TODO 3643beac): Ein Audit-Workflow bekommt bei jedem Lauf die bekannten Befunde seines Audits (`OPEN`, `AWAITING_VERIFICATION`, `ACCEPTED_RISK`, `DISMISSED`; ohne `RESOLVED` und ohne `ruleKey`s, die der Katalog nicht kennt, ob markiert oder nicht). `WorkflowContextDataService` holt sie über die Schnittstelle `AuditContextSource`, umgesetzt von `service.audit.AuditFindingsContext` (damit `service.workflow` nicht von `service.audit` abhängt), aus einer schlanken Projektion ohne TEXT-Blobs – als Daten markiert und saniert wie `open_todos`, mit demselben Lückenpfad. Der Block hat höchstens 250 Zeilen und 60 000 Bytes (ein Shell-Befehl ist ein einziges `bash -c`-Argument); wird es eng, bleiben die eingeordneten Befunde vor den offenen. `ExecutionContextFactory` verwirft einen übergebenen Wert (Sub-Workflows erben alle Variablen), damit die Variable immer den laufenden Workflow beschreibt. So kann ein Report-Prompt eingeordnete Befunde mit derselben Identität weiterführen, und Verschlechterung und Verfall greifen. Details: [audit-workflow-protocol.md](audit-workflow-protocol.md) → „Bekannte Befunde im Prompt“.

## Todo Triggers — Self-Improvement Loop (V51/V52)

Automatische Ausführung von Fix-Workflows wenn TODOs erstellt werden — schliesst den Self-Improvement-Loop:

```
Audit-Workflow → AuditIngestionService → Finding → TODO (OPEN)
                                                 ↓ TodoCreatedEvent
                             TodoTriggerService → Fix-Workflow
                                                     ↓ WorkflowRunCompletedEvent
                                             TodoFeedbackListener → TODO (DONE)
```

- `TodoTriggerService` — `@TransactionalEventListener` auf `TodoCreatedEvent`, matched TODOs gegen konfigurierte Trigger (Tag-Pattern AND-Semantik, Priority-Filter, Projekt-Filter), startet Fix-Workflow mit TODO-Daten als Context-Variablen
- `TodoFeedbackListener` — `@EventListener` auf `WorkflowRunCompletedEvent`, schliesst TODO bei GREEN Gate (wenn `autoClose=true`), loggt Eskalation bei erschöpften Fix-Versuchen
- `TodoTriggerController` — `/api/v1/todo-triggers`
- API: List, Get, Create, Enable/Disable, Delete
- **Loop-Prevention**: 3 Schichten — Metadata-Flag (`todoTriggerRun`), Max-Attempts-Zähler (`todo.fixAttempts`), Running-Check (`todo.lastFixRunId`)
- **Context-Variablen** für Fix-Workflow: `todo_id`, `todo_title`, `todo_description`, `todo_tags`, `todo_priority`, `todo_project_id`, `todo_trigger_id`
- **Abgrenzung — manuelle Todo-Workflow-Verknüpfung**: Der `TodoTrigger` startet Workflows regelbasiert und automatisch. Davon getrennt kann ein einzelnes Todo optional mit einem **Resolver-Workflow** verknüpft werden (`linkedWorkflowId`, eingestellt in der Todo-Ansicht) — dem Workflow, der genau dieses Todo erledigen soll. Die Verknüpfung ist nicht global und startet nichts von selbst: erst die manuelle Aktion „Lösen" startet den Lauf, das Todo wechselt dabei auf `IN_PROGRESS` mit `resolutionState=RUNNING`, wird bei Erfolg automatisch auf `DONE` gesetzt und springt bei einem fehlgeschlagenen Run auf den vorherigen Status zurück (`resolutionState=FAILED`).
- **Pause** (`automation.todo-trigger.skip-until`, ISO-Instant, leer = aus): legt den gesamten Mechanismus bis zu diesem Zeitpunkt still — Backlog-Poll **und** Event-Pfad. Greift ab dem ersten Tick nach dem Start, braucht also kein laufendes Backend zum Setzen. Zusammen mit `automation.scheduler.skip-until` (+ `catch-up-on-resume: false`) ergibt das eine vollständige Trigger-Pause aus wenigen Config-Zeilen, z. B. in der gitignorierten `ai-automation-backend/config/application.yml`. Bewusst ein Datum statt eines Flags: es läuft von selbst aus, während ein vergessenes `enabled=false` auf den einzelnen Triggern den Loop stumm stillstehen lässt (kein Fehler, kein roter Status — die TODOs bleiben nur liegen). Unparsebarer Wert ⇒ fail-open (ERROR-Log, Trigger feuern normal). Eine aktive Pause wird beim Start als WARNING geloggt.

## Projekt-Cockpit & TODO-Hygiene

Da TODOs zu ~96 % maschinell erzeugt und über viele Projekte verstreut sind, gibt es eine projektübergreifende Übersicht plus einen Aufräum-Mechanismus.

- **Cockpit** (`GET /api/v1/todos/overview`, `TodoOverviewService`): liefert pro Projekt einen Rollup (Status-Counts, `staleCount`, `userActive` vs. `machineActive`, `needsYouCount`) plus eine globale „Braucht dich"-Liste (USER-Source ∨ CRITICAL ∨ überfällig). Alles aus schlanken GROUP-BY-Projektionen (keine TEXT-Blobs → pool-size=1-safe), Frontend-Seite `ProjectCockpit.tsx` (Route `/cockpit`).
- **ARCHIVED-Status**: Ablage neben BACKLOG für „aus den Augen". Aus der Default-Liste ausgeblendet (`TodoSpecifications.buildSpec` schließt ARCHIVED aus, wenn kein Status-Filter gesetzt ist), erreichbar über den Archiv-Tab / Edit-Modal.
- **Hygiene** (`TodoHygieneService`): `archiveStaleNow(projectId)` verschiebt verstaubte Auto-Todos (OPEN/BACKLOG, Source AI/WORKFLOW, Tag `auto:workflow`, `updatedAt` älter als Schwelle) nach ARCHIVED — per Bulk-UPDATE in kurzer Transaktion. Cockpit-Knopf „Verstaubtes archivieren" (`POST /todos/archive-stale?projectId=`) ist der primäre, human-in-the-loop Weg. Der `@Scheduled`-Job (täglich 03:30) ruft denselben Pfad, ist aber per Default **AUS** (`todo-hygiene-enabled`). Schwellen runtime-konfigurierbar via `ExecutionSettingsService`: `todo-hygiene-enabled` [0,1], `todo-stale-days` [7,365], `todo-hygiene-max-per-run` [10,2000].
- **Ghost-Watchdog** (`TodoGhostWatchdogService`): setzt verwaiste IN_PROGRESS-TODOs mit Maschinen-Source (AI/WORKFLOW) zurück auf OPEN, wenn die zugehörige CLI-Session ohne abschließenden Status-PATCH gestorben ist (`updatedAt` älter als Schwelle). Poll alle 5 min (`automation.todo-watchdog.poll-ms`), per Default **AN**; Human-TODOs (Source USER) werden nie angefasst. ActivityLog-WARNING pro Lauf. Runtime-Settings: `todo-watchdog-enabled` [0,1], `todo-watchdog-stale-hours` [1,720] (Default 24h).
- **Bulk-Status** (`POST /todos/bulk-status`): generischer Bulk-UPDATE für viele TODO-IDs auf einen Status.

## Ziele — TODOs projektübergreifend einordnen (V85)

Ein **Ziel** (`entity/Goal`, `/api/v1/goals`, TODO dc0566f8) sammelt TODOs aus mehreren Projekten, die auf ein Ergebnis einzahlen – etwa den Open-Source-Launch, der AutoPilot und die Website zugleich betrifft. Es ist bewusst schlank: keine Abhängigkeitsgraphen, Sprints oder Zeiterfassung.

- **Einordnung am TODO:** `goal_id` (höchstens ein Ziel), `goal_rank` (1 = zuerst, NULL = noch nicht eingeordnet), `complexity` (S/M/L/XL), `suggested_effort` (`low`…`xhigh`, dieselben Stufen wie `workflow.effort` und `claude --effort`), `rating_note`, `rated_by` (USER/AI), `rated_at` und `open_question`. Geschrieben über `PATCH /api/v1/todos/{id}/planning` (`TodoPlanningService`); `null` lässt ein Feld stehen, ein leerer Text leert es.
- **Reihenfolge:** Rang, dann Priorität, dann Alter (`GoalService.WORK_ORDER`). `PUT /goals/{id}/order` setzt Rang 1..n für die genannten TODOs, alle übrigen verlieren den Rang. Zuordnen ist `POST /goals/{id}/todos` (neu Zugeordnete stehen ohne Rang hinten).
- **Fortschritt:** ARCHIVED zählt nicht mit; offene Fragen zählen nur bei aktiven TODOs. Liste und Detail lesen schlanke Projektionen (`GoalStatusCounts`, `GoalTodoRow`) ohne Beschreibungen – pool-size=1-sicher.
- **Wer einordnet:** Im Gespräch der Plugin-Skill `/mysuricates:plan-goal`, der das Repo sieht (TODO 9ed0687d); unbeaufsichtigt später ein Workflow (TODO 473f47e0). Ein Mensch überschreibt jede KI-Einschätzung im TODO-Dialog.
- **Relationskarte** (`GET /goals/{id}/relations`, `GoalRelationsService`, TODO c6ea8f5c): Ein Ziel hat keine eigene Verknüpfung zu Workflows, nur über seine TODOs. Je TODO liefert der Endpunkt die **Herkunft** (`source`, erzeugender Lauf `workflow_run_id`, Referenz `workflow_run`/`audit_finding`/`health_check`, `audit_finding_todos`), die **Bearbeitung** (Fix-Workflow `linked_workflow_id` und jeder passende aktive TodoTrigger) und das **Ergebnis** (`last_fix_run_id`, `fix_attempts`, `resolution_state`). Den Trigger-Abgleich teilt er mit `TodoTriggerService` (`service/trigger/TodoTriggerMatcher`, rein). Alles gebündelt: eine schlanke Projektion der TODOs, dann eine Abfrage je Art. `include` holt bis zu 20 Kandidaten ohne Ziel mit (`GET /todos?goalId=none` findet sie).
- **UI:** Menüpunkt „Ziele“ mit Übersicht `/goals` (Aktiv · Erledigt · Archiviert), kompakte Zielkarten im Cockpit (`components/goal/GoalsSection`), Zielansicht `/goals/:goalId` mit Umschalter Liste | Karte | Zeitstrahl: die Liste (Als Nächstes · Offene Fragen · Erledigt · je Projekt; Fälligkeit und Meilenstein als Chips), die Karte als SVG in vier Spalten Herkunft · TODOs · Bearbeitung · Ergebnis (`goalRelationLayout.ts`, `GoalRelationMap`) – gleiche Workflows als ein Knoten, ein gepunkteter Stummel, wo noch kein Workflow andockt. Darunter „Könnte dazugehören“ (`GoalCandidates`) mit Zuordnen per Klick; die gezeigten Kandidaten erscheinen gestrichelt in der Karte. Einordnung im Bearbeiten-Dialog eines TODOs (`TodoPlanningPanel`).
- **Meilensteine** (V88, `entity/GoalMilestone`, `GoalMilestoneService`, TODO 259f4f66): eigener Eintrag mit Titel und Datum (`/goals/{id}/milestones`). TODOs des Ziels werden ihm über `PATCH /todos/{id}/planning` mit `milestoneId` zugeordnet; ein Meilenstein eines anderen Ziels → 400, wechselt oder verlässt das TODO sein Ziel, verliert es den Meilenstein. Der **Zustand wird beim Lesen berechnet** (`GoalMilestone.stateOf`): `REACHED`, sobald alle zugeordneten TODOs (ohne ARCHIVED, mindestens eins) erledigt sind, sonst `OVERDUE` (Datum vorbei), `DUE_SOON` (höchstens 3 Tage) oder `OPEN`. `reached_at` hält das erste Erkennen fest und bleibt; kommt danach ein offenes TODO dazu, meldet die Antwort `reopened: true` („wieder offen“). Ein reines Datum heißt „bis Ende dieses Tages“ (23:59:59 UTC). Die Detailansicht holt Ziel und Meilensteine in **einer** Abfrage (`GoalRepository.findWithMilestones`, Entity-Join) und zählt aus denselben `GoalTodoRow`-Zeilen, die jetzt auch `dueDate` und `milestoneId` tragen – sie bleibt bei zwei Statements (`RequestCostRatchetTest`).
- **„Braucht dich“:** `GET /todos/overview` liefert zusätzlich `milestonesDue` – Meilensteine aktiver Ziele mit `DUE_SOON` oder `OVERDUE`, mit `goalId`/`goalTitle` für den Link. Das Cockpit zeigt sie vor den TODOs, jede Zeile führt zur Zielseite (`components/goal/MilestoneDueRow`).
- **Zeitstrahl** (dritte Ansicht der Zielseite, TODO e192df18; `components/goal/GoalTimeline`, reine Logik in `timelineLayout.ts`): oben die Achse mit „heute“, einer Raute je Meilenstein (Farbe nach Zustand) und einer Fahne fürs Zieldatum, das Raster nach Tag, Woche oder Monat je nach Spanne; darunter eine Zeile je Meilenstein und eine „ohne Meilenstein“ mit den fälligen TODOs (überlappende gestapelt), zuletzt die Leiste „Ungeplant“ mit allen TODOs ohne Fälligkeit. Ziehen (`@dnd-kit/core`) auf einen Tag setzt die Fälligkeit (`PUT /todos/{id}`, 23:59:59 Ortszeit wie im TODO-Dialog), in eine andere Zeile den Meilenstein (`PATCH /todos/{id}/planning`), zurück nach „Ungeplant“ leert das Datum; dasselbe kann das Menü jedes Chips ohne Ziehen. Einen Meilenstein legt ein Klick auf die Achse oder der Knopf „Meilenstein“ an, seine Raute öffnet eine Karte zum Bearbeiten und Löschen mit den zugeordneten TODOs.
- **Schalter „Wächter“** im Kopf der Zielseite (`components/goal/GoalGuardControl`): Beim ersten Einschalten fragt ein Dialog nach dem Projekt (vorbelegt das mit den meisten TODOs des Ziels) und der Uhrzeit; danach zeigt die Zeile den nächsten Lauf, den Workflow und „Jetzt prüfen“ (`POST /workflows/{id}/execute`). Einschalten legt die Ergebnis-Kachel des Wächter-Workflows auf die gezeigte Dashboard-Ansicht, falls noch keine Ansicht sie zeigt – derselbe Weg wie „Kachel hinzufügen“ im Katalog (`components/dashboard/workflowResultTile.ts`), gespeichert mit der nächsten Version des Layouts. Ausschalten lässt die Kachel stehen. Ist das Modul `dashboard-report` aus, gibt es den Schalter nur für einen schon angelegten Wächter.
- **Ziel-Wächter** (`service/goal/guard/GoalGuardService`, `POST /goals/{id}/guard`): ein Workflow aus der mitgelieferten Vorlage `goal-guard` je Ziel, Name „Ziel-Wächter: <Titel>“, `defaultContext.goal_id` und `goal_locale`, angelegt über `WorkflowService` → `WorkflowMutationGateway` mit `ChangeSource` `goal-guard`; dazu ein CRON-Zeitplan (Vorgabe täglich 08:00, `inputContext.goal_id`, Konflikt `SKIP`). Projekt: das angegebene, sonst das mit den meisten TODOs des Ziels, sonst das älteste aktive. `goals.guard_workflow_id` merkt sich den Workflow. Erneutes Einschalten aktiviert nur den Zeitplan (ein neuer `cron` ersetzt den alten), Ausschalten und Löschen des Ziels pausieren ihn – der Workflow bleibt. Ist der Workflow gelöscht, legt das nächste Einschalten einen neuen an. Braucht das Modul `dashboard-report`, sonst 409. `GoalResponse.guard` = `{enabled, workflowId, scheduleId, nextRunAt}` aus einer gebündelten Abfrage (`GoalGuardLookup`). Warum ein Workflow je Ziel: die Dashboard-Kachel wird je Workflow geführt (letzter Lauf mit `dashboard_widget`).
- **Kontextvariablen `goal_status` und `goal_guard_*`:** Trägt der zusammengeführte Kontext eines Laufs `goal_id`, injiziert `WorkflowContextDataService` über die Schnittstelle `GoalContextSource` (umgesetzt von `service.goal.GoalStatusContext`, damit `service.workflow` nicht von `service.goal` abhängt) den Stand des Ziels – als Daten markiert, saniert wie `open_todos`. Ohne `goal_id` gibt es sie nicht; eine unbekannte ID setzt jede davon auf die `NICHT GEMESSEN`-Lücke und warnt im Activity-Log. Details: [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) → „Ziel-Kontext“.

## Notification System (V40)

Benachrichtigungs-Konfigurationen für Workflow-Ereignisse:
- `NotificationService` — CRUD für Benachrichtigungs-Konfigurationen
- `NotificationController` — `/api/v1/notifications`
- API: List, Get, Create, Enable/Disable, Delete

## Assistant System (V41+V42)

Kontext-sensitiver Chat-Assistent für die Plattform. Siehe auch: [assistant-system.md](assistant-system.md)

- `AssistantService` — Chat-Logik und Kontext-Aufbereitung; den Claude-CLI-Aufruf samt Chat-Modell und Basisanweisungen übernimmt `AssistantChatCaller`
- `AssistantSessionService` — Session-Persistierung in DB (V41-Migration: `assistant_sessions`, V58: `preview`-Spalte). Endpunkte: `GET /sessions` (Top 50), `GET /sessions/{id}` (Detail), `DELETE /sessions/{id}`
- `AssistantContextBuilder` — injiziert letzte 5 Sessions (User-Nachricht + Antwort) und bis zu 8 aktive Workflow Memories in den System-Prompt — auf **allen** Seiten
- `PageSuggestionService` — generiert und cached kontextsensitive UI-Vorschläge pro Seite (V42-Migration: `page_suggestions`)
- `AssistantController` — `POST /api/v1/assistant/chat`, `GET /sessions`, `GET /sessions/{id}`, `DELETE /sessions/{id}`, `POST /pending-tools/{id}/confirm`, `POST /pending-tools/{id}/reject`
- `PageSuggestionController` — `GET /api/v1/assistant/suggestions?page=...`, `POST /api/v1/assistant/suggestions/refresh?page=...`
- Sessions werden nach jedem Chat-Aufruf persistiert; `AssistantSessionUpdatedEvent` triggert Page-Suggestion-Refresh
- Standard-Modell: `sonnet` (CLI-Alias, neuestes Sonnet)

## Workflow Memory

Workflows können über Runs hinweg Wissen akkumulieren:
- `WorkflowMemoryService` — extrahiert Wissen aus abgeschlossenen Runs
- `ClaudeSummarizer` / `CodexSummarizer` — AI-basierte Zusammenfassung
- `KnowledgeSummarizer` — Interface für die Summarization-Backends
- `HostCodexService` — führt OpenAI Codex CLI auf dem Host aus (analog zu `HostClaudeService`)
- `SummarizationSetupService` — Konfiguration und Setup der Summarization-Backends
- `ConversationPreprocessor` — Vorverarbeitung von Konversationsdaten für Summarization
- Aktive Memory-Einträge werden in nachfolgende Runs als Kontext injiziert
- `AutoMemoryExtractionListener` — automatische Extraktion nach erfolgreichen Runs, konfigurierbar per Workflow-Metadata (`autoMemoryExtraction.enabled`, `autoMemoryExtraction.interval`)
- `WorkflowMemory`-Entity (V16-Migration)

## Agent Instructions System

Zweistufige Instruction-Injektion via Claude CLI `--append-system-prompt` / `--system-prompt`:

1. **Basis-Instructions** (`src/main/resources/agent-instructions.md`) — geladen beim Start durch `AgentInstructionsService`, gelten für alle Workflows
2. **Workflow-spezifische Instructions** (`workflow.instructions`) — pro Workflow, im UI editierbar (Monaco-Editor)

3. **Arbeitspräferenzen** (opt-in) — nur wenn ein Workflow sie über `metadata.userPreferences: true`
   anfordert. `UserPreferencesService` rendert dann genau drei Laufzeit-Settings in einen Abschnitt
   `## Arbeitspräferenzen`: `user-pref-language`, `user-pref-style`, `user-pref-stack`
   (Antwortsprache, Antwortstil, Tech-Stack). Mehr ist nicht möglich: Die Schlüsselliste steht im
   Code, ein weiterer Schlüssel ist eine bewusste Änderung. **Keine Namen, Kontaktdaten,
   Lebensläufe oder Berufsprofile** — Entscheidung vom 2026-09-15. Ist nichts gesetzt oder fehlt
   das Flag, ist der Prompt byte-identisch zum Stand ohne die Funktion; ein Prüf- oder
   Messworkflow soll nur von den Fakten abhängen. Werte pflegbar über
   `PUT /api/v1/system/settings/{key}`.

Injektionspfade:
- **Block-Ausführung:** Instructions via `--append-system-prompt` auf Host Claude CLI (`HostClaudeService.executeAction()`)
- **Agentic REASON:** System-Prompt via `--system-prompt` an `HostClaudeService.reasonStructured()`, inkl. Workflow-Instructions in `## Workflow Instructions`

## Host Claude Service

Alle Claude-CLI-Aufrufe der Blöcke erfolgen auf dem Host (nicht im Container; nur Ad-hoc-Befehle während eines Runs laufen per `docker exec` im Projekt-Container):
- `HostClaudeService.executeAction()` — ACT-Phase, nutzt `--append-system-prompt`, `--model`-Flag, Projekt-Arbeitsverzeichnis
- `HostClaudeService.reasonStructured()` — REASON-Phase, nutzt `--system-prompt`, `--max-turns 3`; `--model` wird gesetzt, wenn der Workflow ein Modell konfiguriert (`ReasoningRequest.model`)
- Endet die CLI bei `reasonStructured()` mit Exit-Code ≠ 0 (Limit, fehlende Anmeldung, Absturz), wirft der Aufruf eine `IOException` mit Exit-Code und Meldung (`CliExit`, ebenso `HostPiService.reason()`; Codex wirft von sich aus). Ein Reasoning-Ergebnis ist damit immer eine Antwort, sonst speicherten Summarizer und Kontextgenerator die Fehlermeldung als Memory bzw. Projektkontext. `executeAction()` gibt den Exit-Code dagegen zurück, dort entscheidet der Block.
- Prompt wird via **stdin** an den Prozess geschrieben (`writePromptToStdin()`) — vermeidet Linux ARG_MAX-Limit
- `CLAUDE_CODE` und `CLAUDECODE` Umgebungsvariablen werden aus dem Prozess-Environment entfernt (verhindert Nested-Session-Fehler)
- `CommandExecutorService` bietet `executeOnHost(HostExecutionRequest)` / `executeStepOnHost(HostExecutionRequest)` / `writeSkillContextToProject()` — alle Overloads nutzen die `HostExecutionRequest`-Builder-API
- Skill-Kontext wird dateibasiert injiziert: `writeSkillContextToProject()` schreibt `.claude/rules/skills-context.md` ins Projektverzeichnis (ersetzt die alte CLI-prozessbasierte Methode `injectSkillContextOnHost()`)

## Provider Routing

Die KI-fähigen Block-Executors (simple, retry, conditional, foreach, while, switch, skill-capability, compress-ai, agentic) bestimmen den Anbieter mit `context.providerFor(block)`: `provider` am Block vor dem `provider` des Workflows, ohne beide der Systemstandard. Das Setzen von `"provider": "CODEX"` in einem Workflow-JSON gilt damit für jeden KI-Block ohne eigenen `provider`; `compress-ai` läuft nur mit `CLAUDE` (sonst scheitert der Schritt). Beim `integration`-Block bezeichnet `provider` den Integrations-Provider, nicht den KI-Anbieter.

`AiProviderRegistry.resolve()` fällt auf den konfigurierten Standard-Provider zurück (nicht hardcodiertes CLAUDE), wenn eine unbekannte Provider-ID übergeben wird.

Ob ein Anbieter bereit ist, meldet `GET /api/v1/system/ai-providers/status` (`service/ai/status/AiProviderStatusService`): Programm auf dem `PATH` und eine Anmeldung per Anmeldedatei oder Umgebungsvariable — dieselbe Regel wie der Installer (`scripts/lib/providers.sh`), aus `AiProvider#setup()` (`ProviderSetup`). Container-Instanzen lenken die Anmeldeverzeichnisse um (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `PI_CODING_AGENT_DIR`), die Prüfung folgt dem (`ProviderSetup.loginDir`/`loginDirEnv`). Kein CLI-Aufruf, gecacht (`automation.ai-provider-status.ttl-seconds`, Standard 60 s), `POST …/status/refresh` prüft sofort. Die Antwort nennt nur Namen und Befehle, nie einen Wert. Die Startseite zeigt den Status, solange es kein Projekt gibt oder der Standard-Anbieter nicht bereit ist (`AiProviderStartCard`), die Einstellungen immer (`AiProviderStatusCard`).

## Per-Block Skills

Blöcke können über das `skillIds`-Feld eigene Skills mitbringen, die additiv zu den Workflow-Level-Skills gelten:

- `BlockDefinition.skillIds` — optionale Liste von Skill-IDs, die nur für diesen Block aktiviert werden
- `BlockSkillContext` (aus `WorkflowExecutor` herausgelöst) merged Workflow-Level-Skills + Block-Level-Skills vor jeder Block-Ausführung und stellt danach den Workflow-Stand wieder her
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

- Templates werden in der `workflow_templates`-DB-Tabelle gespeichert (V19-Migration), nicht hardcodiert
- **55 Built-in Templates** werden beim Start durch `WorkflowTemplateSeeder` eingespielt (idempotent, fügt fehlende ein, aktualisiert bei Version-Bump). Built-in-Zeilen, deren Datei nicht mehr ausgeliefert wird (in den Pool verschoben, umbenannt, Altlasten mit Zufalls-ID), entfernt er vorher; maßgeblich sind die Dateinamen (`BuiltInTemplateLoader.shippedIds`), damit eine Datei, die nicht parst, ihre Zeile nicht verliert. Verifikationen fallen per `ON DELETE CASCADE` mit, Workflows aus solchen Vorlagen bleiben und verlieren nur den Rückverweis (`ON DELETE SET NULL`). Nutzer-Vorlagen bleiben unberührt
- Template-Definitionen liegen als einzelne JSON-Dateien in `src/main/resources/templates/builtin/` (eine Datei pro Template, geladen durch `BuiltInTemplateLoader`)
- Name und Beschreibung sind i18n JSON-Maps: `{"de":"...","en":"..."}` — gespeichert via `JsonStringMapConverter`
- Built-in Templates (`builtIn=true`) können nicht gelöscht werden; nur `sortOrder` und `category` sind aktualisierbar
- Nutzer können eigene Templates via API oder durch Speichern eines bestehenden Workflows anlegen
- `workflow.templateId` — optionale Back-Referenz auf das Quell-Template
- Frontend löst lokalisierte Template-Namen via `resolveTemplateName()` in `utils/templateUtils.ts` auf
- Jede Antwort trägt `requiredModules` (Module, die ihre Blöcke brauchen, verschachtelte eingeschlossen; `BlockTypeModules.requiredBy`) und `needsAi` (ob ein Lauf ein Modell rufen kann; `executor/ModelUse`, dieselbe Regel wie das KI-Badge im Frontend). Die Vorlagen-Auswahl blendet Vorlagen und Blaupausen mit ausgeschaltetem Modul aus und zeigt stattdessen einen Zähler („N weitere brauchen ein Modul – anzeigen“). Eingeblendet nennt die Karte das Modul und wie man es zuschaltet. Dazu kommen der Filter „Läuft ohne KI“ und übersetzte Kategorien
- CRUD-API unter `/api/v1/workflow-templates`

## Built-in Skills

- Skills werden in der `skills`-DB-Tabelle gespeichert, nicht in Frontend oder In-Memory-Beans hardcodiert
- **19 Built-in Skills** werden beim Start durch `SkillSeeder` eingespielt (idempotent)
- **Ablage:** `src/main/resources/skills/builtin/<id>.json` (Metadaten: `sortOrder`, `version`, `name`, `icon`, `description`, `category`, `capabilities` inkl. `writes`/`async`/`handlerClass`/Schemata) plus `<id>.md` mit dem Prompt-Text. Geladen von `BuiltInSkillLoader` (Vorbild `BuiltInTemplateLoader`); die Java-Klassen `BuiltInSkills`/`BuiltIn<Kategorie>Skills` gibt es nicht mehr
- Die `.md` wird **byte-genau** übernommen (kein Trim, kein angehängter Zeilenumbruch). `BuiltInSkillsSnapshotTest` vergleicht alle Skills kanonisch mit `src/test/resources/skills/builtin-snapshot.json` und schlägt bei jeder unbeabsichtigten Änderung fehl; gewollte Änderungen mit `./mvnw test -Dtest=BuiltInSkillsSnapshotTest -Dskills.snapshot.write=true` neu schreiben
- **Versionsregel:** `SkillSeeder` seedet versionsbasiert (Semver-Vergleich in `SeedVersions`, wie `WorkflowTemplateSeeder`) — fehlende Skills werden angelegt, vorhandene nur bei höherer Ressourcen-`version` überschrieben (inkl. Capabilities). Eine inhaltliche Änderung an einem Built-in braucht also eine höhere `version` in der JSON, sonst erreicht sie bestehende Instanzen nicht. Nutzer-Skills (`builtin=false`) und in den Ressourcen entfallene Built-ins bleiben unangetastet
- **Gate zur Versionsregel:** `BuiltInSkillVersionGateTest` hasht je Skill die `<id>.json` (kanonisch) plus die `<id>.md` (byte-genau) gegen die eingecheckte Liste `src/test/resources/skills/builtin-versions.json` und schlägt fehl, wenn sich der Hash ohne höhere `version` geändert hat oder die Liste veraltet ist — nach einem gewollten Bump mit `./mvnw test -Dtest=BuiltInSkillVersionGateTest -Dskills.versions.write=true` neu schreiben und mitcommitten
- Loader-Fehler sind hart: fehlende `.md`, Dateiname ≠ `id`, fehlender oder doppelter `sortOrder`, fehlende/unbekannte `category` oder ein leeres Verzeichnis brechen den Start ab, statt ein unvollständiges Skill-Set zu seeden
- Built-in Skills haben stabile IDs (z.B. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` registriert alle Skills auch im In-Memory `SkillRegistry` für Capability-Lookups
- `SkillRegistry` hängt nicht mehr von `SkillProvider`-Beans ab — Seeder übernimmt die Registrierung
- Capability-Handler-Klassen (z.B. `LintHandler`, `OCRHandler`, `SecurityScanHandler`, `FactCheckHandler`, `GrammarCheckHandler`) in `skills/handlers/` (Unterverzeichnisse `code/`, `document/`)
- Nutzer können eigene Skills via Import-API anlegen (`POST /api/v1/skills/import`)
- `Skill.java`-Entity nutzt `@PrePersist ensureId()` (statt `@GeneratedValue`) für explizite IDs bei Built-ins

## Template Verification

- `TemplateVerification`-Entity (V22-Migration) — trackt den Qualitätsstatus von Workflow-Templates
- `TemplateVerificationService` — verwaltet den Verifizierungs-Status pro Template+Projekt-Paar
- Workflows, die aus Templates erstellt wurden, können test-gestartet werden, um die Template-Korrektheit zu prüfen
- Verifizierungs-Ergebnisse sind in Template-CRUD-API-Responses sichtbar

## Workflow Factory

Automatisches Generieren und Verifizieren von Workflows aus einem Topic. Das Block-Schema in den Prompts von Factory, Wishes, Dialog-Import, Run-Konvertierung und Workflow-Optimierung kommt aus `BlockSpecs` (dieselben Texte wie `fetch block-spec` des Assistenten); `PromptBlockSchemaGateTest` prüft jeden dort genannten Blocktyp und Config-Schlüssel gegen die Executoren — die alten Prompts nannten `forEach`, `then_blocks`, `if_true` und andere Schlüssel, die kein Executor liest. Ein Beispiel für retry, foreach, while oder conditional mit Befehl muss außerdem `execution_mode` nennen: Ohne geht der Befehl als Prompt an die KI, und die KI baut nach, was das Beispiel zeigt (der Konvertierungs-Prompt zeigte so ein retry mit `npm test`).
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
- Ablauf von `POST /run` („Erstellen & Testen“): generieren auf DEV → `syncDefinitions()` → Testlauf auf der Test-Instanz (Polling) → bei `FAILED` und `autoFix` KI-Reparatur auf DEV aus der Fehlermeldung (neue Version), erneut syncen und testen, bis `maxRetries` → Testergebnis auf DEV vermerken → bei `autoPromote` und bestandenem Test Template. `POST /generate` („Workflow erstellen“) führt nur den ersten Schritt aus.
- Die UI erklärt das mit einem animierten Ablaufdiagramm (`components/factory/FactoryFlowDiagram`, Geometrie und Zustände in `factoryFlow.ts`). Es folgt den Schaltern Auto-Fix/Auto-Promote (auch direkt in der Karte schaltbar, derselbe Zustand wie im Formular), zeigt für „Workflow erstellen“ den Ein-Schritt-Ablauf mit „Ende · kein Test“, hebt die aktuelle Station und ihren Schritt in der Liste hervor (Abspielen/Pause; bei `prefers-reduced-motion` erst auf Klick, ein Durchlauf) und ist eine Erklärung, keine Live-Anzeige: `/run` ist ein blockierender Aufruf ohne Zwischenereignisse.

## Workflow Pool

Vorgefertigte Workflow-Definitionen aus dem `workflow-pool/`-Verzeichnis:
- `WorkflowPoolService` — liest JSON-Dateien aus dem konfigurierten Pool-Verzeichnis
- `WorkflowPoolController` — `/api/v1/pool` mit Endpunkten:
  - `GET /` — alle Pool-Workflows auflisten
  - `GET /{id}` — einzelnen Pool-Workflow abrufen
  - `POST /{id}/install` — Workflow aus Pool in Zielprojekt importieren
  - `POST /export/{workflowId}` — Workflow in den Pool exportieren

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

## Integration System (V55/V56)

Plugin-Framework für externe Dienste:
- `IntegrationRegistry` — Auto-Discovery aller `IntegrationProvider`-Beans
- `IntegrationOAuthService` — OAuth2-Flow (Authorization Code Grant)
- `IntegrationEncryptionService` — Verschlüsselung von Credentials (AES-GCM)
- `IntegrationConnection`-Entity — Verbindungsstatus (CONNECTED, DISCONNECTED, EXPIRED)
- `IntegrationController` — `/api/v1/integrations` mit OAuth-Callback, Credentials, Actions
- 18 Provider in Unterverzeichnissen: CalDav, Confluence, Feed, Gitea, GitHub, GitLab, GoogleCalendar, GoogleGmail, HttpApi, ICalFeed, Jira, Ntfy, OpenMeteo, Slack, Taskwarrior, Telegram, Todoist, Trello
- `IntegrationBlockExecutor` — Führt Provider-Aktionen als Workflow-Block aus
- `TaskExportCapable` — Interface für Provider, die TODO-Export unterstützen

## Dead Letter Queue (V63)

Fehlgeschlagene asynchrone Operationen werden in einer Dead-Letter-Queue gespeichert:
- `DeadLetterService` — legt für jeden FAILED-Run einen Eintrag an, per `@EventListener` auf `WorkflowRunCompletedEvent` (nicht per Aufruf aus `service.workflow`, das hielt einen Paketzyklus); Verwaltung von Dead-Letter-Entries (CRUD, Retry, Discard)
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

## Entities

Alle JPA-Entities und Enums im `com.automation.entity`-Package:

| Entity | Tabelle | Beschreibung |
|---|---|---|
| `ActivityLog` | `activity_log` | System-/Ausführungs-Logeinträge mit Level, Category, Metadata |
| `AssistantSession` | `assistant_sessions` | Persistierte Chat-Sessions des Assistenten |
| `BlockDefinition` | *(eingebettet)* | Block-Definition mit Typ, Config, Required-Capabilities (Value Object) |
| `Capability` | `capabilities` | Skill-Capabilities mit Schema und Handler-Referenz |
| `ChainEntry` | *(eingebettet)* | Eintrag in einer Chain mit Workflow-Referenz und Kontext (Value Object) |
| `ChainRun` | `chain_runs` | Ausführungs-Instanz einer Chain mit Entry-Runs |
| `ChainRunEntry` | *(eingebettet)* | Status eines einzelnen Chain-Eintrags während der Ausführung (Value Object) |
| `CompletionTrigger` | `completion_triggers` | Verknüpfung Source→Target-Workflow bei Abschluss |
| `DeadLetterEntry` | `dead_letter_entries` | Fehlgeschlagene Nachrichten in der Dead-Letter-Queue |
| `IntegrationConnection` | `integration_connections` | Verbindung zu externem Dienst (OAuth, Credentials) |
| `TodoTrigger` | `todo_triggers` | Automatische Fix-Workflow-Ausführung bei TODO-Erstellung (Self-Improvement-Loop) |
| `Container` | `containers` | Docker-Container pro Projekt mit Status und Mounts |
| `NotificationConfig` | `notification_configs` | Benachrichtigungs-Konfiguration (Webhook/Desktop) |
| `OnboardingProfile` | `onboarding_profiles` | Onboarding-Profil mit Typ (BASE/PROFILE) |
| `OnboardingProfileHistory` | `onboarding_profile_history` | Änderungshistorie eines Onboarding-Profils |
| `PageSuggestion` | `page_suggestions` | Gecachte UI-Vorschläge pro Seite |
| `Project` | `projects` | Projekt mit Typ, Pfad, Status, AI-Kontext |
| `ScheduledWorkflow` | `scheduled_workflows` | Cron-/Once-/Delayed-Schedule für Workflows und Chains |
| `Skill` | `skills` | Skill mit Kategorie, Capabilities, Konfigurationsschema |
| `StepResult` | `step_results` | Ergebnis eines einzelnen Workflow-Schritts mit Context-Delta |
| `TemplateVerification` | `template_verifications` | Qualitätsstatus eines Templates pro Projekt |
| `Todo` | `todos` | Aufgabe mit Status, Priorität, Source, Tags |
| `WebhookTrigger` | `webhook_triggers` | Webhook-Trigger für Workflows mit Secret-Token |
| `Wish` | `wishes` | Natürlichsprachiger Wunsch mit Decomposition und Iteration-History |
| `Workflow` | `workflows` | Workflow mit Blöcken, Instructions, Modell, Provider (Soft-Delete) |
| `WorkflowChain` | `workflow_chains` | Chain mit sortierten Entries und Stop-on-Failure |
| `WorkflowMemory` | `workflow_memory` | Extrahiertes Wissen aus Workflow-Runs |
| `WorkflowRun` | `workflow_runs` | Ausführungs-Instanz eines Workflows mit Steps und Metadata |
| `WorkflowTemplate` | `workflow_templates` | Template mit i18n Name/Description und Blöcken |
| `WorkflowVersion` | `workflow_versions` | Versionierte Workflow-Snapshots |

**Enums:**

| Enum | Werte |
|---|---|
| `ContainerStatus` | CREATED, RUNNING, STOPPED, ERROR |
| `GateStatus` | GREEN, YELLOW, RED |
| `ProjectStatus` | STOPPED, STARTING, RUNNING, ERROR |
| `ProjectType` | CODE, CONTENT, RESEARCH, BUSINESS, PERSONAL |
| `RunStatus` | PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED, INTERRUPTED |
| `SkillCategory` | DOCUMENT, CODE, DATA, CONTENT, RESEARCH, BUSINESS, PRODUCTIVITY |
| `StepStatus` | PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, WAITING_APPROVAL |
| `WishStatus` | RECEIVED, DECOMPOSING, AWAITING_DECOMPOSE_APPROVAL, EXECUTING, EVALUATING, REFINING, AWAITING_CLARIFICATION, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED |
| `Todo.Status` | OPEN, IN_PROGRESS, DONE, BACKLOG, ARCHIVED (ARCHIVED = „aus den Augen" — aus der Default-Liste ausgeblendet, eigener Archiv-Tab; Ziel der Hygiene/Bulk-Archivierung) |
| `Todo.Priority` | LOW, MEDIUM, HIGH, CRITICAL |
| `Todo.Source` | USER, AI, WORKFLOW |
| `ActivityLog.LogLevel` | DEBUG, INFO, WARN, ERROR |
| `ActivityLog.LogCategory` | SYSTEM, EXECUTION, SCHEDULE, WEBHOOK, SECURITY, CONTAINER, PLUGIN |
| `ScheduledWorkflow.ScheduleType` | CRON, ONCE, DELAYED |
| `ScheduledWorkflow.TargetType` | WORKFLOW, CHAIN |
| `ScheduledWorkflow.ConflictBehavior` | SKIP, OVERLAP |
| `NotificationConfig.ChannelType` | WEBHOOK, DESKTOP |
| `OnboardingProfileType` | BASE, PROFILE |
| `Todo.ResolutionState` | IDLE, RUNNING, FAILED, SUCCEEDED |
| `IntegrationConnection.ConnectionStatus` | CONNECTED, DISCONNECTED, EXPIRED |
| `DeadLetterEntry.Status` | OPEN, RETRIED, DISCARDED |

## Frontend-Architektur

### Komponentenstruktur

```
App.tsx (Router)
├── Layout
│   ├── Sidebar (Navigation, Collapse)
│   └── Header (Sprache, Theme)
│
├── Pages (28 Routen)
│   ├── Dashboard, Projects, ProjectDetail, ProjectCockpit
│   ├── Workflows, WorkflowDetail, WorkflowExecution, WorkflowDebug, WorkflowFactory
│   ├── Skills, Schedules, Calendar, Webhooks, Integrations, Settings
│   ├── Runs, ActivityLog, Todos, DeadLetterQueue
│   ├── Chains, ChainExecution
│   ├── Wishes, Docs, Reports, Insights, OnboardingProfiles, DialogImport
│   ├── Simple*-Varianten (SimpleHome, SimpleWorkflows, etc.)
│
├── Hooks (TanStack Query + Custom, 40+)
│   ├── useProjects, useWorkflows, useSkills, useSchedules, ...
│   ├── useChains, useChainTemplates, useTodos, useTodoTriggers, useTodoOverview
│   ├── useActivityLog, useWorkflowMemory, useWishes, useWorkflowFactory
│   ├── useStaging, useTesting, usePool, useDocs, useReports, useInsights
│   ├── useIntegrations, useDeadLetters, useCompletionTriggers, useWorkflowVersions
│   ├── useAssistant, useAssistantWebSocket, useCalendarAggregated
│   ├── useWebSocket, useToast
│   └── calendar-adapters/ (Google, Runs, Schedules, Taskwarrior, Trello)
│
├── Stores (Zustand)
│   ├── sidebarStore, themeStore
│   ├── settingsStore, toastStore, assistantStore
│
└── API Client
    └── /api/v1/* → Vite Proxy → Backend :9090
```

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
- **Endpunkt:** `/ws` (SockJS Handshake, Authentifizierung per Ticket — siehe [WebSocket-Authentifizierung](#websocket-authentifizierung))
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
    (Re-)Connect und dedupliziert über `bufferId` + `seq`. Puffer leben nur im Speicher und verfallen 10 Minuten nach
    Run-Ende (auch ohne Completion-Event über den Abgleich mit `WorkflowExecutionRegistry`), maximal 20 Runs.
  - **Konfiguration:** `automation.live-log.*` (`enabled`, `max-line-length`, `buffer-lines`, `flush-interval-ms`,
    `max-lines-per-flush`, `max-chars-per-flush`, `retention-minutes`, `max-buffered-runs`); nur beim Start gelesen.
- **Terminal-WebSocket:** `/ws/terminal/**` für Echtzeit-Container-Zugriff (Raw WebSocket via `RawWebSocketConfig`, Handler: `TerminalWebSocketHandler`)

## Sicherheit

### API-Key-Authentifizierung

- **Header:** `X-API-Key`
- **Filter:** `ApiKeyAuthFilter` (vor Spring Security)
- **Vergleich:** Constant-Time (`MessageDigest.isEqual`)
- **Auto-Generierung:** Key wird beim ersten Start in `app_settings` gespeichert
- **Deaktivierbar:** `automation.security.enabled=false` (Entwicklung)

### Öffentliche Endpunkte (kein API-Key nötig)

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `GET /api/v1/system/auth-status`
- `GET /api/v1/system/jvm-memory` (nur die drei Heap-Zahlen; `/actuator/metrics` verlangt den Key)
- `POST /api/v1/webhook/trigger/{token}`
- `/api/v1/integrations/*/callback` (OAuth2 Callbacks)
- `/ws/**` (WebSocket)
- `/actuator/health`

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
Konstanten (`GLOBAL_BACKOFF_SECONDS`, `DEFAULT_RETRY_AFTER_SECONDS`) nur noch als *Fallback* im
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
- **Kein SockJS-Fallback:** Die Clients laufen mit `transports: ['websocket']`. SockJS legt für jeden Fallback-Transport (xhr-streaming, xhr-polling) eine eigene Session an und bräuchte dafür ein zweites Ticket — das erste ist nach dem WebSocket-Versuch verbraucht. **Die Instanz braucht also einen WebSocket-fähigen Pfad** (Proxy/Reverse-Proxy muss Upgrades durchlassen); einen HTTP-Fallback gibt es nicht mehr.
- **Reconnect:** `reconnectDelay: 5000` mit `ReconnectionTimeMode.EXPONENTIAL` und `maxReconnectDelay: 60000` — jeder Versuch kostet einen `POST /ws-ticket` und soll bei totem Backend nicht gegen das API-Rate-Limit laufen.
- **Deaktiviert:** Bei `automation.security.enabled=false` oder ohne konfigurierten Key lässt der Interceptor jeden Handshake durch (wie der `ApiKeyAuthFilter`).

### CORS

- Eine Antwort für CORS-Filter, WebSocket-Handshakes und OAuth-Popup: `security/AllowedOrigins`
- Konfigurierte Liste `automation.cors.allowed-origins` (Default: `http(s)://localhost:3000` und `:5173`)
- Mit dem Modul `remote-access` zusätzlich die eigene Tailscale-IPv4 und der eigene MagicDNS-Name, jeder Port (`service/system/OwnTailnetAddresses`: `tailscale ip -4` / `tailscale status --json`, 60 s gemerkt)
- WebSocket: `AllowedOriginHandshakeInterceptor` prüft vor dem Ticket; Springs eigene Prüfung bekommt `*`, weil sie nur eine feste Startliste kennt
- Alle HTTP-Methoden (GET, POST, PUT, PATCH, DELETE, OPTIONS)
- Keine Credentials (Auth über `X-API-Key`, keine Cookies)

## Datenbankmigrationen (Flyway)

Die DB-Migrationen (`src/main/resources/db/migration/`) dokumentieren die Feature-Timeline:

| Migration | Feature |
|---|---|
| V1–V9 | Basistabellen: Projects, Containers, Skills, Capabilities, Workflows, Runs, Schedules, Webhooks, Timestamps |
| V10–V11 | Performance-Indexes, Container-Last-Activity |
| V12 | App-Settings |
| V13 | Workflow-Modell + Instructions |
| V14 | Schedule-Typen + Scheduled-At |
| V15 | Activity-Log-Tabelle |
| V16 | Workflow-Memory-Tabelle |
| V17 | Todos-Tabelle |
| V18 | Project-Subtitle + Description |
| V19 | Workflow-Templates-Tabelle |
| V20 | Input-Context in Workflow-Runs |
| V21 | Workflow-Chains |
| V22 | Template-Quality-Status |
| V23 | Default-Context in Workflows |
| V24 | Activity-Log Composite Index |
| V25 | Workflow-Test-Status |
| V26 | Block-Types-Column |
| V27 | Chain-Support für Schedules |
| V28–V29 | Wishes + Wish-Reference-Workflow-IDs |
| V30 | Project-AI-Context |
| V31 | Workflow-ID im Activity-Log |
| V32 | Workflow Soft-Delete (deleted_at) |
| V33 | Wish-Iteration-History |
| V34 | Workflow-Provider |
| V35 | Project-Archivierung (archived_at) |
| V36 | Todo-Session-ID |
| V37 | Delayed-Schedule-Type |
| V38 | Scheduled-Workflow-ID nullable |
| V39 | Completion-Triggers-Tabelle |
| V40 | Notification-Configs-Tabelle |
| V41 | Assistant-Sessions-Tabelle |
| V42 | Page-Suggestions-Tabelle |
| V43 | Triggered-By-Schedule-ID |
| V44 | Schedule-End-Conditions |
| V45 | Schedule-Conflict-Behavior |
| V46 | Stable-Synced-At |
| V47 | Context-Delta in Step-Results |
| V48 | Related-Todo-ID |
| V49 | Onboarding-Profiles |
| V50 | Workflow-Versions |
| V51 | Todo-Triggers-Tabelle |
| V52 | Fix-Attempts + Last-Fix-Run-ID auf TODOs |
| V53 | Process-Backlog-Flag auf Todo-Triggers |
| V54 | Composite-Indexes auf Todos |
| V55 | Integration-Connections-Tabelle |
| V56 | Client-Credentials auf Connections |
| V57 | Due-Date auf Todos |
| V58 | Assistant-Session-Preview-Spalte |
| V59 | Linked-Workflow auf Todos |
| V60 | Workflow-Name in Runs |
| V61 | Workflow-Tags und Category |
| V62 | Auto-Refine auf Wishes |
| V63 | Dead-Letter-Entries-Tabelle |
| V64 | Writes-Flag auf Capabilities |
| V65 | Catch-Up-On-Resume auf Schedules |
| V66 | Todo-Export-Link |
| V67 | Reasoning-Effort auf Workflows |
| V68 | Audit-Reports, Findings, Occurrences und TODO-Verknüpfungen |
| V69 | Beweis-Snapshot und Akzeptanz-Zeitpunkt auf Audit-Findings (`disposition_evidence`, `disposition_at`) |
| V70 | Run-Provenienz: Definitions-Hash auf Versionen und Runs, Versions-Bezug und `trigger_source` |
| V71 | Workflow-Versiegelung: `sealed`, `sealed_hash`, `sealed_at` auf Workflows |
| V72 | Verschlüsseltes HMAC-Signatur-Secret auf Webhook-Triggern |
| V73 | Versionierte Dashboard-Layout-Snapshots (`dashboard_layout_versions`) |
| V74 | Tagesaggregat der gemessenen Request-Kosten je Route (`request_metrics_daily`) |
| V75 | Kennzeichen fuer Findings mit ruleKey ausserhalb des Katalogs (`unknown_rule_key`) |
| V76 | Agenten-Sitzungsherkunft am TODO (`agent_session_ref`) |
| V77 | Beleg-Pruefung am Audit-Finding (`unverified_evidence`) |
| V78 | Zweisprachige Schritte in Vorlagen (`workflow_templates.i18n_source`) |
| V79 | Blaupausen als eigene Art neben Vorlagen (`workflow_templates.kind`) |
| V80 | Skills unter dem Siegel: `workflows.sealed_skill_hashes`, Änderungshistorie `skill_versions` |
| V81 | Onboarding-Profile zweisprachig: Inhalt als `{de, en}`-Map |
| V82 | Siegelstand am Run: `workflow_runs.sealed`, `sealed_skill_pins` |
| V83 | Bearbeitete Onboarding-Profile: `customized`, `shipped_version` |
| V84 | Siegel-Signatur und Siegel-Geschichte: `workflows.sealed_signature`, `workflow_seal_events` |
| V85 | Ziele: Tabelle `goals`, Einordnung und Einschätzung an `todos` |
| V86 | Mehrere benannte Verbindungen je Integrations-Anbieter: `integration_connections.connection_name` |
| V87 | Indizes für Läufe je Schedule (`workflow_runs`, `chain_runs`) und TODOs je Lauf (`todos.last_fix_run_id`, `todos.workflow_run_id`) |
| V88 | Ziele: Tabelle `goal_milestones`, `todos.milestone_id`, `goals.guard_workflow_id` (Ziel-Wächter) |
| V89 | Kettenbezug eines Laufs: `workflow_runs.chain_run_id` + Index |

## Architektur-Gates

Zwei JUnit-Tests im Paket `com.automation.architecture` halten die gemessene Struktur fest, ohne neue Abhängigkeit. Ihre Grundmessung sind dieselben jdeps-Paketkanten wie in `scripts/public-readiness/arch-metrics.sh` (Abschnitt `## packages`); `LayerRuleTest` prüft zusätzlich eine zweite Sicht (siehe unten). `PackageCycleRatchetTest` pinnt die heute wechselseitig abhängigen Paketpaare als Allowlist — ein neues Paar bricht den Build, ein aufgelöstes wird nur als Hinweis gemeldet. Weil Paare allein nicht jeden Zyklus sehen (eine einzige Kante `service.workflow → service.goal` ließ die große Zyklus-Komponente einmal unbemerkt von 17 auf 24 Pakete wachsen), begrenzt er zusätzlich die starken Zusammenhangskomponenten, also die `cycle`-Zeilen von `arch-metrics.sh`: die größte auf `MAX_CYCLE_COMPONENT_SIZE` (Stand 2026-09-25: 17 Pakete), und jede Komponente mit mehr als zwei Paketen ist samt Mitgliedern in `CYCLE_COMPONENTS` festgehalten — ein neu hineingezogenes Paket bricht den Build, und die Meldung nennt die Kanten, die es anbinden; schrumpft eine Komponente, schlägt der Test ebenfalls fehl, bis beide Werte im selben Commit gesenkt sind. `LayerRuleTest` verbietet die Kantenklassen `service.* → controller.*`, `controller.* → repository.*` und `repository.* → service.*` (Allowlist heute leer). Gemessen wird immer das Build-Verzeichnis, aus dem die Tests selbst geladen wurden (`target/` bzw. `target-ide/` im IDE-Profil), nie ein danebenliegender veralteter Stand; fehlt es oder fehlt jdeps, werden beide Tests mit klarer Meldung übersprungen statt still grün zu laufen.

Der blinde Fleck der Bytecode-Messung sind Abhängigkeiten, die nur als generisches Typargument auftauchen und im Bytecode gelöscht sind. `LayerRuleTest` prüft dieselben drei Regeln deshalb aus einer **zweiten Sicht**: `SourceImportEdges` liest die `import`-Zeilen des Quelltexts unter `src/main/java` (Spalte 0, damit Kommentare und String-Literale nicht mitzählen) und wertet sie gegen dieselben Regeln aus, mit eigener Allowlist (heute ebenfalls leer). Die Sichten decken sich nicht — die Bytecode-Sicht sieht Vererbung, statische Verweise und generierten Code, die Quelltext-Sicht die gelöschten Typargumente —, und der Test belegt zusätzlich, dass der Scan überhaupt Klassen gelesen hat: eine leere Messung meldete sonst jede Regel als eingehalten. Ein **Rest bleibt für beide unsichtbar**: ein voll qualifizierter Name innerhalb eines generischen Typarguments (`Page<com.automation.repository.Foo>` ohne Import) ist im Bytecode gelöscht und hat keine Import-Zeile. Für Projektionen, die vom Repository bis in den Controller reisen, heißt das: sie gehören nach `com.automation.dto.response`, nicht ins Repository-Paket.

## Verzeichnisstruktur

```
autotrigger/
├── CLAUDE.md                          ← Projektanweisungen für Claude Code
├── VISION.md                          ← Verweis auf docs/vision.md
├── docs/                              ← Diese Dokumentationsdateien
├── workflow-pool/                     ← Vorgefertigte Workflow-Definitionen (JSON)
├── scripts/
│   ├── release.sh                     ← Versions-Bump + Git-Tag
│   ├── staging.sh                     ← Stable/Test-Umgebung verwalten
│   └── start-autotrigger-dev.sh       ← Dev-Umgebung starten
│
├── ai-automation-backend/
│   ├── pom.xml
│   ├── docker-compose.yml
│   ├── docker-compose.stable.yml      ← Stable-Container
│   ├── docker-compose.test.yml        ← Test-Container
│   ├── docker/
│   │   ├── Dockerfile.backend
│   │   ├── Dockerfile.stable          ← Ubuntu-basiert (glibc für Claude CLI)
│   │   ├── Dockerfile.agent-node
│   │   ├── Dockerfile.agent-python
│   │   ├── Dockerfile.agent-devops
│   │   └── Dockerfile.agent-java
│   ├── data/                          ← SQLite-DB (gitignored)
│   └── src/main/
│       ├── java/com/automation/       ← Java-Quelldateien
│       └── resources/
│           ├── application.yml
│           ├── application-stable.yml ← Stable-Profil
│           ├── application-prod.yml   ← Produktions-Profil
│           ├── application-test.yml   ← Test-Profil
│           ├── application-dev.yml    ← Dev-Profil
│           ├── agent-instructions.md  ← Basis-Agent-Instructions
│           ├── templates/builtin/     ← Built-in Template JSON-Dateien
│           └── db/migration/          ← 89 Flyway-Migrationen (V1–V89)
│
└── ai-automation-frontend/
    ├── package.json
    ├── vite.config.ts
    ├── eslint.config.js               ← Flat Config (ESLint 9+)
    ├── index.html
    └── src/
        ├── App.tsx                    ← Router
        ├── main.tsx                   ← Entry Point
        ├── index.css                  ← Globale Styles + Tailwind-4-Konfiguration (@theme inline)
        ├── api/client.ts              ← HTTP-Client (Singleton)
        ├── types/index.ts             ← TypeScript-Typen
        ├── pages/                     ← 37 Seiten (inkl. Simple-Mode-Varianten)
        ├── components/
        │   ├── ui/                    ← Basis-UI-Primitives
        │   ├── layout/                ← App-Shell, Sidebar, Navigation
        │   ├── shared/                ← StatusBadge, LogViewer, etc.
        │   ├── dashboard/             ← Dashboard-Widgets
        │   ├── workflow/              ← Block-Editor, Templates
        │   ├── assistant/             ← AI-Assistenten-Panel
        │   ├── calendar/              ← Kalender-Ansicht
        │   ├── factory/               ← Workflow-Factory-Builder
        │   ├── insights/              ← Analytics/Insights
        │   ├── simple/                ← Simple-Mode-Komponenten
        │   ├── todo/                  ← TODO-Verwaltung
        │   └── wishes/ u.a.           ← Weitere Feature-Komponenten
        ├── hooks/                     ← TanStack Query Hooks
        ├── stores/                    ← Zustand Stores
        ├── i18n/                      ← DE/EN Übersetzungen
        ├── theme/                     ← Theme-Tokens
        ├── data/                      ← Skill-Konstanten
        ├── utils/                     ← Hilfsfunktionen
        └── test/                      ← MSW-Handler, Test-Setup
```

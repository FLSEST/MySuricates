# Kuratierungsplan für den öffentlichen Workflow-Pool

Der Pool unter `workflow-pool/` wird mitveröffentlicht. Bis zu diesem Plan war nirgends
festgelegt, was hineingehört: die Einträge waren das Ergebnis einzelner Exporte, nicht einer
Auswahl — deshalb standen dort zeitweise Home-Pfade, Instanz-UUIDs und persönliche Daten.

Dieses Dokument beantwortet vier Fragen. Abschnitt 3 ist zugleich das Regelwerk, das
`scripts/ci/pool-neutrality.sh` maschinell durchsetzt: der Plan definiert die Kriterien, das
Gate bewacht sie.

---

## 1. Wofür der Pool da ist — im Unterschied zu den Built-in-Templates

Es gibt zwei Mechanismen nebeneinander, und sie beantworten verschiedene Fragen.

| | Built-in-Templates | Workflow-Pool |
|---|---|---|
| Ablage | `ai-automation-backend/src/main/resources/templates/builtin/*.json` (66) | `workflow-pool/*.json` |
| Weg in die Instanz | `WorkflowTemplateSeeder` spielt sie beim Start per Semver-Vergleich in die Datenbank | manuell: `GET /api/v1/pool` → `POST /api/v1/pool/{id}/install` |
| Sichtbar als | Vorlage im Template-Katalog, in jeder Instanz vorhanden | Eintrag im Pool-Browser, erst nach bewusstem Installieren ein Workflow |
| Aktualisierung | Seeder zieht eine höhere Version nach | keine; ein installierter Workflow ist eine Kopie und lebt weiter für sich |
| Verzeichnis konfigurierbar | nein | ja, `AUTOMATION_POOL_DIR` (Default `workflow-pool`) |

**Die Abgrenzung in einem Satz:** Ein Built-in-Template ist Teil der Plattform und kommt
ungefragt mit; ein Pool-Eintrag ist ein Angebot, das ein Leser bewusst übernimmt.

Daraus folgt die Entscheidungsregel für einen neuen Workflow:

- Er gehört zu dem, was die Plattform von sich aus können soll (Quality-Gates der Plattform,
  Assistant-Bausteine, Dinge, auf die anderer Code sich verlässt) → **Built-in-Template**.
- Er ist ein nützliches Beispiel oder ein Arbeitsmittel, das nicht jeder haben muss → **Pool**.
- Er ist auf eine Person, einen Haushalt oder ein fremdes Projekt zugeschnitten → **keins von
  beidem**; er bleibt in der Instanz und wird nicht getrackt.

## 2. Welche Kategorien es gibt: `scope`

Jeder Eintrag trägt ein Pflichtfeld `scope` mit genau einem von zwei Werten:

| Wert | Bedeutung | Erkennungsmerkmal |
|---|---|---|
| `repo` | greift auf die Struktur **dieses** Repositories zu | nennt `ai-automation-backend`, `ai-automation-frontend`, `mvnw`, `scripts/` oder `workflow-pool/` |
| `portable` | über `project_path` (o. ä. Kontextvariablen) parametrisiert | läuft in jedem Projekt, das der Installierende auswählt |

Beide Arten sind legitim, aber sie sind verschieden nützlich: wer einen `repo`-Eintrag in sein
eigenes Projekt installiert, bekommt einen Workflow, der ins Leere läuft. Deshalb ist `scope`
kein Kommentar, sondern ein Feld:

- in der Definition (`"scope": "repo"`),
- im Pool-Browser als kleines Badge neben der Version (`repo` in Warnfarbe, `portable` in
  Infofarbe, mit Erklärung im Tooltip),
- beim Export als Query-Parameter: `POST /api/v1/pool/export/{workflowId}?scope=repo`
  (ohne Angabe gilt `portable` — die schwächere Zusage muss nicht begründet werden, die
  stärkere Bindung an dieses Repository schon).

`scope` ist ein **Pool-Metadatum**, kein Feld der Workflow-Definition: ein installierter
Workflow trägt es nicht weiter, und die Datenbank kennt es nicht.

### Wo der Bestand steht

Er steht hier bewusst **nicht** als Liste. Eine Aufzaehlung im Dokument altert mit jedem
Export — diese hier tat es bereits: Sie nannte 13 `repo` und 7 `portable` und stimmte
schon wenige Eintraege spaeter nicht mehr.

Die belastbare Auskunft gibt das Verzeichnis selbst, denn jede Definition traegt ihr `scope`
als Feld:

```bash
grep -h '"scope"' workflow-pool/*.json | tr -d ' ",' | sort | uniq -c  # die Aufteilung
grep -l '"scope": "portable"' workflow-pool/*.json                     # die portablen
```

`bash scripts/ci/pool-neutrality.sh` nennt am Ende die gepruefte Gesamtzahl, und der
Pool-Browser zeigt das `scope`-Badge neben jeder Version.

## 3. Kriterien je Eintrag — das Regelwerk des Gates

Jede Regel ist so formuliert, dass sie prüfbar ist, und trägt die Kennung, unter der
`scripts/ci/pool-neutrality.sh` sie meldet. Die Listen dahinter (erlaubte `scope`-Werte,
Blocktypen, Repo-Kennzeichen, Instanz-Schlüssel) stehen in `scripts/lib/hygiene-rules.sh`.

| Kennung | Regel | Prüfung |
|---|---|---|
| `SCOPE` | `scope` ist vorhanden und einer von `repo`, `portable` | Feldvergleich |
| `NAME` | `name` ist ein `{de, en}`-Objekt mit mindestens einem nicht-leeren Text | ohne das lädt der Eintrag gar nicht |
| `DESCRIPTION` | `description` ist ein `{de, en}`-Objekt mit mindestens einem nicht-leeren Text | Feldvergleich |
| `TAGS` | `tags` ist eine nicht-leere Liste nicht-leerer Strings | Feldvergleich |
| `PROJECT-ID` | kein Schlüssel `projectId`/`project_id` mit konkretem Wert — weder als Feld noch im `defaultContext`, den Metadaten oder einer Block-Config | rekursiver Schlüssel-Lauf; ein Platzhalter (`{{ project_id }}`) oder ein leerer Wert ist erlaubt, das ist die portable Parametrisierung |
| `INSTANCE-REF` | dasselbe für `workflowId`, `runId`, `scheduleId`, `chainId`, `executionId` (und Snake-Case-Schreibweisen) | wie oben |
| `UUID` | keine Instanz-UUID irgendwo im Text | Regex `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}` |
| `PATH-ABS` | keine absoluten Benutzer-/Systempfade (`/home/…`, `/Users/…`, `/opt/…`, `/var/log|lib|opt/…`) | Regex |
| `PATH-HOME` | keine Tilde-Pfade (`~/projekt`) | Regex |
| `PATH-WIN` | kein Windows-Pfad mit Laufwerksbuchstaben | Regex |
| `BINARY` | kein fest verdrahteter Werkzeugpfad (`/usr/bin/dot`); `#!/usr/bin/env` bleibt erlaubt | Regex |
| `HOST-LAN` | keine LAN-Adressen (192.168.x, 10.x, 172.16–31.x) | Regex |
| `EMAIL` | keine Mailadresse | Regex |
| `CONTEXT-PII` | kein personenbezogener Schlüssel im `defaultContext` (Kinder, Adresse, IBAN, Lebenslauf …) | Token-Vergleich, der Schlüssel ist das Signal — auch mit Platzhalter als Wert |
| `BLOCK-TYPE` | jeder Blocktyp steht in der Registry, auch die im `switch` verschachtelten (`cases[].blocks` und `default` — die einzigen Stellen, an denen ein Block einen Block enthält; die übrigen Wrapper führen Strings aus) | Listenvergleich |
| `REPO-DEP` | ein Eintrag mit `scope: portable` nennt keines der Repo-Kennzeichen (`ai-automation-backend`, `ai-automation-frontend`, `scripts/`, `mvnw`, `workflow-pool/`) | Substring-Suche |
| `SELF-API` | die Definition ruft die eigene REST-API nicht auf | ein `/api/v1/<endpunkt>` zusammen mit einem Aufruf (`curl`, `wget`, `requests.`, `urllib`, `fetch(`, `http.client`) oder einem HTTP-Verb davor; die auth-freien `/api/v1/system/{health,info,auth-status}` sind als Lebenszeichen-Sonde erlaubt |
| `CREDENTIAL` | die Definition liest oder setzt keine Zugangsdaten | Substring `X-API-Key` oder `app_settings` — ohne Ausnahme |
| `DB-DIRECT` | die Definition liest die Datenbank nicht direkt | `sqlite3` irgendwo, oder `automation.db` zusammen mit einem Lesezugriff (`SELECT`, `INSERT INTO`, `.tables`, …); die Dateigröße anzusehen (`stat`, `du`) bleibt erlaubt |
| `PARSE` | die Datei ist gültiges JSON und ein Objekt | `json.load` |

### Die Blocktyp-Registry

Quelle ist `BlockTypesIndexSection.BLOCK_TYPES` im Backend, das selbst gegen die vorhandenen
`BlockExecutor`-Klassen gepinnt ist (`BlockTypeListsGuardTest`). Für die Skripte wird die Liste
daraus **erzeugt** — `scripts/lib/block-types.generated.sh`, geschrieben von
`BlockTypesGateListTest`. Eine Hand pflegt sie nicht, also driftet sie auch nicht.

Die aktuelle Liste steht deshalb nicht hier, sondern kommt aus der Datei:

```bash
. scripts/lib/block-types.generated.sh && printf '%s\n' "${HYGIENE_POOL_BLOCK_TYPES[@]}"
```

Eine Aufzählung an dieser Stelle wäre eine dritte Kopie — und war es: Sie nannte 19 Typen und
kannte `dashboard-report` nicht mehr, als der hinzukam.

### Eigene REST-API, Zugangsdaten, Datenbank — und warum die Muster zweistufig sind

Die Hausregel steht in `CLAUDE.md`: Workflow-Blöcke rufen weder die eigene REST-API noch die
Datenbank auf. Alles, was ein Workflow an Plattformdaten braucht, injiziert der
`WorkflowExecutor` als Kontextvariable (`{{ open_todos }}`, `{{ previous_report }}`,
`{{ workflow_run_id }}`, …). In einer **mitveröffentlichten** Definition kommt ein zweites
Problem dazu: ein Prompt, der erklärt, wie man sich den API-Key aus `app_settings` holt, ist
eine Anleitung zum Auslesen von Zugangsdaten — jeder Leser installiert sie mit.

Zwei der drei Muster wären einstufig unbrauchbar, deshalb verlangen sie ein zweites Kennzeichen:

- `/api/v1/` allein ist kein Aufruf. `mocking-gap-audit` führt die Zeichenkette als **Suchmuster**
  in einer Liste von Netzwerk-Kennzeichen, direkt neben `fetch(` und `axios.`. Gemeldet wird
  deshalb erst ein konkreter Endpunkt (`/api/v1/todos`, nicht das nackte Präfix) zusammen mit
  einem Aufruf in der Umgebung.
- `automation.db` allein ist kein Datenbankzugriff. `mysuricates-performance-audit` sieht sich
  mit `stat -c %s` nur die **Dateigröße** an und schreibt das auch dazu. Verboten ist das Lesen
  des Inhalts, das an Hibernate und der einzigen Connection (pool-size 1) vorbeigeht.

Wer TODOs schreiben will, nimmt die Blocktypen `todo-create`, `todo-update` und `todo-close`.
Wer nur auf einen Befund reagieren will, gibt am Ende `gate_status:` aus und setzt
`metadata.autoTodo.enabled` — dann legt die Plattform das TODO an, hält es über die Läufe
hinweg aktuell und schließt es bei GREEN wieder. Genau so wurde `system-performance-analysis`
umgestellt (TODO 1ccdc6d5): der agentische Block, der per curl `POST /api/v1/todos` aufrief
und sich den Key aus `app_settings` holen sollte, ist entfallen.

### Was das Gate nicht prüfen kann

Zwei Kriterien bleiben eine Lesefrage und gehören in die Freigabe (Abschnitt 4):

- **Kein auf eine Person zugeschnittener Prompt.** `CONTEXT-PII` erwischt den Schlüssel im
  `defaultContext`, nicht eine Formulierung im Prompt selbst.
- **Keine Abhängigkeit auf Skripte außerhalb des Repositories.** `PATH-ABS` und `PATH-HOME`
  erwischen den offensichtlichen Fall; ein Werkzeug, das nur auf einem bestimmten Rechner
  im `PATH` liegt, sieht das Gate nicht.

## 4. Wie Einträge entstehen und gepflegt werden

### Export

`POST /api/v1/pool/export/{workflowId}?scope=repo|portable` schreibt den Workflow als
Pool-Definition. Der Export schreibt **keine Instanz-Bindungen** mehr mit
(`service/pool/PoolExportSanitizer`):

- Schlüssel wie `projectId`, `workflowId`, `runId`, `scheduleId`, `chainId`, `executionId`
  fallen weg, sofern ihr Wert nicht leer und keine Template-Variable ist,
- ein Wert, der genau eine UUID ist, fällt weg,
- eine UUID mitten in einem Text wird durch `REDACTED-UUID` ersetzt, damit der Text nicht
  zerfällt,
- fabrikinterne Metadaten (`factoryGenerated`, `factoryTopic`, `factoryCategory`) fallen wie
  bisher weg.

Was entfernt wurde, steht als Warnung im Log **und in der Antwort**: `ExportToPoolResponse`
führt die bereinigten Stellen mit Pfad in `sanitizedPaths` auf (z. B.
`blocks[0].config.workflowId`). Der Export ist eine Bereinigung, keine stille Beschneidung.

**Wichtig bei `sub-workflow`-Blöcken:** Deren `config.workflowId` zeigt auf einen Workflow der
exportierenden Instanz und fällt damit unter die Regel. Ein Workflow mit `sub-workflow`-Block
ist nach dem Export deshalb **kein installierbarer Pool-Eintrag**, solange die `workflowId`
nicht von Hand nachgetragen oder durch eine Kontextvariable ersetzt wurde — `sanitizedPaths`
nennt genau die Stelle.

### Freigabe vor dem Commit

Ein exportierter Eintrag ist ein Entwurf. Vor dem Commit:

1. `bash scripts/ci/pool-neutrality.sh` — alle Regeln aus Abschnitt 3 über die getrackten
   Definitionen. Im pre-commit-Hook und in der CI läuft dieselbe Prüfung über
   `scripts/ci/public-hygiene.sh` (Check 3), ein Verstoß blockiert den Commit.
2. Die zwei nicht prüfbaren Kriterien selbst lesen: Prompt auf Personenbezug, Abhängigkeiten
   auf Werkzeuge außerhalb des Repositories.
3. `scope` gegenlesen: Stimmt die Zusage? Ein `portable`-Eintrag, der nur mit einem bestimmten
   Projektaufbau funktioniert, ist ein `repo`-Eintrag.

Ein Eintrag, der die Fragen nicht besteht, wird nicht getrackt. `git rm --cached` genügt: das
Verzeichnis bleibt die Arbeitsfläche der laufenden Instanz, getrackt ist nur die Auswahl.

### Wiederkehrende Prüfung

Das Built-in-Template `pool-neutrality-audit` ruft `scripts/ci/pool-neutrality.sh --all` auf
und prüft damit **jede** `.json` im Pool-Verzeichnis einer laufenden Instanz, auch die
ungetrackten. So fällt ein Eintrag, der beim nächsten Release mitgetrackt würde, schon vorher
auf. Der Musterkatalog steht nur im Skript, nicht zusätzlich in einem JSON-Feld der Vorlage.

---

Verwandt: [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (Block-Typen und Patterns),
[architektur.md](architektur.md) (Templates, Seeder), [deployment.md](deployment.md)
(Vertrauensmodell).

# Frontend

## Routing

| Pfad | Seite | Beschreibung |
|---|---|---|
| `/` | Dashboard | Statistiken, Charts, letzte Runs |
| `/projects` | Projects | Projektliste, Erstellen |
| `/projects/:id` | ProjectDetail | Projektdetails, Container, Workflows |
| `/wishes` | Wishes | Wunsch-System, iterative Erfüllung |
| `/workflows` | Workflows | Workflow-Liste, Erstellen mit Templates |
| `/workflows/:id` | WorkflowDetail | Block-Editor, Skills, Runs, Memory |
| `/workflows/:id/run/:runId` | WorkflowExecution | Live-Ausführung, Logs, Steps |
| `/factory` | WorkflowFactory | Workflow-Generator aus Topics |
| `/dialogs` | DialogImport | Claude-Sessions als Workflows importieren |
| `/chains` | Chains | Workflow-Chains, Split-Panel-Layout |
| `/chains/:id/run/:runId` | ChainExecution | Live Chain-Ausführung, Entry-Status |
| `/runs` | Runs | Alle Workflow-Runs (globale Liste) |
| `/skills` | Skills | Skill-Katalog mit Capabilities |
| `/schedules` | Schedules | Cron-basierte Zeitpläne |
| `/webhooks` | Webhooks | Webhook-Trigger-Verwaltung |
| `/todos` | Todos | TODO-Liste, Filter, Statusverwaltung |
| `/activity` | ActivityLog | System-Activity-Log mit Filtern |
| `/dlq` | DeadLetterQueue | Dead-Letter-Queue für fehlgeschlagene Events |
| `/debug` | WorkflowDebug | Workflows mit fehlgeschlagenen Runs analysieren |
| `/docs` | Docs | Markdown-Dokumentation (Split-Layout) |
| `/reports` | Reports | Generierte Berichte anzeigen |
| `/insights` | Insights | Plattform-Einblicke und Analysen |
| `/integrations` | Integrations | Integrations-Provider verwalten |
| `/runs/:runId` | RunRedirect | Redirect → `/workflows/:workflowId/run/:runId` (API-Lookup) |
| `/calendar` | CalendarPage | Aggregierter Kalender (Schedules, Runs, Integrationen) |
| `/cockpit` | ProjectCockpit | Projekt-Cockpit mit Todo-Übersicht |
| `/goals` | Goals | Ziele nach Status (Aktiv · Erledigt · Archiviert), Anlegen; im Menü eingerückt als Untereintrag direkt unter „Todos“ |
| `/goals/:goalId` | GoalDetail | Ein Ziel: Liste, Relationskarte (Herkunft → TODOs → Bearbeitung → Ergebnis) oder Zeitstrahl (Fälligkeiten und Meilensteine per Ziehen), Schalter „Wächter“, Kandidaten zuordnen |
| `/onboarding-profiles` | OnboardingProfiles | Onboarding-Profile verwalten |
| `/settings` | Settings | Sprache, Theme, API-Key, Tailscale, Staging, Testing |

Alle Routen sind in `src/App.tsx` definiert und von einem `<Layout>`-Wrapper umgeben (Sidebar + Header).

### Simple Mode

Simple Mode ist eine Bedienoberfläche für Einsteiger, die MySuricates selbst installiert haben: Workflows starten, ihre Fragen beantworten, Ergebnisse lesen. Gebaut und eingestellt wird in Pro. Wo ein Weg dorthin führt, ist der Übergang sichtbar, nie ein stiller Sprung.

`useSettingsStore().simpleMode` schaltet neun Seiten zwischen Pro- und Simple-Variante um:

| Route | Pro | Simple |
|---|---|---|
| `/` | `Dashboard` | `SimpleHome` |
| `/projects` | `Projects` | `SimpleProjects` |
| `/workflows` | `Workflows` | `SimpleWorkflows` |
| `/workflows/:id` | `WorkflowDetail` | `SimpleWorkflowDetail` |
| `/workflows/:id/run/:runId` | `WorkflowExecution` | `SimpleWorkflowExecution` |
| `/runs` | `Runs` | `SimpleRuns` |
| `/schedules` | `Schedules` | `SimpleSchedules` |
| `/todos` | `Todos` | `SimpleTodos` |
| `/activity` | `ActivityLog` | `SimpleActivity` |

Die Umschaltung erfolgt über Route-Wrapper-Komponenten (z.B. `DashboardRoute`, `WorkflowsRoute`, `RunsRoute`) in `App.tsx`, die je nach `simpleMode`-Flag die entsprechende Variante rendern.

- **Seitenarten:** `simpleRouteKind(pathname)` (`utils/simpleRoutes.ts`) ordnet jede Seite zu: `simple` hat eine eigene Fassung (die neun oben, dazu `/runs/:runId`), `shared` gilt unverändert in beiden Modi (`/approvals`, `/settings`, `/docs`), alles andere ist `pro`. Landet der Simple Mode auf einer Pro-Seite, sagt `ProPageNotice` das unter dem Header und bietet „Zu Pro wechseln“ oder „Zurück zu Einfach“. Die Simple-Sidebar zeigt nur die `simple`-Seiten unter ihren Simple-Titeln, kein „Menü bearbeiten“ und eingeklappt einen Modus-Umschalter.
- **Übergabe an Pro:** `ProHandoff` (Begründung und Knopf) und `ProBadge` (Pille „Pro“) in `components/simple/ProHandoff.tsx` markieren, was nach Pro führt: Ablauf ändern, Pro-Kacheln der Startseite, Bauen beim Anlegen, ausgeblendete Einstellungen, Fehleranalyse. `ProHandoff` wechselt über `useOpenInPro(to?)` den Modus und öffnet optional ein Ziel. Einträge mit `ProBadge` (Pro-Kacheln, Projektkarten) öffnen die Pro-Seite, ohne den Modus zu wechseln; dort bietet `ProPageNotice` den Wechsel oder den Rückweg an.
- **Ergebnis zuerst:** Die Simple-Laufseite zeigt während des Laufs eine Fortschrittsliste, danach `RunResultCard` oder `RunFailureCard`; die Schritte liegen eingeklappt unter „Details“. `utils/runResult.ts` liest alles aus dem Run, ohne weitere Anfrage: den Bericht aus dem `dashboard_widget`-Vertrag (vom `dashboard-report`-Block oder Marker, geprüft wie im Workflow-Ergebnis-Widget; ungültig → Hinweis, nie Rohtext), das Gate als Banner, die letzte lesbare Ausgabe (neuester abgeschlossener Versuch je Block, ohne Freigabe-, Eingabe-, TODO-, Bericht- und Kompressionsblöcke, Markerzeilen entfernt, Markdown ohne Bilder), absolute Dateipfade aus der Ausgabe (höchstens 10) und die Zahl angelegter TODOs. `RunFailureCard` nennt Grund und Schritt im Klartext, hat genau einen Knopf zum erneuten Ausführen und „Im Pro-Modus untersuchen“. `SimpleWorkflowDetail` zeigt das letzte Ergebnis des neuesten erfolgreichen Laufs.
- **Starten:** `useSimpleRun` (`components/simple`) startet ohne Eingaben sofort, sonst fragt `ExecuteDialog` mit lesbaren Feldnamen nach; danach öffnet sich die Laufseite. „Nochmal versuchen“ in `SimpleRuns` nutzt `/executions/{id}/retry` und behält den Kontext.
- **Anlegen:** `TemplateSelector variant="simple"` (über `WorkflowCreateFlow`) beginnt bei den Vorlagen, der Pool bleibt; Blaupausen und „Leerer Workflow“ brauchen den Editor und sind durch eine `ProHandoff`-Zeile ersetzt. Projekte entstehen mit dem kompakten Formular (`ProjectFormFields compact`: Name und Ordner, Typ `PERSONAL`). `/workflows?new=1` öffnet den Anlegedialog, `?template=<id>` bzw. `?pool=<id>` mit diesem Eintrag gewählt (unbekannt → Vorlagenliste); `/projects?new=1` öffnet das Projektformular.
- **Zeitpläne:** `<CronPicker simple>` ohne „Erweitert“ und Cron-Zeile. Die Liste zeigt `describeCron` (`utils/cronSchedule.ts`: „Täglich um 09:00“, „Mo, Mi um 08:30“ …); nicht Beschreibbares heißt „Eigener Takt“ und trägt das Pro-Abzeichen (`describeSchedule`). `/schedules?new=1&workflow=<id>` öffnet den Anlegedialog mit diesem Workflow.
- **Startseite (`SimpleHome`):** Das Feld „Was möchtest du tun?“ ist `HomeCommand` (`components/simple/home-command`), mit und ohne Assistenten-Modul. Beim Tippen zeigt es Sofort-Treffer **ohne KI** direkt darunter, gruppiert: eigene Workflows (Starten über `useSimpleRun`, „Letztes Ergebnis“), Vorlagen und Pool (→ Anlegen mit gewähltem Eintrag), Läufe (fehlgeschlagene mit „Nochmal versuchen“), Wartendes (Freigaben direkt in der Zeile beantworten, Rolle `human`), Aufgaben (Suche per `q`, dazu „Als Aufgabe anlegen“), Projekte (Pro-Abzeichen), Zeitpläne (auch „Zeitplan für … anlegen“), Aktionen und Einstellungen (Sprache, Hell/Dunkel, Projekt/Workflow anlegen, Einstellungen, Hinweise, Pro-Modus) und Erklärungen aus den Hinweistexten (klappen in der Zeile auf). Jede Quelle ist ein Hook, der `HomeSource` liefert (`dataSources.ts`, `staticSources.ts`); Zuordnung und Rangfolge sind reine Funktionen in `utils/homeCommand/` (Schlüsselwörter de+en: „fehl/failed“ → Läufe, „heute/morgen/Freitag“ → Zeitpläne, „warte/Freigabe“ → Wartendes, „aufgabe:/todo:“ → Aufgabe anlegen, „englisch/Sprache“, „dunkel/hell“ …); ein erkanntes Anliegen hebt seine Gruppe nach oben, sonst stehen Workflows vorn. Höchstens 3 Treffer je Gruppe und 12 insgesamt, „Alle anzeigen“ führt zur Simple-Seite. Vorlagen, Pool, Aufgabensuche und Zeitpläne werden erst mit Eingabe geladen (entprellt). Seiten und Erklärungen abgeschalteter Module erscheinen nicht. ↑/↓ bewegen die Markierung über alle Treffer (`aria-activedescendant`), Enter löst sie aus, Esc leert. Ohne Assistent ist der erste Treffer markiert und Enter startet ihn; mit Assistent fragt Enter ohne Markierung (oder der Pfeil-Knopf) den Assistenten direkt (`useHomeAssistant`, `POST /assistant/chat` über `useAssistantConversation`). Text und Treffer bleiben stehen, die Antwort erscheint darüber (`HomeAnswer`, Rolle `ai`): die Frage klein, die Antwort als Markdown ohne Bilder, ihre Aktionen als Knöpfe (Workflow starten über `useSimpleRun` samt Eingabedialog, Workflow/Lauf zeigen, Auswahl-Optionen gehen als nächste Nachricht ins selbe Gespräch, Wunsch nur mit Wünsche-Modul) und vorgeschlagene Änderungen zum Bestätigen oder Ablehnen. Während der Assistent arbeitet: Status und „Abbrechen“, bei Fehler „Erneut versuchen“. Die Frage landet in einer Assistenten-Session; „Im Chat weiterführen“ öffnet das Seitenpanel mit genau dieser Session. Solange die Antwort offen ist, setzen weitere Fragen das Gespräch fort; × schließt sie (die nächste Frage beginnt ein neues), Esc leert Feld und Antwort. Ohne Assistenten-Modul gibt es nur die Treffer. Beim ersten Start steht ein Angebot am Ort statt einer Tour: ohne Projekt `FirstProjectActions` (Beispielprojekt zuerst), mit Projekt, aber ohne Workflow „Mit einer Vorlage anfangen“. `SimpleOverview` nennt zuerst, wie viele Läufe auf eine Antwort warten (→ `/approvals`).
- **Hinweise:** `simple-home` (wofür Einfach da ist), `simple-result` (Laufseite), `simple-create` (Vorlagen beim Anlegen).
- **Moduswahl:** `ModeChoiceCard` steht auf dem Pro-Dashboard neben „Erste Schritte“, solange es kein Projekt gibt und `modeChoiceMade` nicht gesetzt ist: „Einfach verwenden“ und „Pro verwenden“ gleichwertig, je mit einem Satz. Die Karte, die Sidebar-Umschalter und der Schalter in den Einstellungen setzen `modeChoiceMade` (`chooseMode`). Der Standard bleibt Pro; bestehende Installationen haben Projekte und sehen die Karte nie.

## Seiten

### Dashboard (`/`)

Die Seite rendert die Widgets aus der Registry `components/dashboard/widgetRegistry.ts`: Kennzahlen, Diagramme, Todos, letzte Runs und Schnellaktionen.

- **Widget = Registry-Eintrag** `{ id, label, description, category, size, module?, multiple?, catalogOnly?, configForm?, component }` (Typ in `widgetDefinition.ts`). Die Komponente lädt ihre Daten selbst über Query-Hooks und bekommt `{ instanceId, config }` als Props. Prop-basierte Karten, die Insights mit eigenem Zeitraum nutzt, bekommen einen Wrapper (`DashboardDataWidgets.tsx`, `InsightsWidgets.tsx`). Die Insights-Widgets (Quality Gates, Fehleranalyse, Kosten pro Workflow, Block-Typen, Trigger-Verteilung, Kosten- und Dauerverlauf, Tokens) sind `React.lazy`-Einträge (`insightsWidgetDefs.ts`, ein gemeinsamer Chunk). Rendert ein Widget nichts, fällt sein Slot weg (`empty:hidden`).
- **KI-Anbieter:** Über den Widgets zeigt `AiProviderStartCard` (auch auf der Simple-Startseite), ob die KI-CLIs bereit sind — solange es kein Projekt gibt oder der Standard-Anbieter nicht bereit ist, mit dem Befehl für das, was fehlt. Im Container nennt die Karte keine Befehle, sondern den Startweg. „Ausblenden“ gilt genau für den gezeigten Status (`aiStatusDismissed` im Settings-Store); ändert er sich, erscheint die Karte wieder.
- **Robust:** Jedes Widget liegt in einer Fehlergrenze (`WidgetFrame`) mit `Suspense` darin: Lazy Widgets zeigen ein Skeleton, bis ihr Code geladen ist, ein gescheiterter Chunk die Fehlerkarte. Die Kennzahlen, die Widgets auf Basis von `/learning/dashboard`, „Letzte Ausführungen", Todos, das Todo-Diagramm und „Geplant" zeigen beim Laden ein `WidgetSkeleton`. Scheitert `/learning/dashboard`, erscheint ein Banner mit Retry; die übrigen Widgets bleiben nutzbar.
- **Widget-Arten:** Ein Layout speichert `WidgetInstance { instanceId, type, hidden?, size?, config? }`. *Standard-Singletons* (Default, `instanceId = type`) sind automatisch in jeder Ansicht und lassen sich nur ausblenden; ihre Menge ist in `widgetRegistry.test.ts` festgeschrieben (`DEFAULT_WIDGET_IDS`). *Katalog-Singletons* (`catalogOnly`, z. B. alle Insights-Widgets) kommen nur über den Katalog in eine Ansicht. *Mehrfach-Widgets* (`multiple`) werden mit eigener Konfiguration angelegt. Katalog- und Mehrfach-Widgets lassen sich entfernen, nicht nur ausblenden. `config` enthält nur primitive Werte; die Keys sind sortiert wie im Backend.
- **Größenklassen** `stat | chart | third | twoThirds | full` bestimmen die Flex-Basis pro Breakpoint (`WIDGET_SIZE_CLASSES`). Zeilen mit weniger Widgets verbreitern diese, statt Lücken zu lassen. Im Bearbeiten-Modus setzt das Größenmenü am Widget `instance.size`; die Registry-Größe („Standard") entfernt das Feld wieder.
- **„Dashboard bearbeiten"**: Jedes Widget hat eine Werkzeugleiste mit Griff (Drag & Drop, auch per Tastatur), Größe, Ausblenden und – bei Katalog- und Mehrfach-Widgets – Entfernen. Beim Ziehen bleiben die Kacheln stehen, die Kachel unter dem Zeiger wird als Ziel hervorgehoben (`dashboardDnd.ts`, auch bei gemischten Größen). „Widget hinzufügen" öffnet den Katalog (`WidgetCatalogDialog`): alle Widgets freigeschalteter Module nach Kategorien mit Beschreibung. Er blendet Ausgeblendetes wieder ein, fügt Katalog-Widgets hinzu und fragt Mehrfach-Widgets über ihr `configForm` nach der Konfiguration; höchstens 100 Widgets je Ansicht. „Zurücksetzen" stellt das Standard-Layout her. Widgets abgeschalteter Module fehlen auch hier.
- **Verlauf & Sync:** Beim Beenden der Bearbeitung speichert `useDashboardLayoutSync` einen Snapshot aller Ansichten unter `/api/v1/dashboard/layouts` (Dedupe per Hash, Aufbewahrung als Setting). Beim Öffnen übernimmt das Dashboard einen neueren Server-Stand, solange lokal nichts geändert ist; sonst fragt ein Banner nach. „Verlauf" im Bearbeiten-Modus: wiederherstellen, benennen, einzelne Ansicht übernehmen, Gerätename setzen. Layouts aus einer neueren App-Version werden nicht geladen, und ein im Browser gespeichertes neueres Layout wird beim Laden nicht überschrieben.
- **Ansichten:** `DashboardViewTabs` zeigt über dem Grid einen Tab pro Ansicht; bei nur einer Ansicht erscheint die Leiste erst im Bearbeiten-Modus. Außerhalb davon ist sie eine ARIA-Tab-Liste (Pfeiltasten, Home/End; der Widget-Bereich ist das Tab-Panel), im Bearbeiten-Modus eine sortierbare Liste. Der aktive Tab wird auf schmalen Bildschirmen in den sichtbaren Bereich gescrollt. Im Bearbeiten-Modus lässt sich eine Ansicht hinzufügen (leer, wird aktiv, das Namensfeld öffnet sich), aus einer Vorlage anlegen („Betrieb", „Kosten & Modelle", „Qualität", „Planung"; `dashboardTemplates.ts`), umbenennen (inline, getrimmt, höchstens 80 Codepoints), duplizieren (Widgets und Filter, Name „… (Kopie)"), löschen (mit Bestätigung; die letzte Ansicht bleibt) und am Griff sortieren; höchstens 20 Ansichten. Diese Änderungen landen mit „Fertig" im Verlauf.
- **Leere Ansicht und Vorlage:** `widgets: []` bedeutet Standard-Layout (alle Standard-Singletons). Eine Vorlage speichert eine explizite Liste: ihre Widgets sichtbar, alle übrigen Standard-Singletons ausgeblendet (`templateWidgets`). Weil neue Widgets nie Standard-Singletons sind, zeigt eine Vorlagen-Ansicht auch nach Updates genau ihre Widgets. Vorlagen enthalten nie Mehrfach- oder Workflow-Widgets.
- **Workflow-Widgets (Phase 5):** Zwei Mehrfach-Widgets der Kategorie „Workflows", beide mit `config { workflowId }` aus dem Katalog-Formular (`WorkflowWidgetConfigForm`). *Workflow-Status* zeigt Name, Status, Zeitpunkt, Dauer, Quality Gate und eine Kennzeichnung, wenn der neueste Run fehlgeschlagen ist — **keinen Ausgabetext**. *Workflow-Ergebnis* zeigt, was der Workflow über den Marker `dashboard_widget: {json}` gemeldet hat: Titel, Ampel, bis zu 6 KPIs, bis zu 10 Zeilen und eine Zusammenfassung, mit der Herkunftszeile „Vom Run gemeldet" (neutrales Token — dasselbe Ergebnis kann aus dem Marker einer KI-Ausgabe oder aus einem deterministischen `dashboard-report`-Block stammen, und die Antwort sagt nicht, welches von beidem). Beide öffnen den Run über `navigate()`, nie über einen Link. `WorkflowWidgetProvider` lädt die Daten aller Workflow-Widgets der Ansicht mit **einer** Anfrage an `/api/v1/dashboard/workflow-results` und abonniert **einmal** `/topic/dashboard/runs`; endet ein Run eines gezeigten Workflows, wird neu geladen (nur ohne WebSocket-Verbindung wird alle 60 s gepollt). Zustände mit eigener Meldung: kein Workflow gewählt, Workflow nicht mehr vorhanden, noch kein Run, Ladefehler, „Kein gültiges Dashboard-Ergebnis" (fehlender oder abgelehnter Marker) — nie der Rohtext, nie ein Ersatz-Grün. Die Widgets starten nie einen Run. Eine *Workflow-Ergebnis*-Kachel kann auch von außerhalb kommen: Der Schalter „Wächter“ eines Ziels legt beim Einschalten die seines Workflows an, auf dieselbe Weise wie der Katalog und nur, wenn noch keine Ansicht sie zeigt (`workflowResultTile.ts`).
- **Warum der Vertrag so eng ist:** Workflow-Ausgabe ist KI-Text aus externen Quellen (Prompt-Injection möglich) und der API-Key liegt im `localStorage` desselben Origins. Der Vertrag wird im Backend validiert (`DashboardWidgetResultParser`) und im Browser noch einmal (`workflowWidgetContract.ts`): bekannte Felder, feste Längen und Anzahlen, Strings ohne Steuer-, Zero-Width-, Bidi- und Private-Use-Zeichen, Status und Trend als Enums. Er enthält **kein** ID- und **kein** URL-Feld — aus KI-Text kann also kein Link und kein Navigationsziel entstehen. Markup wird nicht gefiltert: `<script>` bleibt Text und ist harmlos, weil alles ausschließlich als React-Textknoten gerendert wird. Passt etwas nicht, wird der ganze Vertrag verworfen.
- **Aktive Ansicht pro Gerät:** Ein Tab-Wechsel ist keine Layout-Änderung: kein `dirty`, keine Version, andere Geräte behalten ihre Ansicht. `applyRemoteLayout` behält die lokale aktive Ansicht, sofern der Server-Stand sie enthält. `?view=<id>` öffnet eine Ansicht, ein Wechsel schreibt den Parameter per `replace` (`useDashboardViewParam`); bei einer unbekannten ID bleibt die aktuelle Ansicht; kommt die Ansicht kurz darauf per Sync an, öffnet sie sich noch, sofern inzwischen nicht gewechselt wurde.
- **Filter pro Ansicht:** `filter { projectId?, days? }` wird im Bearbeiten-Modus in `DashboardViewFilterBar` gesetzt (Projekt, Zeitraum über `TimeRangeSelector`); außerhalb erscheint ein gesetzter Filter nur als Hinweiszeile. `DashboardDataProvider` fragt `/learning/dashboard` einmal pro Dashboard mit diesem Filter ab, die Widgets lesen das Ergebnis über `useDashboardData()`. Das Projekt gilt für alle Widgets: Todos, Zeitpläne (`/schedules/remaining-*?projectId=`) und die Gesamtzahlen (Workflows, Ausführungen heute, Erfolgsrate, Top-Workflows) eingeschlossen; nur „Aktive Projekte“ zählt weiter alle Projekte und sagt das unter dem Wert, Version und API-Latenz sind systemweit. Der Zeitraum gilt für Diagramme, Kosten und Statistiken — die Gesamtzahlen zählen unabhängig davon über die gesamte Laufzeit. Beim Wechsel von Ansicht oder Filter bleiben die alten Daten sichtbar (`keepPreviousData`), der Widget-Bereich ist bis zur Antwort abgeblendet. `useDashboardData()` liefert dazu `filter`, `isPlaceholderData` und `dataDays` (Zeitraum der gezeigten Daten, z. B. für das Kosten-Label). Die Insights-Widgets folgen dem Filter.
- **Schutz beim Sync:** Solange eine Version wiederhergestellt, übernommen oder gespeichert wird (`busy`), sind Bearbeiten-Schalter, Grid, Tabs und Filter gesperrt. Ein Layout über 256 KiB wird nicht gesendet, eine eigene Meldung sagt warum. „Ansicht übernehmen" fragt nach, wenn die Ansicht lokal existiert und ungespeicherte Änderungen vorliegen, und meldet eine fehlende Ansicht oder das Limit.
- **Neues Widget**: Komponente plus Registry-Eintrag mit Kategorie und Beschreibung (DE+EN). Neue Widgets sind `catalogOnly` oder `multiple` – als Standard-Singleton erschienen sie in jeder Ansicht, auch in Vorlagen-Ansichten (dafür müsste `DEFAULT_WIDGET_IDS` im Test bewusst geändert werden). Schwere Widgets als `React.lazy`-Eintrag. Die `id` ist persistiert und darf nie umbenannt werden (`widgetRegistry.test.ts` prüft IDs, Labels, Beschreibungen, Module). Inhalte nur als React-Text: `dashboardMarkupGuard.test.ts` verbietet `dangerouslySetInnerHTML`/`innerHTML`/`outerHTML`/`insertAdjacentHTML`, Markdown-Renderer, `<img>`, `<iframe>`, `href`, `src`, `srcSet`, `xlinkHref`, `setAttribute` mit einer URL und `window.open` in `components/dashboard` und `components/insights`; die Dateien der Workflow-Widgets sind dort zusätzlich namentlich festgeschrieben.

### Projects (`/projects`, `/projects/:id`)

- Suchfilter nach Name
- Grid-Layout (1/2/3 Spalten je nach Bildschirmbreite)
- Projekt-Cards mit Typ-Icon und Farbcodierung
- Start/Stop-Buttons für Docker-Container
- Detail-Seite: Projektinfo, Container-Status, zugehörige Workflows
- Modals: Erstellen, Bearbeiten, Löschen-Bestätigung

### Workflows (`/workflows`, `/workflows/:id`)

- 2-Schritt-Erstellung: Template-Auswahl → Formular (Projekt, Name, Beschreibung)
- 55 Built-in-Templates (aus DB, via `WorkflowTemplateSeeder`) plus benutzerdefinierte Templates
- `TemplateSelector`: Vorlagen und Blaupausen, deren Modul aus ist (`requiredModules` vs. `useSystemModules`), stehen hinter einem Zähler; eingeblendet zeigt die Karte Modul und Schalter (`TemplateAvailability.tsx`, `templateAvailability.ts`). Filter „Läuft ohne KI“ über `needsAi` vom Server, Kategorien über `t.templates.categories`. Sind Assistent, Factory und Wünsche alle aus (core), zeigt die Auswahl den Hinweis `build-from-description`
- Detail-Seite: 2/3-Spalte Block-Editor (Drag & Drop), 1/3-Sidebar (Skills, Runs, Memory, Info)
- Block-Editor mit `@dnd-kit` für Drag-and-Drop-Sortierung
- Modals: Block hinzufügen (Typ + JSON-Config via Monaco Editor), Skill aktivieren
- Panel der Workflow-Liste (`WorkflowDetailPanel`) und Sidebar der Detailseite (`WorkflowDetailSidebar`) teilen `WorkflowRecentRuns` (`list`: neueste Läufe mit Startzeit und Dauer; `card`: Gesamtzahl, Gate, Notizen und Blättern — dieselbe Seite speist das Memory-Panel). Weitere Bausteine in `components/workflow/`: `WorkflowBlockList` (Blockliste mit BPMN-Diagramm, Klick blinkt im jeweils anderen), `WorkflowAutomationSettings` (Auto-Todo, Auto-Repair, Auto-Memory mit `ui/Toggle`), `WorkflowAiSettingsCards` (Anbieter, Modell, Aufwand) und `DefaultContextEditor`

### WorkflowExecution (`/workflows/:id/run/:runId`)

- Live-Status-Badge (PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED)
- Split-View: Steps-Panel (Schritt-Indikatoren) + Live-Logs (WebSocket)
- Pause/Resume/Cancel-Buttons während der Ausführung
- Sternebewertung (1-5) nach Abschluss
- Fehler-Panel bei fehlgeschlagenen Runs
- Bausteine in `components/workflow/`: `RunProvenance` (Siegel, Auslöser, Definitions-Hash), `RunInputVariables`, `RunPendingTodosPanel` (vorgeschlagene TODOs freigeben/verwerfen), `RunContainerPanel` (Kommando senden bzw. Terminal, nur während der Run läuft oder pausiert)
- `hooks/useRunInputRecovery.ts` holt nach dem Laden eine wartende Freigabe oder Eingabe über `GET /executions/{id}/approval` zurück — gemeinsam mit `SimpleWorkflowExecution`
- Der Kopf mit Zurück, Status und den Aktionen je Zustand (Pausieren, Fortsetzen, Abbrechen, Löschen, ab letztem Schritt fortsetzen, TODO aus einem fehlgeschlagenen Lauf) ist `RunHeader`, die Notizen sind `RunNotesCard` (der ungespeicherte Text bleibt in der Seite). `components/workflow/useRunDiagramSync.ts` färbt die Diagramm-Kästen nach Step-Status und hebt beim Klick auf einen Kasten dessen Step hervor; das TODO eines fehlgeschlagenen Laufs baut `utils/failedRunTodo.ts` (`buildFailedRunTodo`) in der App-Sprache

### Runs (`/runs`)

- Tabs „Workflows“ und „Ketten“ (nur mit Modul `chains`); beide Listen laden unabhängig vom Tab
- `hooks/useRunsFilters.ts`: gemerkte Filter je Tab; jeder Filterwechsel beginnt wieder bei Seite 0 (die Suche entprellt, erst eine geänderte Suche setzt zurück); Tabwechsel leert die Filter des verlassenen Tabs; Statusoptionen in der App-Sprache (`t.runs.statuses`)
- Bausteine in `components/runs/`: `WorkflowsTab`, `ChainsTab`, Spalten in `runColumns.tsx` (`useWorkflowRunColumns`, `useChainRunColumns`; die Löschspalte nur, wenn `automation.executions.allow-delete` es erlaubt), `DeleteRunButton`, `RunResultText`, `ChainProgress`

### Skills (`/skills`)

- Suchfilter + Kategorie-Filter
- Grid-Layout mit Skill-Cards
- Detail-Slide-Over: Capabilities, Async-Badges, AI-Context-Vorschau, aufklappbarer Verlauf (`skill_versions`)
- Karte und Detail zeigen das Icon des Skills (Emoji oder Lucide-Name über `WorkflowIcon`), ohne Icon das Kategorie-Icon in der Kategoriefarbe
- „Neuer Skill“ und „Bearbeiten“ öffnen `SkillEditorModal` (Name, Kategorie, Icon über `EmojiPicker` — kuratiertes Raster, „Keine“, eigene Eingabe —, Beschreibung, AI-Context im Monaco-Markdown-Editor); „Importieren“ liest eine Export-Datei, „Exportieren“ lädt sie herunter
- Built-in-Skills sind schreibgeschützt und lassen sich als eigener Skill kopieren (ohne Capabilities)
- Nutzt ein versiegelter Workflow den Skill, sind Bearbeiten und Löschen gesperrt — unabhängig vom Modul `sealing`; der Hinweis nennt den Ausweg (entsiegeln, Entsiegeln abgeschaltet oder `seal-immutable`)

### Schedules (`/schedules`)

- Tabelle: Workflow, Cron-Ausdruck, nächste/letzte Ausführung, Status-Toggle
- Erstellen-Modal: Workflow-Auswahl + Cron-Ausdruck + Beschreibung

### Webhooks (`/webhooks`)

- Tabelle: Workflow, URL (kopierbar), Token (maskiert + kopierbar), Trigger-Count, Status
- CopyButton-Komponente mit Erfolgsfeedback
- Erstellen-Modal: Workflow-Auswahl + Beschreibung

### Chains (`/chains`, `/chains/:id/run/:runId`)

- Split-Panel-Layout: Chain-Liste links, Detail/Ausführung rechts
- Pipeline-Visualisierung der Chain-Entries
- Erstellen/Bearbeiten-Modal mit Workflow-Suche und Sortierung
- ChainExecution: Live-Polling für Entry-Status, Navigation zu einzelnen Workflow-Runs

### Todos (`/todos`)

- Liste aller TODOs mit Filter nach Status, Priorität, Projekt, Quelle, Tag
- Status-Workflow: OPEN → IN_PROGRESS → DONE / CANCELLED
- Erkennt automatisch von AI erstellte TODOs (source: AI, sourceAgent: claude-code)
- Badge-Anzeige in der Sidebar für offene TODOs
- Filter-, Ansichts- und Seitenzustand (persistiert, Suche entprellt, `?projectId=` aus dem Cockpit) liefert `hooks/useTodoFilters.ts`; die Quick-Add-Leiste ist `components/todo/TodoQuickAdd.tsx`, ihr Zustand und das Kürzel `n` (fokussiert den Titel außerhalb von Eingabefeldern) liegen in `hooks/useTodoQuickAdd.ts` und damit in der Seite — Eingaben überstehen den Lade-Spinner beim Filterwechsel
- `hooks/useTodoBoard.ts` liefert die Daten der aktiven Ansicht: in der Liste eine Seite, im Board eine Abfrage je Spalte, die der Statusfilter zeigt (nie eine gemischte Seite auf Spalten verteilt); `hooks/useTodoEditor.ts` den Bearbeiten-Dialog samt Deep-Link `?todoId=` (öffnet das TODO einmal, lockert verbergende Filter, hebt die Zeile bis kurz nach dem Schließen hervor), Speichern und Export. Filterleiste und Tag-Chips sind `components/todo/TodoFilterBar.tsx` und `TodoTagChips.tsx`

### ActivityLog (`/activity`)

- Paginierte Log-Tabelle mit Filter nach Level, Kategorie, Workflow
- Kategorien: WORKFLOW, SKILL, SYSTEM, SECURITY, u.a.

### Wishes (`/wishes`)

- Natürlichsprachige Wünsche eingeben und asynchron erfüllen lassen
- Iterationsanzeige, Feedback geben, Wunsch abbrechen oder kristallisieren
- Detail (`components/wishes/WishDetail.tsx`): Kopf mit Aktionen, Status-Zeitleiste und Iterationszähler ist `WishHeaderCard.tsx`, der aufklappbare Iterationsverlauf `WishIterationHistory.tsx`; ob er offen ist, hält `WishDetail`, deshalb bleibt er beim Wechsel zu einem anderen Wunsch offen

### WorkflowFactory (`/factory`)

- Workflow aus Topic-Kategorie generieren
- Vollständige Pipeline: Generieren → Test-Instanz → Verifizieren → Promoten
- Topic-Kategorien-Auswahl

### DialogImport (`/dialogs`)

- Claude-Sessions scannen und als Workflows importieren
- Session-Auswahl, Vorschau, Import mit Projekt-Zuordnung

### Docs (`/docs`)

- Split-Layout: Dokument-Liste links, Markdown-Rendering rechts
- Dokumente werden via `GET /api/v1/docs` aus `docs/`-Verzeichnis geladen
- Markdown-Rendering mit `react-markdown` + `remark-gfm` + `@tailwindcss/typography`

### Insights (`/insights`)

- Plattform-Analysen und Statistiken
- Visualisierung von Workflow-Performance und Nutzungsmustern

### WorkflowDetail (`/workflows/:id`) — Erweiterungen

- **Memory-Tab:** Workflow-Memory-Einträge anzeigen, aktivieren, löschen, neu extrahieren
- **Instructions-Tab:** Workflow-spezifische Agent-Instructions im Monaco-Editor bearbeiten
- **Gate-Status:** Anzeige des letzten Gate-Status im Run-Verlauf
- **BPMN-Diagramm:** `BpmnDiagramView.tsx` zeichnet den BPMN-Export als SVG (Zoom, Klick/Hover je Block, Live-Status im Run). Die reine Logik — XML lesen, oben→unten-Layout, Beschriftungen umbrechen, Element-ID → Block-Auswahl — liegt in `bpmnLayout.ts` (Test `bpmnLayout.test.ts`), die Formen und Marker je Element-Typ in `BpmnShapes.tsx`.
- **Dashboard-Bericht-Formular:** Für Blöcke vom Typ `dashboard-report` zeigt der Block-Editor (Hinzufügen und Bearbeiten) zusätzlich zur JSON-Bearbeitung ein Formular für Status, Titel, Zusammenfassung, Kennzahlen und Einträge (`DashboardReportFields.tsx`, Modell in `dashboardReport.ts`). Grenzen und Statuswerte kommen aus dem einen Vertragsspiegel `components/dashboard/workflowWidgetContract.ts`; Warnungen sind Hinweise, geprüft wird im Backend. Werte mit `{{ … }}` werden nicht auf Länge geprüft.
- **KI-Vorschlag:** `DashboardReportAssist.tsx` bietet „Vorschlag aus dem letzten Run" an, wenn Modul, Einstellung und Anbieter `CLAUDE` vorhanden sind (`useDashboardReportAssist.ts`). Der Vorschlag erscheint als Textknoten in einem Prüf-Bereich und wird erst nach „Übernehmen" in die Config geschrieben. Guard: `components/workflow/__tests__/dashboardReportMarkupGuard.test.ts`. Die drei Abschalter wirken unterschiedlich: Ist das Modul aus, wird der Block-Typ im Editor gar nicht angeboten. Ist die Einstellung aus, bleibt der Block funktionsfähig, nur der Knopf „Vorschlag" erscheint nicht. Fehlt die KI auf diesem Host, wird der Knopf ebenfalls nicht angeboten — geprüft wird dafür der Modellkatalog des Anbieters (`useProviderModels('CLAUDE')` → `available`), der für `CLAUDE` nachsieht, ob das Programm `claude` auf dem `PATH` auflösbar ist; `GET /system/ai-providers` taugt dafür nicht, weil es registrierte Spring-Beans aufzählt und `CLAUDE` immer enthält. Eine installierte, aber nicht angemeldete CLI lässt sich ohne einen echten Aufruf nicht davon unterscheiden — dieser Fall scheitert erst beim Vorschlag selbst.
- **Aufbau:** `components/workflow/useWorkflowBlockEditor.ts` hält Hinzufügen, Bearbeiten (mit Sprungziel aus dem Diagramm, `revealTextFor`), Speichern und Entfernen von Blöcken, `hooks/useWorkflowDetailActions.ts` Bearbeiten, Ausführen, Klonen, Löschen, Optimieren, Reparieren, Anweisungen und Skills samt Dialogzustand. Der Blöcke-Tab ist `components/workflow/WorkflowBlocksTab.tsx` (Liste und Diagramm mit Hover-Querverweis); die Seite verdrahtet Tabs, Sidebar und `WorkflowDetailModals`

### Settings (`/settings`)

- Allgemein: Sprache (EN/DE), Advanced Mode
- KI-Anbieter (`AiProviderStatusCard`, auch im Simple Mode): je Anbieter Programm und Anmeldung gefunden oder der Befehl dafür, „Neu prüfen“ (`POST /system/ai-providers/status/refresh`)
- Aussehen (eigene, einklappbare Sektion): Theme-Auswahl (18 Themes nach bekannten Paletten, gruppiert Dunkel/Hell), eigene Farben pro Theme
- Sicherheit: API-Key-Eingabe (maskiert)
- Remote-Access: Tailscale-Status + Enable/Disable
- Ausführung: Claude-Concurrency, Reserved-for-Interactive, Timeouts, Schalter für den KI-Vorschlag der Dashboard-Berichte
- Benachrichtigungen: Notification-Konfigurationen
- Backups: Datenbank-Backup-Liste
- Wishes: Wish-Einstellungen
- **Stable:** Versionsvergleich Dev/Stable, Sync-Preview, Sync-Definitions, Start/Stop/Promote
- **Testing:** Test-Instanz-Status, Start/Stop/Reset, Sync-Preview, Sync-Definitions, Pull-Changes
- System: Health-Status, Systeminfos, Docker-Status

## Komponenten

### `AssistantPanel.tsx` (`src/components/AssistantPanel.tsx`)

- Floating-Chat-Panel, global über dem Layout eingeblendet
- Kontext-sensitiver Chat mit Page Suggestions
- Persistierte Sessions, WebSocket-Integration
- Größenänderung an linker und oberer Kante (nur Desktop) über `hooks/usePanelResize.ts`: Ziehen vom Anker unten rechts weg vergrößert, je Kante zwischen Mindestmaß und Fenster minus Rand; das Eingabefeld mit Auto-Grow (Enter sendet, Shift+Enter bricht um, höchstens 240 px) ist `assistant/AssistantComposer.tsx`
- Siehe [Assistant System](assistant-system.md) für Details

### `src/components/settings/`

- Settings-Unterkomponenten für die modulare Settings-Seite
- Separate Komponenten: `GeneralSettingsCard`, `AppearanceCard`, `SecurityCard`, `ExecutionSettingsCard`, `NotificationsCard`, `BackupCard`, `WishSettingsCard`, `RemoteAccessCard`, `StagingCard`, `TestingCard`, `SystemInfoCard`
- Navigation über `SettingsNav`, gemeinsames Layout via `SettingsSection`
- `SyncSelectModal`: Auswahl-Modal für den Definition-Sync (`StagingCard`, `TestingCard`); die Auswahl (vorausgewählt ist alles, was sich ändert; Umschalten, Einklappen, Anfrage je Art) liegt in `useSyncSelection.ts`, Abschnitt und Zeile samt Diff-Angaben in `SyncSelectRows.tsx`

### Shared-Komponenten (`src/components/shared/`)

**Regel:** `shared` importiert keine Fachordner (`workflow`, `goal`, `hints`, `todo`, `settings` …) und nicht `assistantPanelUtils`, sondern nur `ui`, `api`, `hooks`, `utils`, `types`, `stores` und `i18n`. Eine Komponente, die einen Fachordner braucht, gehört in diesen Ordner; fachfreie Helfer liegen in `src/utils/` (z. B. `placeholderContext.ts`, `internalAppPath.ts`). Deshalb liegen `AgenticStepCard`, `ForEachStepCard` und `ApprovalDialog` (Freigabe-Dialog im Run, nutzt `Hint`) in `components/workflow/`, `TodoEditModal` in `components/todo/`.

| Komponente | Beschreibung |
|---|---|
| `StatusBadge` | Mappt Status-Strings auf Badge-Varianten |
| `LogViewer` | Log-Anzeige im Terminal-Look: Level-Farben, Icons pro Live-Zeilenart, Step-Badge, Auto-Scroll nur solange man am Ende ist (sonst „Zu den neuesten Zeilen“) |
| `MonacoEditor` | Editor für JSON und Markdown (Monaco, folgt dem Theme). `value` darf jeden Tastendruck über den State zurückreichen: Echos des eigenen `onChange` schreibt der Wrapper nicht ins Modell (`monacoValueSync.ts`), jeder andere Wert ersetzt den Inhalt. `prose` für Fließtext (Umbruch, keine Wortvorschläge, sonst übernimmt Enter einen Vorschlag); `jsonSchema` und `placeholders` für Block-Configs; `path` gibt jedem Dokument ein eigenes Modell (eigene Undo-Historie beim Umschalten, z. B. je Onboarding-Profil und Sprache), `lineNumbers="off"` blendet die Zeilennummern aus. Kein Feld nutzt `@monaco-editor/react` direkt (Wächter in `MonacoEditor.test.tsx`) |
| `ErrorState` | Fehlermeldung mit Icon |
| `EmptyState` | Leerer Zustand mit Beschreibung |
| `Pagination` | Seitennavigation für paginierte Listen |
| `CopyButton` | Kopier-Button mit Erfolgsfeedback |
| `GateStatusBadge` | Gate-Status-Anzeige (RED/YELLOW/GREEN) |
| `GateStatusBanner` | Gate-Status-Banner für Run-Details |
| `HumanInputDialog` | Dialog für Human-Input-Block-Interaktion |
| `ExecutionBadges` | Status-Badges für Ausführungen |
| `ConversionSuggestion` | Vorschlag zur Workflow-Konvertierung |
| `InsightsPanel` | Insights-Anzeige für Runs |
| `PathLinkedOutput` | Output mit klickbaren Dateipfaden |
| `ProviderIcon` | Icon-Anzeige für AI-Provider |
| `RouteErrorFallback` | Error-Boundary für Route-Fehler |
| `TerminalPanel` | Interaktives xterm.js-Terminal mit WebSocket-Anbindung |
| `ErrorBoundary` | React Error Boundary mit Fallback-UI |

### Sidebar (`src/components/layout/`)

- `Sidebar.tsx` verdrahtet nur: Seitenliste und Einrückungsregel für Untereinträge (`isNestedAt`) in `utils/navItems.ts`, Logo in `MeerkatIcon.tsx`, Einfach/Pro-Schalter in `SidebarModeToggle.tsx` (Pille, eingeklappt ein Symbolknopf)
- `hooks/useSidebarOrder.ts` liefert Menü, Bearbeiten-Liste und ausgeblendete Seiten samt Verschieben, Unterteiler anlegen/entfernen, Aus-/Einblenden und Zurücksetzen; Seiten abgeschalteter Module fehlen, behalten aber ihren Platz in der gespeicherten Reihenfolge

## UI-Komponentenbibliothek

Alle Komponenten in `src/components/ui/`:

| Komponente | Props | Beschreibung |
|---|---|---|
| `Button` | `variant` (primary/secondary/ghost/danger), `size` (sm/md/lg), `loading`, `icon` | Standard-Button mit Loading-State |
| `Card` | `padding`, `className` | Container mit Border und Hintergrund |
| `CardHeader` | `title`, `subtitle`, `action` | Card-Header mit optionaler Action |
| `Modal` | `open`, `onClose`, `title`, `footer`, `size` (sm/md/lg/xl) | Overlay-Modal mit Backdrop-Blur |
| `Badge` | `variant` (default/success/warning/danger/accent), `dot` | Status-Badge mit optionalem Punkt |
| `Input` | `label`, `error`, `hint`, `icon` + HTML-Attribute | Formular-Input mit Validierung |
| `Select` | `label`, `options`, `error` | Dropdown-Auswahl |
| `Toggle` | `enabled`, `onChange`, `label`, `disabled` | Switch-Element |
| `Spinner` | `size` (sm/md/lg), `text` | Lade-Indikator |
| `Table<T>` | `columns`, `data`, `keyExtractor`, `onRowClick`, `emptyText` | Generische Tabelle |
| `ToastContainer` | (global) | Toast-Benachrichtigungen (max. 5) |
| `DirectoryPicker` | `value`, `onChange`, `label`, `placeholder`, `error` | Server-Dateisystem-Browser |
| `CronPicker` | `value`, `onChange`, `label` | Cron-Ausdruck-Editor mit Vorschau |
| `TagInput` | `tags`, `onChange`, `suggestions`, `label`, `hint`, `placeholder`, `compact` | Tag-Eingabe mit Autovervollständigung |
| `ModalFooter` | `onCancel`, `onAction`, `actionLabel`, `actionVariant`, `actionLoading`, `actionDisabled`, `cancelLabel`, `cancelVariant` | Standardisierter Modal-Footer |
| `ConfirmModal` | `open`, `onClose`, `onConfirm`, `title`, `message`/children, `confirmLabel`, `variant` (primary/danger), `isPending`, `cancelDisabled`, `confirmIcon`, `cancelVariant`, `size` (sm/md), `extraAction` | Bestätigungsdialog auf `Modal` (Escape und Backdrop schließen). Jeder Ja/Nein-Dialog nutzt ihn statt `Modal` + `ModalFooter` selbst zusammenzusetzen; `isPending` sperrt den Bestätigen-Button |

## Design-System

### CSS-Variablen

Das Theme wird über CSS Custom Properties gesteuert (`src/theme/tokens.ts` für JS-Zugriff, `src/index.css` für CSS-Defaults):

**Dark Theme (Standard):**

| Variable | Wert | Verwendung |
|---|---|---|
| `--color-surface` | `#231e19` | Seiten-Hintergrund |
| `--color-surface-secondary` | `#2a241e` | Sekundärer Hintergrund |
| `--color-surface-raised` | `#2e2720` | Cards, erhöhte Flächen |
| `--color-surface-overlay` | `#332c24` | Modal-Hintergrund |
| `--color-surface-hover` | `#3a3128` | Hover-Hintergrund |
| `--color-border` | `#4a3f33` | Standard-Rahmen |
| `--color-border-strong` | `#5e5143` | Betonte Rahmen |
| `--color-text` | `#f7f3ee` | Primärtext |
| `--color-text-secondary` | `#b8aa9a` | Sekundärtext |
| `--color-text-tertiary` | `#8c7e6e` | Tertiärtext |
| `--color-text-inverse` | `#231e19` | Invertierter Text |
| `--color-accent` | `#eba44a` | Akzentfarbe (Amber) |
| `--color-accent-hover` | `#f2ba62` | Akzent-Hover |
| `--color-accent-subtle` | `#3d2e14` | Akzent-Hintergrund |
| `--color-success` | `#6cc66c` | Erfolg (Grün) |
| `--color-warning` | `#eba44a` | Warnung (Amber) |
| `--color-danger` | `#e07272` | Fehler (Rot) |
| `--color-success-subtle` | `#1e3320` | Erfolg-Hintergrund |
| `--color-warning-subtle` | `#3d2e14` | Warnung-Hintergrund |
| `--color-danger-subtle` | `#3d1f1f` | Fehler-Hintergrund |
| `--color-sidebar` | `#1a1614` | Sidebar-Hintergrund |
| `--color-sidebar-border` | `#342b23` | Sidebar-Rahmen |
| `--color-sidebar-hover` | `#252019` | Sidebar-Hover |
| `--color-sidebar-active` | `#332b22` | Sidebar-Aktiv |
| `--color-sidebar-text` | `#f7f3ee` | Sidebar-Text |
| `--color-sidebar-text-muted` | `#a6917f` | Sidebar-Text (gedämpft) |
| `--color-assistant` | `#1c1812` | Assistenten-Hintergrund |
| `--color-assistant-raised` | `#252019` | Assistenten-Card-Hintergrund |
| `--color-assistant-border` | `#3d3020` | Assistenten-Rahmen |

**Light Theme:** Warmes Farbschema mit `#fff8f3`-Hintergrund, `#3a2e22`-Text und `#f7bd54`-Akzentfarbe.

### Tailwind-Konfiguration (v4, Inline)

Kein `tailwind.config.js` — die Konfiguration erfolgt über `@theme inline` und `@custom-variant` direkt in `src/index.css` (Tailwind CSS v4).

- **Themes:** Farben sind CSS-Variablen (`src/theme/tokens.ts`). MySuricates Dark/Light haben feste Werte in `src/theme/themes.ts`; die 16 Paletten-Themes werden zur Laufzeit aus der offiziellen Palette und den Rollen der Theme-Autoren abgeleitet (`palettes.ts` → `derive.ts`, Quellen: Style Guides bzw. offizielle VS-Code-Themes). Regeln aus einer Blindbewertung (2026-09-14): Flächen/Rahmen/Text = Autorenrollen; Akzent-/Status-/Rollenfarben zweigeteilt – `--color-<name>` ist die Fläche im Originalton (≥ 3:1), `--color-<name>-text` die lesbare Textvariante (≥ 4,5:1), `bg-accent` nutzt `--color-accent-emphasis` (trägt `text-text-inverse`); Statusfarben sind funktional (Farbton wird nur gedreht, wenn der Palettenton nicht als Erfolg/Warnung/Fehler erkennbar ist); Kartenrahmen (`--color-card-border`) nur bei Themes, deren Autoren Rahmen nutzen; Chart-Serien `--color-chart-1..8` in fester Reihenfolge, Rot/Gelb zuletzt. Paletten-Themes lenken zusätzlich die Tailwind-Farbklassen (`blue-500` …) per CSS-Variablen auf die Palette um (`tailwindPaletteVars`). `themes.test.ts` prüft Kontraste, Statusfarbtöne, unterscheidbare Chart-Serien und die umgelenkten Tailwind-Stufen — neue Themes müssen ihn bestehen.
- **Token-Nutzung:** Komponenten schreiben weiter `text-success`/`bg-success`; `index.css` bildet `text-*` über `--text-color-*` auf die Textvariante ab. Keine Hex-Farben in Charts (`var(--color-chart-N)`), gerahmte Karten mit `border-card-border`.
- **Dark Mode:** `class`-basiert via `@custom-variant dark (&:is(.dark *))`; die `.dark`-Klasse folgt der `appearance` des aktiven Themes (ebenso Monaco und xterm via `useThemeAppearance()`)
- **Plugin:** `@tailwindcss/typography` via `@plugin`
- **Schriftarten:**
  - Sans: Plus Jakarta Sans (System-UI Fallback)
  - Mono: JetBrains Mono (Fira Code Fallback)
- **Custom-Animationen:** `fade-in`, `slide-in`, `slide-up`, `pulse-subtle`, `slide-in-right`

### CSS-Utility-Klassen (`index.css`)

| Klasse | Beschreibung |
|---|---|
| `.glass` | Glasmorphism-Effekt (Blur + halbtransparent) |
| `.focus-ring` | Focus-State mit Akzent-Ring |
| `.input-base` | Standard-Input-Styling |
| `.btn-base` | Standard-Button-Styling |
| `.text-balance` | Ausgeglichener Textumbruch |
| `.animate-stagger` | Gestaffelte Animation (50ms pro Kind) |

## State Management

### Zustand-Stores (`src/stores/`)

| Store | State | Persistenz |
|---|---|---|
| `useSidebarStore` | `collapsed`, `mobileOpen`, `toggle()`, `setCollapsed()`, `setMobileOpen()` | `localStorage: sidebar-state` (nur `collapsed`) |
| `useThemeStore` | `themeId`, `lastLightThemeId`, `lastDarkThemeId`, `overrides` (je Theme-ID), `setTheme()`, `toggleAppearance()`, `setOverride()`, `resetOverrides()`, `apply()` | `localStorage: theme-settings` (Version 1; migriert das alte `mode`-Format) |
| `useSettingsStore` | `apiKey`, `advancedMode`, `simpleMode`, `autoTodoDefault`, `autoRepairDefault`, `autoRepairMode`, `defaultAiProvider`, `assistantModel`, `suggestionsModel`, `suggestionsEnabled`, `sidebarItemOrder` (Routen + Unterteiler `divider:<id>`, siehe `utils/sidebarLayout.ts`), `sidebarHiddenItems`, `sidebarDividerLabels`, `pinnedWorkflowIds`, `modeChoiceMade`, `hintsEnabled`, `dismissedHints` (Hinweis-ID → abgewählte Version) | `localStorage: app-settings` (Version 2; v2 macht aus `simpleOnboardingSeen` den abgewählten Hinweis `simple-home`) |
| `useDashboardLayoutStore` | `activeViewId`, `views[]` (je Ansicht `widgets[]` als `WidgetInstance`, optional `filter { projectId?, days? }`; leere Liste = Standard-Layout), `setActiveViewWidgets()`, `resetActiveView()`, `applyRemoteLayout()` (behält die lokale aktive Ansicht), `upsertView()`, `setActiveView()` (keine lokale Änderung), `addView(name?, widgets?)` (leer oder mit Vorlagen-Widgets), `renameView()`, `duplicateView()`, `removeView()`, `moveView()`, `setViewFilter()` ; Selektor `selectActiveView` | `localStorage: dashboard-layout` (Version 2; migriert v1, beim Laden per `sanitizeLayout` validiert) |
| `useDashboardSyncStore` | `deviceLabel`, `baseVersionId`, `dirty`, `setDeviceLabel()`, `markDirty()`, `markSynced()` | `localStorage: dashboard-sync` (Version 1) |
| `useAssistantStore` | `open`, `sessions[]`, `activeSessionId`, `showHistory`, `panelWidth`, `panelHeight`, `pendingInput`, `pendingContext`, `inFlight` und `lastModel` (flüchtig, siehe `useAssistantConversation`) | `localStorage: assistant-sessions` (Sessions, Panel-Größe) |
| `useI18nStore` | `locale: 'en'\|'de'`, `setLocale()` | `localStorage: i18n-locale` |
| `useToastStore` | `toasts[]`, `addToast()`, `removeToast()` | Kein (flüchtig) |

`useI18nStore` ist in `src/i18n/index.ts` definiert (nicht in `src/stores/`).

### TanStack Query (Server State)

Alle API-Daten werden über React Query verwaltet:

- **Caching:** Automatisch mit Query-Keys
- **Invalidierung:** Nach Mutations werden zugehörige Queries invalidiert
- **Optimistic Updates:** Bei Drag-and-Drop-Sortierung der Blöcke
- **Auto-Refetch:** Health-Check alle 30 Sekunden

## API-Hooks (`src/hooks/`)

### `useProjects.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useProjects()` | Query | Alle Projekte |
| `useProject(id)` | Query | Einzelnes Projekt |
| `useCreateProject()` | Mutation | Projekt erstellen |
| `useUpdateProject()` | Mutation | Projekt aktualisieren |
| `useArchiveProject()` | Mutation | Projekt archivieren (Soft-Delete) |
| `useRestoreProject()` | Mutation | Archiviertes Projekt wiederherstellen |
| `useArchivedProjects()` | Query | Archivierte Projekte |
| `useStartContainer(id)` | Mutation | Container starten |
| `useStopContainer(id)` | Mutation | Container stoppen |
| `useGenerateProjectContext()` | Mutation | AI-Kontext für Projekt generieren |
| `useOnboardingStatus(id)` | Query | Onboarding-Status eines Projekts |
| `useEnableOnboarding()` | Mutation | Onboarding aktivieren |
| `useDisableOnboarding()` | Mutation | Onboarding deaktivieren |
| `useRefreshOnboarding()` | Mutation | Onboarding aktualisieren |
| `useOnboardingPreview(id)` | Query | Onboarding-Vorschau |

### `useWorkflows.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWorkflows()` | Query | Alle Workflows |
| `useWorkflow(id)` | Query | Einzelner Workflow |
| `useWorkflowRuns(workflowId)` | Query | Runs eines Workflows (paginiert, Auto-Refetch) |
| `useSimilarWorkflows(id)` | Query | Ähnliche Workflows finden |
| `useAllRuns(page, size, filters)` | Query | Alle Runs (paginiert) |
| `useCreateWorkflow()` | Mutation | Workflow erstellen |
| `useUpdateWorkflow()` | Mutation | Workflow aktualisieren |
| `useDeleteWorkflow()` | Mutation | Workflow löschen (Soft-Delete) |
| `useExecuteWorkflow()` | Mutation | Workflow ausführen |
| `usePauseExecution(runId)` | Mutation | Ausführung pausieren |
| `useResumeExecution(runId)` | Mutation | Ausführung fortsetzen |
| `useResumeFromStep()` | Mutation | Ab bestimmtem Step fortsetzen |
| `useCancelExecution(runId)` | Mutation | Ausführung abbrechen |
| `useRetryExecution()` | Mutation | Fehlgeschlagene Ausführung wiederholen |
| `useRunCommand()` | Mutation | Befehl an laufende Ausführung senden |
| `useSubmitApproval()` | Mutation | Approval-Antwort senden |
| `useApproveTodos()` | Mutation | TODOs für Ausführung genehmigen |
| `useImportWorkflow()` | Mutation | Workflow aus JSON importieren |
| `useOptimizeWorkflow()` | Mutation | Workflow per AI optimieren |
| `useRepairWorkflow()` | Mutation | Fehlgeschlagenen Workflow reparieren |
| `useUpdateRunNotes()` | Mutation | Notizen an Run aktualisieren |

### `useSkills.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useSkills()` | Query | Alle Skills |
| `useSkill(id)` | Query | Einzelner Skill |
| `useSkillCapabilities(skillId)` | Query | Capabilities eines Skills |
| `useActivateSkills()` | Mutation | Skills für Workflow aktivieren |
| `useDeactivateSkill()` | Mutation | Skill deaktivieren |
| `useSkillVersions(skillId, enabled)` | Query | Änderungsverlauf eines Skills |
| `useCreateSkill()` / `useUpdateSkill()` / `useDeleteSkill()` / `useImportSkill()` | Mutation | Eigene Skills anlegen, bearbeiten, löschen, importieren; 409 `SKILL_PINNED_BY_SEAL` erkennt `isSkillPinnedBySealError` |

### `useSchedules.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useSchedules()` | Query | Alle Schedules |
| `useMaintenanceMode()` | Query | Maintenance-Mode-Status und aktive `skip-until`-Pause (`pausedUntil`) |
| `useSetMaintenanceMode()` | Mutation | Maintenance-Mode umschalten |
| `useCalendarData(start, end)` | Query | Kalender-Daten für Zeitraum |
| `useRemainingToday()` | Query | Verbleibende Runs heute |
| `useRemainingThisWeek()` | Query | Verbleibende Runs diese Woche |
| `useCronPreview(expr)` | Query | Vorschau nächster Ausführungen |
| `useScheduleRuns(id)` | Query | Runs eines Schedules |
| `useCreateSchedule()` | Mutation | Schedule erstellen |
| `useUpdateSchedule()` | Mutation | Schedule aktualisieren |
| `useToggleSchedule()` | Mutation | Schedule ein-/ausschalten |
| `useDeleteSchedule()` | Mutation | Schedule löschen |

### `useWebhooks.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWebhooks()` | Query | Alle Webhooks |
| `useCreateWebhook()` | Mutation | Webhook erstellen |
| `useToggleWebhook()` | Mutation | Webhook ein-/ausschalten |
| `useDeleteWebhook()` | Mutation | Webhook löschen |

### `useSystem.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useSystemHealth()` | Query (30s) | Health-Check |
| `useSystemInfo()` | Query | Systeminfos |
| `useRemoteAccessStatus()` | Query | Tailscale-Status |
| `useEnableRemoteAccess()` | Mutation | Tailscale aktivieren |
| `useDisableRemoteAccess()` | Mutation | Tailscale deaktivieren |
| `useBackupList()` | Query | Datenbank-Backups |
| `useAiProviders()` | Query | AI-Provider und Default |
| `usePiCatalog()` | Query | Pi-Provider-Katalog |
| `useAiProviderStatus()` | Query (60 s stale) | Bereitschaft der KI-CLIs je Anbieter |
| `useRefreshAiProviderStatus()` | Mutation | Bereitschaft sofort neu prüfen |
| `useUpdateSystemSetting()` | Mutation | System-Einstellung ändern |
| `useAssistantModelSetting()` | Query | Assistenten-Modell-Einstellung |
| `useAutoRepairModeSetting()` | Query | Auto-Repair-Modus-Einstellung |
| `useSuggestionsModelSetting()` | Query | Suggestions-Modell-Einstellung |
| `useAssistantThinkingPhrases()` | Query | Lokalisierte Thinking-Phrasen |
| `useRepairAllFailed()` | Mutation | Alle fehlgeschlagenen Runs reparieren |
| `useExecutionConfig()` | Query | Ausführungs-Konfiguration |
| `useUpdateExecutionConfig()` | Mutation | Ausführungs-Konfiguration ändern |
| `useBrowseDirectory()` | Query | Server-Dateisystem durchsuchen |

### `useChains.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useChains()` | Query | Alle Chains |
| `useChain(id)` | Query | Einzelne Chain |
| `useChainRuns(chainId)` | Query | Runs einer Chain |
| `useChainRun(chainId, runId)` | Query | Einzelner Chain-Run |
| `useAllChainRuns(page, size, status)` | Query | Alle Chain-Runs (paginiert) |
| `useCreateChain()` | Mutation | Chain erstellen |
| `useUpdateChain()` | Mutation | Chain aktualisieren |
| `useDeleteChain()` | Mutation | Chain löschen |
| `useExecuteChain()` | Mutation | Chain ausführen |
| `useCancelChainRun()` | Mutation | Chain-Run abbrechen |

### `useTodos.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTodos(filters)` | Query | TODOs mit Filtern (paginiert, Auto-Refetch) |
| `useTodo(id)` | Query | Einzelnes TODO |
| `useTodoCount()` | Query | Anzahl TODOs nach Status |
| `useProjectActiveTodos(projectId)` | Query | Aktive TODOs eines Projekts |
| `useCreateTodo()` | Mutation | TODO erstellen |
| `useUpdateTodo()` | Mutation | TODO aktualisieren |
| `useUpdateTodoStatus()` | Mutation | Status ändern |
| `useBulkUpdateTodoStatus()` | Mutation | Mehrere TODOs gleichzeitig ändern |
| `useArchiveStale()` | Mutation | Veraltete TODOs archivieren |
| `useDeleteTodo()` | Mutation | TODO löschen |
| `useExportTodo()` | Mutation | TODO in externen Tracker exportieren |
| `useResolveTodo()` | Mutation | AI-Auflösung für TODO starten |

### `useActivityLog.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useActivityLog(page, size, filters)` | Query | Activity-Log-Einträge (paginiert) |

### `useWorkflowMemory.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWorkflowMemories(workflowId)` | Query | Memory-Einträge eines Workflows |
| `useExtractMemory()` | Mutation | Wissen aus Runs extrahieren |
| `useActivateMemory()` | Mutation | Memory-Eintrag aktivieren |
| `useUpdateMemory()` | Mutation | Memory-Eintrag bearbeiten |
| `useDeleteMemory()` | Mutation | Memory-Eintrag löschen |

### `useStaging.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useStagingStatus()` | Query | Stable-Status + Version |
| `useStagingStart()` | Mutation | Stable starten |
| `useStagingStop()` | Mutation | Stable stoppen |
| `useStagingPromote()` | Mutation | Promote ausführen |
| `useStagingCancel()` | Mutation | Laufende Promotion abbrechen |
| `useStagingSyncPreview(enabled)` | Query | Sync-Vorschau |
| `useStagingSync()` | Mutation | Definitionen synchronisieren |

### `useTesting.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTestingStatus()` | Query | Test-Instanz-Status |
| `useTestingStart()` | Mutation | Test-Instanz starten |
| `useTestingStop()` | Mutation | Test-Instanz stoppen |
| `useTestingReset()` | Mutation | Test-Datenbank zurücksetzen |
| `useTestingSyncPreview(enabled)` | Query | Sync-Vorschau für Test-Instanz |
| `useTestingSync()` | Mutation | Definitionen zu Test synchronisieren |
| `useTestingPullChanges()` | Mutation | Änderungen von Test nach Dev ziehen |

### `useWishes.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWishes()` | Query | Alle Wishes |
| `useWish(id)` | Query | Einzelner Wish |
| `useCreateWish()` | Mutation | Wish erstellen |
| `useCancelWish()` | Mutation | Wish abbrechen |
| `useRespondToWish()` | Mutation | Feedback geben |
| `useCrystallizeWish()` | Mutation | Wish kristallisieren |
| `useDeleteWish()` | Mutation | Wish löschen |

### `useDocs.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useDocsList()` | Query | Verfügbare Dokumente |
| `useGroupedDocs()` | Query (Wrapper) | Dokumente gruppiert nach Label mit DE/EN-Varianten |
| `useDocContent(name, lang)` | Query | Inhalt eines Dokuments (sprachspezifisch) |

### `usePool.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `usePoolWorkflows()` | Query | Pool-Workflows |
| `useInstallPoolWorkflow()` | Mutation | Workflow aus Pool installieren |
| `usePoolExport()` | Mutation | Workflow in den Pool exportieren |

### `useAssistant.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useAssistantChat()` | Mutation | Chat-Nachricht an den Assistenten senden (Abort-Support) |
| `useConfirmPendingTool()` | Mutation | Pending-Tool-Aktion bestätigen |
| `useRejectPendingTool()` | Mutation | Pending-Tool-Aktion ablehnen |
| `useAssistantSuggestions(page)` | Query | Seitenspezifische Vorschläge laden |
| `useAssistantSessions(enabled)` | Query | Session-Zusammenfassungen laden |
| `useAssistantSessionDetail(sessionId)` | Query | Vollständige Session-Details |
| `useDeleteAssistantSession()` | Mutation | Session löschen |

### `useChainTemplates.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useChainTemplates()` | Query | Verfügbare Chain-Templates |
| `useInstallChainTemplate()` | Mutation | Chain-Template installieren |

### `useCompletionTriggers.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useCompletionTriggersAsSource(workflowId)` | Query | Trigger, bei denen dieser Workflow Quelle ist |
| `useCompletionTriggersAsTarget(workflowId)` | Query | Trigger, bei denen dieser Workflow Ziel ist |
| `useCreateCompletionTrigger()` | Mutation | Trigger erstellen |
| `useToggleCompletionTrigger(workflowId)` | Mutation | Trigger ein-/ausschalten |
| `useDeleteCompletionTrigger(workflowId)` | Mutation | Trigger löschen |

### `useTodoTriggers.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTodoTriggers()` | Query | Alle TodoTrigger |
| `useCreateTodoTrigger()` | Mutation | TodoTrigger erstellen |
| `useToggleTodoTrigger()` | Mutation | TodoTrigger aktivieren/deaktivieren |
| `useDeleteTodoTrigger()` | Mutation | TodoTrigger löschen |

### `useDialog.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useDialogSessionCounts()` | Query | Session-Anzahl pro Projekt |
| `useDialogSessions(projectPath?, limit)` | Query | Claude-Sessions (optional nach Projekt gefiltert) |
| `useDialogProjects()` | Query | Projektpfade mit Dialog-Sessions |
| `useAnalyzeDialog()` | Mutation | Session analysieren für Workflow-Vorschläge |
| `useCreateWorkflowFromDialog()` | Mutation | Workflow aus Dialog-Analyse erstellen |

### `useInsights.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useRunInsights(runId)` | Query | AI-generierte Insights für einen spezifischen Run |

### `useNotifications.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useNotifications()` | Query | Alle Notification-Konfigurationen |
| `useCreateNotification()` | Mutation | Notification erstellen |
| `useToggleNotification()` | Mutation | Notification ein-/ausschalten |
| `useDeleteNotification()` | Mutation | Notification löschen |

### `usePersistedState.ts`

Generischer Hook für persistierten State via `localStorage`.

### `useWorkflowFactory.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useFactoryTopics()` | Query | Factory-Topic-Kategorien |
| `useFactoryGenerate()` | Mutation | Workflow aus Topic generieren |
| `useFactoryRun()` | Mutation | Factory-Request End-to-End ausführen |
| `useFactoryVerify()` | Mutation | Factory-Workflow verifizieren |
| `useFactoryPromote()` | Mutation | Factory-Workflow promoten |

### `useWebSocket.ts`

```typescript
useExecutionWebSocket(runId: string | null, workflowId?: string, options?: { liveOutput?: boolean }) → {
  logs: ExecutionLogEntry[],   // { id, timestamp, level, message, kind?, stepIndex?, stepLabel?, skipped? }
  connected: boolean,
  clearLogs: () => void,
  pendingApproval: PendingApproval | null,
  pendingHumanInput: object | null,
  agenticStates: Map<number, object>,
  forEachStates: Map<number, ForEachState>
}
```

Verbindet sich über SockJS/STOMP mit dem Backend und empfängt Live-Logs, Approval-Prompts, Human-Input-Prompts, Agentic-States und ForEach-States. Mit `liveOutput` (Default `true`) kommen `live_output`-Batches dazu; beim (Re-)Connect lädt der Hook den Puffer über `GET /executions/{id}/live-log` nach und dedupliziert über `bufferId` + `seq` (Lücken werden als Marker angezeigt). Die Abbildung Nachricht → Eintrag liegt rein funktional in `hooks/executionLogEntries.ts`.

### `useToast.ts`

```typescript
useToast() → {
  success(message), error(message), info(message), warning(message)
}
```

### `useAssistantWebSocket.ts`

```typescript
useAssistantWebSocket(requestId: string | null, sessionId: string | null) → {
  currentPhase: string | null,
  events: AssistantPushEvent[],
  connected: boolean,
  clearEvents: () => void
}
```

WebSocket-Verbindung für Echtzeit-Status-Updates und Push-Events des Assistenten.

### `useIntegrations.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useIntegrations()` | Query | Alle Provider mit Verbindungsstatus |
| `useIntegration(providerId)` | Query | Einzelner Integration-Provider |
| `useTaskTargets(providerId, enabled)` | Query | Task-Export-Ziele eines Providers |
| `useConnectIntegration()` | Mutation | OAuth-Verbindung herstellen |
| `useDisconnectIntegration()` | Mutation | OAuth-Verbindung trennen |
| `useSaveCredentials()` | Mutation | OAuth-Credentials speichern |
| `useDeleteCredentials()` | Mutation | Credentials löschen |
| `useTestIntegrationAction()` | Mutation | Provider-Aktion testen |

Die Seite `/integrations` setzt sich aus `components/integrations/ProviderCard` (Karte mit Status, Berechtigungen, Verbinden/Trennen) und `ProviderDetailPanel` (Verbindung, Berechtigungen, Konfiguration, Aktionstest; je Anbieter neu, also eingeklappt und ohne altes Ergebnis) zusammen. `hooks/useProviderCopy.ts` liefert die Anzeigetexte je Anbieter (Name, Beschreibung, Aktionen; DE/EN, sonst der Text des Backends), `hooks/useIntegrationActionTest.ts` den Probelauf einer Aktion (laufende Aktion, Ergebnis als JSON).

### `useWorkflowVersions.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWorkflowVersions(id)` | Query | Versionshistorie eines Workflows |
| `useRestoreWorkflowVersion()` | Mutation | Version wiederherstellen |

### `useOnboardingProfiles.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useOnboardingProfiles()` | Query | Alle Onboarding-Profile |
| `useOnboardingProfile(id)` | Query | Einzelnes Profil |
| `useCreateOnboardingProfile()` | Mutation | Profil erstellen |
| `useUpdateOnboardingProfile()` | Mutation | Profil aktualisieren |
| `useDeleteOnboardingProfile()` | Mutation | Profil löschen |
| `useResetOnboardingProfile()` | Mutation | Profil auf Built-in-Defaults zurücksetzen |
| `useOnboardingProfileHistory(profileId)` | Query | Versionshistorie eines Profils |

Den Bearbeitungszustand der Seite `/onboarding-profiles` hält `hooks/useOnboardingProfileEditor.ts`: Auswahl (aus der Live-Liste), Entwurf von Name, Beschreibung und Inhalt je Sprache, die bearbeitete Inhaltssprache, `hasChanges`, Speichern, Löschen und das Zurücksetzen auf den ausgelieferten Text (danach zeigt der Editor den übernommenen Text). Die Bausteine liegen in `components/onboarding/`: `ProfileList` (Suche, Markierungen), `ProfileEditor`, `CreateProfileModal`, `ShippedModal`, `HistoryModal` und `ContentLanguageToggle` (DE|EN).

### `useCalendarAggregated.ts`

Aggregiert Events aus mehreren Quellen (Google Calendar, Trello, Taskwarrior, Schedules, Runs) über das Calendar-Adapter-Pattern. Gibt `{ events, sources, isLoading, setSourceColor }` zurück.

### `useCalendarColors.ts`

Verwaltet benutzerdefinierte Farben pro Kalender-Quelle (persistiert in `localStorage`).

### `useCalendarNavigation.ts`

Was `UnifiedCalendarGrid` zeigt: Ansicht (Monat, Woche, Tag), Monat mit gewähltem Tag, Datum der Wochen- und Tagesansicht und der daraus geladene Zeitraum (`from`/`to`, ISO); dazu Vor/Zurück, „Heute“ und `getMonday()`. Woche und Tag öffnen auf dem gewählten Tag, ohne Auswahl auf dem Ersten des Monats; ein Monatsschritt hebt die Auswahl auf. Die Monatsansicht ist `calendar/CalendarMonthView.tsx`, neben `CalendarWeekView` und `CalendarDayView`.

### `useConversion.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useConversionSuggestion(runId, enabled)` | Query | AI-generierter Workflow-Vorschlag aus einem Run |
| `useCreateWorkflowFromSuggestion()` | Mutation | Workflow aus Konvertierungs-Vorschlag erstellen |

### `useLearning.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useDashboard(days, projectId?)` | Query | Lern-Dashboard-Daten (Auto-Poll 30s) |
| `usePlatformStats()` | Query | Plattform-weite Statistiken |
| `useRateRun()` | Mutation | Workflow-Run bewerten |

### `useReports.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useReportsList()` | Query | Liste verfügbarer Reports |
| `useReportContent(name)` | Query | Inhalt eines spezifischen Reports |

### `useTemplates.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTemplates()` | Query | Alle Workflow-Templates |
| `useCreateTemplate()` | Mutation | Workflow-Template erstellen |
| `useSaveWorkflowAsTemplate()` | Mutation | Bestehenden Workflow als Template speichern |

### `useDeadLetters.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useDeadLetters(status, page, size)` | Query | Paginierte Dead-Letter-Queue-Einträge |
| `useRetryDeadLetter()` | Mutation | Dead-Letter-Eintrag erneut versuchen |
| `useDiscardDeadLetter()` | Mutation | Dead-Letter-Eintrag verwerfen |

### `useLatestRunStatuses.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useLatestRunStatuses(size)` | Hook | Map von Workflow-ID → letzter Run-Status (abgeleitet aus `useAllRuns`) |

### `usePaletteHistory.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `usePaletteHistory(contextId)` | Hook | Recent/Pinned-IDs für die Command-Palette (localStorage-basiert) |

### `usePaletteSelection.ts` (`components/palette/`)

Ausgewählte Zeile der Command-Palette und ihre Tastatursteuerung am Fenster (greift auch mit Fokus im Suchfeld): ↑/↓, Enter führt aus (Shift öffnet die Seite, Cmd/Ctrl in neuem Tab), Alt+1–9 springt auf Zeile N, Tab/Shift+Tab wechselt den Kontext, Esc schließt — bei offenem Variablen-Formular schließt Esc nur dieses. Zeilenfolge: leere Suche Angeheftet → Zuletzt, sonst Treffer → Assistenten-Zeile. `CommandPalette.tsx` löst den Kontext aus der Route und wechselt ihn, der Dialog selbst ist `PaletteDialog.tsx`.

### `useTodoOverview.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTodoOverview()` | Query | Projektübergreifende Cockpit-Übersicht mit per-Projekt-Rollups |

### `useWorkflowDebug.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useBrokenWorkflows()` | Query | Workflows mit fehlgeschlagenem letztem Run (Auto-Poll 30s) |

### `useIsMobile.ts`

Responsive-Hook, erkennt Mobile-Breakpoint (max-width: 1023px) für adaptive UI-Anpassungen.

## Internationalisierung (i18n)

- **Sprachen:** Deutsch (`de`), Englisch (`en`)
- **Dateien:** `src/i18n/de.ts`, `src/i18n/en.ts`
- **Hook:** `useT()` → gibt das aktuelle Übersetzungsobjekt zurück
- **Store:** `useI18nStore()` → `locale`, `setLocale()`
- **Umschalten:** Button im Header
- **Datum und Zahl:** nur über `src/utils/localeFormat.ts` (`formatDate`, `formatTimeOfDay`, `formatTimestamp`, `formatNumber`, `formatRelativeUnit`) bzw. `src/utils/formatTime.ts`, immer mit `locale` aus `useI18nStore` — ohne Locale schreibt der Browser in seiner eigenen Sprache. `src/__tests__/localeFormat.guard.test.ts` lässt `Intl` und die `toLocale…String`-Methoden nirgends sonst zu
- **`<html lang>`:** folgt der App-Sprache (`syncDocumentLanguage()` in `main.tsx`), für Screenreader und Silbentrennung

### Übersetzungsstruktur

~1.250 Leaf-Strings in 34+ Top-Level-Keys (Auszug):

```
common (~46 Keys) ──── Allgemeine Labels (Erstellen, Löschen, Suchen, ...)
nav (~27 Keys) ──────── Navigation (Dashboard, Projekte, ...)
simple (~125 Keys) ──── Simple-Mode-Texte (Home, Workflows, Runs, ...)
dashboard (~26 Keys) ── Dashboard-Texte
projects (~52 Keys) ─── Projekte + Onboarding, Typen, Status
workflows (~155 Keys)   Workflows + Block-Typen, Ausführung, Gate, Agentic
skills (~13 Keys) ───── Skills + Kategorien
schedules (~55 Keys) ── Zeitpläne + Wochentage
webhooks (8 Keys) ───── Webhooks
integrations (~40 Keys) Integrations-Provider
calendar (~8 Keys) ──── Kalender + Quellen
notifications (~16 Keys) Benachrichtigungen
todoTriggers (~17 Keys) Todo-Trigger
completionTriggers (~15 Keys) Completion-Trigger
settings (~150 Keys) ── Einstellungen (General, Execution, Staging, Testing)
toast (~60 Keys) ────── Toast-Nachrichten
templates (~14 Keys) ── Workflow-Templates
pool (7 Keys) ───────── Workflow-Pool
insights (~75 Keys) ─── Plattform-Insights
runs (~16 Keys) ──────── Run-Übersicht
activityLog (~12 Keys)  Activity-Log
todos (~38 Keys) ────── Todos + Status, Prioritäten, Quellen
wishes (~55 Keys) ───── Wishes + Status, Iteration
chains (~27 Keys) ───── Chains + Ausführung
factory (~40 Keys) ──── Workflow-Factory
assistant (~22 Keys) ── Assistent
commandPalette (~28 Keys) Command-Palette
charts (~10 Keys) ───── Chart-Labels
```

## PWA-Konfiguration

Konfiguriert über `vite-plugin-pwa` in `vite.config.ts`:

- **Name:** MySuricates
- **Kurzname:** MySuricates
- **Display:** Standalone
- **Theme-Color:** `#231e19`
- **Service Worker:** Auto-Update
- **Icon:** `/icons/icon.svg` (SVG, maskierbar)

## API-Client (`src/api/client.ts`)

```typescript
class ApiClient {
  BASE_URL = '/api/v1'

  getHeaders() → { Content-Type: application/json, X-API-Key: ... }

  get<T>(path) → Promise<T>
  post<T>(path, body?, options?) → Promise<T>
  put<T>(path, body?) → Promise<T>
  patch<T>(path, body?) → Promise<T>
  delete<T>(path) → Promise<T>
}
```

Fehler werden als `ApiError` geworfen mit `{ error, timestamp, fieldErrors? }`. Requests haben ein 300-Sekunden-Timeout via `AbortController`.

Standalone-Exports: `openPath(path)` öffnet einen Dateipfad via Backend, `apiErrorMessage(err, fallback)` extrahiert Fehlermeldungen aus API-Responses.

Der API-Key wird aus dem `settingsStore` gelesen und automatisch an alle Requests angehängt.

## Proxy-Konfiguration (Vite)

In `vite.config.ts`:

| Pfad | Ziel | Beschreibung |
|---|---|---|
| `/api` | `http://localhost:9090` | REST-API |
| `/ws` | `ws://localhost:9090` | WebSocket (STOMP/SockJS) |

Der Dev-Server läuft auf Port 3000 (`strictPort`) und proxied alle API-Anfragen an das Backend auf Port 9090. Via `CYPRESS_TARGET=test` werden die Proxy-Ziele auf Port 8090 umgeleitet. HTTPS wird automatisch aktiviert wenn `../certs/dev.crt` und `../certs/dev.key` existieren.

# Frontend

Die Oberfläche ist eine React-SPA (TypeScript, Vite, TailwindCSS) unter `ai-automation-frontend/`. Diese
Seite beschreibt die Prinzipien und Regeln, nach denen sie gebaut ist. Was es gibt, steht im Code selbst:
die Routen in `src/App.tsx`, die Seiten unter `src/pages/`, die Bausteine je Fachordner unter
`src/components/`, die Hooks unter `src/hooks/`, das Menü in `src/utils/navItems.ts`.

## Aufbau

Jede Route lädt ihre Seite per `lazy()`; Seiten optionaler Module umschließt `ModuleRoute`, das bei
ausgeschaltetem Modul einen Hinweis zeigt, und Abfragen solcher Module schaltet `useModuleEnabled(name)`
ab. Jeder Menüeintrag nennt im Pflichtfeld `hint` den Hinweis seiner Seite — kein Menüpunkt ohne
Erklärung (`utils/__tests__/navHints.test.ts`). Der Chat-Assistent ist ein Panel über dem Layout, siehe
[Assistant System](assistant-system.md).

### Simple Mode

Simple Mode ist eine Bedienoberfläche für Einsteiger, die MySuricates selbst installiert haben: Workflows starten, ihre Fragen beantworten, Ergebnisse lesen. Gebaut und eingestellt wird in Pro. Wo ein Weg dorthin führt, ist der Übergang sichtbar, nie ein stiller Sprung.

- **Seitenarten:** `simpleRouteKind(pathname)` (`utils/simpleRoutes.ts`) ordnet jede Seite zu: `simple` hat eine eigene Fassung, `shared` gilt unverändert in beiden Modi, alles andere ist `pro`. Die Umschaltung machen Route-Wrapper in `App.tsx` anhand von `useSettingsStore().simpleMode`. Landet der Simple Mode auf einer Pro-Seite, sagt `ProPageNotice` das unter dem Header und bietet „Zu Pro wechseln“ oder „Zurück zu Einfach“.
- **Übergabe an Pro:** `ProHandoff` (Begründung und Knopf) und `ProBadge` (Pille „Pro“) in `components/simple/ProHandoff.tsx` markieren alles, was nach Pro führt. `ProHandoff` wechselt über `useOpenInPro(to?)` den Modus; Einträge mit `ProBadge` öffnen die Pro-Seite, ohne den Modus zu wechseln.
- **Ergebnis zuerst:** Nach einem Lauf zeigt die Simple-Laufseite `RunResultCard` oder `RunFailureCard`, die Schritte liegen eingeklappt darunter. `utils/runResult.ts` liest alles aus dem Run, ohne weitere Anfrage; ein ungültiger `dashboard_widget`-Vertrag ergibt einen Hinweis, nie Rohtext.
- **Moduswahl:** Der Standard bleibt Pro. `ModeChoiceCard` bietet die Wahl nur an, solange es kein Projekt gibt und noch keine Wahl getroffen ist (`modeChoiceMade`).

### Shared-Komponenten

**Regel:** `shared` importiert keine Fachordner (`workflow`, `goal`, `hints`, `todo`, `settings` …) und nicht `assistantPanelUtils`, sondern nur `ui`, `api`, `hooks`, `utils`, `types`, `stores` und `i18n`. Eine Komponente, die einen Fachordner braucht, gehört in diesen Ordner; fachfreie Helfer liegen in `src/utils/` (z. B. `placeholderContext.ts`, `internalAppPath.ts`). Deshalb liegen `AgenticStepCard`, `ForEachStepCard` und `ApprovalDialog` (Freigabe-Dialog im Run, nutzt `Hint`) in `components/workflow/`, `TodoEditModal` in `components/todo/`. ESLint prüft die Regel (`no-restricted-imports`, für `import()` `no-restricted-syntax`, in `eslint.config.js`); `npm run lint` scheitert an einem Verstoß.

Die Basis-Bausteine liegen in `src/components/ui/`. Jeder Ja/Nein-Dialog nutzt `ConfirmModal`, statt `Modal` und `ModalFooter` selbst zusammenzusetzen; `isPending` sperrt dort den Bestätigen-Button. Kein Feld nutzt `@monaco-editor/react` direkt, sondern den Wrapper `shared/MonacoEditor` (Wächter in `MonacoEditor.test.tsx`).

## Dashboard-Widgets

Das Dashboard rendert die Widgets aus der Registry `components/dashboard/widgetRegistry.ts`. Ein Widget ist ein Registry-Eintrag (Typ in `widgetDefinition.ts`); die Komponente lädt ihre Daten selbst und bekommt `{ instanceId, config }` als Props. Jedes Widget liegt in einer Fehlergrenze (`WidgetFrame`), ein scheiterndes reißt die übrigen nicht mit.

- **Widget-Arten:** *Standard-Singletons* sind in jeder Ansicht und lassen sich nur ausblenden; ihre Menge ist in `widgetRegistry.test.ts` festgeschrieben (`DEFAULT_WIDGET_IDS`). *Katalog-Singletons* (`catalogOnly`) kommen nur über den Katalog in eine Ansicht, *Mehrfach-Widgets* (`multiple`) mit eigener Konfiguration. `config` enthält nur primitive Werte.
- **Ansichten und Sync:** Ein Layout hält mehrere Ansichten mit eigenen Widgets und Filtern; „Fertig“ speichert einen Snapshot unter `/api/v1/dashboard/layouts`. Ein Tab-Wechsel ist keine Layout-Änderung, andere Geräte behalten ihre Ansicht. Layouts aus einer neueren App-Version werden nicht geladen.
- **Neues Widget:** Komponente plus Registry-Eintrag mit Kategorie und Beschreibung (DE+EN). Neue Widgets sind `catalogOnly` oder `multiple` — als Standard-Singleton erschienen sie in jeder Ansicht, auch in Vorlagen-Ansichten. Schwere Widgets als `React.lazy`-Eintrag. Die `id` ist persistiert und darf nie umbenannt werden.
- **Warum der Vertrag der Workflow-Widgets so eng ist:** Workflow-Ausgabe ist KI-Text aus externen Quellen (Prompt-Injection möglich), und der API-Key liegt im `localStorage` desselben Origins. Der Marker `dashboard_widget` wird im Backend validiert (`DashboardWidgetResultParser`) und im Browser noch einmal (`workflowWidgetContract.ts`): bekannte Felder, feste Längen und Anzahlen, Strings ohne Steuer-, Zero-Width-, Bidi- und Private-Use-Zeichen, Status und Trend als Enums. Er enthält **kein** ID- und **kein** URL-Feld — aus KI-Text kann also kein Link und kein Navigationsziel entstehen. Alles wird ausschließlich als React-Textknoten gerendert; passt etwas nicht, wird der ganze Vertrag verworfen. `dashboardMarkupGuard.test.ts` verbietet in `components/dashboard` und `components/insights` `dangerouslySetInnerHTML` und Verwandte, Markdown-Renderer, `<img>`, `<iframe>`, `href`, `src` und `window.open`. Die Widgets starten nie einen Run.

## Regeln aus einzelnen Seiten

- **Freigaben und Eingaben im Lauf:** `hooks/useRunPendingInput.ts` ist die eine Quelle für Freigabe- und Eingabedialog, gemeinsam mit der Simple-Laufseite: WebSocket-Meldungen zuerst, dazu `GET /executions/{id}/approval` nach dem Laden und erneut, sobald der WebSocket verbindet oder Laufstatus bzw. aktueller Schritt wechseln — eine verpasste Meldung verschluckt so keine Freigabe.
- **Webhook-Geheimnisse:** URL, Token und Signatur-Secret erscheinen genau einmal. Anlegen, Token neu erzeugen und Secret setzen laufen mit `gcTime: 0` und werden beim Schließen der Anzeige zurückgesetzt, damit danach auch keine Mutation den Wert mehr hält.
- **KI-Vorschlag im Dashboard-Bericht-Editor:** Ob die KI auf diesem Host bereitsteht, prüft der Modellkatalog des Anbieters (`useProviderModels('CLAUDE')` → `available`), nicht `GET /system/ai-providers` — das zählt registrierte Spring-Beans auf und enthält `CLAUDE` immer.
- **TODO-Board:** Im Board gibt es eine Abfrage je Spalte, die der Statusfilter zeigt (`hooks/useTodoBoard.ts`), nie eine gemischte Seite, die auf Spalten verteilt wird.

## State Management

### Zustand-Stores (`src/stores/`)

Client-Zustand — Einstellungen samt API-Key, Theme, Sidebar, Dashboard-Layout, Assistent, Toasts — liegt in Zustand-Stores unter `src/stores/`; die Sprache hält `useI18nStore` in `src/i18n/index.ts`. Was den Neuladen überleben soll, speichert `persist` im `localStorage`, mit einer Format-Version und einer Migration beim Laden.

### TanStack Query (Server State)

Alle API-Daten werden über TanStack Query verwaltet. Die Voreinstellungen stehen in `src/api/queryDefaults.ts`: Daten gelten 30 Sekunden als frisch, beim Wechsel des Fensters wird nicht neu geladen, und eine gescheiterte Abfrage wird einmal wiederholt — nie bei einem 429 oder einem abgeschalteten Modul. Nach Ablauf einer 429-Sperre holt `installRateLimitRecovery` die gescheiterten Abfragen einmal nach. Nach einer Mutation werden die zugehörigen Queries invalidiert.

## Internationalisierung (i18n)

- **Sprachen:** Deutsch (`de`), Englisch (`en`) in `src/i18n/de.ts` und `src/i18n/en.ts`, immer beide zusammen; `useT()` liefert das aktuelle Übersetzungsobjekt.
- **Keine festen Texte in Komponenten:** `i18n/__tests__/hardcodedText.test.ts` findet JSX-Text, `title`/`placeholder`/`aria-label`/`alt`/`label` und `toast`/`confirm`-Literale, `untranslated.test.ts` Englisch in `de.ts`.
- **Datum und Zahl:** nur über `src/utils/localeFormat.ts` (`formatDate`, `formatTimeOfDay`, `formatTimestamp`, `formatNumber`, `formatRelativeUnit`) bzw. `src/utils/formatTime.ts`, immer mit `locale` aus `useI18nStore` — ohne Locale schreibt der Browser in seiner eigenen Sprache. `src/__tests__/localeFormat.guard.test.ts` lässt `Intl` und die `toLocale…String`-Methoden nirgends sonst zu. Uhrzeiten stehen in beiden Sprachen im 24-h-Format (`hourCycle: 'h23'` in allen Datums-/Zeitformatierern, ein übergebenes `hour12` wird verworfen; 571524a5)
- **`<html lang>`:** folgt der App-Sprache (`syncDocumentLanguage()` in `main.tsx`), für Screenreader und Silbentrennung

## Design-System

Farben sind Tokens (CSS-Variablen, `src/theme/tokens.ts`), nie feste Hex-Werte oder Tailwind-Palettenklassen in Komponenten. Tailwind 4 hat keine eigene Konfigurationsdatei: Die Konfiguration steht als `@theme inline` und `@custom-variant` in `src/index.css`, ebenso die globalen Styles.

### Themes

- **Herkunft:** MySuricates Dark/Light haben feste Werte in `src/theme/themes.ts`; die Paletten-Themes werden zur Laufzeit aus der offiziellen Palette und den Rollen der Theme-Autoren abgeleitet (`palettes.ts` → `derive.ts`, Quellen: Style Guides bzw. offizielle VS-Code-Themes).
- **Regeln aus einer Blindbewertung (2026-09-14):** Flächen/Rahmen/Text = Autorenrollen; Akzent-/Status-/Rollenfarben zweigeteilt – `--color-<name>` ist die Fläche im Originalton (≥ 3:1), `--color-<name>-text` die lesbare Textvariante (≥ 4,5:1), `bg-accent` nutzt `--color-accent-emphasis` (trägt `text-text-inverse`), Vollflächen mit Text oder Icon in Fehler-/Erfolgsfarbe ebenso `bg-danger-emphasis`/`bg-success-emphasis` – `bg-danger`/`bg-success` bleiben der Palettenton für Punkte und Balken; Statusfarben sind funktional (Farbton wird nur gedreht, wenn der Palettenton nicht als Erfolg/Warnung/Fehler erkennbar ist); Kartenrahmen (`--color-card-border`) nur bei Themes, deren Autoren Rahmen nutzen; Chart-Serien `--color-chart-1..8` in fester Reihenfolge, Rot/Gelb zuletzt. Paletten-Themes lenken zusätzlich die Tailwind-Farbklassen (`blue-500` …) per CSS-Variablen auf die Palette um (`tailwindPaletteVars`).
- **Token-Nutzung:** Komponenten schreiben weiter `text-success`/`bg-success`; `index.css` bildet `text-*` über `--text-color-*` auf die Textvariante ab. Keine Hex-Farben in Charts (`var(--color-chart-N)`), gerahmte Karten mit `border-card-border`.
- **Wächter:** `tokenClasses.test.ts` lässt nur Farbklassen mit in `index.css` definierten Tokens zu und Text auf Vollflächen nur in den geprüften Paarungen. `themes.test.ts` prüft Kontraste, Statusfarbtöne, unterscheidbare Chart-Serien und die umgelenkten Tailwind-Stufen — neue Themes müssen ihn bestehen.
- **Dark Mode:** `class`-basiert via `@custom-variant dark (&:is(.dark *))`; die `.dark`-Klasse folgt der `appearance` des aktiven Themes (ebenso Monaco und xterm via `useThemeAppearance()`)

## PWA-Konfiguration

`vite-plugin-pwa` in `vite.config.ts` macht die Oberfläche als App installierbar (Name MySuricates, Standalone, Service Worker mit Auto-Update). Gecacht wird nur die Shell, keine API-Daten — einen Offline-Modus gibt es nicht.

## API-Client (`src/api/client.ts`)

Alle Anfragen gehen über die eine Instanz `api` aus `src/api/client.ts`, nie über `fetch()` direkt. Sie hängt den API-Key aus dem `settingsStore` als `X-API-Key` an und bricht eine Anfrage nach 300 Sekunden per `AbortController` ab.

Fehler werden als `ApiError` geworfen mit `{ error, timestamp, code?, params?, fieldErrors? }`; `error` und `message` tragen den Text in der Sprache der Oberfläche, sobald der Server `params` mitschickt (`api/errorText.ts`, Übersetzungen unter `errors.codes` in `de.ts`/`en.ts`). In `onError`-Handlern liefert `apiErrorMessage(err, fallback)` die Meldung für den Toast.

## Proxy-Konfiguration (Vite)

Der Dev-Server läuft auf Port 3000 (`strictPort`) und leitet `/api` und `/ws` an das Backend auf Port 9090 weiter. Mit `CYPRESS_TARGET=test` zeigen die Proxy-Ziele auf Port 8090. HTTPS wird automatisch aktiviert, wenn `../certs/dev.crt` und `../certs/dev.key` existieren.

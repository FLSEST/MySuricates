# Frontend

The UI is a React SPA (TypeScript, Vite, TailwindCSS) under `ai-automation-frontend/`. This page
describes the principles and rules it is built on. What exists is in the code itself: the routes in
`src/App.tsx`, the pages under `src/pages/`, the building blocks per feature folder under
`src/components/`, the hooks under `src/hooks/`, the menu in `src/utils/navItems.ts`.

## Structure

Every route loads its page via `lazy()`; pages of optional modules are wrapped in `ModuleRoute`, which
shows a notice when the module is switched off, and `useModuleEnabled(name)` switches off the queries of
such modules. Every menu entry names its page's hint in the required field `hint` — no menu item without
an explanation (`utils/__tests__/navHints.test.ts`). The chat assistant is a panel above the layout, see
[Assistant System](assistant-system.md).

### Simple Mode

Simple Mode is an operating surface for newcomers who installed MySuricates themselves: start workflows, answer their questions, read results. Building and configuring happens in Pro. Wherever a path leads there, the hand-off is visible, never a silent jump.

- **Page kinds:** `simpleRouteKind(pathname)` (`utils/simpleRoutes.ts`) assigns every page: `simple` has a version of its own, `shared` works unchanged in both modes, everything else is `pro`. Route wrappers in `App.tsx` do the switching based on `useSettingsStore().simpleMode`. When Simple Mode lands on a Pro page, `ProPageNotice` says so below the header and offers “Switch to Pro” or “Back to Simple”.
- **Hand-off to Pro:** `ProHandoff` (reason plus button) and `ProBadge` (a “Pro” pill) in `components/simple/ProHandoff.tsx` mark everything that leads to Pro. `ProHandoff` switches the mode through `useOpenInPro(to?)`; entries with a `ProBadge` open the Pro page without switching modes.
- **Result first:** After a run, the Simple run page shows `RunResultCard` or `RunFailureCard`, the steps sit collapsed below. `utils/runResult.ts` reads everything from the run, without another request; an invalid `dashboard_widget` contract yields a notice, never raw text.
- **Mode choice:** The default stays Pro. `ModeChoiceCard` offers the choice only while there is no project and no choice has been made yet (`modeChoiceMade`).

### Shared Components

**Rule:** `shared` imports no feature folders (`workflow`, `goal`, `hints`, `todo`, `settings` …) and not `assistantPanelUtils`, only `ui`, `api`, `hooks`, `utils`, `types`, `stores` and `i18n`. A component that needs a feature folder belongs in that folder; feature-free helpers live in `src/utils/` (e.g. `placeholderContext.ts`, `internalAppPath.ts`). That is why `AgenticStepCard`, `ForEachStepCard` and `ApprovalDialog` (approval dialog in a run, uses `Hint`) live in `components/workflow/`, and `TodoEditModal` in `components/todo/`. ESLint enforces the rule (`no-restricted-imports`, `no-restricted-syntax` for `import()`, in `eslint.config.js`); `npm run lint` fails on a violation.

The basic building blocks live in `src/components/ui/`. Every yes/no dialog uses `ConfirmModal` instead of assembling `Modal` and `ModalFooter` itself; `isPending` locks its confirm button. No field uses `@monaco-editor/react` directly, only the wrapper `shared/MonacoEditor` (guard in `MonacoEditor.test.tsx`).

## Dashboard widgets

The dashboard renders the widgets from the registry `components/dashboard/widgetRegistry.ts`. A widget is a registry entry (type in `widgetDefinition.ts`); the component loads its own data and receives `{ instanceId, config }` as props. Every widget sits in an error boundary (`WidgetFrame`), so a failing one does not take the others down.

- **Widget kinds:** *Standard singletons* are in every view and can only be hidden; their set is pinned in `widgetRegistry.test.ts` (`DEFAULT_WIDGET_IDS`). *Catalog singletons* (`catalogOnly`) only get into a view through the catalog, *multi widgets* (`multiple`) with a config of their own. `config` holds primitive values only.
- **Views and sync:** A layout holds several views with their own widgets and filters; “Done” saves a snapshot under `/api/v1/dashboard/layouts`. Switching tabs is not a layout change, other devices keep their view. Layouts from a newer app version are not loaded, and a layout of a newer format stored in the browser is not overwritten. A layout above 256 KiB is not sent at all; a message of its own says why.
- **Workflow widgets:** *Workflow status* shows only platform facts of the latest run (name, status, time, duration, quality gate) and **no output text**. *Workflow result* shows the checked contract with the origin line “Reported by the run” – neutral, because the same result can come from an AI marker or from a deterministic `dashboard-report` block, and the response does not say which. If the contract is missing or rejected, it says “No valid dashboard result” – never the raw text, never a substitute green. `WorkflowWidgetProvider` loads the data of all workflow widgets of the view with **one** request to `/api/v1/dashboard/workflow-results`, subscribes to `/topic/dashboard/runs` **once** and reloads when a run of a shown workflow ends; only without a WebSocket connection does it poll every 60 s.
- **New widget:** A component plus a registry entry with category and description (DE+EN). New widgets are `catalogOnly` or `multiple` — as a standard singleton they would show up in every view, template views included. Heavy widgets as a `React.lazy` entry. The `id` is persisted and must never be renamed.
- **Why the contract of the workflow widgets is this narrow:** Workflow output is AI text from external sources (prompt injection is possible), and the API key lives in this origin's `localStorage`. The marker `dashboard_widget` is validated in the backend (`DashboardWidgetResultParser`) and again in the browser (`workflowWidgetContract.ts`): known fields only, fixed lengths and counts, strings without control, zero-width, bidi and private-use characters, status and trend as enums. It holds **no** id and **no** URL field — so AI text can never become a link or a navigation target. Everything is rendered as React text nodes only; anything that does not match makes the whole contract fail. `dashboardMarkupGuard.test.ts` forbids `dangerouslySetInnerHTML` and its relatives, Markdown renderers, `<img>`, `<iframe>`, `href`, `src` and `window.open` in `components/dashboard` and `components/insights`. The widgets never start a run.

## Rules from individual pages

- **Approvals and inputs during a run:** `hooks/useRunPendingInput.ts` is the one source for the approval and input dialog, shared with the Simple run page: WebSocket messages first, plus `GET /executions/{id}/approval` after loading and again as soon as the WebSocket connects or the run status or current step changes — so a missed message never swallows an approval.
- **Webhook secrets:** URL, token and signing secret appear exactly once. Create, regenerate token and set secret run with `gcTime: 0` and are reset when the reveal closes, so that afterwards no mutation holds the value either.
- **AI suggestion in the dashboard report editor:** Whether the AI is available on this host is checked through the provider's model catalog (`useProviderModels('CLAUDE')` → `available`), not through `GET /system/ai-providers` — that one lists registered Spring beans and always contains `CLAUDE`.
- **TODO board:** The board runs one query per column the status filter shows (`hooks/useTodoBoard.ts`), never one mixed page spread over the columns.

## State Management

### Zustand stores (`src/stores/`)

Client state — settings including the API key, theme, sidebar, dashboard layout, assistant, toasts — lives in Zustand stores under `src/stores/`; the language is held by `useI18nStore` in `src/i18n/index.ts`. Whatever has to survive a reload is stored in `localStorage` by `persist`. Only settings, theme and dashboard layout (`settingsStore`, `themeStore`, `dashboardLayoutStore`) have a format version with a migration on load; sidebar, assistant, dashboard sync and language are stored without one.

### TanStack Query (Server State)

All API data is managed through TanStack Query. The defaults are in `src/api/queryDefaults.ts`: data counts as fresh for 30 seconds, nothing is refetched on window focus, and a failed query is retried once — never on a 429 or a disabled module. Once a 429 lock has expired, `installRateLimitRecovery` refetches the failed queries once. After a mutation, the related queries are invalidated.

## Internationalization (i18n)

- **Languages:** German (`de`), English (`en`) in `src/i18n/de.ts` and `src/i18n/en.ts`, always both together; `useT()` returns the current translation object.
- **No fixed text in components:** `i18n/__tests__/hardcodedText.test.ts` finds JSX text, `title`/`placeholder`/`aria-label`/`alt`/`label` and `toast`/`confirm` literals, `untranslated.test.ts` English in `de.ts`.
- **Dates and numbers:** only through `src/utils/localeFormat.ts` (`formatDate`, `formatTimeOfDay`, `formatTimestamp`, `formatNumber`, `formatRelativeUnit`) or `src/utils/formatTime.ts`, always with `locale` from `useI18nStore` — without a locale the browser writes in its own language. `src/__tests__/localeFormat.guard.test.ts` allows `Intl` and the `toLocale…String` methods nowhere else. Times of day use the 24-hour clock in both languages (`hourCycle: 'h23'` in every date/time formatter, a passed `hour12` is dropped; 571524a5)
- **`<html lang>`:** follows the app language (`syncDocumentLanguage()` in `main.tsx`), for screen readers and hyphenation

## Design System

Colors are tokens (CSS variables, `src/theme/tokens.ts`), never fixed hex values or Tailwind palette classes in components. Tailwind 4 has no configuration file of its own: the configuration lives as `@theme inline` and `@custom-variant` in `src/index.css`, as do the global styles.

### Themes

- **Origin:** MySuricates Dark/Light have fixed values in `src/theme/themes.ts`; the palette themes are derived at runtime from the official palette and the theme authors' roles (`palettes.ts` → `derive.ts`; sources: style guides or the official VS Code themes).
- **Rules from a blind review (2026-09-14):** surfaces/borders/text = author roles; accent/status/role colors are split — `--color-<name>` is the fill in the palette's own tone (≥ 3:1), `--color-<name>-text` the readable text variant (≥ 4.5:1), `bg-accent` uses `--color-accent-emphasis` (carries `text-text-inverse`), and solid danger/success fills with text or an icon likewise use `bg-danger-emphasis`/`bg-success-emphasis` — `bg-danger`/`bg-success` stay the palette tone for dots and bars; status colors are functional (the hue is only rotated when the palette tone doesn't read as success/warning/danger); card borders (`--color-card-border`) only for themes whose authors use borders; chart series `--color-chart-1..8` in a fixed order, red/yellow last. Palette themes also remap Tailwind's palette classes (`blue-500` …) via CSS variables (`tailwindPaletteVars`).
- **Token usage:** components keep writing `text-success`/`bg-success`; `index.css` maps `text-*` to the text variant via `--text-color-*`. No hex colors in charts (`var(--color-chart-N)`), framed cards use `border-card-border`.
- **Guards:** `tokenClasses.test.ts` allows only colour classes whose token `index.css` defines, and text on solid fills only in the checked pairings. `themes.test.ts` checks contrasts, status hues, distinguishable chart series and the remapped Tailwind shades — new themes must pass it.
- **Dark Mode:** `class`-based via `@custom-variant dark (&:is(.dark *))`; the `.dark` class follows the active theme's `appearance` (as do Monaco and xterm via `useThemeAppearance()`)

## PWA Configuration

`vite-plugin-pwa` in `vite.config.ts` makes the UI installable as an app (name MySuricates, standalone, service worker with auto-update). Only the shell is cached, no API data — there is no offline mode.

## API Client (`src/api/client.ts`)

All requests go through the one instance `api` from `src/api/client.ts`, never through `fetch()` directly. It adds the API key from the `settingsStore` as `X-API-Key` and aborts a request after 300 seconds via `AbortController`.

Errors are thrown as `ApiError` with `{ error, timestamp, code?, params?, fieldErrors? }`; `error` and `message` carry the text in the UI language as soon as the server sends `params` (`api/errorText.ts`, translations under `errors.codes` in `de.ts`/`en.ts`). In `onError` handlers, `apiErrorMessage(err, fallback)` provides the message for the toast.

## Proxy Configuration (Vite)

The dev server runs on port 3000 (`strictPort`) and forwards `/api` and `/ws` to the backend on port 9090. With `CYPRESS_TARGET=test`, the proxy targets point to port 8090. HTTPS is enabled automatically when `../certs/dev.crt` and `../certs/dev.key` exist.

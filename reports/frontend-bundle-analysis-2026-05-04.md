# Frontend Bundle Analyse — 2026-05-04

TODO: `62fd62d5-7069-469a-b555-d8350169fd71`

## Aktueller Stand

```
Total dist/assets/*.js          2.3 MB raw / ~600 KB gz
index-Bd7HJKtP.js               822 KB raw / 252 KB gz   ← Hauptverdächtiger
PieChart-yuZcG5hP.js            386 KB raw / 104 KB gz   ← lazy (recharts)
TerminalPanel-CHewJ1wN.js       336 KB raw /  84 KB gz   ← lazy (xterm.js)
Settings + ModelStatsCard       je ~52 KB raw
restliche Pages/Komponenten     je < 50 KB raw
```

Audit-Vergleich: 1926 → 2104 KB (+178 KB). Aktuell sogar **2300 KB** — Wachstum hat sich fortgesetzt, +374 KB seit dem Audit.

## Was steckt drin

### Lazy-geladene Chunks (✅ unkritisch)

`PieChart`, `TerminalPanel`, alle Pages — werden via React.lazy / Route-Splitting nur bei Bedarf geladen. Diese Größen sind okay, weil sie nicht zur First-Paint-Bandbreite zählen.

### Hauptbundle `index.js` (822 KB) — kritisch

Ohne explizite `manualChunks`-Konfiguration in `vite.config.ts` landen alle Eager-imports im Hauptbundle:

| Likely Contributor | Geschätzte Größe |
|---|---|
| React 19 + ReactDOM | ~140 KB |
| @tanstack/react-query 5 | ~40 KB |
| @stomp/stompjs + sockjs-client | ~80 KB |
| react-router-dom 6 | ~30 KB |
| zustand + @dnd-kit/core+sortable | ~50 KB |
| lucide-react (alle eager-importierten Icons) | ~50–100 KB |
| react-markdown + remark-gfm | ~80–120 KB |
| fuse.js (Palette) | ~20 KB |
| App-Code (zentrale Components, Hooks, i18n) | ~250 KB |

## Wachstumsursachen seit dem Audit

Recente package.json-Änderungen:
- `+ fuse.js@7.3.0` (Commit `bfff29f`, Palette Phase 4) — ~20 KB
- `+ @tailwindcss/typography@0.5.19` (Commit `5531320`) — Tailwind-Plugin, **nur CSS**, kein JS-Wachstum

`fuse.js` allein erklärt nicht die +178 KB. Wahrscheinlichere Hauptursachen:
1. **Mehr eager-importierte Icons** aus `lucide-react` durch neue Pages (Wishes, Insights, Calendar, Webhooks, etc.)
2. **Mehr Komponenten im Hauptbundle**, weil keine `manualChunks` definiert sind — alles was nicht via `React.lazy` kommt, landet hier.
3. **Markdown-Stack** (`react-markdown` + `remark-gfm`) wird wahrscheinlich eager geladen, obwohl nur einzelne Pages (Docs, Wishes) Markdown brauchen.

## Empfehlungen (priorisiert)

### R1 — `vite.config.ts` mit `manualChunks` für Vendor-Splitting (geringer Aufwand, hoher Effekt)

```ts
build: {
  rollupOptions: {
    output: {
      manualChunks: {
        react: ['react', 'react-dom', 'react-router-dom'],
        query: ['@tanstack/react-query'],
        ws: ['@stomp/stompjs', 'sockjs-client'],
        markdown: ['react-markdown', 'remark-gfm'],
        dnd: ['@dnd-kit/core', '@dnd-kit/sortable', '@dnd-kit/utilities'],
      },
    },
  },
  chunkSizeWarningLimit: 600,
},
```

Erwartete Wirkung: Hauptbundle fällt auf ~300–400 KB, der Rest wird parallel und browser-cacheable in eigenen Vendor-Chunks ausgeliefert.

### R2 — `react-markdown` lazy laden (mittlerer Aufwand)

Markdown wird nur in wenigen Pages genutzt (`Docs.tsx`, ggf. Wishes/Reports). Stattdessen `React.lazy()` für die markdown-rendernden Komponenten. Spart ~80–120 KB im Hauptbundle.

### R3 — `lucide-react` Icon-Imports prüfen (kleiner Aufwand)

`import { History, Link2, Sparkles, ... } from 'lucide-react'` — Vite tree-shaket grundsätzlich, aber große Sammelimporte oder dynamische Icon-Auswahl können das verhindern. Ein Audit per `grep -r "from 'lucide-react'"` und Anzahl distinkte Icon-Namen würde das beziffern.

### R4 — `chunkSizeWarningLimit: 500` als CI-Guard (sehr klein)

Damit zukünftiges Wachstum sichtbar wird, statt in der Audit-Schleife unterzugehen.

### R5 (NICHT empfohlen) — `recharts` durch leichtere Alternative ersetzen

Lazy bereits getrennt (`PieChart` Chunk), 104 KB gz für lazy ist okay. Wechsel wäre teuer und würde Insights-Page UX zurückwerfen. Nicht angreifen.

## Ergebnis nach R1 + R4 (angewendet)

```
                          vorher          nachher          Δ
Main index.js             822 KB / 252g   437 KB / 137g    −47 % gzipped
Neue Vendor-Chunks (lazy/parallel):
  react.js                   —             68 KB /  23g
  query.js                   —             44 KB /  14g
  ws.js                      —             70 KB /  22g
  markdown.js                —            157 KB /  47g
  dnd.js                     —             45 KB /  15g
```

Hauptbundle ist jetzt unter dem 600-KB-Warning-Limit. Vendor-Chunks werden parallel geladen und sind Browser-cacheable über App-Releases hinweg, solange ihre Deps unverändert bleiben.

**`markdown.js` mit 157 KB** (47 KB gz) bleibt der größte Vendor-Chunk. R2 (lazy-load) bleibt offen als nächste Optimierung.

## Offene Follow-ups

- **R2** — `react-markdown` lazy laden (separates TODO)
- **R3** — `lucide-react` Icon-Imports auditieren

import type { useNavigate } from 'react-router-dom';

export type BadgeTone = 'success' | 'danger' | 'warn' | 'info' | 'neutral';

export type HighlightKey = 'title' | 'subtitle';

export interface PaletteMatch {
  key: HighlightKey;
  indices: Array<[number, number]>; // inclusive ranges of matched char positions
}

export interface PaletteItem {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
  badge?: { label: string; tone: BadgeTone };
  /** Optional per-field match ranges, filled by Fuse-backed contexts for highlighting. */
  matches?: PaletteMatch[];
  raw: unknown;
}

export interface ActionDeps {
  navigate: ReturnType<typeof useNavigate>;
  onClose: () => void;
}

export type PaletteContextId = 'workflow' | 'run' | 'project';

export interface PaletteLabels {
  triggerLabel: string;
  placeholder: string;
  empty: string;
}

export interface PaletteContextHandle {
  id: PaletteContextId;
  labels: PaletteLabels;
  useResults: (query: string) => { items: PaletteItem[]; loading: boolean };
  useByIds: (ids: string[]) => PaletteItem[];
  onEnter: (item: PaletteItem, deps: ActionDeps) => void;
  onOpen?: (item: PaletteItem, deps: ActionDeps) => void;
  /** Optional per-context check before onEnter (e.g. show variable collector). Return true to short-circuit. */
  onBeforeEnter?: (item: PaletteItem, deps: ActionDeps, showCollector: (w: unknown, vars: string[], defaults: Record<string, string>) => void) => boolean;
  /** Optional secondary action, e.g. Re-Run on the run context. If set, an extra button is rendered. */
  altAction?: {
    icon: 'rotate-cw';
    label: string;
    shortcut: string; // e.g. 'r' for single-key when row selected
    run: (item: PaletteItem, deps: ActionDeps) => void;
  };
  /** Optional: produce a URL path for "open in new tab" (Cmd+Enter). Falls back to onOpen otherwise. */
  getPath?: (item: PaletteItem) => string | null;
}

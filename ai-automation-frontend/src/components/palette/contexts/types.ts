import type { useNavigate } from 'react-router-dom';

export type BadgeTone = 'success' | 'danger' | 'warn' | 'info' | 'neutral';

export interface PaletteItem {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
  badge?: { label: string; tone: BadgeTone };
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
}

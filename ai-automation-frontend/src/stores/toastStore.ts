import { create } from 'zustand';

export type ToastType = 'success' | 'error' | 'info' | 'warning';

/** A button in the toast, e.g. to show where something went; clicking it closes the toast unless `keepOpen`. */
export interface ToastAction {
  label: string;
  onClick: () => void;
  /** Leave the toast open after the click – e.g. “Show”, so “Undo” is still there after a look. */
  keepOpen?: boolean;
}

export interface Toast {
  id: string;
  type: ToastType;
  message: string;
  createdAt: number;
  /** The first button – kept for callers that only know one. */
  action?: ToastAction;
  /** All buttons, e.g. “Show” and “Undo” after adding a TODO to a goal. */
  actions?: ToastAction[];
}

interface ToastState {
  toasts: Toast[];
  addToast: (type: ToastType, message: string, action?: ToastAction | ToastAction[]) => void;
  removeToast: (id: string) => void;
}

const MAX_TOASTS = 5;

export const useToastStore = create<ToastState>()((set) => ({
  toasts: [],

  addToast: (type, message, action) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    const actions = action === undefined ? undefined : Array.isArray(action) ? action : [action];
    set((state) => {
      const next = [...state.toasts, { id, type, message, createdAt: Date.now(), action: actions?.[0], actions }];
      // Evict oldest if over max
      return { toasts: next.slice(-MAX_TOASTS) };
    });
  },

  removeToast: (id) => {
    set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) }));
  },
}));

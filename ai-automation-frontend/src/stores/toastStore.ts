import { create } from 'zustand';

export type ToastType = 'success' | 'error' | 'info' | 'warning';

/** A button in the toast, e.g. to show where something went; clicking it also closes the toast. */
export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface Toast {
  id: string;
  type: ToastType;
  message: string;
  createdAt: number;
  action?: ToastAction;
}

interface ToastState {
  toasts: Toast[];
  addToast: (type: ToastType, message: string, action?: ToastAction) => void;
  removeToast: (id: string) => void;
}

const MAX_TOASTS = 5;

export const useToastStore = create<ToastState>()((set) => ({
  toasts: [],

  addToast: (type, message, action) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    set((state) => {
      const next = [...state.toasts, { id, type, message, createdAt: Date.now(), action }];
      // Evict oldest if over max
      return { toasts: next.slice(-MAX_TOASTS) };
    });
  },

  removeToast: (id) => {
    set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) }));
  },
}));

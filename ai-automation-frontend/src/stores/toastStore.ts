import { create } from 'zustand';

export type ToastType = 'success' | 'error' | 'info' | 'warning';

export interface Toast {
  id: string;
  type: ToastType;
  message: string;
  createdAt: number;
}

interface ToastState {
  toasts: Toast[];
  addToast: (type: ToastType, message: string) => void;
  removeToast: (id: string) => void;
}

const MAX_TOASTS = 5;

export const useToastStore = create<ToastState>()((set) => ({
  toasts: [],

  addToast: (type, message) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    set((state) => {
      const next = [...state.toasts, { id, type, message, createdAt: Date.now() }];
      // Evict oldest if over max
      return { toasts: next.slice(-MAX_TOASTS) };
    });
  },

  removeToast: (id) => {
    set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) }));
  },
}));

import { useToastStore, type ToastAction } from '../stores/toastStore';

export function useToast() {
  const addToast = useToastStore((s) => s.addToast);

  return {
    success: (message: string, action?: ToastAction) => addToast('success', message, action),
    error: (message: string, action?: ToastAction) => addToast('error', message, action),
    info: (message: string, action?: ToastAction) => addToast('info', message, action),
    warning: (message: string, action?: ToastAction) => addToast('warning', message, action),
  };
}

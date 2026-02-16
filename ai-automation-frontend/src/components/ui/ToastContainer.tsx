import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle2, XCircle, Info, AlertTriangle, X } from 'lucide-react';
import { useToastStore, type Toast, type ToastType } from '../../stores/toastStore';

const AUTO_DISMISS_MS = 4000;

const icons: Record<ToastType, React.ReactNode> = {
  success: <CheckCircle2 className="w-5 h-5 text-[var(--color-success)]" />,
  error: <XCircle className="w-5 h-5 text-[var(--color-danger)]" />,
  info: <Info className="w-5 h-5 text-accent" />,
  warning: <AlertTriangle className="w-5 h-5 text-[var(--color-warning)]" />,
};

const progressColors: Record<ToastType, string> = {
  success: 'bg-[var(--color-success)]',
  error: 'bg-[var(--color-danger)]',
  info: 'bg-accent',
  warning: 'bg-[var(--color-warning)]',
};

function ToastItem({ toast }: { toast: Toast }) {
  const removeToast = useToastStore((s) => s.removeToast);
  const [exiting, setExiting] = useState(false);
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    const interval = setInterval(() => {
      setElapsed(Date.now() - toast.createdAt);
    }, 50);
    return () => clearInterval(interval);
  }, [toast.createdAt]);

  useEffect(() => {
    const timer = setTimeout(() => {
      setExiting(true);
      setTimeout(() => removeToast(toast.id), 200);
    }, AUTO_DISMISS_MS);
    return () => clearTimeout(timer);
  }, [toast.id, removeToast]);

  const handleClose = () => {
    setExiting(true);
    setTimeout(() => removeToast(toast.id), 200);
  };

  const progress = Math.min(elapsed / AUTO_DISMISS_MS, 1);

  return (
    <div
      className={`flex items-start gap-3 w-80 bg-surface-raised border border-border rounded-xl p-4 shadow-lg transition-all duration-200 ${
        exiting ? 'opacity-0 translate-x-4' : 'animate-slide-in-right'
      }`}
    >
      <div className="shrink-0 mt-0.5">{icons[toast.type]}</div>
      <p className="flex-1 text-sm text-text leading-snug">{toast.message}</p>
      <button
        onClick={handleClose}
        className="shrink-0 p-0.5 rounded-md text-text-tertiary hover:text-text transition-colors"
        aria-label="Close"
      >
        <X className="w-4 h-4" />
      </button>
      {/* Progress bar */}
      <div className="absolute bottom-0 left-0 right-0 h-0.5 rounded-b-xl overflow-hidden">
        <div
          className={`h-full ${progressColors[toast.type]} transition-none`}
          style={{ width: `${(1 - progress) * 100}%` }}
        />
      </div>
    </div>
  );
}

export function ToastContainer() {
  const toasts = useToastStore((s) => s.toasts);

  return createPortal(
    <div className="fixed top-4 right-4 z-50 flex flex-col gap-2" aria-live="polite">
      {toasts.map((toast) => (
        <ToastItem key={toast.id} toast={toast} />
      ))}
    </div>,
    document.body
  );
}

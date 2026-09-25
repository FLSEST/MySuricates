import { useCallback, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle2, XCircle, Info, AlertTriangle, X } from 'lucide-react';
import { useToastStore, type Toast, type ToastType } from '../../stores/toastStore';

const AUTO_DISMISS_MS = 4000;
/**
 * A toast with a button stays longer: time to read it and to reach the button. While the pointer is
 * on it or focus is inside, its timer holds — reaching the button by keyboard takes a while.
 */
const ACTION_DISMISS_MS = 8000;

const icons: Record<ToastType, React.ReactNode> = {
  success: <CheckCircle2 className="w-5 h-5 text-success" />,
  error: <XCircle className="w-5 h-5 text-danger" />,
  info: <Info className="w-5 h-5 text-accent" />,
  warning: <AlertTriangle className="w-5 h-5 text-warning" />,
};

const progressColors: Record<ToastType, string> = {
  success: 'bg-success',
  error: 'bg-danger',
  info: 'bg-accent',
  warning: 'bg-warning',
};

function ToastItem({ toast }: { toast: Toast }) {
  const removeToast = useToastStore((s) => s.removeToast);
  const [exiting, setExiting] = useState(false);
  const [now, setNow] = useState(toast.createdAt);
  const { action } = toast;
  const dismissMs = action ? ACTION_DISMISS_MS : AUTO_DISMISS_MS;
  // `spent`: time the toast ran before the current stretch; `runningSince` is null while it is held.
  const [spent, setSpent] = useState(0);
  const [runningSince, setRunningSince] = useState<number | null>(toast.createdAt);
  const [pointerOn, setPointerOn] = useState(false);
  const [focusIn, setFocusIn] = useState(false);

  const handleClose = useCallback(() => {
    setExiting(true);
    setTimeout(() => removeToast(toast.id), 200);
  }, [removeToast, toast.id]);

  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), 50);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    if (runningSince === null) return;
    const timer = setTimeout(handleClose, dismissMs - spent - (Date.now() - runningSince));
    return () => clearTimeout(timer);
  }, [runningSince, spent, dismissMs, handleClose]);

  /** Only a toast with a button holds; the others keep their fixed time. */
  const hold = (change: { pointer?: boolean; focus?: boolean }) => {
    if (!action) return;
    const wasHeld = pointerOn || focusIn;
    const held = (change.pointer ?? pointerOn) || (change.focus ?? focusIn);
    if (change.pointer !== undefined) setPointerOn(change.pointer);
    if (change.focus !== undefined) setFocusIn(change.focus);
    if (!wasHeld && held && runningSince !== null) {
      setSpent(spent + Date.now() - runningSince);
      setRunningSince(null);
    } else if (wasHeld && !held) {
      setRunningSince(Date.now());
    }
  };

  const elapsed = spent + (runningSince === null ? 0 : Math.max(0, now - runningSince));
  const progress = Math.min(elapsed / dismissMs, 1);

  return (
    <div
      className={`flex items-start gap-3 w-80 bg-surface-raised border border-border rounded-xl p-4 shadow-lg transition-all duration-200 ${
        exiting ? 'opacity-0 translate-x-4' : 'animate-slide-in-right'
      }`}
      data-testid="toast"
      onMouseEnter={() => hold({ pointer: true })}
      onMouseLeave={() => hold({ pointer: false })}
      onFocus={() => hold({ focus: true })}
      onBlur={(event) => {
        // Moving between the toast's own buttons keeps it held.
        if (!event.currentTarget.contains(event.relatedTarget)) hold({ focus: false });
      }}
    >
      <div className="shrink-0 mt-0.5">{icons[toast.type]}</div>
      <p className="flex-1 text-sm text-text leading-snug">{toast.message}</p>
      {action && (
        <button
          type="button"
          onClick={() => {
            action.onClick();
            handleClose();
          }}
          className="shrink-0 text-sm font-medium text-accent hover:underline"
        >
          {action.label}
        </button>
      )}
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

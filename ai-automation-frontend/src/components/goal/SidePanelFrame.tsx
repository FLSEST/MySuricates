import { useCallback, useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react';
import { GripVertical, PanelRight, PictureInPicture2, X } from 'lucide-react';
import { createPortal } from 'react-dom';
import { useT } from '../../i18n';
import { useIsMobile } from '../../hooks/useIsMobile';
import { hasOpenModalLayer, tabbables, useModalLayer } from '../ui/modalStack';
import {
  DOCK_MAX, DOCK_MIN, clampRect, clampWidth, movedRect, readPanelPrefs, resizedRect, storePanelPrefs,
  type PanelPrefs, type ResizeEdge, type Viewport,
} from './panelGeometry';

const STEP = 16;
/** Distance of the docked bar from the top and bottom edge when it sticks. */
const STICKY_TOP = 16;
const BIG_STEP = 64;
const viewport = (): Viewport => ({ width: window.innerWidth, height: window.innerHeight });

/**
 * Drags with the mouse (pointer): `onMove` receives the distance since the press until release. Returns
 * the cleanup function - if the bar closes mid-drag, no listeners are left behind.
 */
function dragFrom(event: ReactPointerEvent, onMove: (dx: number, dy: number) => void): (() => void) | null {
  if (event.button !== 0) return null;
  event.preventDefault();
  const startX = event.clientX;
  const startY = event.clientY;
  const move = (e: PointerEvent) => onMove(e.clientX - startX, e.clientY - startY);
  const stop = () => {
    window.removeEventListener('pointermove', move);
    window.removeEventListener('pointerup', stop);
    window.removeEventListener('pointercancel', stop);
  };
  window.addEventListener('pointermove', move);
  window.addEventListener('pointerup', stop);
  window.addEventListener('pointercancel', stop);
  return stop;
}

/**
 * Holds the drag currently in progress: a new one ends the old one, and unmounting the bar ends it
 * too (review c60bf914).
 */
function useDrag() {
  const stop = useRef<(() => void) | null>(null);
  useEffect(() => () => stop.current?.(), []);
  return (event: ReactPointerEvent, onMove: (dx: number, dy: number) => void) => {
    stop.current?.();
    stop.current = dragFrom(event, onMove);
  };
}

/**
 * Escape closes the panel (d86f27b2) – unless something inside already used the key (an inline field,
 * a keyboard drag: they prevent the default) or `covered()` reports a dialog above the panel.
 *
 * Two listeners on `window`: the capture one runs before any other listener and notes whether a dialog
 * lies above the panel – the dialog's own Escape handler may close it before the panel's turn, and the
 * browser lets React re-render between two listeners, so asking later would find the dialog already gone.
 * The bubble one runs after the document-level listeners of dnd-kit and the inline fields, so it sees
 * whether they used the key.
 */
function useEscapeToClose(onClose: () => void, covered: () => boolean) {
  useEffect(() => {
    let coveredWhenPressed = false;
    const notePressed = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') coveredWhenPressed = covered();
    };
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || coveredWhenPressed) return;
      onClose();
    };
    window.addEventListener('keydown', notePressed, true);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', notePressed, true);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [onClose, covered]);
}

/**
 * Frame of the side bar (c60bf914): from 1024 px docked on the right next to the content - as tall as the
 * viewport, with its own scroll area, width adjustable at the left handle (mouse, ← →) - or released as a
 * window, movable by the title bar (also with the arrow keys), resizable at the edges and corner,
 * always fully within the viewport. Width, position, size and mode are remembered. Below that,
 * a sheet over the content.
 */
export function SidePanelFrame({ labelledBy, header, children, footer, onClose, testId, kind, storageKey, closeLabel }: {
  labelledBy: string;
  header: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  onClose: () => void;
  testId: string;
  kind: string;
  /** Where width, mode and window position are remembered - each bar has its own key. */
  storageKey?: string;
  /** Name of the ×; without it, that of the side bar. */
  closeLabel?: string;
}) {
  const t = useT();
  const tp = t.goals.panel;
  const mobile = useIsMobile();
  const drag = useDrag();
  const [prefs, setPrefs] = useState<PanelPrefs>(() => readPanelPrefs(viewport(), storageKey));
  useEffect(() => storePanelPrefs(prefs, storageKey), [prefs, storageKey]);
  // Docked or floating the panel is not modal: any open dialog keeps Escape. The sheet asks itself.
  useEscapeToClose(onClose, useCallback(() => mobile || hasOpenModalLayer(), [mobile]));

  // When the browser window shrinks, the window comes back into the viewport
  useEffect(() => {
    const onResize = () => setPrefs((current) => ({
      ...current, width: clampWidth(current.width, viewport()), rect: clampRect(current.rect, viewport()),
    }));
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const floating = !mobile && prefs.mode === 'floating';
  const setMode = (mode: PanelPrefs['mode']) => setPrefs((current) => ({
    ...current, mode, rect: mode === 'floating' && current.mode !== 'floating' ? clampRect(current.rect, viewport()) : current.rect,
  }));

  const resizeWidth = (event: ReactPointerEvent) => {
    const start = prefs.width;
    drag(event, (dx) => setPrefs((current) => ({ ...current, width: clampWidth(start - dx, viewport()) })));
  };
  const widthKeys = (event: KeyboardEvent) => {
    const step = event.shiftKey ? BIG_STEP : STEP;
    const next = { ArrowLeft: prefs.width + step, ArrowRight: prefs.width - step, Home: DOCK_MIN, End: DOCK_MAX }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    setPrefs((current) => ({ ...current, width: clampWidth(next, viewport()) }));
  };
  const move = (event: ReactPointerEvent) => {
    if ((event.target as HTMLElement).closest('button')) return;
    const start = prefs.rect;
    drag(event, (dx, dy) => setPrefs((current) => ({ ...current, rect: movedRect(start, dx, dy, viewport()) })));
  };
  const moveKeys = (event: KeyboardEvent) => {
    if (event.target !== event.currentTarget) return;
    const step = event.shiftKey ? BIG_STEP : STEP;
    const delta = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[event.key];
    if (!delta) return;
    event.preventDefault();
    setPrefs((current) => ({ ...current, rect: movedRect(current.rect, delta[0]!, delta[1]!, viewport()) }));
  };
  const resize = (edge: ResizeEdge) => (event: ReactPointerEvent) => {
    const start = prefs.rect;
    drag(event, (dx, dy) => setPrefs((current) => ({ ...current, rect: resizedRect(start, edge, dx, dy, viewport()) })));
  };
  const resizeKeys = (event: KeyboardEvent) => {
    const step = event.shiftKey ? BIG_STEP : STEP;
    const delta = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[event.key];
    if (!delta) return;
    event.preventDefault();
    setPrefs((current) => ({ ...current, rect: resizedRect(current.rect, 'corner', delta[0]!, delta[1]!, viewport()) }));
  };

  const buttons = (
    <div className="flex shrink-0 items-center">
      {!mobile && (
        <button type="button" onClick={() => setMode(floating ? 'docked' : 'floating')}
          aria-label={floating ? tp.dock : tp.undock} title={floating ? tp.dock : tp.undock}
          className="rounded-md p-1.5 text-text-secondary hover:bg-surface-hover hover:text-text focus-ring">
          {floating ? <PanelRight className="h-4 w-4" aria-hidden="true" /> : <PictureInPicture2 className="h-4 w-4" aria-hidden="true" />}
        </button>
      )}
      <button type="button" onClick={onClose} aria-label={closeLabel ?? tp.close} title={closeLabel ?? tp.close}
        className="rounded-md p-1.5 text-text-secondary hover:bg-surface-hover hover:text-text focus-ring">
        <X className="h-4 w-4" aria-hidden="true" />
      </button>
    </div>
  );
  const body = (
    <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-4" data-testid="goal-side-panel-scroll">
      {children}
      {footer}
    </div>
  );

  if (floating) {
    const { x, y, width, height } = prefs.rect;
    return (
      <aside aria-labelledby={labelledBy} data-testid={testId} data-kind={kind} data-mode="floating"
        style={{ left: x, top: y, width, height }}
        className="fixed z-40 flex flex-col overflow-hidden rounded-xl border border-border bg-surface-raised shadow-2xl">
        <div role="group" tabIndex={0} aria-label={tp.moveWindow} onPointerDown={move} onKeyDown={moveKeys}
          data-testid="goal-side-panel-titlebar"
          className="flex cursor-move items-start gap-2 border-b border-border bg-surface px-4 py-3 focus-ring">
          <GripVertical className="mt-1 h-4 w-4 shrink-0 text-text-tertiary" aria-hidden="true" />
          <div className="min-w-0 flex-1">{header}</div>
          {buttons}
        </div>
        <div className="pt-3" />
        {body}
        <span aria-hidden="true" onPointerDown={resize('left')} className="absolute inset-y-0 left-0 w-1.5 cursor-ew-resize" />
        <span aria-hidden="true" onPointerDown={resize('right')} className="absolute inset-y-0 right-0 w-1.5 cursor-ew-resize" />
        <span aria-hidden="true" onPointerDown={resize('bottom')} className="absolute inset-x-0 bottom-0 h-1.5 cursor-ns-resize" />
        <button type="button" aria-label={tp.resizeWindow} title={tp.resizeWindow} onPointerDown={resize('corner')} onKeyDown={resizeKeys}
          data-testid="goal-side-panel-corner"
          className="absolute bottom-0 right-0 h-4 w-4 cursor-nwse-resize rounded-tl text-text-tertiary focus-ring">
          <svg viewBox="0 0 16 16" className="h-4 w-4" aria-hidden="true"><path d="M14 6v8H6M14 10v4h-4" fill="none" stroke="currentColor" strokeWidth="1.5" /></svg>
        </button>
      </aside>
    );
  }

  if (mobile) {
    return (
      <SheetPanel labelledBy={labelledBy} testId={testId} kind={kind} onClose={onClose}>
        <div className="flex items-start gap-2 px-4 pb-2 pt-4"><div className="min-w-0 flex-1">{header}</div>{buttons}</div>
        {body}
      </SheetPanel>
    );
  }

  return (
    <DockedPanel labelledBy={labelledBy} testId={testId} kind={kind} width={prefs.width}>
      <div role="separator" aria-orientation="vertical" tabIndex={0} aria-label={tp.resizeWidth}
        aria-valuenow={prefs.width} aria-valuemin={DOCK_MIN} aria-valuemax={clampWidth(DOCK_MAX, viewport())}
        onPointerDown={resizeWidth} onKeyDown={widthKeys} data-testid="goal-side-panel-resize"
        className="group absolute inset-y-3 -left-2 z-10 flex w-3 cursor-col-resize items-center justify-center rounded focus-ring">
        <span className="h-10 w-1 rounded-full bg-border-strong opacity-60 transition-opacity group-hover:opacity-100" />
      </div>
      <div className="flex items-start gap-2 px-4 pb-2 pt-4"><div className="min-w-0 flex-1">{header}</div>{buttons}</div>
      {body}
    </DockedPanel>
  );
}

/**
 * Docked, the bar stands next to the content and stays at the top while scrolling (sticky). Its height
 * always reaches exactly to the bottom edge of the viewport - even before it sticks, while the page
 * header is still above it -, so nothing is ever cut off at the bottom; it scrolls the rest internally.
 */
function DockedPanel({ labelledBy, testId, kind, width, children }: {
  labelledBy: string;
  testId: string;
  kind: string;
  width: number;
  children: ReactNode;
}) {
  const ref = useRef<HTMLElement>(null);
  const [height, setHeight] = useState<number | null>(null);
  useLayoutEffect(() => {
    const fit = () => {
      const top = ref.current?.getBoundingClientRect().top ?? 0;
      setHeight(Math.max(240, window.innerHeight - Math.max(STICKY_TOP, top) - STICKY_TOP));
    };
    fit();
    window.addEventListener('scroll', fit, { passive: true });
    window.addEventListener('resize', fit);
    return () => {
      window.removeEventListener('scroll', fit);
      window.removeEventListener('resize', fit);
    };
  }, []);
  return (
    <aside ref={ref} aria-labelledby={labelledBy} data-testid={testId} data-kind={kind} data-mode="docked"
      style={{ width, height: height ?? undefined, top: STICKY_TOP }}
      className="sticky flex max-h-[calc(100vh-2rem)] shrink-0 flex-col rounded-xl border border-card-border bg-surface-raised shadow-sm">
      {children}
    </aside>
  );
}

/**
 * Below 1024 px a sheet over the content - then modal (review c60bf914): `role="dialog"` with
 * `aria-modal`, via portal on `document.body`, everything else `inert` (unreachable for mouse, keyboard and
 * screen reader), Tab stays in the sheet. Docked and as a window, the bar remains an
 * ordinary region next to the page.
 *
 * The sheet joins the stack of modal layers of `Modal` (d86f27b2): a dialog opened from the sheet lies
 * above it, so Escape and Tab belong to that dialog until it closes.
 */
function SheetPanel({ labelledBy, testId, kind, onClose, children }: {
  labelledBy: string;
  testId: string;
  kind: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLElement>(null);
  const [host] = useState(() => document.createElement('div'));
  const isTop = useModalLayer();
  useEscapeToClose(onClose, useCallback(() => !isTop(), [isTop]));
  useLayoutEffect(() => {
    document.body.appendChild(host);
    // Everything except the sheet and the always-operable portals (`data-always-interactive`: toasts)
    // steps back. If the sheet itself opens a modal dialog (TODO,
    // edit, delete - they are in the page tree), that one rules while it is open: then nothing is inert.
    const marked = new Set<Element>();
    const apply = () => {
      const otherModal = [...document.querySelectorAll('[aria-modal="true"]')].some((element) => !host.contains(element));
      for (const element of [...document.body.children]) {
        // Toasts („Rückgängig“) stay operable - review c60bf914
        if (element === host || element.hasAttribute('data-always-interactive')) continue;
        if (!otherModal && !element.hasAttribute('inert')) {
          element.setAttribute('inert', '');
          marked.add(element);
        } else if (otherModal && marked.has(element)) {
          element.removeAttribute('inert');
          marked.delete(element);
        }
      }
    };
    apply();
    const observer = new MutationObserver(apply);
    observer.observe(document.body, { childList: true, subtree: true });
    return () => {
      observer.disconnect();
      marked.forEach((element) => element.removeAttribute('inert'));
      host.remove();
    };
  }, [host]);

  const trapTab = (event: KeyboardEvent) => {
    if (event.key !== 'Tab' || !ref.current || !isTop()) return;
    const focusable = tabbables(ref.current);
    const first = focusable[0];
    const last = focusable.at(-1);
    if (!first || !last) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return createPortal(
    <>
      <div aria-hidden="true" onClick={onClose} className="fixed inset-0 z-30 bg-black/40" />
      <aside ref={ref} role="dialog" aria-modal="true" aria-labelledby={labelledBy} data-testid={testId} data-kind={kind}
        data-mode="sheet" onKeyDown={trapTab}
        className="fixed inset-y-0 right-0 z-40 flex w-full max-w-md flex-col border-l border-border bg-surface-raised shadow-2xl">
        {children}
      </aside>
    </>,
    host,
  );
}

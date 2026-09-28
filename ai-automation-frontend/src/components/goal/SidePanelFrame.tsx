import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react';
import { GripVertical, PanelRight, PictureInPicture2, X } from 'lucide-react';
import { useT } from '../../i18n';
import { useIsMobile } from '../../hooks/useIsMobile';
import {
  DOCK_MAX, DOCK_MIN, clampRect, clampWidth, movedRect, readPanelPrefs, resizedRect, storePanelPrefs,
  type PanelPrefs, type ResizeEdge, type Viewport,
} from './panelGeometry';

const STEP = 16;
/** Abstand der angedockten Leiste zum oberen und unteren Rand, wenn sie klebt. */
const STICKY_TOP = 16;
const BIG_STEP = 64;
const viewport = (): Viewport => ({ width: window.innerWidth, height: window.innerHeight });

/** Zieht mit der Maus (Zeiger): `onMove` bekommt den Weg seit dem Drücken, bis losgelassen wird. */
function dragFrom(event: ReactPointerEvent, onMove: (dx: number, dy: number) => void) {
  if (event.button !== 0) return;
  event.preventDefault();
  const startX = event.clientX;
  const startY = event.clientY;
  const move = (e: PointerEvent) => onMove(e.clientX - startX, e.clientY - startY);
  const up = () => {
    window.removeEventListener('pointermove', move);
    window.removeEventListener('pointerup', up);
  };
  window.addEventListener('pointermove', move);
  window.addEventListener('pointerup', up);
}

/**
 * Rahmen der Seitenleiste (c60bf914): ab 1024 px angedockt rechts neben dem Inhalt – so hoch wie der
 * Sichtbereich, mit eigener Scrollfläche, die Breite am linken Griff änderbar (Maus, ← →) – oder als
 * Fenster gelöst, an der Titelleiste verschiebbar (auch mit den Pfeiltasten), an Rändern und Ecke in
 * der Größe änderbar, immer ganz im Sichtbereich. Breite, Lage, Größe und Art bleiben gemerkt. Darunter
 * ein Blatt über dem Inhalt.
 */
export function SidePanelFrame({ labelledBy, header, children, footer, onClose, testId, kind }: {
  labelledBy: string;
  header: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  onClose: () => void;
  testId: string;
  kind: string;
}) {
  const t = useT();
  const tp = t.goals.panel;
  const mobile = useIsMobile();
  const [prefs, setPrefs] = useState<PanelPrefs>(() => readPanelPrefs(viewport()));
  useEffect(() => storePanelPrefs(prefs), [prefs]);

  // Wird das Browserfenster kleiner, kommt das Fenster zurück in den Sichtbereich
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
    dragFrom(event, (dx) => setPrefs((current) => ({ ...current, width: clampWidth(start - dx, viewport()) })));
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
    dragFrom(event, (dx, dy) => setPrefs((current) => ({ ...current, rect: movedRect(start, dx, dy, viewport()) })));
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
    dragFrom(event, (dx, dy) => setPrefs((current) => ({ ...current, rect: resizedRect(start, edge, dx, dy, viewport()) })));
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
      <button type="button" onClick={onClose} aria-label={tp.close} title={tp.close}
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
      <>
        <div aria-hidden="true" onClick={onClose} className="fixed inset-0 z-30 bg-black/40" />
        <aside aria-labelledby={labelledBy} data-testid={testId} data-kind={kind} data-mode="sheet"
          className="fixed inset-y-0 right-0 z-40 flex w-full max-w-md flex-col border-l border-border bg-surface-raised shadow-2xl">
          <div className="flex items-start gap-2 px-4 pb-2 pt-4"><div className="min-w-0 flex-1">{header}</div>{buttons}</div>
          {body}
        </aside>
      </>
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
 * Angedockt steht die Leiste neben dem Inhalt und bleibt beim Scrollen oben stehen (sticky). Ihre Höhe
 * reicht immer genau bis zum unteren Rand des Sichtbereichs – auch bevor sie klebt, solange der Kopf der
 * Seite noch darüber steht –, damit nie etwas unten abgeschnitten ist; den Rest scrollt sie in sich.
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

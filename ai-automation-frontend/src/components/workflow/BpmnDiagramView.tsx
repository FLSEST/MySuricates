import { useEffect, useRef } from 'react';
import { useT } from '../../i18n';
import { FIT_MIN_WIDTH_RATIO, useDiagramZoom } from './useDiagramZoom';
import {
  diagramBounds, parseBpmn, selectionOf, verticalLayout,
  type BpmnLabelMaps, type DiagramSelection, type RunStatus,
} from './bpmnLayout';
import { ShapeSvg } from './BpmnShapes';

export type { DiagramSelection, RunStatus } from './bpmnLayout';

export type DiagramOrientation = 'horizontal' | 'vertical';

/** Kurzes Aufblitzen eines Blocks (Querverweis Liste ↔ Diagramm); ein neuer `nonce` blitzt erneut. */
export type BlockFlash = { index: number; nonce: number; scroll?: boolean };

export default function BpmnDiagramView({
  xml,
  onSelectBlock,
  statusByBlock,
  infoByBlock,
  orientation,
  flashBlock,
  onHoverBlock,
}: {
  xml: string;
  onSelectBlock?: (selection: DiagramSelection) => void;
  /** Live-Status je Top-Level-Block-Index (färbt den zugehörigen Kasten). */
  statusByBlock?: Map<number, RunStatus>;
  /** Zeilenbereich je Block-Index — eingeschaltet zeigt jeder Kasten „#N · Z.a–b". */
  infoByBlock?: Map<number, { start: number; end: number }>;
  /** Fluss-Richtung: links→rechts (Standard) oder oben→unten. */
  orientation?: DiagramOrientation;
  /** Lässt den Kasten dieses Top-Level-Blocks kurz aufblitzen; ein neuer `nonce` blitzt erneut. */
  flashBlock?: BlockFlash | null;
  /** Maus über einem Kasten — meldet den Top-Level-Block-Index (verschachtelte Kästen: ihren Eltern-Block). */
  onHoverBlock?: (blockIndex: number) => void;
}) {
  const t = useT();
  const maps = t.workflows.bpmnLabels as unknown as BpmnLabelMaps;
  const isVertical = orientation === 'vertical';
  const parsed = parseBpmn(xml);
  const diagram = parsed && parsed.shapes.length > 0 ? (isVertical ? verticalLayout(parsed) : parsed) : null;
  const bounds = diagram ? diagramBounds(diagram) : null;
  const scrollRef = useRef<HTMLDivElement>(null);
  const flashIndex = flashBlock?.index;
  const flashNonce = flashBlock?.nonce;
  // Per Hover ausgelöst wird nicht gescrollt — sonst springt die Seite unter der Maus weg
  const flashScroll = flashBlock?.scroll !== false;
  useEffect(() => {
    if (flashIndex === undefined || !flashScroll) return;
    const el = scrollRef.current?.querySelector(`[data-block-index="${flashIndex}"]`);
    el?.scrollIntoView?.({ block: 'nearest', inline: 'center', behavior: 'smooth' });
  }, [flashIndex, flashNonce, flashScroll]);
  const zoom = useDiagramZoom(scrollRef, isVertical ? 'vertical' : 'horizontal', bounds?.width ?? 0, isVertical ? 'native' : 'width');
  if (!parsed) {
    return <div role="alert" className="p-4 text-sm text-text-secondary">{t.workflows.diagramInvalid}</div>;
  }
  if (!diagram || !bounds) {
    return <div role="alert" className="p-4 text-sm text-text-secondary">{t.workflows.diagramEmpty}</div>;
  }
  const { minX, minY, width, height } = bounds;

  // Eingepasst, horizontal: breitenfüllend (fit-to-width), horizontal scrollbar.
  // Eingepasst, vertikal: native Größe (schmal, hoch) mit begrenzter Höhe + vertikalem Scrollen —
  // sonst streckt der Container die schmal-hohe viewBox und das Diagramm wird riesig.
  // Gezoomt: feste Größe in beide Richtungen, verschoben wird per Scrollen.
  const { scale } = zoom;
  const fixedSize = scale !== null ? { width: width * scale, height: height * scale } : isVertical ? { width, height } : null;

  return (
    <div className="relative">
      <div
        role="group"
        aria-label={t.workflows.zoomHint}
        title={t.workflows.zoomHint}
        className="absolute top-1 right-1 z-10 flex items-center gap-0.5 rounded-md border border-border bg-surface/85 p-0.5 text-text-secondary shadow-sm"
      >
        <button type="button" onClick={zoom.zoomOut} disabled={!zoom.canZoomOut}
          aria-label={t.workflows.zoomOut} title={t.workflows.zoomOut}
          className="h-6 w-6 rounded text-base leading-none hover:bg-surface-raised hover:text-text disabled:opacity-40">
          −
        </button>
        <button type="button" onClick={zoom.fit} disabled={scale === null}
          aria-label={t.workflows.zoomFit} title={t.workflows.zoomFit}
          className="min-w-[3.25rem] rounded px-1 py-0.5 text-xs tabular-nums hover:bg-surface-raised hover:text-text disabled:hover:bg-transparent">
          {scale !== null ? `${Math.round(scale * 100)} %` : t.workflows.zoomFit}
        </button>
        <button type="button" onClick={zoom.zoomIn} disabled={!zoom.canZoomIn}
          aria-label={t.workflows.zoomIn} title={t.workflows.zoomIn}
          className="h-6 w-6 rounded text-base leading-none hover:bg-surface-raised hover:text-text disabled:opacity-40">
          +
        </button>
      </div>
      <div ref={scrollRef} className={fixedSize ? 'overflow-auto max-h-[70vh] text-text' : 'overflow-x-auto text-text'}>
        <svg
          viewBox={`${minX} ${minY} ${width} ${height}`}
          {...(fixedSize ?? {})}
          style={fixedSize ? { maxWidth: 'none' } : { minWidth: width * FIT_MIN_WIDTH_RATIO }}
          role="img"
        >
          <defs>
            <marker id="bpmn-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" className="fill-text" />
            </marker>
            {/* Datenassoziation: offene Pfeilspitze (BPMN-Notation) */}
            <marker id="bpmn-arrow-open" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10" fill="none" className="stroke-text-secondary" strokeWidth={1.4} />
            </marker>
          </defs>
          {diagram.edges.map((e) => (
            <g key={e.id}>
              <polyline
                points={e.points.map((p) => p.join(',')).join(' ')}
                fill="none"
                className={e.dashed ? 'stroke-text-secondary' : 'stroke-text'}
                strokeWidth={e.dashed ? 1.3 : 1.6}
                {...(e.dashed ? { strokeDasharray: '5 4' } : {})}
                markerEnd={e.dashed ? 'url(#bpmn-arrow-open)' : 'url(#bpmn-arrow)'} />
              {e.name && e.points.length >= 2 && (
                <text
                  x={(e.points[0]![0] + e.points[e.points.length - 1]![0]) / 2}
                  y={(e.points[0]![1] + e.points[e.points.length - 1]![1]) / 2 - 6}
                  textAnchor="middle" className="fill-text-secondary text-[11px]">
                  {e.name}
                </text>
              )}
            </g>
          ))}
          {diagram.shapes.map((s) => {
            const sel = selectionOf(s.id);
            const clickable = sel !== null && !!onSelectBlock;
            const status = sel && statusByBlock ? statusByBlock.get(sel.blockIndex) : undefined;
            const info = sel && infoByBlock ? infoByBlock.get(sel.blockIndex) : undefined;
            const topLevel = sel !== null && !sel.sub;
            const flashing = topLevel && flashBlock?.index === sel.blockIndex;
            return (
              <g
                key={s.id}
                data-block-index={topLevel ? sel.blockIndex : undefined}
                className={clickable ? 'cursor-pointer transition-opacity hover:opacity-70' : undefined}
                onClick={clickable ? () => onSelectBlock!(sel!) : undefined}
                onMouseEnter={sel && onHoverBlock ? () => onHoverBlock(sel.blockIndex) : undefined}
                role={clickable ? 'button' : undefined}
                tabIndex={clickable ? 0 : undefined}
                onKeyDown={clickable ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelectBlock!(sel!); } } : undefined}
              >
                <ShapeSvg s={s} status={status} maps={maps} />
                {flashing && (
                  <rect key={flashBlock!.nonce} data-testid="bpmn-flash" x={s.x - 4} y={s.y - 4} width={s.w + 8} height={s.h + 8} rx={12}
                    className="block-flash fill-accent/20 stroke-accent pointer-events-none" strokeWidth={2.5} />
                )}
                {info && sel && (
                  <text x={s.x} y={s.y - 4} className="fill-text-tertiary text-[9px]" style={{ fontFamily: 'monospace' }}>
                    #{sel.blockIndex + 1} · Z.{info.start}–{info.end}
                  </text>
                )}
              </g>
            );
          })}
        </svg>
      </div>
    </div>
  );
}

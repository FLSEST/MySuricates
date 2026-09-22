import { useState, useSyncExternalStore } from 'react';
import { ChevronDown, ChevronRight, Info, AlertTriangle } from 'lucide-react';
import { Card } from '../ui';
import { useT } from '../../i18n';
import {
  NODES, EDGES, VIEW_W, VIEW_H, LANE_SPLIT,
  activeParts, tokenRoute, routeDuration, pointsToPath,
  type FactoryMode, type FlowNode, type FlowNodeId, type FlowEdgeId,
} from './factoryFlow';

const OPEN_KEY = 'mysuricates-factory-flow-open';

const TONES: Record<FlowNode['tone'], { fill: string; stroke: string }> = {
  ai: { fill: 'var(--color-ai-subtle)', stroke: 'var(--color-ai)' },
  info: { fill: 'var(--color-info-subtle)', stroke: 'var(--color-info)' },
  success: { fill: 'var(--color-success-subtle)', stroke: 'var(--color-success)' },
  neutral: { fill: 'var(--color-surface-secondary)', stroke: 'var(--color-border-strong)' },
};

const EDGE_COLORS: Record<FlowEdgeId, string> = {
  sync: 'var(--color-text-secondary)',
  fail: 'var(--color-danger)',
  retry: 'var(--color-ai)',
  pass: 'var(--color-success)',
  promote: 'var(--color-text-secondary)',
};

function readOpen(): boolean {
  try { return localStorage.getItem(OPEN_KEY) !== 'false'; } catch { return true; }
}

function subscribeReducedMotion(onChange: () => void) {
  if (typeof window === 'undefined' || !window.matchMedia) return () => {};
  const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
  mq.addEventListener('change', onChange);
  return () => mq.removeEventListener('change', onChange);
}

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

interface FactoryFlowDiagramProps {
  autoFix: boolean;
  autoPromote: boolean;
  maxRetries: number;
  testHealthy: boolean;
}

/**
 * Explains what the factory does — and in which mode. Only "Create & test" runs the
 * DEV → TEST → repair loop; plain "Create workflow" stops after generating on DEV.
 * An explanation, not live progress: /factory/run is one blocking call without events.
 */
export function FactoryFlowDiagram({ autoFix, autoPromote, maxRetries, testHealthy }: FactoryFlowDiagramProps) {
  const t = useT();
  const f = t.factory;
  const [open, setOpen] = useState(readOpen);
  const [mode, setMode] = useState<FactoryMode>('createAndTest');
  const reducedMotion = useSyncExternalStore(subscribeReducedMotion, prefersReducedMotion, () => false);

  const toggleOpen = () => {
    const next = !open;
    setOpen(next);
    try { localStorage.setItem(OPEN_KEY, String(next)); } catch { /* per-viewer convenience only */ }
  };

  const options = { mode, autoFix, autoPromote };
  const active = activeParts(options);
  const route = tokenRoute(options);
  const isOn = (id: FlowNodeId | FlowEdgeId) => active.has(id);

  const nodeText: Record<FlowNodeId, { title: string; sub?: string }> = {
    generate: { title: f.flowGenerate, sub: f.flowGenerateSub },
    test: { title: f.flowTest, sub: f.flowTestSub },
    repair: { title: f.flowRepair, sub: autoFix ? f.flowRepairSub : f.flowOff },
    result: { title: f.flowResult },
    template: { title: f.flowTemplate },
  };

  const steps = mode === 'create'
    ? [f.flowStepGenerate, f.flowStepCreateOnly]
    : [
        f.flowStepGenerate,
        f.flowStepSync,
        f.flowStepTest,
        autoFix ? f.flowStepRepair.replace('{n}', String(maxRetries)) : f.flowStepRepairOff,
        autoPromote ? f.flowStepResultPromote : f.flowStepResult,
      ];

  const modeButton = (value: FactoryMode, label: string) => (
    <button
      type="button"
      role="tab"
      aria-selected={mode === value}
      onClick={() => setMode(value)}
      className={`px-3 py-1.5 text-sm rounded-md transition-colors ${
        mode === value
          ? 'bg-accent text-text-inverse'
          : 'text-text-secondary hover:text-text-primary hover:bg-surface-hover'
      }`}
    >
      {label}
    </button>
  );

  return (
    <Card className="p-0 overflow-hidden">
      <button
        type="button"
        onClick={toggleOpen}
        aria-expanded={open}
        className="w-full flex items-center gap-2 px-6 py-4 text-left hover:bg-surface-hover transition-colors"
      >
        {open ? <ChevronDown className="w-4 h-4 text-text-secondary" /> : <ChevronRight className="w-4 h-4 text-text-secondary" />}
        <span className="font-semibold text-text-primary">{f.flowTitle}</span>
        <span className="text-sm text-text-secondary hidden sm:inline">— {f.flowSubtitle}</span>
      </button>

      {open && (
        <div className="px-6 pb-6 space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-sm text-text-secondary">{f.flowModeLabel}</span>
            <div role="tablist" aria-label={f.flowModeLabel} className="inline-flex gap-1 p-1 rounded-lg bg-surface-secondary">
              {modeButton('create', f.createWorkflow)}
              {modeButton('createAndTest', f.createAndTest)}
            </div>
          </div>

          <div className={`flex gap-2 rounded-lg p-3 text-sm ${
            mode === 'create' ? 'bg-surface-secondary text-text-secondary' : 'bg-info-subtle text-info'
          }`}>
            <Info className="w-4 h-4 shrink-0 mt-0.5" />
            <p>{mode === 'create' ? f.flowNoteCreate : f.flowNoteCreateAndTest}</p>
          </div>

          {mode === 'createAndTest' && !testHealthy && (
            <div className="flex gap-2 rounded-lg p-3 text-sm bg-warning-subtle text-warning">
              <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
              <p>{f.flowTestOffline}</p>
            </div>
          )}

          {/* Min width keeps labels legible on phones; the box scrolls, not the page */}
          <div className="overflow-x-auto -mx-2 px-2">
            <svg
              viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
              className="w-full min-w-[520px] max-w-3xl mx-auto block"
              role="img"
              aria-label={f.flowTitle}
            >
              <defs>
                {Object.entries(EDGE_COLORS).map(([id, color]) => (
                  <marker key={id} id={`factory-arrow-${id}`} viewBox="0 0 10 10" refX="9" refY="5"
                    markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                    <path d="M0,0 L10,5 L0,10 z" fill={color} />
                  </marker>
                ))}
              </defs>

              {/* Lanes */}
              <rect x={8} y={8} width={LANE_SPLIT - 16} height={VIEW_H - 16} rx={12}
                fill="var(--color-surface-secondary)" />
              <rect x={LANE_SPLIT + 8} y={8} width={VIEW_W - LANE_SPLIT - 16} height={VIEW_H - 16} rx={12}
                fill="var(--color-surface-secondary)" opacity={mode === 'create' ? 0.45 : 1} />
              <text x={24} y={34} fontSize={12} fontWeight={600} fill="var(--color-text-secondary)">{f.flowLaneDev}</text>
              <text x={LANE_SPLIT + 24} y={34} fontSize={12} fontWeight={600} fill="var(--color-text-secondary)"
                opacity={mode === 'create' ? 0.45 : 1}>{f.flowLaneTest}</text>

              {/* Edges */}
              {(Object.keys(EDGES) as FlowEdgeId[]).map(id => (
                <path
                  key={id}
                  data-edge={id}
                  data-active={isOn(id)}
                  d={pointsToPath(EDGES[id])}
                  fill="none"
                  stroke={EDGE_COLORS[id]}
                  strokeWidth={2}
                  strokeDasharray={isOn(id) ? undefined : '4 4'}
                  opacity={isOn(id) ? 0.9 : 0.25}
                  markerEnd={`url(#factory-arrow-${id})`}
                />
              ))}
              <g fontSize={11} fill="var(--color-text-secondary)">
                <text x={285} y={74} textAnchor="middle" opacity={isOn('sync') ? 1 : 0.3}>{f.flowEdgeSync}</text>
                <text x={375} y={238} textAnchor="middle" fill="var(--color-danger)" opacity={isOn('fail') ? 1 : 0.3}>
                  {f.flowEdgeFail}
                </text>
                <text x={345} y={186} textAnchor="middle" opacity={isOn('retry') ? 1 : 0.3}>{f.flowEdgeRetry}</text>
                <text x={345} y={200} textAnchor="middle" opacity={isOn('retry') ? 1 : 0.3}>
                  {autoFix ? f.flowEdgeRetryMax.replace('{n}', String(maxRetries)) : f.flowOff}
                </text>
                <text x={440} y={294} textAnchor="middle" fill="var(--color-success)" opacity={isOn('pass') ? 1 : 0.3}>
                  {f.flowEdgePass}
                </text>
              </g>

              {/* Token travels the route of the current settings, drawn under the nodes so it
                  only shows on the arrows and never covers a label */}
              {route && !reducedMotion && (
                <circle key={pointsToPath(route)} r={6} fill="var(--color-accent)" data-token="moving">
                  <animateMotion dur={`${routeDuration(route)}s`} repeatCount="indefinite" path={pointsToPath(route)} />
                </circle>
              )}

              {/* Nodes */}
              {(Object.values(NODES)).map(node => {
                const tone = TONES[node.tone];
                const on = isOn(node.id);
                const text = nodeText[node.id];
                const cx = node.x + node.w / 2;
                return (
                  <g key={node.id} data-node={node.id} data-active={on} opacity={on ? 1 : 0.35}>
                    <rect x={node.x} y={node.y} width={node.w} height={node.h} rx={10}
                      fill={tone.fill} stroke={tone.stroke} strokeWidth={1.5}
                      strokeDasharray={on ? undefined : '4 4'} />
                    <text x={cx} y={text.sub ? node.y + 20 : node.y + node.h / 2 + 4} textAnchor="middle"
                      fontSize={13} fontWeight={600} fill="var(--color-text)">{text.title}</text>
                    {text.sub && (
                      <text x={cx} y={node.y + 37} textAnchor="middle" fontSize={11}
                        fill="var(--color-text-secondary)">{text.sub}</text>
                    )}
                  </g>
                );
              })}

              {/* "Create" mode (or reduced motion): nothing travels, the dot marks where it starts */}
              {(!route || reducedMotion) && (
                <circle cx={NODES.generate.x + 14} cy={NODES.generate.y + NODES.generate.h / 2} r={5}
                  fill="var(--color-accent)" data-token="static" />
              )}
            </svg>
          </div>

          <ol className="space-y-1.5 text-sm text-text-secondary list-decimal pl-5">
            {steps.map((step, i) => <li key={i}>{step}</li>)}
          </ol>
        </div>
      )}
    </Card>
  );
}

import { useT } from '../../i18n';
import {
  NODES, EDGES, VIEW_W, VIEW_H, LANE_SPLIT, pointsToPath,
  type FactoryMode, type FlowFrame, type FlowNode, type FlowNodeId, type FlowEdgeId,
} from './factoryFlow';

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

interface FactoryFlowSvgProps {
  mode: FactoryMode;
  autoFix: boolean;
  maxRetries: number;
  active: ReadonlySet<FlowNodeId | FlowEdgeId>;
  frame: FlowFrame | null;
  currentPart: FlowNodeId | FlowEdgeId;
  currentStep: number;
}

/**
 * The drawing of the factory flow: DEV and TEST lanes, edges with their labels, the moving token
 * and the nodes. Parts outside the chosen mode and switches are dashed and dimmed.
 */
export function FactoryFlowSvg({
  mode, autoFix, maxRetries, active, frame, currentPart, currentStep,
}: FactoryFlowSvgProps) {
  const f = useT().factory;
  const isOn = (id: FlowNodeId | FlowEdgeId) => active.has(id);

  const nodeText: Record<FlowNodeId, { title: string; sub?: string }> = {
    generate: { title: f.flowGenerate, sub: f.flowGenerateSub },
    test: { title: f.flowTest, sub: f.flowTestSub },
    repair: { title: f.flowRepair, sub: autoFix ? f.flowRepairSub : f.flowRepairOnlyWithFix },
    result: { title: f.flowResult },
    template: { title: f.flowTemplate },
  };

  return (
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
          data-current={currentPart === id}
          d={pointsToPath(EDGES[id])}
          fill="none"
          stroke={EDGE_COLORS[id]}
          strokeWidth={currentPart === id ? 3 : 2}
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
      {mode === 'create' && (
        <text x={NODES.generate.x + NODES.generate.w / 2} y={NODES.generate.y + NODES.generate.h + 26}
          textAnchor="middle" fontSize={12} fontWeight={600} data-end="create"
          fill={currentStep === 1 ? 'var(--color-accent)' : 'var(--color-text-secondary)'}>
          {f.flowEndNoTest}
        </text>
      )}

      {/* Token on the arrows, drawn under the nodes so it never covers a label; while it
          dwells in a node, the node's highlight shows where it is */}
      {frame && !frame.inNode && (
        <circle cx={frame.x} cy={frame.y} r={6} fill="var(--color-accent)" data-token="moving" />
      )}

      {/* Nodes */}
      {(Object.values(NODES)).map(node => {
        const tone = TONES[node.tone];
        const on = isOn(node.id);
        const current = currentPart === node.id;
        const text = nodeText[node.id];
        const cx = node.x + node.w / 2;
        return (
          <g key={node.id} data-node={node.id} data-active={on} data-current={current}
            opacity={on ? 1 : 0.35}>
            <rect x={node.x} y={node.y} width={node.w} height={node.h} rx={10}
              fill={tone.fill} stroke={tone.stroke} strokeWidth={current ? 3 : 1.5}
              strokeDasharray={on ? undefined : '4 4'}
              style={current ? { filter: `drop-shadow(0 0 6px ${tone.stroke})` } : undefined} />
            <text x={cx} y={text.sub ? node.y + 20 : node.y + node.h / 2 + 4} textAnchor="middle"
              fontSize={13} fontWeight={600} fill="var(--color-text)">{text.title}</text>
            {text.sub && (
              <text x={cx} y={node.y + 37} textAnchor="middle" fontSize={11}
                fill="var(--color-text-secondary)">{text.sub}</text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

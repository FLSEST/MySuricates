import type { PanelDiagramId } from '../../data/walkthroughPanels';

/**
 * The deterministic half of a panel: boxes, arrows and order are drawn from code, never generated.
 * A model cannot place arrows or labels reliably, and a diagram that *looks* explanatory while
 * explaining the wrong thing is worse than none. The generated meerkat illustration sits beside
 * this, never inside it.
 *
 * All five share one viewBox, one box size and one stroke weight, so the set looks like a set
 * without anyone curating it. Colour comes from `currentColor`, so every theme is served.
 */

const VIEW = '0 0 320 104';

/** One step. Dashed + accent marks the step where a model decides. */
function Box({ x, y, w = 52, h = 30, ai = false }: { x: number; y: number; w?: number; h?: number; ai?: boolean }) {
  return (
    <g className={ai ? 'text-accent' : 'text-text-tertiary'}>
      <rect
        x={x}
        y={y}
        width={w}
        height={h}
        rx={8}
        fill="none"
        stroke="currentColor"
        strokeWidth={2}
        strokeDasharray={ai ? '5 4' : undefined}
      />
      {ai && <circle cx={x + w / 2} cy={y + h / 2} r={3} fill="currentColor" />}
    </g>
  );
}

/** Horizontal hand-off between two steps. */
function Arrow({ from, to, y, accent = false, dashed = false }: { from: number; to: number; y: number; accent?: boolean; dashed?: boolean }) {
  return (
    <g className={accent ? 'text-accent' : 'text-text-tertiary'} stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
      <line x1={from} y1={y} x2={to - 4} y2={y} strokeDasharray={dashed ? '4 4' : undefined} />
      <polyline points={`${to - 7},${y - 4} ${to},${y} ${to - 7},${y + 4}`} strokeLinejoin="round" />
    </g>
  );
}

function Mix() {
  const xs = [8, 82, 156, 230];
  return (
    <>
      {xs.map((x, i) => (
        <Box key={x} x={x} y={37} ai={i === 2} />
      ))}
      {xs.slice(0, 3).map((x) => (
        <Arrow key={x} from={x + 54} to={x + 72} y={52} />
      ))}
    </>
  );
}

function Trace() {
  const xs = [8, 74, 140, 206];
  return (
    <>
      {xs.map((x) => (
        <Box key={x} x={x} y={16} w={44} h={26} />
      ))}
      {xs.slice(0, 3).map((x) => (
        <Arrow key={x} from={x + 46} to={x + 64} y={29} />
      ))}
      <g className="text-text-tertiary" stroke="currentColor" strokeWidth={2} strokeLinecap="round">
        <line x1={8} y1={78} x2={300} y2={78} opacity={0.5} />
        {xs.map((x) => (
          <line key={x} x1={x + 22} y1={74} x2={x + 22} y2={82} opacity={0.5} />
        ))}
      </g>
      {/* One tick is picked up again: an earlier version, restored. */}
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
        <circle cx={162} cy={78} r={4} fill="currentColor" stroke="none" />
        <path d="M158 74 C 130 60, 112 58, 100 48" strokeDasharray="4 4" />
        <polyline points="104,52 99,46 106,44" strokeLinejoin="round" />
      </g>
    </>
  );
}

function Wait() {
  return (
    <>
      <Box x={8} y={36} w={48} h={32} />
      <Box x={68} y={36} w={48} h={32} />
      <Arrow from={56} to={66} y={52} />
      <Arrow from={116} to={132} y={52} />
      {/* The run stands still here — and picks up on the far side of the gap. */}
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none">
        <circle cx={150} cy={52} r={16} strokeDasharray="5 4" />
        <line x1={145} y1={45} x2={145} y2={59} strokeLinecap="round" />
        <line x1={155} y1={45} x2={155} y2={59} strokeLinecap="round" />
      </g>
      <Arrow from={168} to={182} y={52} accent />
      <Box x={184} y={36} w={48} h={32} />
      <Box x={244} y={36} w={48} h={32} />
      <Arrow from={232} to={242} y={52} />
    </>
  );
}

function Verdict() {
  return (
    <>
      <Box x={8} y={14} w={56} h={30} />
      <Arrow from={64} to={82} y={29} />
      {/* The disposition: accepted, with the evidence of the moment stamped onto it. */}
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none">
        <rect x={84} y={14} width={56} height={30} rx={8} />
        <polyline points="101,29 109,37 124,21" strokeLinecap="round" strokeLinejoin="round" />
      </g>
      <Arrow from={140} to={162} y={29} />
      <g className="text-text-tertiary" stroke="currentColor" strokeWidth={2} strokeLinecap="round" opacity={0.5}>
        <line x1={164} y1={29} x2={300} y2={29} />
        {[196, 232, 268].map((x) => (
          <line key={x} x1={x} y1={25} x2={x} y2={33} />
        ))}
      </g>
      {/* Evidence degrades, or half a year passes: the finding returns on its own. */}
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
        <path d="M300 36 C 300 88, 70 92, 38 56" strokeDasharray="4 4" />
        <polyline points="34,62 37,52 45,57" strokeLinejoin="round" />
      </g>
    </>
  );
}

function Refill() {
  return (
    <>
      {/* The frame stays; what is inside it is replaced over time. */}
      <g className="text-text-tertiary" stroke="currentColor" strokeWidth={2} fill="none">
        <rect x={88} y={20} width={144} height={64} rx={10} />
        <rect x={104} y={32} width={112} height={10} rx={3} opacity={0.6} />
        <rect x={104} y={48} width={112} height={10} rx={3} opacity={0.6} />
      </g>
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none">
        <rect x={104} y={64} width={112} height={10} rx={3} strokeDasharray="5 4" />
      </g>
      <Arrow from={20} to={84} y={52} accent />
      {/* What has expired leaves by itself. */}
      <Arrow from={236} to={300} y={78} dashed />
      <g className="text-text-tertiary" stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
        <path d="M120 14 A 44 16 0 0 1 204 12" />
        <polyline points="197,6 205,12 197,17" strokeLinejoin="round" />
      </g>
    </>
  );
}

/** Few shapes, many arrangements — the point of the whole tour, said once without words. */
function Intro() {
  const grid = [90, 140, 190, 240];
  const rows = [12, 42, 72];
  return (
    <>
      {rows.map((y) => (
        <Box key={y} x={8} y={y} w={28} h={20} />
      ))}
      <Arrow from={40} to={82} y={52} />
      {rows.map((y) =>
        grid.map((x) => <Box key={`${x}-${y}`} x={x} y={y} w={30} h={20} ai={x === 190 && y === 42} />),
      )}
    </>
  );
}

function Notice() {
  return (
    <>
      <g className="text-text-tertiary" stroke="currentColor" strokeWidth={2} strokeLinecap="round" opacity={0.5}>
        <line x1={8} y1={88} x2={104} y2={88} />
      </g>
      {/* Caught on the way out. */}
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
        <path d="M56 84 C 56 60, 64 48, 76 40" strokeDasharray="4 4" />
        <polyline points="72,44 78,38 80,46" strokeLinejoin="round" />
        <rect x={98} y={22} width={34} height={54} rx={17} />
        <circle cx={115} cy={49} r={5} fill="currentColor" stroke="none" />
      </g>
      <g className="text-text-tertiary" stroke="currentColor" strokeWidth={2} fill="none">
        {[186, 234, 282].map((x) => (
          <g key={x}>
            <rect x={x - 15} y={28} width={30} height={48} rx={15} />
            <circle cx={x} cy={52} r={4} fill="currentColor" stroke="none" />
          </g>
        ))}
      </g>
      {/* And back to the bench it came from: the loop is the point, not the shelf. */}
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
        <path d="M282 80 C 282 99, 150 102, 72 94" strokeDasharray="4 4" />
        <polyline points="79,89 70,94 80,98" strokeLinejoin="round" />
      </g>
    </>
  );
}

/** Two things that need no hand start the same line; the line runs on out of the frame. */
function Unattended() {
  return (
    <>
      {/* A time and an arriving item — the two triggers that are core, not a module. */}
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
        <circle cx={26} cy={26} r={13} />
        <polyline points="26,18 26,26 32,30" strokeLinejoin="round" />
        <rect x={12} y={62} width={28} height={22} rx={6} />
        <line x1={19} y1={69} x2={33} y2={69} />
        <line x1={19} y1={77} x2={28} y2={77} />
      </g>
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
        <path d="M42 30 C 62 34, 68 42, 74 50" strokeDasharray="4 4" />
        <path d="M44 72 C 62 70, 68 62, 74 54" strokeDasharray="4 4" />
        <polyline points="70,44 77,52 68,55" strokeLinejoin="round" />
      </g>
      <Box x={82} y={37} w={52} h={30} />
      <Arrow from={134} to={152} y={52} />
      <Box x={152} y={37} w={52} h={30} ai />
      <Arrow from={204} to={222} y={52} />
      <Box x={222} y={37} w={52} h={30} />
      {/* Runs on past the edge: nobody is waiting at the end of it. */}
      <Arrow from={274} to={312} y={52} />
    </>
  );
}

/** A failed step is picked up again — and the return has a floor it cannot go below. */
function Repair() {
  return (
    <>
      <Box x={8} y={12} w={48} h={28} />
      <Arrow from={56} to={74} y={26} />
      <Box x={74} y={12} w={48} h={28} />
      <Arrow from={122} to={140} y={26} />
      {/* The step that broke. */}
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
        <rect x={140} y={12} width={48} height={28} rx={8} />
        <line x1={155} y1={19} x2={173} y2={33} />
        <line x1={173} y1={19} x2={155} y2={33} />
        {/* Back to the step before it, not to the start. */}
        <path d="M188 34 C 196 58, 130 62, 98 44" strokeDasharray="4 4" />
        <polyline points="102,49 94,42 103,38" strokeLinejoin="round" />
      </g>
      {/* The ceiling: the attempts run along it and stop where it ends. */}
      <g className="text-text-tertiary" stroke="currentColor" strokeWidth={2} strokeLinecap="round">
        <line x1={8} y1={84} x2={232} y2={84} opacity={0.5} />
        {[64, 120, 176].map((x) => (
          <line key={x} x1={x} y1={80} x2={x} y2={88} opacity={0.5} />
        ))}
        <line x1={232} y1={72} x2={232} y2={96} />
      </g>
      <g className="text-accent" stroke="currentColor" strokeWidth={2} strokeLinecap="round">
        <line x1={8} y1={84} x2={120} y2={84} />
      </g>
    </>
  );
}

function Connect() {
  // Deliberately three different outlines: what hangs on the outside is not one kind of thing.
  const shape = (x: number, y: number, kind: number) =>
    kind === 0 ? <rect x={x} y={y} width={26} height={20} rx={4} /> :
    kind === 1 ? <circle cx={x + 13} cy={y + 10} r={11} /> :
    <polygon points={`${x + 13},${y - 1} ${x + 26},${y + 10} ${x + 13},${y + 21} ${x},${y + 10}`} />;
  const rows = [10, 42, 74];
  return (
    <>
      <g className="text-text-tertiary" stroke="currentColor" strokeWidth={2} fill="none" strokeLinejoin="round">
        {rows.map((y, i) => (
          <g key={`l${y}`}>{shape(8, y, i)}</g>
        ))}
        {rows.map((y, i) => (
          <g key={`r${y}`}>{shape(286, y, i)}</g>
        ))}
      </g>
      {/* In and out through the same opening — a source here is a target there. */}
      {rows.map((y) => (
        <Arrow key={`ai${y}`} from={38} to={126} y={y + 10} />
      ))}
      {rows.map((y) => (
        <Arrow key={`ao${y}`} from={194} to={282} y={y + 10} />
      ))}
      {/* Tall enough for all three arrows to land on it — a row pointing past the box would say
          the outer system connects to nothing. */}
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none">
        <rect x={128} y={12} width={64} height={80} rx={12} />
        <circle cx={160} cy={52} r={4} fill="currentColor" stroke="none" />
      </g>
    </>
  );
}

function Swap() {
  return (
    <>
      {/* Lifted out while the line keeps moving. */}
      <Box x={20} y={10} w={48} h={26} ai />
      <g className="text-accent" stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
        <path d="M44 62 L44 42" strokeDasharray="4 4" />
        <polyline points="39,47 44,40 49,47" strokeLinejoin="round" />
      </g>
      <g className="text-text-tertiary" stroke="currentColor" strokeWidth={2} fill="none" strokeLinecap="round">
        <polyline points="20,64 20,88 68,88 68,64" strokeLinejoin="round" />
        <line x1={8} y1={98} x2={300} y2={98} opacity={0.5} />
      </g>
      <Box x={150} y={52} w={48} h={26} />
      <Box x={214} y={52} w={48} h={26} />
    </>
  );
}

function Blocks() {
  return (
    <>
      <Box x={16} y={40} w={40} h={24} />
      <Arrow from={58} to={108} y={52} />
      {/* A whole flow is one step inside another. */}
      <g className="text-text-tertiary" stroke="currentColor" strokeWidth={2} fill="none">
        <rect x={112} y={16} width={168} height={74} rx={12} />
      </g>
      {[128, 176, 224].map((x) => (
        <Box key={x} x={x} y={28} w={40} h={22} ai={x === 176} />
      ))}
      <Box x={152} y={58} w={40} h={22} />
      <Box x={200} y={58} w={40} h={22} />
    </>
  );
}

function Closing() {
  const xs = [8, 84, 160, 236];
  return (
    <>
      {xs.map((x) => (
        <Box key={`a${x}`} x={x} y={10} w={62} h={34} />
      ))}
      {xs.map((x) => (
        <Box key={`b${x}`} x={x} y={56} w={62} h={34} ai={x === 236} />
      ))}
    </>
  );
}

const DIAGRAMS: Record<PanelDiagramId, () => React.JSX.Element> = {
  intro: Intro,
  mix: Mix,
  trace: Trace,
  notice: Notice,
  unattended: Unattended,
  wait: Wait,
  repair: Repair,
  connect: Connect,
  verdict: Verdict,
  refill: Refill,
  swap: Swap,
  blocks: Blocks,
  closing: Closing,
};

export function PanelDiagram({ id }: { id: PanelDiagramId }) {
  const Shape = DIAGRAMS[id];
  return (
    <svg viewBox={VIEW} className="w-full h-full" role="presentation" aria-hidden="true">
      <Shape />
    </svg>
  );
}

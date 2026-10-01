import { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, Info, AlertTriangle, Play, Pause } from 'lucide-react';
import { Card, Toggle } from '../ui';
import { useT } from '../../i18n';
import { usePersistedState } from '../../hooks/usePersistedState';
import { useTimelineClock } from '../../hooks/useTimelineClock';
import { activeParts, buildTimeline, timelineDuration, frameAt, type FactoryMode, type FlowNodeId, type FlowEdgeId } from './factoryFlow';
import { FactoryFlowSvg } from './FactoryFlowSvg';

const OPEN_KEY = 'mysuricates-factory-flow-open';

interface FactoryFlowDiagramProps {
  autoFix: boolean;
  autoPromote: boolean;
  /** Same state as the form's switches, so the variants can be tried right at the diagram. */
  onAutoFixChange: (enabled: boolean) => void;
  onAutoPromoteChange: (enabled: boolean) => void;
  maxRetries: number;
  testHealthy: boolean;
}

/**
 * Explains what the factory does — and in which mode. Only "Create & test" runs the
 * DEV → TEST → repair loop; plain "Create workflow" stops after generating on DEV.
 * An explanation, not live progress: /factory/run is one blocking call without events.
 * The token pauses at every station; that node and its step in the list are highlighted.
 * Under prefers-reduced-motion it waits for "Play" and runs a single pass.
 */
export function FactoryFlowDiagram({
  autoFix, autoPromote, onAutoFixChange, onAutoPromoteChange, maxRetries, testHealthy,
}: FactoryFlowDiagramProps) {
  const t = useT();
  const f = t.factory;
  // Per-viewer convenience: the card remembers whether it was collapsed.
  const [open, setOpen] = usePersistedState(OPEN_KEY, true);
  const [mode, setMode] = useState<FactoryMode>('createAndTest');
  const toggleOpen = () => setOpen(!open);

  const active = activeParts({ mode, autoFix, autoPromote });
  const legs = useMemo(() => buildTimeline({ mode, autoFix, autoPromote }), [mode, autoFix, autoPromote]);
  // Clock of the animation; a changed switch restarts the story from the beginning.
  const { elapsed, playing, togglePlaying } = useTimelineClock({
    key: `${mode}|${autoFix}|${autoPromote}`,
    total: timelineDuration(legs),
    running: open && legs.length > 0,
  });

  const frame = frameAt(legs, elapsed);
  const currentPart: FlowNodeId | FlowEdgeId = frame?.part ?? 'generate';
  const currentStep = frame?.step ?? 0;

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
          : 'text-text-secondary hover:text-text hover:bg-surface-hover'
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
        <span className="font-semibold text-text">{f.flowTitle}</span>
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
            <button
              type="button"
              onClick={togglePlaying}
              className="ml-auto inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md border border-border-strong text-text hover:bg-surface-hover transition-colors"
            >
              {playing ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4" />}
              {playing ? f.flowPause : f.flowPlay}
            </button>
          </div>

          {mode === 'createAndTest' && (
            <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
              <Toggle label={f.flowSwitchAutoFix} enabled={autoFix} onChange={onAutoFixChange} />
              <Toggle label={f.flowSwitchAutoPromote} enabled={autoPromote} onChange={onAutoPromoteChange} />
              <span className="text-xs text-text-secondary">{f.flowSwitchHint}</span>
            </div>
          )}

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
            <FactoryFlowSvg
              mode={mode} autoFix={autoFix} maxRetries={maxRetries} active={active}
              frame={frame} currentPart={currentPart} currentStep={currentStep}
            />
          </div>

          <ol className="space-y-1 text-sm">
            {steps.map((step, i) => {
              const current = i === currentStep;
              return (
                <li
                  key={i}
                  aria-current={current ? 'step' : undefined}
                  className={`flex gap-2.5 rounded-md px-2 py-1 transition-colors ${
                    current ? 'bg-accent-subtle text-accent' : 'text-text-secondary'
                  }`}
                >
                  <span className={`shrink-0 w-5 h-5 mt-px rounded-full text-xs font-semibold flex items-center justify-center ${
                    current ? 'bg-accent text-text-inverse' : 'bg-surface-secondary text-text-secondary'
                  }`}>{i + 1}</span>
                  <span>{step}</span>
                </li>
              );
            })}
          </ol>
        </div>
      )}
    </Card>
  );
}

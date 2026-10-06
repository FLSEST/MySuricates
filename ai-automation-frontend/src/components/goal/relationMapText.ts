import { useT } from '../../i18n';
import type { MapNode } from './goalRelationLayout';

/** Shortens a text for a map node; the full text is in the tooltip. */
export function truncate(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max - 1).trimEnd()}…` : text;
}

/** Label of a node; without text, derived from the data by kind. */
export function useNodeText() {
  const t = useT();
  const m = t.goals.map;
  return (node: MapNode): { label: string; title: string } => {
    switch (node.kind) {
      case 'origin': {
        const kind = m.origin[node.originKind ?? 'USER'];
        const label = node.label ?? kind;
        const count = node.todoIds.length > 1 ? ` · ${node.todoIds.length}` : '';
        const failed = node.runStatus === 'FAILED' ? ` · ${m.runFailed}` : '';
        return { label: `${label}${count}`, title: `${kind}: ${label}${failed}` };
      }
      case 'worker': {
        const kinds = (node.workerKinds ?? []).map((k) => (k === 'LINKED' ? m.linked : m.trigger)).join(', ');
        const triggers = node.triggerLabels?.length ? ` (${node.triggerLabels.join(', ')})` : '';
        return { label: node.label ?? '', title: `${node.label} – ${kinds}${triggers}` };
      }
      case 'result': {
        const tone = (node.tone in m.result ? node.tone : 'neutral') as keyof typeof m.result;
        const attempts = node.fixAttempts ? ` · ${m.attempts.replace('{n}', String(node.fixAttempts))}` : '';
        return { label: `${m.result[tone]}${attempts}`, title: `${m.result[tone]}${attempts}` };
      }
      case 'gap':
        return { label: m.noWorkflow, title: m.noWorkflow };
      default:
        return { label: node.label ?? '', title: node.label ?? '' };
    }
  };
}


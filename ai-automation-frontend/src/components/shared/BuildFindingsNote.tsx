import { AlertTriangle } from 'lucide-react';
import { useT } from '../../i18n';

/**
 * Findings of the build check (analyzer, from MEDIUM up, HIGH first) that an AI-generated definition still
 * has — one line each, as the backend formats them (TODO 326d5987). Renders nothing without findings, also
 * for answers of an older backend that has no `buildFindings` field.
 */
export function BuildFindingsNote({ findings }: { findings?: string[] | null }) {
  const t = useT();
  if (!findings || findings.length === 0) return null;
  return (
    <div className="p-2 rounded-lg text-xs border bg-warning/10 border-warning/30 text-warning" role="note">
      <p className="flex items-center gap-1.5 font-medium">
        <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
        {t.buildCheck.title}
      </p>
      <ul className="mt-1 space-y-0.5 font-mono break-words">
        {findings.map((line, i) => <li key={i}>{line}</li>)}
      </ul>
      <p className="mt-1 text-text-secondary">{t.buildCheck.hint}</p>
    </div>
  );
}

import { Shrink } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import type { StepResult } from '../../types';
import { compressionFacts } from './stepMetadata';
import { useStepAnchor } from './stepAnchors';

/** Makes a compression unmistakable in the run view: size change, source link, capped flag, stage table. */
export function CompressionPanel({ step }: { step: StepResult }) {
  const t = useT().workflows.compression;
  const locale = useI18nStore((s) => s.locale) === 'de' ? 'de-DE' : 'en-US';
  const facts = compressionFacts(step);
  const anchor = useStepAnchor();
  if (!facts) return null;
  const fmt = (n: number) => n.toLocaleString(locale);
  const change = facts.inChars > 0 ? Math.round(Math.abs(1 - facts.outChars / facts.inChars) * 1000) / 10 : 0;
  const percent = `${facts.outChars > facts.inChars ? '+' : '−'}${change.toLocaleString(locale)} %`;
  const src = facts.source;

  return (
    <div className="mb-2 rounded-lg border border-card-border bg-surface p-2 text-xs" data-testid="compression-panel">
      <div className="flex flex-wrap items-center gap-2">
        <span className="inline-flex items-center gap-1 rounded-full bg-info-subtle px-2 py-0.5 font-medium text-info">
          <Shrink className="h-3 w-3" />
          {t.badge.replace('{in}', fmt(facts.inChars)).replace('{out}', fmt(facts.outChars)).replace('{percent}', percent)}
        </span>
        {src && src.stepIndex !== null && (
          <a href={`#${anchor(src.stepIndex)}`} className="text-accent hover:underline">
            {t.source}: {src.block ?? `#${src.stepIndex + 1}`} ({src.status ?? '?'}{src.exitCode !== null ? ` · exit ${src.exitCode}` : ''})
          </a>
        )}
        {facts.capped && <span className="text-warning">{t.capped}</span>}
      </div>
      {facts.stages.length > 0 && (
        <div className="mt-2 overflow-x-auto">
          <table className="w-full font-mono">
            <thead className="text-text-tertiary">
              <tr>
                <th className="pr-3 text-left font-normal">{t.stage}</th>
                <th className="pr-3 text-right font-normal">{t.before}</th>
                <th className="pr-3 text-right font-normal">{t.after}</th>
                <th className="text-left font-normal">{t.note}</th>
              </tr>
            </thead>
            <tbody className="text-text-secondary">
              {facts.stages.map((s, i) => (
                <tr key={`${s.op}-${i}`}>
                  <td className="pr-3">{s.op}</td>
                  <td className="pr-3 text-right">{fmt(s.inChars)}</td>
                  <td className="pr-3 text-right">{fmt(s.outChars)}</td>
                  <td>{s.note ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

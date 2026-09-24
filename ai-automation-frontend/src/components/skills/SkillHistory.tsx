import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { Spinner } from '../ui';
import { useSkillVersions } from '../../hooks/useSkills';
import { useT, useI18nStore } from '../../i18n';
import { formatRelativeTime } from '../../utils/formatTime';

/** Who wrote a version row — a person, an import (a SECURITY event) or the bundled release. */
const SOURCE_STYLES: Record<string, string> = {
  user: 'bg-human-subtle text-human',
  import: 'bg-warning-subtle text-warning',
  'builtin-seed': 'bg-surface-raised text-text-secondary',
};

/** Collapsible change history of a skill (`skill_versions`), loaded once it is opened. */
export function SkillHistory({ skillId }: { skillId: string }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const [open, setOpen] = useState(false);
  const { data: versions, isLoading } = useSkillVersions(skillId, open);
  const sources = t.skills.sources as Record<string, string>;

  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1.5 text-sm font-semibold text-text hover:text-accent transition-colors"
        aria-expanded={open}
      >
        {open ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
        {t.skills.history}
      </button>
      {open && (
        <div className="mt-3 space-y-2" data-testid="skill-history">
          {isLoading ? (
            <Spinner />
          ) : !versions?.length ? (
            <p className="text-xs text-text-tertiary">{t.skills.historyEmpty}</p>
          ) : (
            versions.map((v) => (
              <div key={v.id} className="flex items-center gap-2 px-3 py-2 rounded-lg border border-border text-xs">
                <span className="font-bold text-accent w-6 shrink-0">#{v.versionNumber}</span>
                <span className="text-text">
                  {t.skills.operations[v.operation as keyof typeof t.skills.operations] ?? v.operation}
                </span>
                <span className={`px-2 py-0.5 rounded-full font-medium ${SOURCE_STYLES[v.changeSource] ?? SOURCE_STYLES['builtin-seed']}`}>
                  {sources[v.changeSource] ?? v.changeSource}
                </span>
                <span className="font-mono text-[11px] text-text-tertiary" title={v.contentHash}>
                  {v.contentHash.slice(0, 8)}
                </span>
                <span className="ml-auto text-text-tertiary shrink-0">{formatRelativeTime(v.createdAt, locale)}</span>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
}

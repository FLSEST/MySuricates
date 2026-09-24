import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Search } from 'lucide-react';
import { useT } from '../../i18n';
import { Button } from '../ui';
import { matchWorkflows } from '../../utils/matchWorkflows';
import { RunChip, SectionLabel } from './kit';
import type { Workflow } from '../../types';

const MAX_MATCHES = 6;

/**
 * The Simple home's hero field without the assistant module: a search over one's own workflows.
 * Matches start right away (via `onRun`); without a match it offers the full list and a template,
 * so a search never ends in a dead end.
 */
export function WorkflowSearch({
  workflows, latestStatus, onRun,
}: {
  workflows: Workflow[];
  latestStatus: ReadonlyMap<string, string>;
  onRun: (workflow: Workflow) => void;
}) {
  const t = useT();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const matches = useMemo(
    () => matchWorkflows(query, workflows).slice(0, MAX_MATCHES).map((m) => m.workflow),
    [query, workflows],
  );
  const searching = query.trim().length > 0;

  return (
    <div className="space-y-3">
      <form
        role="search"
        onSubmit={(e) => e.preventDefault()}
        className="flex items-center gap-3 rounded-2xl border border-border bg-surface-raised px-4 py-3 focus-within:border-accent/50 transition-colors"
      >
        <Search className="w-[18px] h-[18px] text-text-tertiary shrink-0" aria-hidden />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label={t.simple.home.searchButton}
          placeholder={t.simple.home.inputPlaceholder}
          className="flex-1 min-w-0 bg-transparent text-sm text-text placeholder:text-text-tertiary focus:outline-none"
        />
      </form>

      {searching && matches.length > 0 && (
        <div>
          <SectionLabel>{t.simple.home.matchesTitle}</SectionLabel>
          <div className="flex flex-wrap gap-2">
            {matches.map((w) => (
              <RunChip key={w.id} workflow={w} status={latestStatus.get(w.id)} onRun={() => onRun(w)} />
            ))}
          </div>
        </div>
      )}

      {searching && matches.length === 0 && (
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-dashed border-border px-4 py-3">
          <p className="text-sm text-text-secondary mr-auto">{t.simple.home.noMatch}</p>
          <Button size="sm" variant="secondary" onClick={() => navigate('/workflows')}>
            {t.simple.home.allWorkflows}
          </Button>
          <Button size="sm" onClick={() => navigate('/workflows?new=1')}>
            {t.simple.home.startFromTemplate}
          </Button>
        </div>
      )}
    </div>
  );
}

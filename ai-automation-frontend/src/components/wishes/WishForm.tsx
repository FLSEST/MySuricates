import { useState, useMemo } from 'react';
import { Send, ChevronRight, ChevronDown, X } from 'lucide-react';
import { Card, Button } from '../ui';
import { useT } from '../../i18n';
import type { Project, Workflow } from '../../types';

interface WishFormProps {
  onSubmit: (data: { text: string; projectId?: string; referenceWorkflowIds?: string[] }) => void;
  isPending: boolean;
  projects: Project[] | undefined;
  allWorkflows: Workflow[] | undefined;
}

export function WishForm({ onSubmit, isPending, projects, allWorkflows }: WishFormProps) {
  const t = useT();
  const [wishText, setWishText] = useState('');
  const [projectId, setProjectId] = useState<string>('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [selectedWorkflowIds, setSelectedWorkflowIds] = useState<string[]>([]);
  const [workflowSearch, setWorkflowSearch] = useState('');

  const workflowsByProject = useMemo(() => {
    if (!allWorkflows) return new Map<string, typeof allWorkflows>();
    const filtered = workflowSearch
      ? allWorkflows.filter(wf => wf.name.toLowerCase().includes(workflowSearch.toLowerCase()) || wf.projectName?.toLowerCase().includes(workflowSearch.toLowerCase()))
      : allWorkflows;
    const map = new Map<string, typeof allWorkflows>();
    for (const wf of filtered) {
      const key = wf.projectName || 'No Project';
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(wf);
    }
    return map;
  }, [allWorkflows, workflowSearch]);

  const toggleWorkflow = (id: string) => {
    setSelectedWorkflowIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
  };

  const handleSubmit = () => {
    if (!wishText.trim()) return;
    onSubmit({
      text: wishText,
      projectId: projectId || undefined,
      referenceWorkflowIds: selectedWorkflowIds.length > 0 ? selectedWorkflowIds : undefined,
    });
    setWishText('');
    setSelectedWorkflowIds([]);
    setShowAdvanced(false);
  };

  return (
    <Card>
      <div className="p-4 space-y-3">
        <textarea
          value={wishText}
          onChange={(e) => setWishText(e.target.value)}
          placeholder={t.wishes.placeholder}
          className="w-full h-24 px-3 py-2 rounded-lg bg-surface-raised border border-border text-text-primary placeholder-text-tertiary resize-none focus:outline-none focus:ring-2 focus:ring-accent"
        />
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <select
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              className="px-3 py-2 rounded-lg bg-surface-raised border border-border text-text-primary text-sm focus:outline-none focus:ring-2 focus:ring-accent"
            >
              <option value="">{t.wishes.projectHint}</option>
              {projects?.map(p => (
                <option key={p.id} value={p.id}>{p.name}</option>
              ))}
            </select>
            <button
              type="button"
              onClick={() => setShowAdvanced(v => !v)}
              className="flex items-center gap-1 px-2 py-1.5 text-xs text-text-secondary hover:text-text-primary transition-colors"
            >
              {showAdvanced ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
              {showAdvanced ? t.wishes.hideAdvanced : t.wishes.advanced}
            </button>
          </div>
          <Button
            onClick={handleSubmit}
            disabled={!wishText.trim() || !projectId || isPending}
            icon={<Send className="w-4 h-4" />}
          >
            {t.wishes.create}
          </Button>
        </div>

        {/* Advanced: Reference Workflow Selector */}
        {showAdvanced && (
          <div className="space-y-2 pt-1">
            <div>
              <label className="text-xs font-medium text-text-secondary">{t.wishes.referenceWorkflows}</label>
              <p className="text-xs text-text-tertiary">{t.wishes.referenceWorkflowsHint}</p>
              <ul className="mt-1.5 space-y-0.5">
                {t.wishes.referenceWorkflowsTips.map((tip, i) => (
                  <li key={i} className="text-[11px] text-text-tertiary flex items-start gap-1.5">
                    <span className="mt-0.5 shrink-0 w-1 h-1 rounded-full bg-text-tertiary/50" />
                    {tip}
                  </li>
                ))}
              </ul>
            </div>

            {/* Selected chips */}
            {selectedWorkflowIds.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {selectedWorkflowIds.map(id => {
                  const wf = allWorkflows?.find(w => w.id === id);
                  return (
                    <span key={id} className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-accent/15 text-accent text-xs">
                      {wf?.name ?? id}
                      <button type="button" onClick={() => toggleWorkflow(id)} className="hover:text-accent/70">
                        <X className="w-3 h-3" />
                      </button>
                    </span>
                  );
                })}
                <span className="text-xs text-text-tertiary self-center">{selectedWorkflowIds.length} {t.wishes.selectedCount}</span>
              </div>
            )}

            {/* Search + grouped list */}
            <input
              value={workflowSearch}
              onChange={(e) => setWorkflowSearch(e.target.value)}
              placeholder={t.wishes.searchWorkflows}
              className="w-full px-3 py-1.5 rounded-lg bg-surface-raised border border-border text-text-primary placeholder-text-tertiary text-sm focus:outline-none focus:ring-2 focus:ring-accent"
            />
            <div className="max-h-48 overflow-y-auto rounded-lg border border-border bg-surface-raised">
              {[...workflowsByProject.entries()].map(([projName, wfs]) => (
                <div key={projName}>
                  <div className="px-3 py-1 text-[10px] font-semibold text-text-tertiary uppercase tracking-wide bg-surface-base sticky top-0">
                    {projName}
                  </div>
                  {wfs?.map(wf => (
                    <label
                      key={wf.id}
                      className="flex items-center gap-2 px-3 py-1.5 hover:bg-surface-base cursor-pointer text-sm"
                    >
                      <input
                        type="checkbox"
                        checked={selectedWorkflowIds.includes(wf.id)}
                        onChange={() => toggleWorkflow(wf.id)}
                        className="rounded border-border text-accent focus:ring-accent"
                      />
                      <span className="text-text-primary truncate">{wf.name}</span>
                    </label>
                  ))}
                </div>
              ))}
              {workflowsByProject.size === 0 && (
                <p className="px-3 py-2 text-xs text-text-tertiary">{t.common.noResults}</p>
              )}
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}

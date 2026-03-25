import { useState, useMemo, useCallback } from 'react';
import { Modal, Button } from '../ui';
import { ChevronDown, ChevronRight, Loader2 } from 'lucide-react';
import type { SyncPreview, SyncPreviewItem, SyncDefinitionsRequest, SyncResult, WorkflowDiff } from '../../types';
import type { useT } from '../../i18n';

interface SyncSelectModalProps {
  open: boolean;
  onClose: () => void;
  preview: SyncPreview | undefined;
  previewLoading: boolean;
  onSync: (request: SyncDefinitionsRequest) => Promise<SyncResult>;
  syncLoading: boolean;
  title: string;
  t: ReturnType<typeof useT>;
}

const ACTION_STYLES: Record<string, string> = {
  NEW: 'bg-success/15 text-success',
  UPDATE: 'bg-warning/15 text-warning',
  SKIP: 'bg-surface-raised text-text-tertiary',
};

export function SyncSelectModal({ open, onClose, preview, previewLoading, onSync, syncLoading, title, t }: SyncSelectModalProps) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [initialized, setInitialized] = useState(false);

  // Initialize selection when preview loads
  if (preview && !initialized) {
    const initial = new Set<string>();
    for (const p of preview.projects) {
      if (p.action !== 'SKIP') initial.add(`project:${p.id}`);
    }
    for (const w of preview.workflows) {
      if (w.action !== 'SKIP') initial.add(`workflow:${w.id}`);
    }
    for (const s of preview.skills) {
      if (s.action !== 'SKIP') initial.add(`skill:${s.id}`);
    }
    setSelected(initial);
    setInitialized(true);
  }

  // Reset when modal closes
  const handleClose = useCallback(() => {
    setInitialized(false);
    setSelected(new Set());
    setCollapsed({});
    onClose();
  }, [onClose]);

  const allItems = useMemo(() => {
    if (!preview) return [];
    return [
      ...preview.projects.map(p => `project:${p.id}`),
      ...preview.workflows.map(w => `workflow:${w.id}`),
      ...preview.skills.map(s => `skill:${s.id}`),
    ];
  }, [preview]);

  const selectedCount = selected.size;
  const allSelected = allItems.length > 0 && allItems.every(k => selected.has(k));

  const toggle = (key: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const toggleAll = () => {
    if (allSelected) {
      setSelected(new Set());
    } else {
      setSelected(new Set(allItems));
    }
  };

  const toggleSection = (section: string) => {
    setCollapsed(prev => ({ ...prev, [section]: !prev[section] }));
  };

  const sectionCount = (items: SyncPreviewItem[], prefix: string) => {
    const total = items.length;
    const sel = items.filter(i => selected.has(`${prefix}:${i.id}`)).length;
    return `${sel}/${total}`;
  };

  const actionLabel = (action: string) => {
    if (action === 'NEW') return t.settings.syncActionNew;
    if (action === 'UPDATE') return t.settings.syncActionUpdate;
    return t.settings.syncActionSkip;
  };

  // Group workflows by project
  const workflowsByProject = useMemo(() => {
    if (!preview) return new Map<string, SyncPreviewItem[]>();
    const groups = new Map<string, SyncPreviewItem[]>();
    for (const w of preview.workflows) {
      const key = w.projectName ?? '';
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(w);
    }
    return groups;
  }, [preview]);

  const hasChanges = useMemo(() => {
    if (!preview) return false;
    return [...preview.projects, ...preview.workflows, ...preview.skills].some(i => i.action !== 'SKIP');
  }, [preview]);

  const handleSync = async () => {
    const projectIds = preview?.projects
      .filter(p => selected.has(`project:${p.id}`))
      .map(p => p.id);
    const workflowIds = preview?.workflows
      .filter(w => selected.has(`workflow:${w.id}`))
      .map(w => w.id);
    const skillIds = preview?.skills
      .filter(s => selected.has(`skill:${s.id}`))
      .map(s => s.id);

    await onSync({
      projectIds: projectIds?.length ? projectIds : undefined,
      workflowIds: workflowIds?.length ? workflowIds : undefined,
      skillIds: skillIds?.length ? skillIds : undefined,
    });
    handleClose();
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title={title}
      size="lg"
      footer={
        <>
          <Button variant="ghost" onClick={handleClose}>
            {t.common.cancel}
          </Button>
          <Button
            onClick={handleSync}
            loading={syncLoading}
            disabled={selectedCount === 0 || previewLoading}
          >
            {t.settings.syncItemCount.replace('{count}', String(selectedCount))}
          </Button>
        </>
      }
    >
      {previewLoading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="w-5 h-5 animate-spin text-text-tertiary" />
        </div>
      ) : !preview ? (
        <p className="text-sm text-text-tertiary py-8 text-center">{t.common.error}</p>
      ) : !hasChanges ? (
        <p className="text-sm text-text-secondary py-8 text-center">{t.settings.syncNoChanges}</p>
      ) : (
        <div className="space-y-4">
          {/* Select all */}
          <label className="flex items-center gap-2 cursor-pointer px-1">
            <input
              type="checkbox"
              checked={allSelected}
              onChange={toggleAll}
              className="rounded border-border text-accent focus:ring-accent"
            />
            <span className="text-sm font-medium text-text">{t.settings.syncSelectAll}</span>
          </label>

          {/* Projects */}
          {preview.projects.length > 0 && (
            <Section
              label={t.settings.syncProjects}
              count={sectionCount(preview.projects, 'project')}
              total={preview.projects.length}
              collapsed={!!collapsed['projects']}
              onToggle={() => toggleSection('projects')}
            >
              {preview.projects.map(p => (
                <ItemRow
                  key={p.id}
                  item={p}
                  checked={selected.has(`project:${p.id}`)}
                  onToggle={() => toggle(`project:${p.id}`)}
                  actionLabel={actionLabel(p.action)}
                  actionStyle={ACTION_STYLES[p.action] ?? ''}
                  t={t}
                />
              ))}
            </Section>
          )}

          {/* Workflows */}
          {preview.workflows.length > 0 && (
            <Section
              label={t.settings.syncWorkflows}
              count={sectionCount(preview.workflows, 'workflow')}
              total={preview.workflows.length}
              collapsed={!!collapsed['workflows']}
              onToggle={() => toggleSection('workflows')}
            >
              {[...workflowsByProject.entries()].map(([projectName, workflows]) => (
                <div key={projectName || '_none'}>
                  <p className="text-xs font-medium text-text-tertiary px-1 pt-1.5 pb-0.5">
                    {projectName || t.settings.syncNoProject}
                  </p>
                  {workflows.map(w => (
                    <ItemRow
                      key={w.id}
                      item={w}
                      checked={selected.has(`workflow:${w.id}`)}
                      onToggle={() => toggle(`workflow:${w.id}`)}
                      actionLabel={actionLabel(w.action)}
                      actionStyle={ACTION_STYLES[w.action] ?? ''}
                      indent
                      t={t}
                    />
                  ))}
                </div>
              ))}
            </Section>
          )}

          {/* Skills */}
          {preview.skills.length > 0 && (
            <Section
              label={t.settings.syncSkills}
              count={sectionCount(preview.skills, 'skill')}
              total={preview.skills.length}
              collapsed={!!collapsed['skills']}
              onToggle={() => toggleSection('skills')}
            >
              {preview.skills.map(s => (
                <ItemRow
                  key={s.id}
                  item={s}
                  checked={selected.has(`skill:${s.id}`)}
                  onToggle={() => toggle(`skill:${s.id}`)}
                  actionLabel={actionLabel(s.action)}
                  actionStyle={ACTION_STYLES[s.action] ?? ''}
                  t={t}
                />
              ))}
            </Section>
          )}
        </div>
      )}
    </Modal>
  );
}

function Section({ label, count, total, collapsed, onToggle, children }: {
  label: string;
  count: string;
  total: number;
  collapsed: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  return (
    <div>
      <button
        onClick={onToggle}
        className="flex items-center justify-between w-full px-1 py-1.5 text-sm font-medium text-text hover:bg-surface-raised rounded transition-colors"
      >
        <div className="flex items-center gap-1.5">
          {collapsed
            ? <ChevronRight className="w-4 h-4 text-text-tertiary" />
            : <ChevronDown className="w-4 h-4 text-text-tertiary" />}
          <span>{label} ({total})</span>
        </div>
        <span className="text-xs text-text-tertiary font-normal">{count}</span>
      </button>
      {!collapsed && <div className="ml-2">{children}</div>}
    </div>
  );
}

function blocksDiffLabel(diff: WorkflowDiff, t: ReturnType<typeof useT>): string {
  if (diff.blocksAdded > 0) return t.settings.syncDiffBlocksAdded.replace('{n}', String(diff.blocksAdded));
  if (diff.blocksRemoved > 0) return t.settings.syncDiffBlocksRemoved.replace('{n}', String(diff.blocksRemoved));
  return t.settings.syncDiffBlocksChanged;
}

function DiffDetails({ diff, t }: { diff: WorkflowDiff; t: ReturnType<typeof useT> }) {
  const parts: string[] = [];
  if (diff.changedFields.includes('blocks')) parts.push(blocksDiffLabel(diff, t));
  if (diff.changedFields.includes('model') && diff.modelBefore != null)
    parts.push(`model: ${diff.modelBefore} → ${diff.modelAfter}`);
  if (diff.changedFields.includes('instructions')) parts.push(t.settings.syncDiffInstructions);
  if (diff.changedFields.includes('description')) parts.push(t.settings.syncDiffDescription);
  if (parts.length === 0) return null;
  return (
    <div className="ml-6 mt-0.5 text-[10px] text-text-tertiary leading-tight">
      {parts.join(' · ')}
    </div>
  );
}

function ItemRow({ item, checked, onToggle, actionLabel, actionStyle, indent, t }: {
  item: SyncPreviewItem;
  checked: boolean;
  onToggle: () => void;
  actionLabel: string;
  actionStyle: string;
  indent?: boolean;
  t: ReturnType<typeof useT>;
}) {
  return (
    <label
      className={`block py-1 px-1 rounded hover:bg-surface-raised cursor-pointer transition-colors ${indent ? 'ml-4' : ''}`}
    >
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <input
            type="checkbox"
            checked={checked}
            onChange={onToggle}
            className="rounded border-border text-accent focus:ring-accent shrink-0"
          />
          <span className="text-sm text-text truncate">{item.name}</span>
        </div>
        <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded shrink-0 ${actionStyle}`}>
          {actionLabel}
        </span>
      </div>
      {item.action === 'UPDATE' && item.diff && item.diff.changedFields.length > 0 && (
        <DiffDetails diff={item.diff} t={t} />
      )}
    </label>
  );
}

import { useMemo, useCallback } from 'react';
import { Modal, Button } from '../ui';
import { Loader2 } from 'lucide-react';
import type { SyncPreview, SyncPreviewItem, SyncDefinitionsRequest, SyncResult } from '../../types';
import type { useT } from '../../i18n';
import { Section, ItemRow } from './SyncSelectRows';
import { SyncNotes } from './SyncNotes';
import { syncItems, useSyncSelection, type SyncKind } from './useSyncSelection';

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

export function SyncSelectModal({ open, onClose, preview, previewLoading, onSync, syncLoading, title, t }: SyncSelectModalProps) {
  const selection = useSyncSelection(preview);
  const { reset } = selection;

  // Reset when modal closes
  const handleClose = useCallback(() => {
    reset();
    onClose();
  }, [reset, onClose]);

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
    return [
      ...preview.projects,
      ...preview.workflows,
      ...preview.skills,
      ...(preview.chains ?? []),
    ].some(i => i.action !== 'SKIP');
  }, [preview]);

  const handleSync = async () => {
    await onSync(selection.buildRequest());
    handleClose();
  };

  const sectionProps = (kind: SyncKind, label: string, items: SyncPreviewItem[]) => ({
    label,
    count: selection.sectionCount(kind),
    total: items.length,
    collapsed: selection.isCollapsed(kind),
    onToggle: () => selection.toggleSection(kind),
  });

  // Projects, skills and chains: one row per definition, without grouping
  const flatSection = (kind: SyncKind, label: string) => {
    const items = preview ? syncItems(preview, kind) : [];
    if (items.length === 0) return null;
    return (
      <Section {...sectionProps(kind, label, items)}>
        {items.map(item => (
          <ItemRow
            key={item.id}
            item={item}
            checked={selection.isSelected(kind, item.id)}
            onToggle={() => selection.toggle(kind, item.id)}
            t={t}
          />
        ))}
      </Section>
    );
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
            disabled={selection.selectedCount === 0 || previewLoading}
          >
            {t.settings.syncItemCount.replace('{count}', String(selection.selectedCount))}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {!previewLoading && <SyncNotes notes={preview?.notes} title={t.settings.syncNotesTitle} />}
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
                checked={selection.allSelected}
                onChange={selection.toggleAll}
                className="rounded border-border text-accent focus:ring-accent"
              />
              <span className="text-sm font-medium text-text">{t.settings.syncSelectAll}</span>
            </label>

            {flatSection('project', t.settings.syncProjects)}

            {/* Workflows, grouped by project */}
            {preview.workflows.length > 0 && (
              <Section {...sectionProps('workflow', t.settings.syncWorkflows, preview.workflows)}>
                {[...workflowsByProject.entries()].map(([projectName, workflows]) => (
                  <div key={projectName || '_none'}>
                    <p className="text-xs font-medium text-text-tertiary px-1 pt-1.5 pb-0.5">
                      {projectName || t.settings.syncNoProject}
                    </p>
                    {workflows.map(w => (
                      <ItemRow
                        key={w.id}
                        item={w}
                        checked={selection.isSelected('workflow', w.id)}
                        onToggle={() => selection.toggle('workflow', w.id)}
                        indent
                        t={t}
                      />
                    ))}
                  </div>
                ))}
              </Section>
            )}

            {flatSection('skill', t.settings.syncSkills)}
            {flatSection('chain', t.settings.syncChains)}
          </div>
        )}
      </div>
    </Modal>
  );
}

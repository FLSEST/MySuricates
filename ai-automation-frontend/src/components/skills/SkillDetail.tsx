import { useState } from 'react';
import { Zap, Lock, Download, Pencil, Copy, Trash2 } from 'lucide-react';
import { Button, Badge, ConfirmModal, Spinner } from '../ui';
import { useSkillCapabilities, useDeleteSkill, exportSkill } from '../../hooks/useSkills';
import { useSkillSealPins } from '../../hooks/useSealStatus';
import { useModuleEnabled, useModuleInfo } from '../../hooks/useModules';
import { useToast } from '../../hooks/useToast';
import { apiErrorMessage } from '../../api/client';
import { useT } from '../../i18n';
import { CATEGORY_COLORS } from '../../data/skillConstants';
import type { Skill } from '../../types';
import { SkillIcon } from './SkillIcon';
import { SkillHistory } from './SkillHistory';

/**
 * Slide-over of one skill: actions (edit, copy, export, delete — locked while a sealed workflow
 * uses the skill), capabilities, AI context and history.
 */
export function SkillDetail({ skill, onClose, onEdit, onCopy }: {
  skill: Skill;
  onClose: () => void;
  onEdit: (s: Skill) => void;
  onCopy: (s: Skill) => void;
}) {
  const t = useT();
  const toast = useToast();
  const { data: capabilities, isLoading } = useSkillCapabilities(skill.id);
  const { data: sealPins } = useSkillSealPins();
  const deleteSkill = useDeleteSkill();
  // Deleting asks first, in a dialog above the slide-over (23635ef6: was window.confirm)
  const [confirmDelete, setConfirmDelete] = useState(false);
  const sealedBy = sealPins?.[skill.id] ?? [];
  const locked = sealedBy.length > 0;
  // The lock holds regardless of any switch; the switches only decide whether it can be lifted here
  const sealingModule = useModuleEnabled('sealing');
  const sealImmutable = useModuleInfo('seal-immutable')?.enabled === true;
  const wayOut = !sealingModule
    ? t.skills.sealedSealingOffHint
    : sealImmutable ? t.skills.sealedImmutableHint : t.skills.sealedUnsealHint;

  const handleExport = async () => {
    try {
      await exportSkill(skill.id, skill.name);
    } catch (err) {
      toast.error(apiErrorMessage(err, t.toast.skillExportFailed));
    }
  };

  const handleDelete = () => deleteSkill.mutate(skill.id, {
    onSuccess: onClose,
    onSettled: () => setConfirmDelete(false),
  });

  return (
    <div className="fixed inset-0 z-50 flex">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-xs" onClick={onClose} />
      <div className="relative ml-auto w-full max-w-lg bg-surface-overlay border-l border-border h-full overflow-y-auto animate-slide-in">
        <div className="p-6 space-y-6">
          {/* Header */}
          <div className="flex items-start gap-3">
            <div className={`p-3 rounded-xl bg-surface flex items-center justify-center w-11 h-11 ${CATEGORY_COLORS[skill.category]}`}>
              <SkillIcon skill={skill} />
            </div>
            <div>
              <h2 className="text-xl font-bold text-text">{skill.name}</h2>
              <div className="flex items-center gap-2 mt-1">
                <Badge variant="accent">{t.skills.categories[skill.category]}</Badge>
                {skill.builtin && <Badge>{t.skills.builtin}</Badge>}
                <span className="text-xs text-text-tertiary font-mono">v{skill.version}</span>
              </div>
            </div>
          </div>

          {/* Actions */}
          <div className="flex flex-wrap items-center gap-2" data-testid="skill-actions">
            {!skill.builtin && (
              <Button size="sm" variant="secondary" icon={<Pencil className="w-3.5 h-3.5" />}
                      onClick={() => onEdit(skill)} disabled={locked} title={locked ? wayOut : undefined}>
                {t.common.edit}
              </Button>
            )}
            {(skill.builtin || locked) && (
              <Button size="sm" variant="secondary" icon={<Copy className="w-3.5 h-3.5" />} onClick={() => onCopy(skill)}>
                {t.skills.copyAsCustom}
              </Button>
            )}
            <Button size="sm" variant="ghost" icon={<Download className="w-3.5 h-3.5" />} onClick={handleExport}>
              {t.common.export}
            </Button>
            {!skill.builtin && (
              <Button size="sm" variant="ghost" icon={<Trash2 className="w-3.5 h-3.5" />}
                      onClick={() => setConfirmDelete(true)} disabled={locked || deleteSkill.isPending} loading={deleteSkill.isPending}
                      title={locked ? wayOut : undefined} className="text-danger ml-auto">
                {t.common.delete}
              </Button>
            )}
          </div>

          {skill.builtin && <p className="text-xs text-text-tertiary">{t.skills.builtinReadOnly}</p>}

          {skill.description && <p className="text-sm text-text-secondary">{skill.description}</p>}

          {locked && (
            <div className="flex items-start gap-2 p-3 rounded-lg border border-border-strong bg-surface-raised"
                 data-testid="skill-sealed-detail">
              <Lock className="w-4 h-4 shrink-0 mt-0.5 text-accent" />
              <div className="text-sm">
                <p className="font-semibold text-text">{t.skills.sealedBy}</p>
                <p className="text-text-secondary">{t.skills.sealedByNames.replace('{names}', sealedBy.join(', '))}</p>
                <p className="text-text-secondary mt-1" data-testid="skill-sealed-way-out">{wayOut}</p>
              </div>
            </div>
          )}

          {/* Capabilities — custom skills usually have none, then the section stays out */}
          {(isLoading || (capabilities?.length ?? 0) > 0) && (
          <div>
            <h3 className="text-sm font-semibold text-text mb-3">{t.skills.capabilities}</h3>
            {isLoading ? (
              <Spinner />
            ) : (
              <div className="space-y-3">
                {capabilities?.map((cap) => (
                  <div key={cap.id} className="p-3 rounded-lg bg-surface border border-border">
                    <div className="flex items-center gap-2 mb-1">
                      <Zap className="w-3.5 h-3.5 text-accent" />
                      <span className="text-sm font-medium text-text">{cap.name}</span>
                      {cap.async && <Badge variant="warning">async</Badge>}
                    </div>
                    <p className="text-xs text-text-secondary">{cap.description}</p>
                  </div>
                ))}
              </div>
            )}
          </div>
          )}

          {/* AI Context preview */}
          {skill.aiContext && (
            <div>
              <h3 className="text-sm font-semibold text-text mb-2">{t.skills.aiContext}</h3>
              <pre className="text-xs text-text-secondary font-mono bg-surface rounded-lg p-3 overflow-x-auto max-h-40 whitespace-pre-wrap">
                {skill.aiContext}
              </pre>
            </div>
          )}

          <SkillHistory skillId={skill.id} />

          <Button variant="secondary" onClick={onClose} className="w-full">
            {t.common.close}
          </Button>
        </div>
      </div>
      <ConfirmModal
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        onConfirm={handleDelete}
        title={t.skills.deleteTitle}
        message={t.skills.deleteConfirm.replace('{name}', () => skill.name)}
        confirmLabel={t.common.delete}
        confirmIcon={<Trash2 className="w-4 h-4" />}
        variant="danger"
        isPending={deleteSkill.isPending}
      />
    </div>
  );
}

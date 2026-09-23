import { Hint } from '../components/hints/Hint';
import { useRef, useState } from 'react';
import { Zap, Search, Lock, Plus, Upload, Download, Pencil, Copy, Trash2, ChevronDown, ChevronRight } from 'lucide-react';
import { Card, Button, Input, Badge, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { SkillEditorModal } from '../components/skills/SkillEditorModal';
import {
  useSkills, useSkillCapabilities, useSkillVersions, useDeleteSkill, useImportSkill,
  exportSkill, isSkillExportData,
} from '../hooks/useSkills';
import { useSkillSealPins } from '../hooks/useSealStatus';
import { useModuleEnabled, useModuleInfo } from '../hooks/useModules';
import { useToast } from '../hooks/useToast';
import { apiErrorMessage } from '../api/client';
import { useT, useI18nStore } from '../i18n';
import { formatRelativeTime } from '../utils/formatTime';
import { CATEGORY_ICONS, CATEGORY_COLORS, SKILL_CATEGORIES } from '../data/skillConstants';
import type { Skill, SkillCategory, SkillInput } from '../types';

/** Who wrote a version row — a person, an import (a SECURITY event) or the bundled release. */
const SOURCE_STYLES: Record<string, string> = {
  user: 'bg-human-subtle text-human',
  import: 'bg-warning-subtle text-warning',
  'builtin-seed': 'bg-surface-raised text-text-secondary',
};

type EditorState = { skill?: Skill; initial?: SkillInput; copied?: boolean };

function SkillCard({ skill, onSelect }: { skill: Skill; onSelect: (s: Skill) => void }) {
  const t = useT();
  const { data: sealPins } = useSkillSealPins();
  const sealedBy = sealPins?.[skill.id] ?? [];
  return (
    <Card
      className="cursor-pointer hover:border-border-strong transition-all group"
      onClick={() => onSelect(skill)}
    >
      <div className="flex items-start gap-3 mb-3">
        <div className={`p-2.5 rounded-xl bg-surface ${CATEGORY_COLORS[skill.category]}`}>
          {CATEGORY_ICONS[skill.category]}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <h3 className="font-semibold text-text truncate">{skill.name}</h3>
            {skill.builtin && (
              <Badge variant="accent">{t.skills.builtin}</Badge>
            )}
            {sealedBy.length > 0 && (
              <span title={t.skills.sealedByHint.replace('{names}', sealedBy.join(', '))} data-testid="skill-sealed"
                    className="shrink-0 inline-flex">
                <Lock className="w-3.5 h-3.5 text-accent" aria-label={t.skills.sealedBy} />
              </span>
            )}
          </div>
          <p className="text-xs text-text-tertiary mt-0.5">
            {t.skills.categories[skill.category]}
          </p>
        </div>
      </div>
      <p className="text-sm text-text-secondary line-clamp-2 mb-3">{skill.description}</p>
      <div className="flex items-center justify-between text-xs text-text-tertiary">
        <span>{skill.capabilities?.length ?? 0} {t.skills.capabilities.toLowerCase()}</span>
        <span className="font-mono">v{skill.version}</span>
      </div>
    </Card>
  );
}

function SkillHistory({ skillId }: { skillId: string }) {
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

function SkillDetail({ skill, onClose, onEdit, onCopy }: {
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

  const handleDelete = () => {
    if (!window.confirm(t.skills.deleteConfirm.replace('{name}', skill.name))) return;
    deleteSkill.mutate(skill.id, { onSuccess: onClose });
  };

  return (
    <div className="fixed inset-0 z-50 flex">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-xs" onClick={onClose} />
      <div className="relative ml-auto w-full max-w-lg bg-surface-overlay border-l border-border h-full overflow-y-auto animate-slide-in">
        <div className="p-6 space-y-6">
          {/* Header */}
          <div className="flex items-start gap-3">
            <div className={`p-3 rounded-xl bg-surface ${CATEGORY_COLORS[skill.category]}`}>
              {CATEGORY_ICONS[skill.category]}
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
                      onClick={handleDelete} disabled={locked || deleteSkill.isPending} loading={deleteSkill.isPending}
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

          {/* Capabilities */}
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
    </div>
  );
}

export function Skills() {
  const t = useT();
  const toast = useToast();
  const { data: skills, isLoading, error, refetch } = useSkills();
  const importSkill = useImportSkill();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [search, setSearch] = useState('');
  const [categoryFilter, setCategoryFilter] = useState<SkillCategory | 'ALL'>('ALL');
  // Keep the id, not the object: the detail view then shows the refetched state after an edit
  const [selectedSkillId, setSelectedSkillId] = useState<string | null>(null);
  const [editor, setEditor] = useState<EditorState | null>(null);
  const selectedSkill = skills?.find((s) => s.id === selectedSkillId) ?? null;

  const filtered = skills?.filter((s) => {
    const query = search.toLowerCase();
    const matchSearch = s.name.toLowerCase().includes(query) ||
      (s.description ?? '').toLowerCase().includes(query);
    const matchCategory = categoryFilter === 'ALL' || s.category === categoryFilter;
    return matchSearch && matchCategory;
  }) ?? [];

  const openCopy = (skill: Skill) => setEditor({
    copied: true,
    initial: {
      name: t.skills.copyName.replace('{name}', skill.name),
      description: skill.description,
      icon: skill.icon,
      category: skill.category,
      aiContext: skill.aiContext,
    },
  });

  const handleImportFile = async (file: File) => {
    let data: unknown;
    try {
      data = JSON.parse(await file.text());
    } catch {
      data = null;
    }
    if (!isSkillExportData(data)) {
      toast.error(t.skills.importInvalidFile);
      return;
    }
    importSkill.mutate(data, { onSuccess: (skill) => setSelectedSkillId(skill.id) });
  };

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.skills.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{t.skills.subtitle}</p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            icon={<Upload className="w-4 h-4" />}
            onClick={() => fileInputRef.current?.click()}
            loading={importSkill.isPending}
          >
            {t.common.import}
          </Button>
          <Button icon={<Plus className="w-4 h-4" />} onClick={() => setEditor({})}>
            {t.skills.newSkill}
          </Button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".json,application/json"
            className="hidden"
            data-testid="skill-import-input"
            onChange={(e) => {
              const file = e.target.files?.[0];
              e.target.value = '';
              if (file) void handleImportFile(file);
            }}
          />
        </div>
      </div>

      <Hint id="skills-packages" />

      {/* Filters */}
      <div className="flex items-center gap-4 flex-wrap">
        <div className="max-w-sm flex-1">
          <Input
            placeholder={t.common.search}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            icon={<Search className="w-4 h-4 text-text-tertiary" />}
          />
        </div>
        <div className="flex items-center gap-1.5 flex-wrap">
          <button
            onClick={() => setCategoryFilter('ALL')}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
              categoryFilter === 'ALL'
                ? 'bg-accent text-text-inverse'
                : 'text-text-secondary hover:text-text hover:bg-surface-raised'
            }`}
          >
            {t.common.all}
          </button>
          {SKILL_CATEGORIES.map((cat) => (
            <button
              key={cat}
              onClick={() => setCategoryFilter(cat)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                categoryFilter === cat
                  ? 'bg-accent text-text-inverse'
                  : 'text-text-secondary hover:text-text hover:bg-surface-raised'
              }`}
            >
              {t.skills.categories[cat]}
            </button>
          ))}
        </div>
      </div>

      {/* Skills grid */}
      {filtered.length === 0 ? (
        <EmptyState
          icon={<Zap className="w-8 h-8 text-text-tertiary" />}
          title={search || categoryFilter !== 'ALL' ? t.common.noResults : t.skills.title}
          description={t.skills.noSkills}
        />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 animate-stagger">
          {filtered.map((skill) => (
            <SkillCard key={skill.id} skill={skill} onSelect={(s) => setSelectedSkillId(s.id)} />
          ))}
        </div>
      )}

      {/* Skill detail slide-over */}
      {selectedSkill && (
        <SkillDetail
          skill={selectedSkill}
          onClose={() => setSelectedSkillId(null)}
          onEdit={(s) => setEditor({ skill: s })}
          onCopy={openCopy}
        />
      )}

      {editor && (
        <SkillEditorModal
          skill={editor.skill}
          initial={editor.initial}
          copied={editor.copied}
          onClose={() => setEditor(null)}
          onSaved={(saved) => setSelectedSkillId(saved.id)}
        />
      )}
    </div>
  );
}

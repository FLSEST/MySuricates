import { Hint } from '../components/hints/Hint';
import { useRef, useState } from 'react';
import { Zap, Search, Plus, Upload } from 'lucide-react';
import { Button, Input, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { SkillEditorModal } from '../components/skills/SkillEditorModal';
import { SkillCard } from '../components/skills/SkillCard';
import { SkillDetail } from '../components/skills/SkillDetail';
import { useSkills, useImportSkill, isSkillExportData } from '../hooks/useSkills';
import { useToast } from '../hooks/useToast';
import { useT } from '../i18n';
import { SKILL_CATEGORIES } from '../data/skillConstants';
import type { Skill, SkillCategory, SkillInput } from '../types';

type EditorState = { skill?: Skill; initial?: SkillInput; copied?: boolean };

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

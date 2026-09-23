import { useState } from 'react';
import { Package } from 'lucide-react';
import { Modal } from '../ui/Modal';
import { Button, Input, Select } from '../ui';
import { Spinner } from '../ui';
import { usePoolExport } from '../../hooks/usePool';
import { useFactoryTopics } from '../../hooks/useWorkflowFactory';
import { useT } from '../../i18n';
import type { PoolScope } from '../../types';

/** Order of the scope options; `external` = needs a tool outside the repo and the project (TODO 7ddacd1d). */
const SCOPE_OPTIONS: PoolScope[] = ['portable', 'repo', 'external'];

interface ExportToPoolModalProps {
  open: boolean;
  onClose: () => void;
  workflowId: string;
  workflowName: string;
  workflowDescription?: string | null;
  onExported?: () => void;
}

export function ExportToPoolModal({
  open, onClose, workflowId, workflowName, workflowDescription, onExported,
}: ExportToPoolModalProps) {
  const t = useT();
  const exportMutation = usePoolExport();
  const { data: topics } = useFactoryTopics();

  const [nameEn, setNameEn] = useState(workflowName);
  const [nameDe, setNameDe] = useState(workflowName);
  const [descEn, setDescEn] = useState(workflowDescription ?? '');
  const [descDe, setDescDe] = useState(workflowDescription ?? '');
  const [category, setCategory] = useState('');
  const [tagsInput, setTagsInput] = useState('');
  const [author, setAuthor] = useState('MySuricates');
  const [scope, setScope] = useState<PoolScope>('portable');
  const scopeTexts: Record<PoolScope, { label: string; hint: string }> = {
    portable: { label: t.factory.exportScopePortable, hint: t.factory.exportScopePortableHint },
    repo: { label: t.factory.exportScopeRepo, hint: t.factory.exportScopeRepoHint },
    external: { label: t.factory.exportScopeExternal, hint: t.factory.exportScopeExternalHint },
  };

  const handleExport = () => {
    const tags = tagsInput.trim()
      ? tagsInput.split(',').map(t => t.trim()).filter(Boolean)
      : undefined;

    exportMutation.mutate({
      workflowId,
      scope,
      request: {
        nameEn: nameEn || undefined,
        nameDe: nameDe || undefined,
        descriptionEn: descEn || undefined,
        descriptionDe: descDe || undefined,
        category: category || undefined,
        tags,
        author: author || undefined,
      },
    }, {
      onSuccess: () => {
        onExported?.();
        onClose();
      },
    });
  };

  const categoryOptions = [
    { value: '', label: '-' },
    ...(topics?.map(t => ({ value: t.id, label: t.name })) ?? []),
  ];

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.factory.exportTitle}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>{t.common.cancel}</Button>
          <Button
            variant="primary"
            onClick={handleExport}
            disabled={exportMutation.isPending || !nameEn.trim()}
          >
            {exportMutation.isPending ? <Spinner size="sm" /> : <Package className="w-4 h-4" />}
            {t.factory.exportConfirm}
          </Button>
        </>
      }
    >
      <p className="text-sm text-text-secondary mb-4">{t.factory.exportDesc}</p>
      <div className="space-y-3">
        <div>
          <span className="block text-sm font-medium text-text-secondary mb-1">{t.factory.exportScopeLabel}</span>
          <div className="space-y-2">
            {SCOPE_OPTIONS.map((value) => (
              <label key={value} className="flex items-start gap-2 cursor-pointer">
                <input
                  type="radio"
                  name="pool-export-scope"
                  value={value}
                  checked={scope === value}
                  onChange={() => setScope(value)}
                  className="mt-1"
                />
                <span>
                  <span className="block text-sm text-text">{scopeTexts[value].label}</span>
                  <span className="block text-xs text-text-secondary">{scopeTexts[value].hint}</span>
                </span>
              </label>
            ))}
          </div>
        </div>
        <Input
          label={t.factory.nameEn}
          value={nameEn}
          onChange={e => setNameEn(e.target.value)}
        />
        <Input
          label={t.factory.nameDe}
          value={nameDe}
          onChange={e => setNameDe(e.target.value)}
        />
        <div>
          <label className="block text-sm font-medium text-text-secondary mb-1">{t.factory.descEn}</label>
          <textarea
            className="input-base w-full text-sm"
            rows={2}
            value={descEn}
            onChange={e => setDescEn(e.target.value)}
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-text-secondary mb-1">{t.factory.descDe}</label>
          <textarea
            className="input-base w-full text-sm"
            rows={2}
            value={descDe}
            onChange={e => setDescDe(e.target.value)}
          />
        </div>
        <Select
          label={t.factory.categoryLabel}
          options={categoryOptions}
          value={category}
          onChange={e => setCategory(e.target.value)}
        />
        <Input
          label={t.factory.exportTags}
          placeholder="tag1, tag2, tag3"
          value={tagsInput}
          onChange={e => setTagsInput(e.target.value)}
        />
        <Input
          label={t.factory.exportAuthor}
          value={author}
          onChange={e => setAuthor(e.target.value)}
        />
      </div>
    </Modal>
  );
}

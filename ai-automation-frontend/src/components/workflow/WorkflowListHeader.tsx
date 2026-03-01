import { useRef } from 'react';
import { Plus, Upload } from 'lucide-react';
import { Button } from '../ui';
import type { WorkflowExportData } from '../../types';
import type { TranslationKeys } from '../../i18n';
import { useToast } from '../../hooks/useToast';

interface WorkflowListHeaderProps {
  t: TranslationKeys;
  onOpenCreate: () => void;
  onImportReady: (data: WorkflowExportData) => void;
}

export function WorkflowListHeader({ t, onOpenCreate, onImportReady }: WorkflowListHeaderProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const toast = useToast();

  return (
    <div className="flex items-center justify-between mb-4">
      <div>
        <h1 className="text-2xl font-bold text-text">{t.workflows.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.workflows.subtitle}</p>
      </div>
      <div className="flex items-center gap-2">
        <Button
          variant="secondary"
          icon={<Upload className="w-4 h-4" />}
          onClick={() => fileInputRef.current?.click()}
        >
          {t.common.import}
        </Button>
        <Button icon={<Plus className="w-4 h-4" />} onClick={onOpenCreate}>
          {t.workflows.create}
        </Button>
        <input
          ref={fileInputRef}
          type="file"
          accept=".json"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = (ev) => {
              try {
                const data = JSON.parse(ev.target?.result as string) as WorkflowExportData;
                if (!data.name || !Array.isArray(data.blocks)) {
                  toast.error(t.workflows.invalidFile);
                  return;
                }
                onImportReady(data);
              } catch {
                toast.error(t.workflows.invalidFile);
              }
            };
            reader.readAsText(file);
            e.target.value = '';
          }}
        />
      </div>
    </div>
  );
}

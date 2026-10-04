import { Button, Input, Modal, Select } from '../ui';
import { ExecutionBadges } from '../shared/ExecutionBadges';
import type { Project, WorkflowExportData } from '../../types';
import type { TranslationKeys } from '../../i18n';

interface WorkflowImportModalProps {
  t: TranslationKeys;
  open: boolean;
  importData: WorkflowExportData | null;
  importName: string;
  importProjectId: string;
  projects: Project[] | undefined;
  importLoading: boolean;
  onImportNameChange: (name: string) => void;
  onImportProjectIdChange: (id: string) => void;
  onImport: () => void;
  onClose: () => void;
}

export function WorkflowImportModal({
  t,
  open,
  importData,
  importName,
  importProjectId,
  projects,
  importLoading,
  onImportNameChange,
  onImportProjectIdChange,
  onImport,
  onClose,
}: WorkflowImportModalProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.workflows.importWorkflow}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            {t.common.cancel}
          </Button>
          <Button
            onClick={onImport}
            loading={importLoading}
            disabled={!importName.trim()}
          >
            {t.common.import}
          </Button>
        </>
      }
    >
      {importData && (
        <div className="space-y-4">
          <div className="flex items-center gap-2 p-3 rounded-lg bg-accent/5 border border-accent/20">
            <span className="text-xl">{importData.icon || '📋'}</span>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-text">{importData.name}</p>
              <p className="text-xs text-text-secondary flex items-center gap-2">
                <span>{importData.blocks?.length ?? 0} {t.workflows.blocks.toLowerCase()}</span>
                <ExecutionBadges blocks={importData.blocks ?? []} />
              </p>
            </div>
          </div>
          {/* Without a checksum the server imports with a warning only (b18939a6); a mismatch it refuses itself */}
          {!importData.integrity && (
            <p className="text-xs text-warning" data-testid="import-integrity-missing">
              {t.workflows.importIntegrityMissing}
            </p>
          )}
          <div>
            <Select
              label={t.workflows.importSelectProject}
              value={importProjectId}
              onChange={(e) => onImportProjectIdChange(e.target.value)}
              options={[
                { value: '', label: t.workflows.importNoProject },
                ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
              ]}
            />
            {!importProjectId && (
              <p className="text-xs text-text-tertiary mt-1">{t.workflows.importCollectionHint}</p>
            )}
          </div>
          <Input
            label={t.common.name}
            value={importName}
            onChange={(e) => onImportNameChange(e.target.value)}
          />
        </div>
      )}
    </Modal>
  );
}

import { useState } from 'react';
import { useImportWorkflow } from './useWorkflows';
import type { WorkflowExportData } from '../types';

/**
 * Import of an exported workflow file from the list: the file that was read opens the dialog
 * (name prefilled, project empty), importing closes it on success.
 */
export function useWorkflowImportFlow() {
  const importWorkflow = useImportWorkflow();
  const [showImport, setShowImport] = useState(false);
  const [importData, setImportData] = useState<WorkflowExportData | null>(null);
  const [importProjectId, setImportProjectId] = useState('');
  const [importName, setImportName] = useState('');

  const importReady = (data: WorkflowExportData) => {
    setImportData(data);
    setImportName(data.name);
    setImportProjectId('');
    setShowImport(true);
  };

  const runImport = () => {
    if (!importData || !importName.trim()) return;
    importWorkflow.mutate(
      { ...importData, name: importName, projectId: importProjectId || '' },
      {
        onSuccess: () => {
          setShowImport(false);
          setImportData(null);
        },
      },
    );
  };

  const closeImport = () => { setShowImport(false); setImportData(null); };

  return {
    showImport, importData, importName, setImportName, importProjectId, setImportProjectId,
    importReady, runImport, closeImport, importLoading: importWorkflow.isPending,
  };
}

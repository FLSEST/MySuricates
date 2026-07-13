import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { FileUp } from 'lucide-react';
import { Button, Modal, Select } from '../ui';
import { useProjects } from '../../hooks/useProjects';
import { useImportWorkflowBpmn } from '../../hooks/useWorkflows';
import { useToast } from '../../hooks/useToast';
import { apiErrorMessage } from '../../api/client';
import { useT } from '../../i18n';

/** Eigenständiger BPMN-Import: Datei wählen → (bei mehreren Projekten) Projekt → neuer Workflow. */
export function BpmnImportButton() {
  const t = useT();
  const navigate = useNavigate();
  const toast = useToast();
  const { data: projects } = useProjects();
  const importBpmn = useImportWorkflowBpmn();
  const fileRef = useRef<HTMLInputElement>(null);

  const [pendingXml, setPendingXml] = useState<string | null>(null);
  const [projectId, setProjectId] = useState('');

  const runImport = (xml: string, project: string) => {
    importBpmn.mutate(
      { xml, projectId: project },
      {
        onSuccess: (wf) => {
          setPendingXml(null);
          toast.success(t.workflows.bpmnImported);
          navigate(`/workflows/${wf.id}`);
        },
        onError: (err) => toast.error(apiErrorMessage(err, t.workflows.bpmnImportFailed)),
      },
    );
  };

  const handleFile = async (file: File) => {
    const xml = await file.text();
    const list = projects ?? [];
    if (list.length <= 1) {
      runImport(xml, list[0]?.id ?? ''); // ein/kein Projekt → direkt importieren
    } else {
      setProjectId(list[0]!.id);
      setPendingXml(xml); // mehrere Projekte → Auswahl-Dialog
    }
  };

  return (
    <>
      <Button
        variant="secondary"
        icon={<FileUp className="w-4 h-4" />}
        onClick={() => fileRef.current?.click()}
        loading={importBpmn.isPending && pendingXml === null}
      >
        {t.workflows.importBpmn}
      </Button>
      <input
        ref={fileRef}
        type="file"
        accept=".bpmn,.xml"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) void handleFile(file);
          e.target.value = '';
        }}
      />

      <Modal
        open={pendingXml !== null}
        onClose={() => setPendingXml(null)}
        title={t.workflows.importBpmn}
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setPendingXml(null)}>
              {t.common.cancel}
            </Button>
            <Button loading={importBpmn.isPending} onClick={() => pendingXml && runImport(pendingXml, projectId)}>
              {t.common.import}
            </Button>
          </>
        }
      >
        <Select
          label={t.workflows.selectProjectForImport}
          value={projectId}
          onChange={(e) => setProjectId(e.target.value)}
          options={(projects ?? []).map((p) => ({ value: p.id, label: p.name }))}
        />
      </Modal>
    </>
  );
}

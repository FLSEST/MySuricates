import { useRef } from 'react';
import { Upload } from 'lucide-react';
import { Button, Modal, Spinner } from '../ui';
import { useProjects } from '../../hooks/useProjects';
import { useProjectBundleImportFlow } from '../../hooks/useProjectBundleImportFlow';
import { useT } from '../../i18n';
import { apiErrorMessage } from '../../api/client';
import { BundleImportPreview } from './BundleImportPreview';
import { BundleImportTargetFields } from './BundleImportTargetFields';
import { BundleImportPathFields } from './BundleImportPathFields';
import { BundleImportResult } from './BundleImportResult';

/**
 * "Import project" on the project list: file → preview (workflows with collapsible shell
 * commands, chains, schedules, warnings) → target + path tokens → result with a link to the
 * project. The Import button only appears once the preview came back (spec: it must not be
 * possible to skip the preview). Switching the target to an existing project re-fetches the
 * preview with that project's id, so `nameTakenInTarget` reflects real conflicts.
 */
export function ProjectImportButton() {
  const t = useT();
  const flow = useProjectBundleImportFlow();
  const fileRef = useRef<HTMLInputElement>(null);
  const { data: projects } = useProjects();
  const activeProjects = (projects ?? []).filter((p) => !p.archivedAt);

  return (
    <>
      <Button variant="secondary" icon={<Upload className="w-4 h-4" />} onClick={flow.openDialog}>
        {t.projects.bundle.importProject}
      </Button>
      <input
        ref={fileRef}
        type="file"
        accept=".json,application/json"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) void flow.loadFile(file);
          e.target.value = '';
        }}
      />

      <Modal
        open={flow.open}
        onClose={flow.closeDialog}
        title={t.projects.bundle.importProject}
        size="lg"
        footer={
          flow.step === 'result' ? (
            <Button variant="secondary" onClick={flow.closeDialog}>{t.common.close}</Button>
          ) : flow.step === 'form' ? (
            <>
              <Button variant="secondary" onClick={flow.closeDialog}>{t.common.cancel}</Button>
              <Button onClick={flow.runImport} disabled={!flow.canImport} loading={flow.importing}>
                {t.common.import}
              </Button>
            </>
          ) : (
            <Button variant="secondary" onClick={flow.closeDialog}>{t.common.cancel}</Button>
          )
        }
      >
        {flow.step === 'file' && (
          <div className="space-y-3">
            <Button
              variant="secondary"
              icon={<Upload className="w-4 h-4" />}
              onClick={() => fileRef.current?.click()}
              disabled={flow.previewPending}
            >
              {t.projects.bundle.selectFile}
            </Button>
            <p className="text-xs text-text-tertiary">{t.projects.bundle.fileHint}</p>
            {flow.previewPending && <Spinner text={t.common.loading} />}
            {flow.fileError && <p className="text-xs text-danger">{flow.fileError}</p>}
            {flow.previewError && (
              <p className="text-xs text-danger">
                {apiErrorMessage(flow.previewError, t.projects.bundle.previewFailed)}
              </p>
            )}
          </div>
        )}

        {flow.step === 'form' && flow.previewData && flow.bundle && (
          <div className="space-y-5">
            <BundleImportPreview preview={flow.previewData} />
            {flow.previewPending && (
              <p className="text-xs text-text-tertiary">{t.projects.bundle.refreshingPreview}</p>
            )}
            {flow.previewError && (
              <p className="text-xs text-danger">
                {apiErrorMessage(flow.previewError, t.projects.bundle.previewFailed)}
              </p>
            )}
            <BundleImportTargetFields
              target={flow.target}
              onTargetChange={flow.setTarget}
              onConflict={flow.onConflict}
              onConflictChange={flow.setOnConflict}
              projects={activeProjects}
              defaultName={flow.bundle.project.name}
              nameTaken={flow.previewData.project.nameTaken}
            />
            <BundleImportPathFields
              paths={flow.previewData.paths}
              pathMappings={flow.pathMappings}
              onChange={flow.setPathMapping}
              canApplyProjectPath={flow.canApplyProjectPath}
              onApplyProjectPath={flow.applyProjectPath}
            />
          </div>
        )}

        {flow.step === 'result' && flow.result && (
          <BundleImportResult result={flow.result} onOpenProject={flow.closeDialog} />
        )}
      </Modal>
    </>
  );
}

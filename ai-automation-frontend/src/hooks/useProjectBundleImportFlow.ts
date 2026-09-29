import { useState } from 'react';
import { useT } from '../i18n';
import { usePreviewProjectBundleImport, useImportProjectBundle } from './useProjectBundle';
import type {
  ProjectBundle, ProjectBundleImportTarget, ProjectBundleConflictBehavior,
} from '../types';

/**
 * State of the "Import project" dialog: file → preview → target/paths → result, in the shape of
 * `docs/... Projekt-Bündel` (spec). The dialog itself only renders; every decision (which screen,
 * whether Import may be pressed) lives here so the screens stay presentational and testable apart.
 */
export function useProjectBundleImportFlow() {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [bundle, setBundle] = useState<ProjectBundle | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [target, setTarget] = useState<ProjectBundleImportTarget>({ mode: 'new', name: '', path: '' });
  const [onConflict, setOnConflict] = useState<ProjectBundleConflictBehavior>('skip');
  const [pathMappings, setPathMappings] = useState<Record<string, string>>({});

  const preview = usePreviewProjectBundleImport();
  const doImport = useImportProjectBundle();

  // 'file' (nothing parsed yet, or the preview failed) → 'form' (preview in hand) → 'result'.
  const step: 'file' | 'form' | 'result' = doImport.isSuccess ? 'result' : bundle && preview.isSuccess ? 'form' : 'file';

  function reset() {
    setBundle(null);
    setFileError(null);
    setTarget({ mode: 'new', name: '', path: '' });
    setOnConflict('skip');
    setPathMappings({});
    preview.reset();
    doImport.reset();
  }

  function openDialog() {
    reset();
    setOpen(true);
  }

  function closeDialog() {
    setOpen(false);
    reset();
  }

  async function loadFile(file: File) {
    setFileError(null);
    let parsed: ProjectBundle;
    try {
      parsed = JSON.parse(await file.text()) as ProjectBundle;
    } catch {
      setFileError(t.projects.bundle.invalidFile);
      return;
    }
    if (!parsed || parsed.format !== 'mysuricates-project-bundle') {
      setFileError(t.projects.bundle.invalidFile);
      return;
    }
    setBundle(parsed);
    setTarget({ mode: 'new', name: parsed.project?.name ?? '', path: '' });
    setPathMappings(Object.fromEntries((parsed.paths ?? []).map((p) => [p.token, p.original])));
    preview.mutate(parsed);
  }

  function setPathMapping(token: string, value: string) {
    setPathMappings((prev) => ({ ...prev, [token]: value }));
  }

  // Import unlocks only once the preview came back clean — the dialog is the one place the spec
  // requires this check (the API itself doesn't demand a prior preview call).
  const targetValid = target.mode === 'new' ? target.name.trim().length > 0 : target.projectId.length > 0;
  const pathsComplete = Object.values(pathMappings).every((v) => v.trim().length > 0);
  const canImport = !!bundle && preview.isSuccess && targetValid && pathsComplete && !doImport.isPending;

  function runImport() {
    if (!bundle || !canImport) return;
    doImport.mutate({ bundle, project: target, pathMappings, onConflict });
  }

  return {
    open, openDialog, closeDialog,
    step,
    loadFile, fileError,
    bundle,
    preview,
    target, setTarget, onConflict, setOnConflict,
    pathMappings, setPathMapping,
    canImport, runImport, importing: doImport.isPending,
    result: doImport.data,
  };
}

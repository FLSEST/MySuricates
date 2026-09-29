import { useState } from 'react';
import { useT } from '../i18n';
import { usePreviewProjectBundleImport, useImportProjectBundle } from './useProjectBundle';
import { useProjects } from './useProjects';
import type {
  ProjectBundle, ProjectBundlePreview, ProjectBundleImportTarget, ProjectBundleConflictBehavior,
  BundlePathToken,
} from '../types';

/**
 * The bundle's own project directory is not exported (spec: paths only carry `@@path:N@@`
 * tokens), so "the old project path" has to be guessed from the token list: the shortest
 * `original` that at least one other `original` is a subpath of (`X` or `X/...`). Returns `null`
 * when nothing qualifies, or more than one path ties for the shortest — the caller then hides the
 * "use project path" shortcut rather than applying a wrong guess.
 */
export function detectSharedRootPath(paths: BundlePathToken[]): string | null {
  const originals = paths.map((p) => p.original);
  const candidates = originals.filter((candidate) =>
    originals.some((other) => other !== candidate && other.startsWith(`${candidate}/`)),
  );
  if (candidates.length === 0) return null;
  const shortest = Math.min(...candidates.map((c) => c.length));
  const shortestCandidates = candidates.filter((c) => c.length === shortest);
  return shortestCandidates.length === 1 ? shortestCandidates[0]! : null;
}

/**
 * State of the "Import project" dialog: file → preview → target/paths → result, in the shape of
 * spec.md. The dialog itself only renders; every decision (which screen, whether Import may be
 * pressed, when the preview must be re-fetched) lives here so the screens stay presentational.
 */
export function useProjectBundleImportFlow() {
  const t = useT();
  const { data: projects } = useProjects();
  const [open, setOpen] = useState(false);
  const [bundle, setBundle] = useState<ProjectBundle | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [target, setTargetState] = useState<ProjectBundleImportTarget>({ mode: 'new', name: '', path: '' });
  const [onConflict, setOnConflict] = useState<ProjectBundleConflictBehavior>('skip');
  const [pathMappings, setPathMappings] = useState<Record<string, string>>({});
  // The target project id the current/last preview was fetched for (undefined = "new project"),
  // so the dialog only re-fetches when it actually changes.
  const [previewedForTargetId, setPreviewedForTargetId] = useState<string | undefined>(undefined);
  // The mutation's own `.data` resets to `undefined` the instant a new `mutate()` call starts
  // (query-core dispatches `data: undefined` on the 'pending' action) — kept separately so a
  // background re-fetch (target project switched) doesn't blank the screen while it runs.
  const [previewData, setPreviewData] = useState<ProjectBundlePreview | null>(null);

  const preview = usePreviewProjectBundleImport();
  const doImport = useImportProjectBundle();

  // 'file' (nothing parsed yet, or the preview failed) → 'form' (a preview came back at least
  // once) → 'result'.
  const step: 'file' | 'form' | 'result' = doImport.isSuccess ? 'result' : bundle && previewData ? 'form' : 'file';

  function reset() {
    setBundle(null);
    setFileError(null);
    setTargetState({ mode: 'new', name: '', path: '' });
    setOnConflict('skip');
    setPathMappings({});
    setPreviewedForTargetId(undefined);
    setPreviewData(null);
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
    setTargetState({ mode: 'new', name: parsed.project?.name ?? '', path: '' });
    setPathMappings(Object.fromEntries((parsed.paths ?? []).map((p) => [p.token, p.original])));
    setPreviewedForTargetId(undefined);
    preview.mutate({ bundle: parsed }, { onSuccess: (data) => setPreviewData(data) });
  }

  /**
   * Replaces the whole target and, when it now names a different (or no) existing project than
   * the last preview used, re-fetches the preview with that `targetProjectId` — real
   * `nameTakenInTarget` conflicts only exist once a concrete target project is known.
   */
  function setTarget(next: ProjectBundleImportTarget) {
    setTargetState(next);
    if (!bundle) return;
    const nextTargetId = next.mode === 'existing' ? next.projectId : undefined;
    if (nextTargetId === previewedForTargetId) return;
    setPreviewedForTargetId(nextTargetId);
    preview.mutate({ bundle, targetProjectId: nextTargetId }, { onSuccess: (data) => setPreviewData(data) });
  }

  function setPathMapping(token: string, value: string) {
    setPathMappings((prev) => ({ ...prev, [token]: value }));
  }

  // The path the bundle's own tokens were exported under — used only to offer the "use project
  // path" shortcut below, never sent to the API.
  const oldProjectPath = bundle ? detectSharedRootPath(bundle.paths) : null;
  const newProjectPath = !bundle
    ? null
    : target.mode === 'new'
      ? (target.path?.trim() || null)
      : (projects ?? []).find((p) => p.id === target.projectId)?.path?.trim() || null;
  const canApplyProjectPath = !!bundle && oldProjectPath !== null && newProjectPath !== null;

  /** Sets every path field whose original is the old project path (or a subpath of it) to the new one. */
  function applyProjectPath() {
    if (!bundle || oldProjectPath === null || newProjectPath === null) return;
    const updates: Record<string, string> = {};
    for (const p of bundle.paths) {
      if (p.original === oldProjectPath) {
        updates[p.token] = newProjectPath;
      } else if (p.original.startsWith(`${oldProjectPath}/`)) {
        updates[p.token] = newProjectPath + p.original.slice(oldProjectPath.length);
      }
    }
    setPathMappings((prev) => ({ ...prev, ...updates }));
  }

  // Import unlocks only once the preview came back clean — the dialog is the one place the spec
  // requires this check (the API itself doesn't demand a prior preview call).
  const targetValid = target.mode === 'new' ? target.name.trim().length > 0 : target.projectId.length > 0;
  const pathsComplete = Object.values(pathMappings).every((v) => v.trim().length > 0);
  const canImport = !!bundle && !!previewData && !preview.isPending && targetValid && pathsComplete && !doImport.isPending;

  function runImport() {
    if (!bundle || !canImport) return;
    doImport.mutate({ bundle, project: target, pathMappings, onConflict });
  }

  return {
    open, openDialog, closeDialog,
    step,
    loadFile, fileError,
    bundle,
    previewData, previewPending: preview.isPending, previewError: preview.isError ? preview.error : null,
    target, setTarget, onConflict, setOnConflict,
    pathMappings, setPathMapping,
    oldProjectPath, canApplyProjectPath, applyProjectPath,
    canImport, runImport, importing: doImport.isPending,
    result: doImport.data,
  };
}

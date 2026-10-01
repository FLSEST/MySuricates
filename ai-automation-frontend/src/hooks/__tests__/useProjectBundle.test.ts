import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useToastStore } from '../../stores/toastStore';
import { useI18nStore } from '../../i18n';
import type { ProjectBundle, ProjectBundlePreview, ProjectBundleImportResult } from '../../types';
import {
  bundleFilename, useExportProjectBundle, usePreviewProjectBundleImport, useImportProjectBundle,
} from '../useProjectBundle';

vi.mock('../../utils/downloadFile', () => ({ downloadFromResponse: vi.fn() }));
import { downloadFromResponse } from '../../utils/downloadFile';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useToastStore.setState({ toasts: [] });
  useI18nStore.setState({ locale: 'en' });
  vi.mocked(downloadFromResponse).mockClear();
});

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children);
}

const bundle: ProjectBundle = {
  format: 'mysuricates-project-bundle',
  formatVersion: 1,
  exportedAt: '2026-09-29T00:00:00Z',
  appVersion: '0.7.8',
  project: { name: 'widgetco', type: 'CODE' },
  workflows: [],
  chains: [],
  schedules: [],
  paths: [],
  skillNames: [],
};

describe('bundleFilename', () => {
  it('slugifies the project name', () => {
    expect(bundleFilename('widgetco')).toBe('widgetco.mysuricates-bundle.json');
    expect(bundleFilename('WIDGETCO Test / Prod')).toBe('widgetco-test-prod.mysuricates-bundle.json');
    expect(bundleFilename('  ')).toBe('project.mysuricates-bundle.json');
  });
});

describe('useExportProjectBundle', () => {
  it('downloads the export response under the fallback filename', async () => {
    server.use(http.get('/api/v1/projects/p1/export', () =>
      HttpResponse.json(bundle, { headers: { 'Content-Disposition': 'attachment; filename="widgetco.mysuricates-bundle.json"' } }),
    ));
    const { result } = renderHook(() => useExportProjectBundle(), { wrapper: createWrapper() });

    result.current.mutate({ projectId: 'p1', fallbackFilename: 'widgetco.mysuricates-bundle.json' });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(downloadFromResponse).toHaveBeenCalledTimes(1);
    expect(downloadFromResponse).toHaveBeenCalledWith(expect.any(Response), 'widgetco.mysuricates-bundle.json');
  });

  it('shows the server error on a failed export', async () => {
    server.use(http.get('/api/v1/projects/p1/export', () => HttpResponse.json({ error: 'Project not found' }, { status: 404 })));
    const { result } = renderHook(() => useExportProjectBundle(), { wrapper: createWrapper() });

    result.current.mutate({ projectId: 'p1', fallbackFilename: 'x.json' });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(useToastStore.getState().toasts.at(-1)).toMatchObject({
      type: 'error', message: 'Export failed: Project not found',
    });
    expect(downloadFromResponse).not.toHaveBeenCalled();
  });
});

describe('usePreviewProjectBundleImport', () => {
  it('posts the bundle without a targetProjectId and returns the preview', async () => {
    let body: unknown;
    const preview: ProjectBundlePreview = {
      project: { name: 'widgetco', nameTaken: false },
      workflows: [], chains: [], schedules: [], paths: [], warnings: [],
    };
    server.use(http.post('/api/v1/projects/import/preview', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(preview);
    }));
    const { result } = renderHook(() => usePreviewProjectBundleImport(), { wrapper: createWrapper() });

    result.current.mutate({ bundle });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(body).toEqual({ bundle });
    expect(result.current.data).toEqual(preview);
  });

  it('posts targetProjectId once an existing target project is known', async () => {
    let body: unknown;
    server.use(http.post('/api/v1/projects/import/preview', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ project: { name: 'widgetco', nameTaken: false }, workflows: [], chains: [], schedules: [], paths: [], warnings: [] });
    }));
    const { result } = renderHook(() => usePreviewProjectBundleImport(), { wrapper: createWrapper() });

    result.current.mutate({ bundle, targetProjectId: 'p1' });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(body).toEqual({ bundle, targetProjectId: 'p1' });
  });
});

describe('useImportProjectBundle', () => {
  it('imports and invalidates projects, workflows, chains and schedules', async () => {
    const importResult: ProjectBundleImportResult = {
      projectId: 'new-1',
      created: { workflows: 1, chains: 0, schedules: 0 },
      skipped: [], renamed: [], warnings: [],
    };
    server.use(http.post('/api/v1/projects/import', () => HttpResponse.json(importResult, { status: 201 })));
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children);
    const { result } = renderHook(() => useImportProjectBundle(), { wrapper });

    result.current.mutate({
      bundle, project: { mode: 'new', name: 'widgetco (Import)', path: '/home/x/code/widgetco' },
      pathMappings: {}, onConflict: 'skip',
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(importResult);
    const invalidatedKeys = invalidateSpy.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey[0]);
    expect(invalidatedKeys).toEqual(expect.arrayContaining(['projects', 'workflows', 'chains', 'schedules']));
    expect(useToastStore.getState().toasts.at(-1)).toMatchObject({ type: 'success', message: 'Project imported' });
  });
});

import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useToastStore } from '../../stores/toastStore';
import { useI18nStore } from '../../i18n';
import {
  useCreateSkill, useUpdateSkill, useDeleteSkill, useImportSkill, isSkillPinnedBySealError, isSkillExportData,
} from '../useSkills';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useToastStore.setState({ toasts: [] });
  useI18nStore.setState({ locale: 'en' });
});

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children);
}

const input = { name: 'Reviewer', description: null, icon: null, category: 'CODE' as const, aiContext: 'Review.' };
const saved = { id: 'n1', ...input, configSchema: null, builtin: false, version: '1.0.0', capabilities: [], createdAt: '2026-09-23T00:00:00Z' };

describe('skill mutations', () => {
  it('creates a skill with POST /skills', async () => {
    let body: unknown;
    server.use(http.post('/api/v1/skills', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(saved, { status: 201 });
    }));
    const { result } = renderHook(() => useCreateSkill(), { wrapper: createWrapper() });

    result.current.mutate(input);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(body).toEqual(input);
    expect(result.current.data?.id).toBe('n1');
    expect(useToastStore.getState().toasts.at(-1)).toMatchObject({ type: 'success', message: 'Skill created' });
  });

  it('updates a skill with PUT /skills/{id}', async () => {
    let body: unknown;
    server.use(http.put('/api/v1/skills/n1', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(saved);
    }));
    const { result } = renderHook(() => useUpdateSkill(), { wrapper: createWrapper() });

    result.current.mutate({ id: 'n1', data: input });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(body).toEqual(input);
  });

  it('names the seal when an update is refused with SKILL_PINNED_BY_SEAL', async () => {
    server.use(http.put('/api/v1/skills/n1', () => HttpResponse.json(
      { code: 'SKILL_PINNED_BY_SEAL', error: "Skill 'Reviewer' is used by sealed workflow(s) Deploy — unseal them before changing it" },
      { status: 409 },
    )));
    const { result } = renderHook(() => useUpdateSkill(), { wrapper: createWrapper() });

    result.current.mutate({ id: 'n1', data: input });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(isSkillPinnedBySealError(result.current.error)).toBe(true);
    const toast = useToastStore.getState().toasts.at(-1);
    expect(toast?.type).toBe('error');
    expect(toast?.message).toContain('A sealed workflow uses this skill');
    expect(toast?.message).toContain('Deploy');
  });

  it('keeps the generic message for other conflicts', async () => {
    server.use(http.put('/api/v1/skills/b1', () => HttpResponse.json(
      { code: 'STATE_CONFLICT', error: 'Cannot edit built-in skill: Code Review' }, { status: 409 },
    )));
    const { result } = renderHook(() => useUpdateSkill(), { wrapper: createWrapper() });

    result.current.mutate({ id: 'b1', data: input });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(isSkillPinnedBySealError(result.current.error)).toBe(false);
    expect(useToastStore.getState().toasts.at(-1)?.message).toContain('Failed to save skill');
  });

  it('deletes a skill with DELETE /skills/{id} and drops its cached queries', async () => {
    let called = false;
    server.use(http.delete('/api/v1/skills/n1', () => {
      called = true;
      return new HttpResponse(null, { status: 204 });
    }));
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    qc.setQueryData(['skills', 'n1', 'capabilities'], []);
    qc.setQueryData(['skills', 'other', 'capabilities'], []);
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children);
    const { result } = renderHook(() => useDeleteSkill(), { wrapper });

    result.current.mutate('n1');

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(called).toBe(true);
    expect(qc.getQueryCache().find({ queryKey: ['skills', 'n1', 'capabilities'] })).toBeUndefined();
    expect(qc.getQueryCache().find({ queryKey: ['skills', 'other', 'capabilities'] })).toBeDefined();
  });

  it('imports an export file with POST /skills/import', async () => {
    const file = { name: 'Reviewer', category: 'CODE' as const, capabilities: [{ name: 'review' }] };
    let body: unknown;
    server.use(http.post('/api/v1/skills/import', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(saved, { status: 201 });
    }));
    const { result } = renderHook(() => useImportSkill(), { wrapper: createWrapper() });

    result.current.mutate(file);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(body).toEqual(file);
  });
});

describe('isSkillExportData', () => {
  it('accepts a file with name and category', () => {
    expect(isSkillExportData({ name: 'X', category: 'CODE' })).toBe(true);
    expect(isSkillExportData({ name: 'X', category: 'CODE', capabilities: [] })).toBe(true);
  });

  it('rejects anything else', () => {
    expect(isSkillExportData(null)).toBe(false);
    expect(isSkillExportData({ name: ' ', category: 'CODE' })).toBe(false);
    expect(isSkillExportData({ name: 'X' })).toBe(false);
    expect(isSkillExportData({ name: 'X', category: 'CODE', capabilities: 'no' })).toBe(false);
    expect(isSkillExportData({ blocks: [] })).toBe(false);
  });
});

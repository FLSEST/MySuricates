import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { http, HttpResponse, delay } from 'msw';
import { server } from '../../test/server';
import type { PageResponse } from '../../types';
import { placeFinding, useAuditFindingsByStatus, useUpdateAuditFinding, type AuditFinding, type AuditFindingStatus } from '../useAuditCenter';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function finding(id: string, status: AuditFindingStatus, extra: Partial<AuditFinding> = {}): AuditFinding {
  return {
    id, projectId: 'p1', auditKey: 'security', status, severity: 'HIGH', title: id, todos: [],
    lastSeenAt: '2026-09-30T08:00:00Z', ...extra,
  } as unknown as AuditFinding;
}

function page(content: AuditFinding[], totalElements = content.length): PageResponse<AuditFinding> {
  return { content, page: 0, size: 200, totalElements, totalPages: 1 };
}

describe('placeFinding (c5f3cff5)', () => {
  const key = (status: AuditFindingStatus, auditKey = 'security') => ['audit-findings', 'p1', auditKey, status];
  const moved = finding('f-1', 'AWAITING_VERIFICATION');

  it('takes the finding out of the page of its old status and counts it down', () => {
    expect(placeFinding(page([finding('f-1', 'OPEN'), finding('f-2', 'OPEN')], 5), key('OPEN'), moved))
      .toEqual(page([finding('f-2', 'OPEN')], 4));
  });

  it('adds it to the page of its new status, or replaces it there', () => {
    expect(placeFinding(page([], 0), key('AWAITING_VERIFICATION'), moved)).toEqual(page([moved], 1));
    expect(placeFinding(page([finding('f-1', 'OPEN')], 1), key('AWAITING_VERIFICATION'), moved)).toEqual(page([moved], 1));
  });

  it('leaves pages of other audits and statuses alone', () => {
    const other = page([finding('f-9', 'AWAITING_VERIFICATION')]);
    expect(placeFinding(other, key('AWAITING_VERIFICATION', 'quality'), moved)).toBe(other);
    expect(placeFinding(other, key('RESOLVED'), moved)).toBe(other);
  });
});

describe('useUpdateAuditFinding with the findings list (c5f3cff5)', () => {
  it('keeps a finding moving from OPEN to AWAITING_VERIFICATION visible while the pages refetch', async () => {
    let open = [finding('f-1', 'OPEN')];
    let awaiting: AuditFinding[] = [];
    let awaitingDelay = 0;
    server.use(
      http.get('/api/v1/audit-findings', async ({ request }) => {
        const status = new URL(request.url).searchParams.get('status');
        if (status === 'AWAITING_VERIFICATION') {
          await delay(awaitingDelay);
          return HttpResponse.json(page(awaiting));
        }
        return HttpResponse.json(page(open));
      }),
      http.patch('/api/v1/audit-findings/:id', () => {
        open = [];
        awaiting = [finding('f-1', 'AWAITING_VERIFICATION')];
        return HttpResponse.json(awaiting[0]);
      }),
      http.get('/api/v1/audits', () => HttpResponse.json([])),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    const { result } = renderHook(() => ({
      list: useAuditFindingsByStatus('p1', 'security', ['OPEN', 'AWAITING_VERIFICATION']),
      update: useUpdateAuditFinding(),
    }), { wrapper });
    await waitFor(() => expect(result.current.list.findings.map((f) => f.id)).toEqual(['f-1']));

    // The OPEN page comes back first without the finding; the AWAITING page takes its time
    awaitingDelay = 200;
    await act(async () => {
      await result.current.update.mutateAsync({ id: 'f-1', status: 'AWAITING_VERIFICATION' });
    });
    await waitFor(() => expect(client.getQueryState(['audit-findings', 'p1', 'security', 'OPEN'])?.fetchStatus).toBe('idle'));
    expect(client.getQueryState(['audit-findings', 'p1', 'security', 'AWAITING_VERIFICATION'])?.fetchStatus).toBe('fetching');
    expect(result.current.list.findings.map((f) => [f.id, f.status])).toEqual([['f-1', 'AWAITING_VERIFICATION']]);

    await waitFor(() => expect(client.getQueryState(['audit-findings', 'p1', 'security', 'AWAITING_VERIFICATION'])?.fetchStatus).toBe('idle'));
    expect(result.current.list.findings.map((f) => [f.id, f.status])).toEqual([['f-1', 'AWAITING_VERIFICATION']]);
  });
});

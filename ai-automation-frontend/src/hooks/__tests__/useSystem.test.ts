import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { parseRetryAfter, resetRateLimitBackoff } from '../../api/client';
import {
  useSystemHealth,
  useSystemInfo,
  useAiProviders,
  useBackupList,
  useExecutionConfig,
  useUpdateExecutionConfig,
  useUpdateSystemSetting,
  useRemoteAccessStatus,
  useEnableRemoteAccess,
  useDisableRemoteAccess,
  usePiCatalog,
  useProviderModels,
  useRefreshProviderModels,
  useAssistantModelSetting,
  useAutoRepairModeSetting,
  useSuggestionsModelSetting,
  useAssistantThinkingPhrases,
  useRepairAllFailed,
  useBrowseDirectory,
} from '../useSystem';

const apiBase = '/api/v1';

const mocks = vi.hoisted(() => ({
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}));

vi.mock('../useToast', () => ({
  useToast: () => ({ success: mocks.toastSuccess, error: mocks.toastError }),
}));

vi.mock('../../i18n', () => ({
  useT: () => ({
    toast: {
      repairAllStarted: '{count} workflows queued for repair',
      repairFailed: 'Failed to start repair',
    },
    settings: {
      executionSaved: 'Execution settings saved',
      executionSaveFailed: 'Failed to save execution settings',
      settingSaveFailed: 'Could not save the setting',
    },
  }),
  useI18nStore: (selector: (s: { locale: string }) => unknown) =>
    selector({ locale: 'de' }),
}));

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  const Wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children);
  return { Wrapper, client };
}

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  vi.clearAllMocks();
});
afterAll(() => server.close());

// ────── Existing tests ──────

describe('useSystemHealth', () => {
  it('fetches system health', async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useSystemHealth(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.status).toBe('UP');
  });

  it('returns loading state initially', () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useSystemHealth(), { wrapper: Wrapper });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useSystemInfo', () => {
  it('fetches system info', async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useSystemInfo(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.version).toBe('0.5.0');
  });

  it('übernimmt die Rate-Limit-Parameter der Instanz für den 429-Backoff (37b98c97)', async () => {
    server.use(http.get(`${apiBase}/system/info`, () => HttpResponse.json({
      version: '0.5.0',
      rateLimit: { enabled: true, requestsPerMinute: 600, globalBackoffSeconds: 7, defaultRetryAfterSeconds: 21 },
    })));

    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useSystemInfo(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // Ohne den Serverwert stünden hier die eingebauten 10 s.
    expect(parseRetryAfter(null)).toBe(21);
    resetRateLimitBackoff();
    expect(parseRetryAfter(null)).toBe(10);
  });

  it('bleibt bei den eingebauten Werten, wenn die Instanz kein rateLimit meldet', async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useSystemInfo(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(parseRetryAfter(null)).toBe(10);
  });
});

describe('useAiProviders', () => {
  it('fetches AI providers', async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useAiProviders(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.providers).toContain('CLAUDE');
    expect(result.current.data!.default).toBe('CLAUDE');
  });
});

describe('useBackupList', () => {
  it('fetches backup list', async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useBackupList(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].name).toBe('backup1');
  });
});

describe('useExecutionConfig', () => {
  it('fetches execution config', async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useExecutionConfig(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.claudeMaxConcurrentCalls).toBe(5);
  });
});

describe('useUpdateExecutionConfig', () => {
  it('updates execution config via mutation', async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useUpdateExecutionConfig(), { wrapper: Wrapper });
    await act(async () => {
      result.current.mutate({ executionMaxConcurrent: 5, claudeMaxConcurrentCalls: 5, claudeReservedForInteractive: 1, claudeTimeoutSeconds: 300, claudeInterCallDelayMs: 0, chainWorkflowTimeoutMinutes: 30, rateLimitEnabled: 1, rateLimitRequestsPerMinute: 120, assistantChatTimeoutSeconds: 240 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it('shows error toast when update fails', async () => {
    server.use(
      http.put(`${apiBase}/system/execution-config`, () =>
        HttpResponse.json({ error: 'server error' }, { status: 500 }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useUpdateExecutionConfig(), { wrapper: Wrapper });
    await expect(
      act(async () => {
        await result.current.mutateAsync({ executionMaxConcurrent: 5, claudeMaxConcurrentCalls: 5, claudeReservedForInteractive: 1, claudeTimeoutSeconds: 300, claudeInterCallDelayMs: 0, chainWorkflowTimeoutMinutes: 30, rateLimitEnabled: 1, rateLimitRequestsPerMinute: 120, assistantChatTimeoutSeconds: 240 });
      }),
    ).rejects.toBeDefined();
    await waitFor(() => expect(mocks.toastError).toHaveBeenCalled());
  });
});

describe('useUpdateSystemSetting', () => {
  it('reports a rejected setting with the server message (c5f3cff5)', async () => {
    server.use(http.put(`${apiBase}/system/settings/:key`, () =>
      HttpResponse.json({ error: 'Value out of range' }, { status: 400 })));
    mocks.toastError.mockClear();
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useUpdateSystemSetting(), { wrapper: Wrapper });
    await act(async () => {
      result.current.mutate({ key: 'backup-retain-count', value: '0' });
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(mocks.toastError).toHaveBeenCalledWith('Could not save the setting: Value out of range');
  });

  it('updates a system setting via mutation', async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useUpdateSystemSetting(), { wrapper: Wrapper });
    await act(async () => {
      result.current.mutate({ key: 'test', value: 'val' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it('re-reads the saved setting, so its reader no longer holds the old value', async () => {
    let stored = 'sonnet';
    server.use(
      http.get(`${apiBase}/system/settings/assistant-model`, () => HttpResponse.json({ value: stored })),
      http.put(`${apiBase}/system/settings/:key`, async ({ request }) => {
        stored = ((await request.json()) as { value: string }).value;
        return HttpResponse.json({});
      }),
    );
    const { Wrapper, client } = createWrapper();
    const { result } = renderHook(() => ({ model: useAssistantModelSetting(), update: useUpdateSystemSetting() }), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.model.data).toBe('sonnet'));

    await act(async () => {
      await result.current.update.mutateAsync({ key: 'assistant-model', value: 'opus' });
    });

    // Refreshed before the mutation settles: the 5-minute staleTime no longer brings back the old value
    expect(client.getQueryData(['system', 'settings', 'assistant-model'])).toBe('opus');
    await waitFor(() => expect(result.current.model.data).toBe('opus'));
  });

  it('refreshes only the queries of that setting, plus the provider list for the default provider', async () => {
    const { Wrapper, client } = createWrapper();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useUpdateSystemSetting(), { wrapper: Wrapper });

    await act(async () => {
      await result.current.mutateAsync({ key: 'assistant-thinking-phrases', value: '[]' });
    });
    expect(invalidate.mock.calls.map(([filters]) => filters)).toEqual([
      { queryKey: ['system', 'settings', 'assistant-thinking-phrases'] },
    ]);

    invalidate.mockClear();
    await act(async () => {
      await result.current.mutateAsync({ key: 'default-ai-provider', value: 'PI' });
    });
    expect(invalidate.mock.calls.map(([filters]) => filters)).toEqual([
      { queryKey: ['system', 'settings', 'default-ai-provider'] },
      { queryKey: ['system', 'ai-providers'], exact: true },
    ]);
  });
});

// ────── New tests ──────

describe('useRemoteAccessStatus', () => {
  it('fetches remote access status', async () => {
    server.use(
      http.get(`${apiBase}/system/remote-access/status`, () =>
        HttpResponse.json({ enabled: true, port: 9090 }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useRemoteAccessStatus(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.enabled).toBe(true);
  });

  // The frontend goes live with the merge before the backend restarts — an OLD backend answers
  // without `inContainer` at all, and the hook must pass that through unchanged rather than fail.
  it('passes through a response without inContainer (old backend)', async () => {
    server.use(
      http.get(`${apiBase}/system/remote-access/status`, () =>
        HttpResponse.json({ enabled: false, ip: null, hostname: null }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useRemoteAccessStatus(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.inContainer).toBeUndefined();
  });

  it('passes through inContainer on a NEW backend', async () => {
    server.use(
      http.get(`${apiBase}/system/remote-access/status`, () =>
        HttpResponse.json({ enabled: false, ip: null, hostname: null, inContainer: true }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useRemoteAccessStatus(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.inContainer).toBe(true);
  });
});

describe('useEnableRemoteAccess', () => {
  it('calls enable endpoint and invalidates remote-access queries', async () => {
    server.use(
      http.post(`${apiBase}/system/remote-access/enable`, () =>
        HttpResponse.json({ enabled: true, port: 9090 }),
      ),
    );
    const { Wrapper, client } = createWrapper();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useEnableRemoteAccess(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync();
    });
    // TanStack benachrichtigt Beobachter per setTimeout(0) — der Status kann nach act noch fehlen
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['system', 'remote-access'] }),
    );
  });
});

describe('useDisableRemoteAccess', () => {
  it('calls disable endpoint and invalidates remote-access queries', async () => {
    server.use(
      http.post(`${apiBase}/system/remote-access/disable`, () =>
        HttpResponse.json({ enabled: false }),
      ),
    );
    const { Wrapper, client } = createWrapper();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useDisableRemoteAccess(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync();
    });
    // TanStack benachrichtigt Beobachter per setTimeout(0) — der Status kann nach act noch fehlen
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['system', 'remote-access'] }),
    );
  });
});

describe('usePiCatalog', () => {
  it('fetches PI catalog with all fields', async () => {
    const catalogData = {
      installed: true,
      authenticatedProviders: ['anthropic'],
      customProviders: [],
      defaultProvider: 'anthropic',
      defaultModel: 'sonnet',
    };
    server.use(
      http.get(`${apiBase}/system/ai-providers/PI/catalog`, () =>
        HttpResponse.json(catalogData),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => usePiCatalog(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.installed).toBe(true);
    expect(result.current.data!.authenticatedProviders).toEqual(['anthropic']);
    expect(result.current.data!.customProviders).toEqual([]);
    expect(result.current.data!.defaultProvider).toBe('anthropic');
    expect(result.current.data!.defaultModel).toBe('sonnet');
  });

  it('exposes error when request fails', async () => {
    server.use(
      http.get(`${apiBase}/system/ai-providers/PI/catalog`, () =>
        HttpResponse.json({ error: 'catalog unavailable' }, { status: 500 }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => usePiCatalog(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe('useProviderModels', () => {
  it('fetches the catalog for the given provider (uppercased)', async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useProviderModels('claude'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.providerId).toBe('CLAUDE');
    expect(result.current.data!.available).toBe(true);
    expect(result.current.data!.models.map((m) => m.value)).toEqual(['opus', 'sonnet']);
  });

  it('is disabled without a provider', () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useProviderModels(null), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
  });

  it('surfaces an unavailable catalog as data, not as an error', async () => {
    server.use(
      http.get(`${apiBase}/system/ai-providers/CODEX/models`, () =>
        HttpResponse.json({ providerId: 'CODEX', available: false, source: 'codex-cli', fetchedAt: null, reason: 'codex missing', models: [] }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useProviderModels('CODEX'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.available).toBe(false);
    expect(result.current.data!.reason).toBe('codex missing');
  });
});

describe('useRefreshProviderModels', () => {
  it('posts a refresh and writes the result into the query cache', async () => {
    const { Wrapper, client } = createWrapper();
    // gcTime is 0 in this wrapper, so an unobserved cache entry is dropped immediately — assert the write itself.
    const setQueryData = vi.spyOn(client, 'setQueryData');
    const { result } = renderHook(() => useRefreshProviderModels(), { wrapper: Wrapper });
    result.current.mutate('pi');
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(setQueryData).toHaveBeenCalledWith(
      ['system', 'ai-providers', 'PI', 'models'],
      expect.objectContaining({ providerId: 'PI', fetchedAt: '2026-09-12T10:05:00Z' }),
    );
  });
});

describe('useAssistantModelSetting', () => {
  it('returns model value from settings', async () => {
    server.use(
      http.get(`${apiBase}/system/settings/assistant-model`, () =>
        HttpResponse.json({ value: 'sonnet' }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useAssistantModelSetting(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBe('sonnet');
  });

  it('returns empty string when value is null', async () => {
    server.use(
      http.get(`${apiBase}/system/settings/assistant-model`, () =>
        HttpResponse.json({ value: null }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useAssistantModelSetting(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBe('');
  });
});

describe('useAutoRepairModeSetting', () => {
  it('returns setting value from server', async () => {
    server.use(
      http.get(`${apiBase}/system/settings/auto-repair-mode`, () =>
        HttpResponse.json({ value: 'manual' }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useAutoRepairModeSetting(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBe('manual');
  });

  it('returns auto as default when value is empty string', async () => {
    server.use(
      http.get(`${apiBase}/system/settings/auto-repair-mode`, () =>
        HttpResponse.json({ value: '' }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useAutoRepairModeSetting(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBe('auto');
  });
});

describe('useSuggestionsModelSetting', () => {
  it('returns model value from settings', async () => {
    server.use(
      http.get(`${apiBase}/system/settings/suggestions-model`, () =>
        HttpResponse.json({ value: 'haiku' }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useSuggestionsModelSetting(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBe('haiku');
  });

  it('returns empty string when value is null', async () => {
    server.use(
      http.get(`${apiBase}/system/settings/suggestions-model`, () =>
        HttpResponse.json({ value: null }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useSuggestionsModelSetting(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBe('');
  });
});

describe('useAssistantThinkingPhrases', () => {
  it('parses plain JSON array format', async () => {
    server.use(
      http.get(`${apiBase}/system/settings/assistant-thinking-phrases`, () =>
        HttpResponse.json({ value: '["A","B"]' }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useAssistantThinkingPhrases(), { wrapper: Wrapper });
    // placeholderData makes isSuccess true immediately — wait until real data arrives
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
      expect(result.current.isPlaceholderData).toBe(false);
    });
    expect(result.current.data).toEqual(['A', 'B']);
  });

  it('resolves correct locale from keyed map', async () => {
    server.use(
      http.get(`${apiBase}/system/settings/assistant-thinking-phrases`, () =>
        HttpResponse.json({ value: '{"de":["X"],"en":["Y"]}' }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useAssistantThinkingPhrases(), { wrapper: Wrapper });
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
      expect(result.current.isPlaceholderData).toBe(false);
    });
    expect(result.current.data).toEqual(['X']);
  });

  it('returns placeholderData before fetch resolves', () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useAssistantThinkingPhrases(), { wrapper: Wrapper });
    expect(result.current.data).toBeDefined();
    expect(Array.isArray(result.current.data)).toBe(true);
  });
});

describe('useRepairAllFailed', () => {
  it('shows success toast with queued count', async () => {
    server.use(
      http.post(`${apiBase}/system/repair-failed`, () =>
        HttpResponse.json({ queued: 3 }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useRepairAllFailed(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync(false);
    });
    expect(mocks.toastSuccess).toHaveBeenCalledWith(expect.stringContaining('3'));
  });

  it('shows error toast when repair fails', async () => {
    server.use(
      http.post(`${apiBase}/system/repair-failed`, () =>
        HttpResponse.json({ error: 'server error' }, { status: 500 }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useRepairAllFailed(), { wrapper: Wrapper });
    await expect(
      act(async () => {
        await result.current.mutateAsync(false);
      }),
    ).rejects.toBeDefined();
    await waitFor(() => expect(mocks.toastError).toHaveBeenCalled());
  });
});

describe('useBrowseDirectory', () => {
  it('does not fetch when path is null', () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useBrowseDirectory(null), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
  });

  it('fetches directory entries for a given path', async () => {
    server.use(
      http.get(`${apiBase}/system/browse`, ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get('path')).toBe('/tmp');
        return HttpResponse.json({ path: '/tmp', entries: [{ name: 'foo', type: 'DIRECTORY' }] });
      }),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useBrowseDirectory('/tmp'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.entries).toHaveLength(1);
    expect(result.current.data!.entries[0].name).toBe('foo');
  });
});

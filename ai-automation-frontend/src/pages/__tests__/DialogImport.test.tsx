import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';

// Mock MonacoEditor — jsdom can't run Monaco
vi.mock('../../components/shared/MonacoEditor', () => ({
  MonacoEditor: ({ value, onChange }: { value: string; onChange?: (v: string) => void }) =>
    createElement('textarea', {
      'data-testid': 'mock-monaco',
      value,
      onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => onChange?.(e.target.value),
    }),
}));

// Mock useNavigate
const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => mockNavigate };
});

import { DialogImport } from '../DialogImport';

const BASE = '/api/v1';

// ── Test Data ──────────────────────────────────────────────

const mockCounts: Record<string, number> = {
  '/tmp/test': 3,
  '/tmp/unknown': 2,
};

const mockSessions = [
  {
    sessionId: 's1',
    filePath: '/sessions/s1.jsonl',
    projectPath: '/tmp/test',
    startedAt: '2025-06-01T10:00:00Z',
    endedAt: '2025-06-01T10:30:00Z',
    turnCount: 12,
    toolCallCount: 8,
    dominantTools: ['Read', 'Edit'],
    firstUserMessage: 'Fix the login bug',
    substantial: true,
  },
  {
    sessionId: 's2',
    filePath: '/sessions/s2.jsonl',
    projectPath: '/tmp/test',
    startedAt: '2025-06-01T11:00:00Z',
    endedAt: '2025-06-01T11:05:00Z',
    turnCount: 3,
    toolCallCount: 1,
    dominantTools: ['Read'],
    firstUserMessage: 'Quick check',
    substantial: false,
  },
  {
    sessionId: 's3',
    filePath: '/sessions/s3.jsonl',
    projectPath: '/tmp/test',
    startedAt: '2025-06-01T12:00:00Z',
    endedAt: '2025-06-01T12:45:00Z',
    turnCount: 20,
    toolCallCount: 15,
    dominantTools: ['Edit', 'Bash'],
    firstUserMessage: 'Refactor the auth module',
    substantial: true,
  },
];

const mockSuggestion = {
  suggestedName: 'Fix Login Bug',
  suggestedDescription: 'Fixes the login bug by patching auth logic',
  blocks: [
    { type: 'shell', config: { command: 'npm test' }, requiredCapabilities: [] },
    { type: 'simple', config: { prompt: 'Review changes' }, requiredCapabilities: [] },
  ],
  sessionId: 's1',
  sourceFilePath: '/sessions/s1.jsonl',
  projectPath: '/tmp/test',
  confidence: 0.85,
  patternType: 'scripting',
  notAvailableReason: null,
  toolCallsAnalyzed: 8,
  available: true,
};

// ── Helpers ────────────────────────────────────────────────

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => {
  server.resetHandlers();
  mockNavigate.mockReset();
});
afterAll(() => server.close());

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    createElement(
      QueryClientProvider,
      { client: qc },
      createElement(MemoryRouter, null, createElement(DialogImport)),
    ),
  );
}

function setupDialogHandlers() {
  server.use(
    http.get(`${BASE}/dialog/sessions/count`, () => HttpResponse.json(mockCounts)),
    http.get(`${BASE}/dialog/sessions`, ({ request }) => {
      const url = new URL(request.url);
      const projectPath = url.searchParams.get('projectPath');
      if (projectPath) {
        return HttpResponse.json(mockSessions.filter(s => s.projectPath === projectPath));
      }
      return HttpResponse.json(mockSessions);
    }),
    http.post(`${BASE}/dialog/sessions/analyze`, () => HttpResponse.json(mockSuggestion)),
    http.post(`${BASE}/dialog/sessions/create-workflow`, () =>
      HttpResponse.json({ id: 'w-new', name: 'Fix Login Bug', projectId: 'p1' }),
    ),
  );
}

// ── Tests ──────────────────────────────────────────────────

describe('DialogImport', () => {
  it('renders the page header with title', async () => {
    setupDialogHandlers();
    renderPage();

    expect(screen.getByText('Session2Workflow')).toBeInTheDocument();
  });

  it('shows experimental badge', () => {
    setupDialogHandlers();
    renderPage();

    expect(screen.getByText(/Experimental/)).toBeInTheDocument();
  });

  it('shows subtitle text', () => {
    setupDialogHandlers();
    renderPage();

    expect(screen.getByText(/Convert Claude Code sessions/)).toBeInTheDocument();
  });

  it('renders project sidebar from session counts', async () => {
    setupDialogHandlers();
    renderPage();

    // Project "Test Project" from default handlers should appear with count 3
    await waitFor(() => {
      expect(screen.getByText('Test Project')).toBeInTheDocument();
    });
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('shows "Other Sessions" group for unknown project paths', async () => {
    setupDialogHandlers();
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Other Sessions')).toBeInTheDocument();
    });
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('shows empty state when no counts loaded', async () => {
    server.use(
      http.get(`${BASE}/dialog/sessions/count`, () => HttpResponse.json({})),
    );
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('No sessions found')).toBeInTheDocument();
    });
  });

  it('shows select-project prompt before any project is selected', () => {
    setupDialogHandlers();
    renderPage();

    expect(screen.getByText('Select a project to see its sessions')).toBeInTheDocument();
  });

  it('loads sessions when a project is selected', async () => {
    setupDialogHandlers();
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Test Project')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Test Project'));

    // With substantialOnly=true by default, only substantial sessions shown
    await waitFor(() => {
      expect(screen.getByText('Fix the login bug')).toBeInTheDocument();
    });
    expect(screen.getByText('Refactor the auth module')).toBeInTheDocument();
    // Non-substantial "Quick check" should be filtered out
    expect(screen.queryByText('Quick check')).not.toBeInTheDocument();
  });

  it('session card displays dominant tools', async () => {
    setupDialogHandlers();
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Test Project')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByText('Test Project'));

    await waitFor(() => {
      expect(screen.getByText('Fix the login bug')).toBeInTheDocument();
    });

    // Session s1 has tools Read, Edit — multiple sessions may show same tools
    expect(screen.getAllByText('Read').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Edit').length).toBeGreaterThanOrEqual(1);
  });

  it('shows non-substantial sessions when toggle is off', async () => {
    setupDialogHandlers();
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Test Project')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByText('Test Project'));

    await waitFor(() => {
      expect(screen.getByText('Fix the login bug')).toBeInTheDocument();
    });

    // Click the toggle to disable substantial-only filter
    const toggle = screen.getByRole('switch');
    fireEvent.click(toggle);

    await waitFor(() => {
      expect(screen.getByText('Quick check')).toBeInTheDocument();
    });
  });

  it('filters sessions by search text', async () => {
    setupDialogHandlers();
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Test Project')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByText('Test Project'));

    await waitFor(() => {
      expect(screen.getByText('Fix the login bug')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText('Search...');
    fireEvent.change(searchInput, { target: { value: 'Refactor' } });

    await waitFor(() => {
      expect(screen.queryByText('Fix the login bug')).not.toBeInTheDocument();
    });
    expect(screen.getByText('Refactor the auth module')).toBeInTheDocument();
  });

  it('shows "select a session" prompt in detail panel initially', () => {
    setupDialogHandlers();
    renderPage();

    expect(screen.getByText('Select a session from the list to analyze it.')).toBeInTheDocument();
  });

  it('analyzes session on click and shows suggestion', async () => {
    setupDialogHandlers();
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Test Project')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByText('Test Project'));

    await waitFor(() => {
      expect(screen.getByText('Fix the login bug')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Fix the login bug'));

    // Should show suggestion with confidence badge and blocks
    await waitFor(() => {
      expect(screen.getByText('85%')).toBeInTheDocument();
    });
    expect(screen.getByText('scripting')).toBeInTheDocument();
    expect(screen.getByText('shell')).toBeInTheDocument();
    expect(screen.getByText('simple')).toBeInTheDocument();
    expect(screen.getByText('8 tool calls')).toBeInTheDocument();
  });
});

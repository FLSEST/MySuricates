import { http, HttpResponse } from 'msw';

const BASE = '/api/v1';

export const handlers = [
  // Projects
  http.get(`${BASE}/projects`, () =>
    HttpResponse.json([
      { id: 'p1', name: 'Test Project', type: 'CODE', path: '/tmp/test', status: 'STOPPED', container: null, workflowCount: 2, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' },
    ]),
  ),
  http.post(`${BASE}/projects`, () =>
    HttpResponse.json({ id: 'p2', name: 'New Project', type: 'CODE', path: '/tmp/new', status: 'STOPPED', container: null, workflowCount: 0, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' }),
  ),
  http.delete(`${BASE}/projects/:id`, () => new HttpResponse(null, { status: 204 })),

  // Workflows
  http.get(`${BASE}/workflows`, () =>
    HttpResponse.json([
      { id: 'w1', projectId: 'p1', projectName: 'Test Project', projectSubtitle: null, projectDescription: null, name: 'Test Workflow', description: 'A test', icon: '🔧', blocks: [], version: '1', metadata: {}, activatedSkillIds: ['s1'], activatedSkillNames: ['Code Review'], runCount: 5, instructions: null, model: null, templateId: null, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' },
    ]),
  ),

  // Skills
  http.get(`${BASE}/skills`, () =>
    HttpResponse.json([
      { id: 's1', name: 'Code Review', description: 'Review code', icon: '🔍', category: 'CODE', aiContext: '', configSchema: {}, builtin: true, version: '1', capabilityCount: 2, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' },
    ]),
  ),

  // Todos
  http.get(`${BASE}/todos`, () =>
    HttpResponse.json({ content: [{ id: 't1', title: 'Test Todo', description: null, status: 'OPEN', priority: 'MEDIUM', tags: [], projectId: null, projectName: null, source: 'USER', sourceAgent: null, workflowRunId: null, referenceType: null, referenceId: null, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' }], page: 0, size: 50, totalElements: 1, totalPages: 1 }),
  ),
  http.get(`${BASE}/todos/count`, () =>
    HttpResponse.json({ open: 3, inProgress: 1, done: 5 }),
  ),
  http.post(`${BASE}/todos`, () =>
    HttpResponse.json({ id: 't2', title: 'New Todo', description: null, status: 'OPEN', priority: 'MEDIUM', tags: [], projectId: null, projectName: null, source: 'USER', sourceAgent: null, workflowRunId: null, referenceType: null, referenceId: null, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' }),
  ),
  http.delete(`${BASE}/todos/:id`, () => new HttpResponse(null, { status: 204 })),

  // Chains
  http.get(`${BASE}/chains`, () =>
    HttpResponse.json([
      { id: 'ch1', name: 'Test Chain', entries: [], createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' },
    ]),
  ),
  http.get(`${BASE}/chains/:id`, () =>
    HttpResponse.json({ id: 'ch1', name: 'Test Chain', entries: [], createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' }),
  ),
  http.get(`${BASE}/chains/runs`, () =>
    HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
  ),
  http.get(`${BASE}/chains/:chainId/runs`, () =>
    HttpResponse.json([]),
  ),
  http.post(`${BASE}/chains`, () =>
    HttpResponse.json({ id: 'ch2', name: 'New Chain', entries: [], createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' }),
  ),
  http.put(`${BASE}/chains/:id`, () =>
    HttpResponse.json({ id: 'ch1', name: 'Updated Chain', entries: [], createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' }),
  ),
  http.delete(`${BASE}/chains/:id`, () => new HttpResponse(null, { status: 204 })),
  http.post(`${BASE}/chains/:id/execute`, () =>
    HttpResponse.json({ id: 'cr1', chainId: 'ch1', status: 'RUNNING', entryRuns: [], createdAt: '2025-01-01T00:00:00Z' }),
  ),
  http.post(`${BASE}/chains/runs/:runId/cancel`, () => new HttpResponse(null, { status: 200 })),

  // Schedules
  http.get(`${BASE}/schedules`, () =>
    HttpResponse.json([
      { id: 'sc1', workflowId: 'w1', workflowName: 'Test Workflow', cronExpression: '0 0 * * *', enabled: true, createdAt: '2025-01-01T00:00:00Z' },
    ]),
  ),
  http.get(`${BASE}/schedules/maintenance`, () =>
    HttpResponse.json({ enabled: false }),
  ),
  http.post(`${BASE}/schedules/maintenance`, () =>
    HttpResponse.json({ enabled: true }),
  ),
  http.get(`${BASE}/schedules/remaining-today`, () =>
    HttpResponse.json({ schedules: [], total: 0 }),
  ),
  http.get(`${BASE}/schedules/remaining-this-week`, () =>
    HttpResponse.json({ schedules: [], total: 0 }),
  ),
  http.get(`${BASE}/schedules/calendar`, () =>
    HttpResponse.json({ entries: [] }),
  ),
  http.post(`${BASE}/schedules/preview`, () =>
    HttpResponse.json({ runs: ['2025-01-01T00:00:00Z'] }),
  ),
  http.get(`${BASE}/schedules/:id/runs`, () =>
    HttpResponse.json({ runs: [], total: 0 }),
  ),
  http.post(`${BASE}/schedules`, () =>
    HttpResponse.json({ id: 'sc2', workflowId: 'w1', cronExpression: '0 0 * * *', enabled: true, createdAt: '2025-01-01T00:00:00Z' }),
  ),
  http.put(`${BASE}/schedules/:id`, () =>
    HttpResponse.json({ id: 'sc1', workflowId: 'w1', cronExpression: '0 12 * * *', enabled: true, createdAt: '2025-01-01T00:00:00Z' }),
  ),
  http.post(`${BASE}/schedules/:id/enable`, () =>
    HttpResponse.json({ id: 'sc1', enabled: true }),
  ),
  http.post(`${BASE}/schedules/:id/disable`, () =>
    HttpResponse.json({ id: 'sc1', enabled: false }),
  ),
  http.delete(`${BASE}/schedules/:id`, () => new HttpResponse(null, { status: 204 })),

  // Wishes
  http.get(`${BASE}/wishes`, () =>
    HttpResponse.json([
      { id: 'wi1', title: 'Test Wish', status: 'COMPLETED', createdAt: '2025-01-01T00:00:00Z' },
    ]),
  ),
  http.get(`${BASE}/wishes/:id`, () =>
    HttpResponse.json({ id: 'wi1', title: 'Test Wish', status: 'COMPLETED', createdAt: '2025-01-01T00:00:00Z' }),
  ),
  http.post(`${BASE}/wishes`, () =>
    HttpResponse.json({ id: 'wi2', title: 'New Wish', status: 'PENDING', createdAt: '2025-01-01T00:00:00Z' }),
  ),
  http.post(`${BASE}/wishes/:id/cancel`, () => HttpResponse.json({})),
  http.delete(`${BASE}/wishes/:id`, () => new HttpResponse(null, { status: 204 })),
  http.post(`${BASE}/wishes/:id/respond`, () =>
    HttpResponse.json({ id: 'wi1', title: 'Test Wish', status: 'PROCESSING', createdAt: '2025-01-01T00:00:00Z' }),
  ),
  http.post(`${BASE}/wishes/:id/crystallize`, () =>
    HttpResponse.json({ templateId: 'tmpl1' }),
  ),

  // System
  http.get(`${BASE}/system/health`, () =>
    HttpResponse.json({ status: 'UP', components: {} }),
  ),
  http.get(`${BASE}/system/info`, () =>
    HttpResponse.json({ version: '0.5.0', uptime: '1h' }),
  ),
  http.get(`${BASE}/system/remote-access/status`, () =>
    HttpResponse.json({ enabled: false, url: null }),
  ),
  http.post(`${BASE}/system/remote-access/enable`, () =>
    HttpResponse.json({ enabled: true, url: 'https://example.com' }),
  ),
  http.post(`${BASE}/system/remote-access/disable`, () =>
    HttpResponse.json({ enabled: false, url: null }),
  ),
  http.get(`${BASE}/system/backups`, () =>
    HttpResponse.json([{ name: 'backup1', size: 1024, createdAt: '2025-01-01T00:00:00Z' }]),
  ),
  http.get(`${BASE}/system/ai-providers`, () =>
    HttpResponse.json({ providers: ['CLAUDE', 'CODEX'], default: 'CLAUDE' }),
  ),
  http.get(`${BASE}/system/execution-config`, () =>
    HttpResponse.json({ executionMaxConcurrent: 5, claudeMaxConcurrentCalls: 5, claudeReservedForInteractive: 1, claudeTimeoutSeconds: 300, claudeInterCallDelayMs: 0, chainWorkflowTimeoutMinutes: 30, activeRuns: 0 }),
  ),
  http.put(`${BASE}/system/execution-config`, () =>
    HttpResponse.json({ executionMaxConcurrent: 5, claudeMaxConcurrentCalls: 5, claudeReservedForInteractive: 1, claudeTimeoutSeconds: 300, claudeInterCallDelayMs: 0, chainWorkflowTimeoutMinutes: 30, activeRuns: 0 }),
  ),
  http.put(`${BASE}/system/settings/:key`, () =>
    HttpResponse.json({ key: 'test', value: 'val' }),
  ),
  http.get(`${BASE}/system/settings/assistant-model`, () =>
    HttpResponse.json({ value: 'claude-sonnet-4-6' }),
  ),
  http.get(`${BASE}/system/settings/auto-repair-mode`, () =>
    HttpResponse.json({ value: 'auto' }),
  ),
  http.get(`${BASE}/system/settings/suggestions-model`, () =>
    HttpResponse.json({ value: 'claude-haiku-4-5' }),
  ),
  http.get(`${BASE}/system/settings/assistant-thinking-phrases`, () =>
    HttpResponse.json({ value: '["Thinking..."]' }),
  ),
  http.post(`${BASE}/system/repair-failed`, () =>
    HttpResponse.json({ queued: 3 }),
  ),
  http.get(`${BASE}/system/browse`, () =>
    HttpResponse.json({ path: '/home', entries: [{ name: 'test', type: 'DIRECTORY' }] }),
  ),

  // Webhooks
  http.get(`${BASE}/webhooks`, () =>
    HttpResponse.json([
      { id: 'wh1', workflowId: 'w1', name: 'Test Webhook', path: '/hook1', enabled: true, createdAt: '2025-01-01T00:00:00Z' },
    ]),
  ),
  http.post(`${BASE}/webhooks`, () =>
    HttpResponse.json({ id: 'wh2', workflowId: 'w1', name: 'New Webhook', path: '/hook2', enabled: true, createdAt: '2025-01-01T00:00:00Z' }),
  ),
  http.post(`${BASE}/webhooks/:id/enable`, () =>
    HttpResponse.json({ id: 'wh1', enabled: true }),
  ),
  http.post(`${BASE}/webhooks/:id/disable`, () =>
    HttpResponse.json({ id: 'wh1', enabled: false }),
  ),
  http.delete(`${BASE}/webhooks/:id`, () => new HttpResponse(null, { status: 204 })),

  // Todo Triggers
  http.get(`${BASE}/todo-triggers`, () =>
    HttpResponse.json([
      { id: 'tt1', tagPattern: 'backend,bug', targetWorkflowId: 'w1', enabled: true, maxAttempts: 3, autoClose: true, createdAt: '2025-01-01T00:00:00Z' },
    ]),
  ),
  http.post(`${BASE}/todo-triggers`, () =>
    HttpResponse.json({ id: 'tt2', tagPattern: 'frontend', targetWorkflowId: 'w1', enabled: true, maxAttempts: 3, autoClose: true, createdAt: '2025-01-01T00:00:00Z' }),
  ),
  http.post(`${BASE}/todo-triggers/:id/enable`, () =>
    HttpResponse.json({ id: 'tt1', enabled: true }),
  ),
  http.post(`${BASE}/todo-triggers/:id/disable`, () =>
    HttpResponse.json({ id: 'tt1', enabled: false }),
  ),
  http.delete(`${BASE}/todo-triggers/:id`, () => new HttpResponse(null, { status: 204 })),

  // Templates
  http.get(`${BASE}/workflow-templates`, () =>
    HttpResponse.json([
      { id: 'tmpl1', name: '{"de":"Test","en":"Test"}', description: '{"de":"Desc","en":"Desc"}', category: 'CODE', builtIn: true, blocks: [], createdAt: '2025-01-01T00:00:00Z' },
    ]),
  ),
  http.post(`${BASE}/workflow-templates`, () =>
    HttpResponse.json({ id: 'tmpl2', name: '{"de":"Neu","en":"New"}', category: 'CODE', builtIn: false, blocks: [], createdAt: '2025-01-01T00:00:00Z' }),
  ),
  http.post(`${BASE}/workflow-templates/from-workflow/:id`, () =>
    HttpResponse.json({ id: 'tmpl3', name: '{"de":"Von WF","en":"From WF"}', category: 'CODE', builtIn: false, blocks: [], createdAt: '2025-01-01T00:00:00Z' }),
  ),

  // Activity Log
  http.get(`${BASE}/activity-log`, () =>
    HttpResponse.json({ content: [{ id: 'al1', level: 'INFO', category: 'SYSTEM', message: 'Test log', createdAt: '2025-01-01T00:00:00Z' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
  ),

  // Notifications
  http.get(`${BASE}/notifications`, () =>
    HttpResponse.json([
      { id: 'n1', name: 'Test Notification', type: 'EMAIL', enabled: true, createdAt: '2025-01-01T00:00:00Z' },
    ]),
  ),
  http.post(`${BASE}/notifications`, () =>
    HttpResponse.json({ id: 'n2', name: 'New Notification', type: 'EMAIL', enabled: true, createdAt: '2025-01-01T00:00:00Z' }),
  ),
  http.post(`${BASE}/notifications/:id/enable`, () =>
    HttpResponse.json({ id: 'n1', enabled: true }),
  ),
  http.post(`${BASE}/notifications/:id/disable`, () =>
    HttpResponse.json({ id: 'n1', enabled: false }),
  ),
  http.delete(`${BASE}/notifications/:id`, () => new HttpResponse(null, { status: 204 })),
];

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
      { id: 'w1', projectId: 'p1', projectName: 'Test Project', projectSubtitle: null, projectDescription: null, name: 'Test Workflow', description: 'A test', icon: '🔧', blocks: [], version: '1', metadata: {}, activatedSkills: [{ id: 's1', name: 'Code Review' }], runCount: 5, instructions: null, model: null, templateId: null, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z' },
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
];

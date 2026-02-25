import {
  ApiError,
  NetworkError,
  NotFoundError,
  TimeoutError,
  UnauthorizedError,
  ValidationError,
} from "./errors";
import type {
  ActivateSkillsRequest,
  ActivityLogPageResponse,
  ApprovalResponse,
  BrowseDirectoryResponse,
  CalendarDataResponse,
  CalendarParams,
  CapabilityResponse,
  ClientConfig,
  CreateProjectRequest,
  CreateScheduleRequest,
  CreateSkillRequest,
  CreateTodoRequest,
  CreateWebhookRequest,
  CreateWorkflowRequest,
  CreateWorkflowTemplateRequest,
  DashboardResponse,
  ExecuteWorkflowRequest,
  ExportSkillResponse,
  ExportWorkflowResponse,
  HealthResponse,
  ImportSkillRequest,
  ImportWorkflowRequest,
  ListActivityLogsParams,
  ListExecutionsParams,
  ListTodosParams,
  ProjectResponse,
  RemoteAccessResponse,
  ScheduledWorkflowResponse,
  SkillResponse,
  SystemInfoResponse,
  TodoCountResponse,
  TodoResponse,
  UpdateProjectRequest,
  UpdateTodoRequest,
  UpdateTodoStatusRequest,
  UpdateWorkflowRequest,
  UpdateWorkflowTemplateRequest,
  UpcomingScheduleResponse,
  WebhookTriggerResponse,
  WorkflowResponse,
  WorkflowRunResponse,
  WorkflowSuggestion,
  WorkflowTemplateResponse,
} from "./types";

// ── Helpers ────────────────────────────────────────────────────────────────────

function buildQuery(params: object): string {
  const entries = Object.entries(params as Record<string, unknown>).filter(
    ([, v]) => v !== undefined && v !== null,
  );
  if (entries.length === 0) return "";
  const qs = entries
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join("&");
  return `?${qs}`;
}

// ── Client ─────────────────────────────────────────────────────────────────────

export class MySuricatesClient {
  private readonly baseUrl: string;
  private readonly apiKey?: string;
  private readonly timeout: number;
  private readonly extraHeaders: Record<string, string>;

  constructor(config: ClientConfig) {
    this.baseUrl = config.baseUrl.replace(/\/+$/, "");
    this.apiKey = config.apiKey;
    this.timeout = config.timeout ?? 30_000;
    this.extraHeaders = config.headers ?? {};
  }

  // ── Low-level fetch ────────────────────────────────────────────────────────

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
  ): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const headers: Record<string, string> = {
      "Accept": "application/json",
      ...this.extraHeaders,
    };
    if (this.apiKey) {
      headers["X-API-Key"] = this.apiKey;
    }
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeout);

    let res: Response;
    try {
      res = await fetch(url, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      });
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        throw new TimeoutError(url, this.timeout);
      }
      throw new NetworkError(
        `Request failed: ${url}`,
        err instanceof Error ? err : undefined,
      );
    } finally {
      clearTimeout(timer);
    }

    if (res.status === 204) {
      return undefined as T;
    }

    let responseBody: unknown;
    const contentType = res.headers.get("content-type") ?? "";
    if (contentType.includes("application/json")) {
      responseBody = await res.json();
    } else {
      responseBody = await res.text();
    }

    if (!res.ok) {
      switch (res.status) {
        case 400:
          throw new ValidationError(url, responseBody);
        case 401:
        case 403:
          throw new UnauthorizedError(url, responseBody);
        case 404:
          throw new NotFoundError(url, responseBody);
        default:
          throw new ApiError(res.status, res.statusText, responseBody, url);
      }
    }

    return responseBody as T;
  }

  private get<T>(path: string): Promise<T> {
    return this.request<T>("GET", path);
  }
  private post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>("POST", path, body);
  }
  private put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>("PUT", path, body);
  }
  private patch<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>("PATCH", path, body);
  }
  private del<T>(path: string): Promise<T> {
    return this.request<T>("DELETE", path);
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  TODOS
  // ══════════════════════════════════════════════════════════════════════════════

  listTodos(params: ListTodosParams = {}): Promise<TodoResponse[]> {
    return this.get(`/api/v1/todos${buildQuery(params)}`);
  }

  createTodo(req: CreateTodoRequest): Promise<TodoResponse> {
    return this.post("/api/v1/todos", req);
  }

  getTodo(id: string): Promise<TodoResponse> {
    return this.get(`/api/v1/todos/${id}`);
  }

  updateTodo(id: string, req: UpdateTodoRequest): Promise<TodoResponse> {
    return this.put(`/api/v1/todos/${id}`, req);
  }

  updateTodoStatus(id: string, req: UpdateTodoStatusRequest): Promise<TodoResponse> {
    return this.patch(`/api/v1/todos/${id}/status`, req);
  }

  deleteTodo(id: string): Promise<void> {
    return this.del(`/api/v1/todos/${id}`);
  }

  countTodos(): Promise<TodoCountResponse> {
    return this.get("/api/v1/todos/count");
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  PROJECTS
  // ══════════════════════════════════════════════════════════════════════════════

  listProjects(): Promise<ProjectResponse[]> {
    return this.get("/api/v1/projects");
  }

  createProject(req: CreateProjectRequest): Promise<ProjectResponse> {
    return this.post("/api/v1/projects", req);
  }

  getProject(id: string): Promise<ProjectResponse> {
    return this.get(`/api/v1/projects/${id}`);
  }

  updateProject(id: string, req: UpdateProjectRequest): Promise<ProjectResponse> {
    return this.put(`/api/v1/projects/${id}`, req);
  }

  deleteProject(id: string): Promise<void> {
    return this.del(`/api/v1/projects/${id}`);
  }

  startProject(id: string): Promise<ProjectResponse> {
    return this.post(`/api/v1/projects/${id}/start`);
  }

  stopProject(id: string): Promise<ProjectResponse> {
    return this.post(`/api/v1/projects/${id}/stop`);
  }

  getProjectWorkflows(projectId: string): Promise<WorkflowResponse[]> {
    return this.get(`/api/v1/projects/${projectId}/workflows`);
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  WORKFLOWS
  // ══════════════════════════════════════════════════════════════════════════════

  listWorkflows(): Promise<WorkflowResponse[]> {
    return this.get("/api/v1/workflows");
  }

  createWorkflow(req: CreateWorkflowRequest): Promise<WorkflowResponse> {
    return this.post("/api/v1/workflows", req);
  }

  getWorkflow(id: string): Promise<WorkflowResponse> {
    return this.get(`/api/v1/workflows/${id}`);
  }

  updateWorkflow(id: string, req: UpdateWorkflowRequest): Promise<WorkflowResponse> {
    return this.put(`/api/v1/workflows/${id}`, req);
  }

  deleteWorkflow(id: string): Promise<void> {
    return this.del(`/api/v1/workflows/${id}`);
  }

  executeWorkflow(id: string, req?: ExecuteWorkflowRequest): Promise<WorkflowRunResponse> {
    return this.post(`/api/v1/workflows/${id}/execute`, req ?? {});
  }

  getWorkflowRuns(workflowId: string): Promise<WorkflowRunResponse[]> {
    return this.get(`/api/v1/workflows/${workflowId}/runs`);
  }

  getWorkflowRun(workflowId: string, runId: string): Promise<WorkflowRunResponse> {
    return this.get(`/api/v1/workflows/${workflowId}/runs/${runId}`);
  }

  optimizeWorkflow(id: string): Promise<Record<string, unknown>> {
    return this.post(`/api/v1/workflows/${id}/optimize`);
  }

  activateSkills(workflowId: string, req: ActivateSkillsRequest): Promise<WorkflowResponse> {
    return this.post(`/api/v1/workflows/${workflowId}/skills`, req);
  }

  deactivateSkill(workflowId: string, skillId: string): Promise<WorkflowResponse> {
    return this.del(`/api/v1/workflows/${workflowId}/skills/${skillId}`);
  }

  exportWorkflow(id: string): Promise<ExportWorkflowResponse> {
    return this.get(`/api/v1/workflows/export/${id}`);
  }

  importWorkflow(req: ImportWorkflowRequest): Promise<WorkflowResponse> {
    return this.post("/api/v1/workflows/import", req);
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  SKILLS
  // ══════════════════════════════════════════════════════════════════════════════

  listSkills(): Promise<SkillResponse[]> {
    return this.get("/api/v1/skills");
  }

  getSkill(id: string): Promise<SkillResponse> {
    return this.get(`/api/v1/skills/${id}`);
  }

  getSkillCapabilities(skillId: string): Promise<CapabilityResponse[]> {
    return this.get(`/api/v1/skills/${skillId}/capabilities`);
  }

  createSkill(req: CreateSkillRequest): Promise<SkillResponse> {
    return this.post("/api/v1/skills", req);
  }

  deleteSkill(id: string): Promise<void> {
    return this.del(`/api/v1/skills/${id}`);
  }

  exportSkill(id: string): Promise<ExportSkillResponse> {
    return this.get(`/api/v1/skills/export/${id}`);
  }

  importSkill(req: ImportSkillRequest): Promise<SkillResponse> {
    return this.post("/api/v1/skills/import", req);
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  EXECUTIONS
  // ══════════════════════════════════════════════════════════════════════════════

  listExecutions(params: ListExecutionsParams = {}): Promise<WorkflowRunResponse[]> {
    return this.get(`/api/v1/executions${buildQuery(params)}`);
  }

  getExecution(id: string): Promise<WorkflowRunResponse> {
    return this.get(`/api/v1/executions/${id}`);
  }

  pauseExecution(id: string): Promise<void> {
    return this.post(`/api/v1/executions/${id}/pause`);
  }

  resumeExecution(id: string): Promise<void> {
    return this.post(`/api/v1/executions/${id}/resume`);
  }

  cancelExecution(id: string): Promise<void> {
    return this.post(`/api/v1/executions/${id}/cancel`);
  }

  approveExecution(id: string, answer: string): Promise<void> {
    return this.post(`/api/v1/executions/${id}/approve`, { answer });
  }

  getPendingApproval(id: string): Promise<ApprovalResponse> {
    return this.get(`/api/v1/executions/${id}/approval`);
  }

  executeCommand(executionId: string, command: string): Promise<{ output: string }> {
    return this.post(`/api/v1/executions/${executionId}/command`, { command });
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  SCHEDULES
  // ══════════════════════════════════════════════════════════════════════════════

  listSchedules(): Promise<ScheduledWorkflowResponse[]> {
    return this.get("/api/v1/schedules");
  }

  getRemainingToday(): Promise<UpcomingScheduleResponse> {
    return this.get("/api/v1/schedules/remaining-today");
  }

  getRemainingThisWeek(): Promise<UpcomingScheduleResponse> {
    return this.get("/api/v1/schedules/remaining-this-week");
  }

  getSchedule(id: string): Promise<ScheduledWorkflowResponse> {
    return this.get(`/api/v1/schedules/${id}`);
  }

  getSchedulesByWorkflow(workflowId: string): Promise<ScheduledWorkflowResponse[]> {
    return this.get(`/api/v1/schedules/workflow/${workflowId}`);
  }

  getCalendarData(params: CalendarParams): Promise<CalendarDataResponse> {
    return this.get(`/api/v1/schedules/calendar${buildQuery(params)}`);
  }

  createSchedule(req: CreateScheduleRequest): Promise<ScheduledWorkflowResponse> {
    return this.post("/api/v1/schedules", req);
  }

  enableSchedule(id: string): Promise<ScheduledWorkflowResponse> {
    return this.post(`/api/v1/schedules/${id}/enable`);
  }

  disableSchedule(id: string): Promise<ScheduledWorkflowResponse> {
    return this.post(`/api/v1/schedules/${id}/disable`);
  }

  deleteSchedule(id: string): Promise<void> {
    return this.del(`/api/v1/schedules/${id}`);
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  WEBHOOKS
  // ══════════════════════════════════════════════════════════════════════════════

  listWebhooks(): Promise<WebhookTriggerResponse[]> {
    return this.get("/api/v1/webhooks");
  }

  getWebhook(id: string): Promise<WebhookTriggerResponse> {
    return this.get(`/api/v1/webhooks/${id}`);
  }

  getWebhooksByWorkflow(workflowId: string): Promise<WebhookTriggerResponse[]> {
    return this.get(`/api/v1/webhooks/workflow/${workflowId}`);
  }

  createWebhook(req: CreateWebhookRequest): Promise<WebhookTriggerResponse> {
    return this.post("/api/v1/webhooks", req);
  }

  enableWebhook(id: string): Promise<WebhookTriggerResponse> {
    return this.post(`/api/v1/webhooks/${id}/enable`);
  }

  disableWebhook(id: string): Promise<WebhookTriggerResponse> {
    return this.post(`/api/v1/webhooks/${id}/disable`);
  }

  deleteWebhook(id: string): Promise<void> {
    return this.del(`/api/v1/webhooks/${id}`);
  }

  triggerWebhook(token: string, payload?: Record<string, unknown>): Promise<WorkflowRunResponse> {
    return this.post(`/api/v1/webhook/trigger/${token}`, payload ?? {});
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  WORKFLOW TEMPLATES
  // ══════════════════════════════════════════════════════════════════════════════

  listWorkflowTemplates(): Promise<WorkflowTemplateResponse[]> {
    return this.get("/api/v1/workflow-templates");
  }

  getWorkflowTemplate(id: string): Promise<WorkflowTemplateResponse> {
    return this.get(`/api/v1/workflow-templates/${id}`);
  }

  createWorkflowTemplate(req: CreateWorkflowTemplateRequest): Promise<WorkflowTemplateResponse> {
    return this.post("/api/v1/workflow-templates", req);
  }

  updateWorkflowTemplate(
    id: string,
    req: UpdateWorkflowTemplateRequest,
  ): Promise<WorkflowTemplateResponse> {
    return this.put(`/api/v1/workflow-templates/${id}`, req);
  }

  deleteWorkflowTemplate(id: string): Promise<void> {
    return this.del(`/api/v1/workflow-templates/${id}`);
  }

  createTemplateFromWorkflow(workflowId: string): Promise<WorkflowTemplateResponse> {
    return this.post(`/api/v1/workflow-templates/from-workflow/${workflowId}`);
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  LEARNING / ANALYTICS
  // ══════════════════════════════════════════════════════════════════════════════

  getDashboard(): Promise<DashboardResponse> {
    return this.get("/api/v1/learning/dashboard");
  }

  getWorkflowStats(workflowId: string): Promise<Record<string, unknown>> {
    return this.get(`/api/v1/learning/workflows/${workflowId}/stats`);
  }

  getStepAnalytics(workflowId: string): Promise<Record<string, unknown>[]> {
    return this.get(`/api/v1/learning/workflows/${workflowId}/step-analytics`);
  }

  getOptimizationSuggestions(workflowId: string): Promise<Record<string, unknown>[]> {
    return this.get(`/api/v1/learning/workflows/${workflowId}/suggestions`);
  }

  getSimilarWorkflows(workflowId: string): Promise<Record<string, unknown>[]> {
    return this.get(`/api/v1/learning/workflows/${workflowId}/similar`);
  }

  getAgenticStrategyHistory(workflowId: string): Promise<Record<string, unknown>[]> {
    return this.get(`/api/v1/learning/workflows/${workflowId}/agentic-history`);
  }

  getAgenticAnalytics(workflowId: string): Promise<Record<string, unknown>> {
    return this.get(`/api/v1/learning/workflows/${workflowId}/agentic-analytics`);
  }

  getToolPatterns(workflowId: string): Promise<Record<string, unknown>> {
    return this.get(`/api/v1/learning/workflows/${workflowId}/tool-patterns`);
  }

  getExecutionInsights(workflowId: string): Promise<Record<string, unknown>> {
    return this.get(`/api/v1/learning/workflows/${workflowId}/execution-insights`);
  }

  getRunInsights(runId: string): Promise<Record<string, unknown>> {
    return this.get(`/api/v1/learning/runs/${runId}/insights`);
  }

  rateRun(runId: string, rating: number): Promise<void> {
    return this.post(`/api/v1/learning/runs/${runId}/rate?rating=${rating}`);
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  CONVERSIONS
  // ══════════════════════════════════════════════════════════════════════════════

  suggestWorkflow(runId: string): Promise<WorkflowSuggestion> {
    return this.get(`/api/v1/conversions/runs/${runId}/suggest`);
  }

  createWorkflowFromRun(
    runId: string,
    projectId: string,
    name?: string,
  ): Promise<WorkflowResponse> {
    return this.post(`/api/v1/conversions/runs/${runId}/create-workflow`, {
      projectId,
      name,
    });
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  SYSTEM
  // ══════════════════════════════════════════════════════════════════════════════

  getAuthStatus(): Promise<{ securityEnabled: boolean }> {
    return this.get("/api/v1/system/auth-status");
  }

  getHealth(): Promise<HealthResponse> {
    return this.get("/api/v1/system/health");
  }

  getSystemInfo(): Promise<SystemInfoResponse> {
    return this.get("/api/v1/system/info");
  }

  enableRemoteAccess(): Promise<RemoteAccessResponse> {
    return this.post("/api/v1/system/remote-access/enable");
  }

  disableRemoteAccess(): Promise<void> {
    return this.post("/api/v1/system/remote-access/disable");
  }

  getRemoteAccessStatus(): Promise<RemoteAccessResponse> {
    return this.get("/api/v1/system/remote-access/status");
  }

  browseDirectory(path?: string): Promise<BrowseDirectoryResponse> {
    const qs = path ? buildQuery({ path }) : "";
    return this.get(`/api/v1/system/browse${qs}`);
  }

  // ══════════════════════════════════════════════════════════════════════════════
  //  ACTIVITY LOG
  // ══════════════════════════════════════════════════════════════════════════════

  getActivityLogs(params: ListActivityLogsParams = {}): Promise<ActivityLogPageResponse> {
    return this.get(`/api/v1/activity-log${buildQuery(params)}`);
  }
}

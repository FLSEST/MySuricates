// ── Enums ──────────────────────────────────────────────────────────────────────

export enum TodoStatus {
  OPEN = "OPEN",
  IN_PROGRESS = "IN_PROGRESS",
  DONE = "DONE",
}

export enum TodoPriority {
  LOW = "LOW",
  MEDIUM = "MEDIUM",
  HIGH = "HIGH",
}

export enum TodoSource {
  USER = "USER",
  AI = "AI",
  WORKFLOW = "WORKFLOW",
}

export enum RunStatus {
  PENDING = "PENDING",
  RUNNING = "RUNNING",
  PAUSED = "PAUSED",
  COMPLETED = "COMPLETED",
  FAILED = "FAILED",
  CANCELLED = "CANCELLED",
}

export enum StepStatus {
  PENDING = "PENDING",
  RUNNING = "RUNNING",
  COMPLETED = "COMPLETED",
  FAILED = "FAILED",
  SKIPPED = "SKIPPED",
  WAITING_APPROVAL = "WAITING_APPROVAL",
}

export enum ProjectType {
  CODE = "CODE",
  CONTENT = "CONTENT",
  RESEARCH = "RESEARCH",
  BUSINESS = "BUSINESS",
  PERSONAL = "PERSONAL",
}

export enum ProjectStatus {
  STOPPED = "STOPPED",
  STARTING = "STARTING",
  RUNNING = "RUNNING",
  ERROR = "ERROR",
}

export enum ContainerStatus {
  CREATED = "CREATED",
  RUNNING = "RUNNING",
  STOPPED = "STOPPED",
  ERROR = "ERROR",
}

export enum SkillCategory {
  DOCUMENT = "DOCUMENT",
  CODE = "CODE",
  DATA = "DATA",
  CONTENT = "CONTENT",
  RESEARCH = "RESEARCH",
  BUSINESS = "BUSINESS",
  PRODUCTIVITY = "PRODUCTIVITY",
}

export enum ScheduleType {
  CRON = "CRON",
  ONCE = "ONCE",
}

export enum LogLevel {
  DEBUG = "DEBUG",
  INFO = "INFO",
  WARN = "WARN",
  ERROR = "ERROR",
}

export enum LogCategory {
  SYSTEM = "SYSTEM",
  EXECUTION = "EXECUTION",
  SCHEDULE = "SCHEDULE",
  WEBHOOK = "WEBHOOK",
  SECURITY = "SECURITY",
  CONTAINER = "CONTAINER",
}

// ── Shared / Nested Types ──────────────────────────────────────────────────────

export interface BlockDefinition {
  type: string;
  config: Record<string, unknown>;
  requiredCapabilities: string[];
}

// ── Request DTOs ───────────────────────────────────────────────────────────────

export interface CreateTodoRequest {
  title: string;
  description?: string;
  status?: TodoStatus;
  priority?: TodoPriority;
  tags?: string[];
  projectId?: string;
  source?: TodoSource;
  sourceAgent?: string;
  workflowRunId?: string;
  referenceType?: string;
  referenceId?: string;
}

export interface UpdateTodoRequest {
  title?: string;
  description?: string;
  status?: TodoStatus;
  priority?: TodoPriority;
  tags?: string[];
  projectId?: string;
  referenceType?: string;
  referenceId?: string;
}

export interface UpdateTodoStatusRequest {
  status: TodoStatus;
}

export interface CreateProjectRequest {
  name: string;
  subtitle?: string;
  description?: string;
  type: ProjectType;
  path: string;
  config?: Record<string, unknown>;
}

export interface UpdateProjectRequest {
  name?: string;
  subtitle?: string;
  description?: string;
  type?: ProjectType;
  path?: string;
  config?: Record<string, unknown>;
}

export interface CreateWorkflowRequest {
  projectId: string;
  name: string;
  description?: string;
  icon?: string;
  blocks?: BlockDefinition[];
  activatedSkillIds?: string[];
  instructions?: string;
  model?: string;
  templateId?: string;
}

export interface UpdateWorkflowRequest {
  name?: string;
  description?: string;
  icon?: string;
  blocks?: BlockDefinition[];
  instructions?: string;
  model?: string;
  metadata?: Record<string, unknown>;
}

export interface ExecuteWorkflowRequest {
  context?: Record<string, unknown>;
}

export interface ImportWorkflowRequest {
  projectId?: string;
  name: string;
  description?: string;
  icon?: string;
  blocks?: BlockDefinition[];
  instructions?: string;
  model?: string;
}

export interface ActivateSkillsRequest {
  skillIds: string[];
}

export interface CreateSkillRequest {
  name: string;
  description?: string;
  icon?: string;
  category: SkillCategory;
  aiContext?: string;
  configSchema?: Record<string, unknown>;
}

export interface ImportCapabilityRequest {
  name: string;
  description?: string;
  handlerClass?: string;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  async?: boolean;
}

export interface ImportSkillRequest {
  name: string;
  description?: string;
  icon?: string;
  category: SkillCategory;
  aiContext?: string;
  configSchema?: Record<string, unknown>;
  version?: string;
  capabilities?: ImportCapabilityRequest[];
}

export interface CreateScheduleRequest {
  workflowId: string;
  cronExpression?: string;
  description?: string;
  inputContext?: Record<string, unknown>;
  scheduleType?: string;
  scheduledAt?: string;
}

export interface CreateWebhookRequest {
  workflowId: string;
  description?: string;
  defaultContext?: Record<string, unknown>;
}

export interface CreateWorkflowTemplateRequest {
  name: Record<string, string>;
  description?: Record<string, string>;
  icon?: string;
  category?: string;
  blocks: BlockDefinition[];
  instructions?: string;
  model?: string;
}

export interface UpdateWorkflowTemplateRequest {
  name?: Record<string, string>;
  description?: Record<string, string>;
  icon?: string;
  category?: string;
  blocks?: BlockDefinition[];
  instructions?: string;
  model?: string;
  sortOrder?: number;
}

// ── Response DTOs ──────────────────────────────────────────────────────────────

export interface TodoResponse {
  id: string;
  title: string;
  description: string | null;
  status: TodoStatus;
  priority: TodoPriority;
  tags: string[];
  projectId: string | null;
  projectName: string | null;
  source: TodoSource;
  sourceAgent: string | null;
  workflowRunId: string | null;
  referenceType: string | null;
  referenceId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TodoCountResponse {
  open: number;
  inProgress: number;
  done: number;
}

export interface ContainerResponse {
  id: string;
  dockerContainerId: string;
  imageName: string;
  status: ContainerStatus;
  startedAt: string | null;
  stoppedAt: string | null;
}

export interface ProjectResponse {
  id: string;
  name: string;
  subtitle: string | null;
  description: string | null;
  type: ProjectType;
  path: string;
  config: Record<string, unknown>;
  status: ProjectStatus;
  container: ContainerResponse | null;
  createdAt: string;
  updatedAt: string;
}

export interface CapabilityResponse {
  id: string;
  name: string;
  description: string | null;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown>;
  async: boolean;
}

export interface SkillResponse {
  id: string;
  name: string;
  description: string | null;
  icon: string | null;
  category: SkillCategory;
  aiContext: string | null;
  configSchema: Record<string, unknown>;
  builtin: boolean;
  version: string;
  capabilities: CapabilityResponse[];
  createdAt: string;
}

export interface StepResultResponse {
  id: string;
  stepIndex: number;
  input: string | null;
  output: string | null;
  status: StepStatus;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  metadata: Record<string, unknown>;
}

export interface WorkflowRunResponse {
  id: string;
  workflowId: string;
  workflowName: string;
  projectName: string;
  status: RunStatus;
  steps: StepResultResponse[];
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  userRating: number | null;
  error: Record<string, unknown>;
  metadata: Record<string, unknown>;
}

export interface WorkflowResponse {
  id: string;
  projectId: string;
  projectName: string;
  projectSubtitle: string | null;
  projectDescription: string | null;
  name: string;
  description: string | null;
  icon: string | null;
  blocks: BlockDefinition[];
  version: string;
  metadata: Record<string, unknown>;
  activatedSkills: SkillResponse[];
  runCount: number;
  instructions: string | null;
  model: string | null;
  templateId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ExportWorkflowResponse {
  name: string;
  description: string | null;
  icon: string | null;
  blocks: BlockDefinition[];
  instructions: string | null;
  model: string | null;
  version: string;
  exportedAt: string;
}

export interface ExportCapabilityResponse {
  name: string;
  description: string | null;
  handlerClass: string | null;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown>;
  async: boolean;
}

export interface ExportSkillResponse {
  name: string;
  description: string | null;
  icon: string | null;
  category: SkillCategory;
  aiContext: string | null;
  configSchema: Record<string, unknown>;
  version: string;
  capabilities: ExportCapabilityResponse[];
}

export interface ScheduledWorkflowResponse {
  id: string;
  workflowId: string;
  workflowName: string;
  projectName: string;
  cronExpression: string;
  scheduleType: string;
  scheduledAt: string | null;
  enabled: boolean;
  description: string | null;
  inputContext: Record<string, unknown>;
  lastRunAt: string | null;
  nextRunAt: string | null;
  createdAt: string;
}

export interface WebhookTriggerResponse {
  id: string;
  workflowId: string;
  workflowName: string;
  secretToken: string;
  webhookUrl: string;
  enabled: boolean;
  description: string | null;
  defaultContext: Record<string, unknown>;
  lastTriggeredAt: string | null;
  triggerCount: number;
  createdAt: string;
}

export interface WorkflowTemplateResponse {
  id: string;
  name: Record<string, string>;
  description: Record<string, string>;
  icon: string | null;
  category: string | null;
  blocks: BlockDefinition[];
  instructions: string | null;
  model: string | null;
  builtIn: boolean;
  sortOrder: number;
  version: string;
  createdAt: string;
  updatedAt: string;
}

export interface TopWorkflowEntry {
  workflowId: string;
  workflowName: string;
  runCount: number;
}

export interface TimeSeriesPoint {
  date: string;
  count: number;
  successRate: number;
}

export interface DashboardResponse {
  totalWorkflows: number;
  totalRuns: number;
  runsToday: number;
  successfulRuns: number;
  failedRuns: number;
  averageDurationMs: number;
  successRate: number;
  totalCostUsd30d: number;
  totalTokens30d: number;
  topWorkflows: TopWorkflowEntry[];
  recentRuns: WorkflowRunResponse[];
  timeSeries: TimeSeriesPoint[];
}

export interface UpcomingRun {
  scheduleId: string;
  workflowName: string;
  projectName: string;
  scheduledAt: string;
}

export interface UpcomingScheduleResponse {
  count: number;
  schedules: UpcomingRun[];
}

export interface CalendarDataResponse {
  upcoming: ScheduledWorkflowResponse[];
  pastRuns: WorkflowRunResponse[];
}

export interface ActivityLogResponse {
  id: string;
  timestamp: string;
  level: string;
  category: string;
  message: string;
  source: string | null;
  referenceId: string | null;
  metadata: Record<string, unknown>;
  createdAt: string;
}

export interface ActivityLogPageResponse {
  content: ActivityLogResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface RemoteAccessResponse {
  [key: string]: unknown;
}

export interface HealthResponse {
  status: string;
  timestamp: string;
  docker: string;
}

export interface SystemInfoResponse {
  name: string;
  version: string;
  buildType: string;
  buildTimestamp: string;
  java: string;
  tailscale: string;
}

export interface BrowseDirectoryResponse {
  current: string;
  parent: string | null;
  directories: Array<{ name: string; path: string }>;
}

export interface ApprovalResponse {
  pending: boolean;
  blockId?: string;
  question?: string;
  options?: string[];
  stepIndex?: number;
}

export interface WorkflowSuggestion {
  [key: string]: unknown;
}

// ── Query parameter types ──────────────────────────────────────────────────────

export interface ListTodosParams {
  projectId?: string;
  status?: TodoStatus;
  priority?: TodoPriority;
  source?: TodoSource;
  tag?: string;
  q?: string;
}

export interface ListExecutionsParams {
  status?: RunStatus;
  workflowId?: string;
  projectId?: string;
}

export interface ListActivityLogsParams {
  page?: number;
  size?: number;
  level?: LogLevel;
  category?: LogCategory;
}

export interface CalendarParams {
  from: string;
  to: string;
}

// ── Client config ──────────────────────────────────────────────────────────────

export interface ClientConfig {
  baseUrl: string;
  apiKey?: string;
  timeout?: number;
  headers?: Record<string, string>;
}

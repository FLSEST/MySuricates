// ── Project ──────────────────────────────────────────────

export type ProjectType = 'CODE' | 'RESEARCH' | 'CONTENT' | 'BUSINESS' | 'PERSONAL';
export type ProjectStatus = 'STOPPED' | 'STARTING' | 'RUNNING' | 'ERROR';

export interface Project {
  id: string;
  name: string;
  type: ProjectType;
  path: string;
  config: Record<string, unknown>;
  status: ProjectStatus;
  container: Container | null;
  workflowCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateProjectRequest {
  name: string;
  type: ProjectType;
  path: string;
  config?: Record<string, unknown>;
}

export interface UpdateProjectRequest {
  name?: string;
  type?: ProjectType;
  path?: string;
  config?: Record<string, unknown>;
}

// ── Container ────────────────────────────────────────────

export type ContainerStatus = 'CREATED' | 'RUNNING' | 'STOPPED' | 'ERROR';

export interface Container {
  id: string;
  dockerContainerId: string;
  imageName: string;
  status: ContainerStatus;
  mounts: Record<string, string>;
  startedAt: string | null;
  stoppedAt: string | null;
}

// ── Workflow ─────────────────────────────────────────────

export interface Workflow {
  id: string;
  projectId: string;
  projectName: string;
  name: string;
  description: string;
  icon: string;
  blocks: BlockDefinition[];
  activatedSkillIds: string[];
  activatedSkillNames: string[];
  version: string;
  runCount: number;
  instructions: string | null;
  model: string | null;
  createdAt: string;
  updatedAt: string;
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
}

export interface UpdateWorkflowRequest {
  name?: string;
  description?: string;
  icon?: string;
  blocks?: BlockDefinition[];
  instructions?: string;
  model?: string;
}

export type BlockType = 'simple' | 'shell' | 'retry' | 'foreach' | 'while' | 'conditional' | 'skill-capability' | 'approval' | 'agentic' | 'sub-workflow';

export interface BlockDefinition {
  type: BlockType;
  config: Record<string, unknown>;
  requiredCapabilities?: string[];
}

// ── Workflow Run ─────────────────────────────────────────

export type RunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type StepStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'WAITING_APPROVAL';

export interface WorkflowRun {
  id: string;
  workflowId: string;
  workflowName: string;
  status: RunStatus;
  steps: StepResult[];
  startedAt: string;
  completedAt: string | null;
  durationMs: number | null;
  userRating: number | null;
  error: Record<string, unknown> | null;
  metadata: Record<string, unknown>;
}

export interface StepResult {
  id: string;
  stepIndex: number;
  blockType: string;
  input: string;
  output: string;
  status: StepStatus;
  durationMs: number | null;
  error: string | null;
}

// ── Skill ────────────────────────────────────────────────

export type SkillCategory = 'DOCUMENT' | 'CODE' | 'DATA' | 'CONTENT' | 'RESEARCH' | 'BUSINESS';

export interface Skill {
  id: string;
  name: string;
  description: string;
  icon: string;
  category: SkillCategory;
  aiContext: string;
  configSchema: Record<string, unknown>;
  builtin: boolean;
  version: string;
  capabilityCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface Capability {
  id: string;
  skillId: string;
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown>;
  async: boolean;
}

// ── Schedule ─────────────────────────────────────────────

export interface ScheduledWorkflow {
  id: string;
  workflowId: string;
  workflowName: string;
  cronExpression: string;
  scheduleType: 'CRON' | 'ONCE';
  scheduledAt: string | null;
  description: string;
  inputContext: Record<string, unknown>;
  enabled: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateScheduleRequest {
  workflowId: string;
  cronExpression?: string;
  description?: string;
  inputContext?: Record<string, unknown>;
  scheduleType?: 'CRON' | 'ONCE';
  scheduledAt?: string;
}

export interface CalendarEvent {
  id: string;
  title: string;
  date: string;
  type: 'scheduled' | 'past_run';
  status?: string;
  workflowId: string;
}

export interface CalendarDataResponse {
  upcoming: ScheduledWorkflow[];
  pastRuns: WorkflowRun[];
}

// ── Upcoming Schedule ────────────────────────────────────

export interface UpcomingScheduleData {
  count: number;
  schedules: Array<{
    scheduleId: string;
    workflowName: string;
    scheduledAt: string;
  }>;
}

// ── Webhook ──────────────────────────────────────────────

export interface WebhookTrigger {
  id: string;
  workflowId: string;
  workflowName: string;
  secretToken: string;
  description: string;
  defaultContext: Record<string, unknown>;
  enabled: boolean;
  webhookUrl: string;
  triggerCount: number;
  lastTriggeredAt: string | null;
  createdAt: string;
}

export interface CreateWebhookRequest {
  workflowId: string;
  description?: string;
  defaultContext?: Record<string, unknown>;
}

// ── System ───────────────────────────────────────────────

export interface SystemHealth {
  status: string;
  timestamp: string;
  docker: string;
}

export interface SystemInfo {
  name: string;
  version: string;
  buildType: string;
  buildTimestamp: string;
  java: string;
  tailscale: string;
}

export interface RemoteAccessStatus {
  enabled: boolean;
  ip: string | null;
  hostname: string | null;
}

export interface BrowseDirectoryResponse {
  current: string;
  parent: string | null;
  directories: Array<{ name: string; path: string }>;
}

// ── Learning / Analytics ─────────────────────────────────

export interface TimeSeriesPoint {
  date: string;
  count: number;
  successRate: number;
}

export interface DashboardData {
  totalWorkflows: number;
  totalRuns: number;
  runsToday: number;
  successfulRuns: number;
  failedRuns: number;
  averageDurationMs: number;
  successRate: number;
  totalCostUsd30d: number;
  totalTokens30d: number;
  topWorkflows: Array<{ workflowId: string; workflowName: string; runCount: number }>;
  recentRuns: WorkflowRun[];
  timeSeries?: TimeSeriesPoint[];
}

export interface WorkflowStats {
  workflowId: string;
  totalRuns: number;
  successfulRuns: number;
  failedRuns: number;
  averageDurationMs: number;
  successRate: number;
}

// ── Agentic Loop ─────────────────────────────────────────

export type AgenticPhase = 'reasoning' | 'acting' | 'acted' | 'approval' | 'completed' | 'error';

export interface AgenticIteration {
  iteration: number;
  reasoning?: string;
  action?: string;
  output?: string;
  exitCode?: number;
  phase: AgenticPhase;
  timestamp: string;
}

export interface AgenticState {
  stepIndex: number;
  goal: string;
  maxIterations: number;
  currentIteration: number;
  iterations: AgenticIteration[];
  goalAchieved: boolean | null;
  finalSummary?: string;
}

// ── Execution Insights ──────────────────────────────────

export interface StepInsight {
  stepIndex: number;
  status: string;
  durationMs: number | null;
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  model: string | null;
  numTurns: number | null;
  toolNames: string[] | null;
  toolUseCount: number | null;
}

export interface RunInsights {
  runId: string;
  status: string;
  totalCostUsd: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalTokens: number;
  durationMs: number | null;
  steps: StepInsight[];
}

export interface ExecutionInsights {
  totalRuns: number;
  runsWithInsights: number;
  totalCostUsd: number;
  avgCostPerRunUsd: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalTokens: number;
  modelsUsed: string[];
}

// ── Tool Patterns ───────────────────────────────────────

export interface ToolPattern {
  pattern: string[];
  frequency: number;
  avgCostUsd: number;
  occurrences: number;
}

export interface ToolPatternsResponse {
  workflowId: string;
  totalAgenticRuns: number;
  patterns: ToolPattern[];
  topTools: Array<{ name: string; count: number }>;
}

// ── Agentic → Workflow Conversion ───────────────────────

export interface WorkflowSuggestion {
  suggestedName: string;
  suggestedDescription: string;
  blocks: BlockDefinition[];
  originalGoal: string;
  agenticStepsAnalyzed: number;
  available: boolean;
}

// ── Activity Log ─────────────────────────────────────────

export type ActivityLogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
export type ActivityLogCategory = 'SYSTEM' | 'EXECUTION' | 'SCHEDULE' | 'WEBHOOK' | 'SECURITY' | 'CONTAINER';

export interface ActivityLogEntry {
  id: string;
  timestamp: string;
  level: ActivityLogLevel;
  category: ActivityLogCategory;
  message: string;
  source: string | null;
  referenceId: string | null;
  metadata: Record<string, unknown>;
  createdAt: string;
}

export interface ActivityLogPage {
  content: ActivityLogEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// ── Workflow Memory ──────────────────────────────────────

export interface WorkflowMemory {
  id: string;
  workflowId: string;
  title: string;
  content: string;
  tags: string[];
  sourceRunIds: string[];
  provider: string;
  active: boolean;
  createdAt: string;
}

export interface ExtractMemoryRequest {
  runIds?: string[];
  provider: string;
}

// ── Todo ────────────────────────────────────────────────

export type TodoStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE';
export type TodoPriority = 'LOW' | 'MEDIUM' | 'HIGH';
export type TodoSource = 'USER' | 'AI' | 'WORKFLOW';

export interface Todo {
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
}

export interface UpdateTodoRequest {
  title?: string;
  description?: string;
  status?: TodoStatus;
  priority?: TodoPriority;
  tags?: string[];
  projectId?: string;
}

export interface TodoCountResponse {
  open: number;
  inProgress: number;
  done: number;
}

// ── API Error ────────────────────────────────────────────

export interface ApiError {
  error: string;
  timestamp: string;
  fieldErrors?: Record<string, string>;
}

// ── Project ──────────────────────────────────────────────

export type ProjectType = 'CODE' | 'RESEARCH' | 'CONTENT' | 'BUSINESS' | 'PERSONAL';
export type ProjectStatus = 'STOPPED' | 'STARTING' | 'RUNNING' | 'ERROR';

export interface Project {
  id: string;
  name: string;
  subtitle?: string;
  description?: string;
  type: ProjectType;
  path: string;
  config: Record<string, unknown>;
  aiContext?: string;
  status: ProjectStatus;
  container: Container | null;
  workflowCount: number;
  archivedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateProjectRequest {
  name: string;
  subtitle?: string;
  description?: string;
  type: ProjectType;
  path: string;
  config?: Record<string, unknown>;
  aiContext?: string;
}

export interface UpdateProjectRequest {
  name?: string;
  subtitle?: string;
  description?: string;
  type?: ProjectType;
  path?: string;
  config?: Record<string, unknown>;
  aiContext?: string;
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

export interface AutoTodoConfig {
  enabled: boolean;
  priority?: TodoPriority;
  tags?: string[];
}

export interface WorkflowMetadata {
  autoTodo?: AutoTodoConfig;
  [key: string]: unknown;
}

export interface Workflow {
  id: string;
  projectId: string;
  projectName: string;
  projectSubtitle?: string;
  projectDescription?: string;
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
  provider: string | null;
  metadata?: WorkflowMetadata;
  defaultContext?: Record<string, string>;
  testStatus?: 'UNTESTED' | 'PASSED' | 'FAILED';
  lastTestedAt?: string | null;
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
  provider?: string;
  templateId?: string;
  metadata?: WorkflowMetadata;
}

// ── Workflow Template ────────────────────────────────────
export interface TemplateVerification {
  projectId: string;
  projectName: string;
  lastRunId: string;
  verifiedAt: string;
}

export interface WorkflowTemplate {
  id: string;
  name: Record<string, string>;
  description: Record<string, string>;
  icon: string;
  category: string | null;
  blocks: BlockDefinition[];
  instructions: string | null;
  model: string | null;
  builtIn: boolean;
  sortOrder: number;
  qualityStatus: 'UNTESTED' | 'TESTED';
  verifications: TemplateVerification[];
  version: string;
  createdAt: string;
  updatedAt: string;
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

export interface UpdateWorkflowRequest {
  name?: string;
  description?: string;
  icon?: string;
  blocks?: BlockDefinition[];
  instructions?: string;
  model?: string;
  provider?: string;
  metadata?: Record<string, unknown>;
  defaultContext?: Record<string, string>;
}

export type BlockType = 'simple' | 'shell' | 'retry' | 'foreach' | 'while' | 'conditional' | 'skill-capability' | 'approval' | 'agentic' | 'sub-workflow' | 'human-input';

export interface BlockDefinition {
  type: BlockType;
  config: Record<string, unknown>;
  requiredCapabilities?: string[];
}

export interface WorkflowExportData {
  name: string;
  description?: string;
  icon?: string;
  blocks: BlockDefinition[];
  instructions?: string;
  model?: string;
  version?: string;
  exportedAt?: string;
}

// ── Workflow Pool ────────────────────────────────────────

export interface PoolWorkflowEntry {
  id: string;
  name: Record<string, string>;
  description: Record<string, string>;
  icon: string;
  category: string | null;
  tags: string[];
  blocks: BlockDefinition[];
  instructions: string | null;
  model: string | null;
  version: string;
  author: string | null;
  defaultContext: Record<string, unknown> | null;
  filename: string;
}

// ── Workflow Run ─────────────────────────────────────────

export type RunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED' | 'CANCELLED';
export type StepStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'WAITING_APPROVAL';

export interface WorkflowRunError {
  message?: string;
  type?: string;
  stepIndex?: number;
  [key: string]: unknown;
}

export interface WorkflowRun {
  id: string;
  workflowId: string;
  workflowName: string;
  projectName: string | null;
  projectPath: string | null;
  status: RunStatus;
  steps: StepResult[];
  startedAt: string;
  completedAt: string | null;
  durationMs: number | null;
  userRating: number | null;
  error: WorkflowRunError | null;
  metadata: Record<string, unknown>;
  inputContext?: Record<string, unknown>;
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
  workflowId: string | null;
  workflowName: string | null;
  projectName: string | null;
  chainId: string | null;
  chainName: string | null;
  targetType: 'WORKFLOW' | 'CHAIN';
  cronExpression: string;
  scheduleType: 'CRON' | 'ONCE' | 'DELAYED';
  scheduledAt: string | null;
  delayExpression: string | null;
  description: string;
  inputContext: Record<string, unknown>;
  enabled: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateScheduleRequest {
  workflowId?: string;
  chainId?: string;
  targetType?: 'WORKFLOW' | 'CHAIN';
  cronExpression?: string;
  description?: string;
  inputContext?: Record<string, unknown>;
  scheduleType?: 'CRON' | 'ONCE' | 'DELAYED';
  scheduledAt?: string;
  delayExpression?: string;
}

export interface UpdateScheduleRequest {
  cronExpression?: string;
  description?: string;
  inputContext?: Record<string, unknown>;
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
    workflowName: string | null;
    projectName: string | null;
    chainId: string | null;
    chainName: string | null;
    targetType: string;
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

export interface HealthComponent {
  status: 'UP' | 'DOWN';
  responseTimeMs?: number;
}

export interface SystemHealth {
  status: 'UP' | 'DEGRADED' | 'DOWN';
  timestamp: string;
  docker: string;
  components?: {
    database: HealthComponent;
    docker: HealthComponent;
  };
}

export interface SystemInfo {
  name: string;
  version: string;
  buildType: string;
  buildTimestamp: string;
  java: string;
  tailscale: string;
  environment: string;
}

export interface RemoteAccessStatus {
  enabled: boolean;
  ip: string | null;
  hostname: string | null;
}

export interface BackupInfo {
  filename: string;
  sizeBytes: number;
  createdAt: string;
}

export interface BrowseDirectoryResponse {
  current: string;
  parent: string | null;
  directories: Array<{ name: string; path: string }>;
}

// ── Staging ──────────────────────────────────────────────

export interface StagingStatus {
  containerRunning: boolean;
  healthy: boolean;
  promotePhase: string;
  promoteStep: number;
  promoteTotalSteps: number;
  lastSnapshot: string | null;
  error: string | null;
  stableVersion: string | null;
  stableBuildTimestamp: string | null;
}

export interface SyncResult {
  projectsSynced: number;
  workflowsSynced: number;
  skillsSynced: number;
  skipped: number;
  errors: string[];
}

// ── Sync Preview / Selective Sync ─────────────────────────

export interface SyncPreview {
  projects: SyncPreviewItem[];
  workflows: SyncPreviewItem[];
  skills: SyncPreviewItem[];
}

export interface SyncPreviewItem {
  id: string;
  name: string;
  projectName?: string;
  action: 'NEW' | 'UPDATE' | 'SKIP';
}

export interface SyncDefinitionsRequest {
  projectIds?: string[];
  workflowIds?: string[];
  skillIds?: string[];
}

// ── Testing ───────────────────────────────────────────────

export interface TestingStatus {
  containerRunning: boolean;
  healthy: boolean;
  testVersion: string | null;
  testBuildTimestamp: string | null;
}

// ── Learning / Analytics ─────────────────────────────────

export interface TimeSeriesPoint {
  date: string;
  count: number;
  successRate: number;
}

export interface ProviderStats {
  provider: string;
  runCount: number;
  totalTokens: number;
  totalCostUsd: number;
  successRate: number;
}

export interface ModelStats {
  model: string;
  stepCount: number;
  totalTokens: number;
  totalCostUsd: number;
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
  providerStats?: ProviderStats[];
  modelStats?: ModelStats[];
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
  provider: string | null;
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

// ── Optimize Result ─────────────────────────────────────

export interface OptimizeResult {
  suggestedName?: string;
  suggestedDescription?: string;
  suggestedInstructions?: string;
  blocks?: BlockDefinition[];
  changes?: Array<{ type: string; description: string }>;
  error?: boolean;
  message?: string;
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

// ── Pagination ───────────────────────────────────────────

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
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
  workflowId: string | null;
  metadata: Record<string, unknown>;
  createdAt: string;
}

export type ActivityLogPage = PageResponse<ActivityLogEntry>;

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

export type TodoStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'BACKLOG';
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
  sessionId: string | null;
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
  sessionId?: string;
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
  backlog: number;
}

// ── Workflow Chain ──────────────────────────────────────

export interface WorkflowChain {
  id: string;
  name: string;
  description: string | null;
  icon: string | null;
  entries: ChainEntry[];
  stopOnFailure: boolean;
  runCount: number;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface ChainEntry {
  workflowId: string;
  workflowName: string | null;
  projectName: string | null;
  label: string | null;
  sortOrder: number;
  stopOnFailure: boolean;
  inputContext: Record<string, unknown>;
}

export interface CreateChainRequest {
  name: string;
  description?: string;
  icon?: string;
  entries: ChainEntryRequest[];
  stopOnFailure?: boolean;
}

export interface UpdateChainRequest {
  name?: string;
  description?: string;
  icon?: string;
  entries?: ChainEntryRequest[];
  stopOnFailure?: boolean;
}

export interface ChainEntryRequest {
  workflowId: string;
  label?: string;
  sortOrder: number;
  stopOnFailure?: boolean;
  inputContext?: Record<string, unknown>;
}

export interface ChainRun {
  id: string;
  chainId: string;
  chainName: string | null;
  status: RunStatus;
  entryRuns: ChainRunEntry[];
  inputContext: Record<string, unknown>;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  error: string | null;
  metadata: Record<string, unknown>;
}

export interface ChainRunEntry {
  entryIndex: number;
  workflowId: string;
  workflowName: string | null;
  projectName: string | null;
  workflowRunId: string | null;
  status: RunStatus;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
}

// ── Human Input ─────────────────────────────────────────

export interface PendingHumanInput {
  blockId: string;
  prompt: string;
  inputType: 'text' | 'number' | 'textarea' | 'select';
  options: string[];
  defaultValue: string;
  required: boolean;
  validationPattern: string | null;
  variableName: string;
  stepIndex: number;
}

// ── Wishes ───────────────────────────────────────────────

export type WishStatus = 'RECEIVED' | 'DECOMPOSING' | 'AWAITING_DECOMPOSE_APPROVAL' | 'EXECUTING' | 'EVALUATING' | 'REFINING' | 'AWAITING_CLARIFICATION' | 'AWAITING_APPROVAL' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export type WishResponseAction = 'ACCEPT' | 'REFINE' | 'CANCEL' | 'CLARIFY' | 'RETRY';

export interface WishRespondRequest {
  action: WishResponseAction;
  feedback?: string;
}

export interface Wish {
  id: string;
  originalText: string;
  status: WishStatus;
  projectId: string | null;
  projectName: string | null;
  decomposition: Record<string, unknown> | null;
  generatedWorkflowIds: string[];
  generatedWorkflowNames: string[];
  referenceWorkflowIds: string[];
  referenceWorkflowNames: string[];
  generatedChainId: string | null;
  iterationCount: number;
  maxIterations: number;
  currentRunId: string | null;
  resultSummary: string | null;
  error: string | null;
  iterationHistory: Array<Record<string, unknown>>;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface CreateWishRequest {
  text: string;
  projectId?: string;
  maxIterations?: number;
  referenceWorkflowIds?: string[];
}

// ── Workflow Factory ─────────────────────────────────────

export interface WorkflowFactoryRequest {
  topic: string;
  description?: string;
  category?: string;
  projectId: string;
  testInputs?: Record<string, unknown>;
  autoPromote?: boolean;
  autoFix?: boolean;
  maxRetries?: number;
  provider?: string;
}

export interface WorkflowFactoryResult {
  workflowId: string | null;
  workflowName: string | null;
  status: 'VERIFIED' | 'FAILED' | 'CREATED' | 'PROMOTED';
  testRunId: string | null;
  testStatus: 'PASSED' | 'FAILED' | null;
  testError: string | null;
  promotedAsTemplate: boolean;
  durationMs: number;
  retryErrors: string[];
}

export interface FactoryTopicCategory {
  id: string;
  name: string;
  description: string;
}

// ── Chain Templates ──────────────────────────────────────

export interface ChainTemplate {
  id: string;
  name: Record<string, string>;
  description: Record<string, string>;
  icon: string;
  entries: ChainTemplateEntry[];
}

export interface ChainTemplateEntry {
  templateId: string;
  label: Record<string, string>;
}

export interface ChainInstallResponse {
  chainId: string;
  chainName: string;
  workflowIds: string[];
}

// ── API Error ────────────────────────────────────────────

export interface ApiError {
  error: string;
  timestamp: string;
  fieldErrors?: Record<string, string>;
}

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
  mode?: 'auto' | 'approval' | 'track';
  priority?: TodoPriority;
  tags?: string[];
}

export interface AutoRepairConfig {
  enabled: boolean;
  mode?: 'auto' | 'test' | 'local';
  maxRetries?: number;
  timeoutSeconds?: number;
}

export interface PendingTodoItem {
  title: string;
  priority: TodoPriority;
  description?: string;
  category?: string;
  gateStatus?: string;
  projectId?: string;
}

export interface WorkflowMetadata {
  autoTodo?: AutoTodoConfig;
  autoRepair?: AutoRepairConfig;
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
  stableSyncedAt?: string | null;
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

// ── Workflow Version ─────────────────────────────────────

export interface WorkflowVersion {
  id: string;
  workflowId: string;
  versionNumber: number;
  snapshot: string;
  changeSource: string;
  changeMessage: string | null;
  createdAt: string;
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

export type BlockType = 'simple' | 'shell' | 'retry' | 'foreach' | 'while' | 'conditional' | 'switch' | 'skill-capability' | 'approval' | 'agentic' | 'sub-workflow' | 'human-input';

export interface BlockDefinition {
  type: BlockType;
  config: Record<string, unknown>;
  requiredCapabilities?: string[];
  skillIds?: string[];
}

export interface WorkflowExportData {
  name: string;
  description?: string;
  icon?: string;
  blocks: BlockDefinition[];
  instructions?: string;
  model?: string;
  version?: string;
  defaultContext?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
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

export type RunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED';
export const TERMINAL_RUN_STATUSES: ReadonlySet<RunStatus> = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED']);
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
  triggeredByScheduleId?: string | null;
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
  lastRunStatus?: RunStatus | null;
  endDate?: string | null;
  maxRuns?: number;
  runCount?: number;
  conflictBehavior?: 'SKIP' | 'OVERLAP';
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
  endDate?: string | null;
  maxRuns?: number | null;
  conflictBehavior?: 'SKIP' | 'OVERLAP';
}

export interface UpdateScheduleRequest {
  cronExpression?: string;
  description?: string;
  inputContext?: Record<string, unknown>;
  scheduledAt?: string;
  endDate?: string | null;
  maxRuns?: number | null;
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

// ── Integrations ──────────────────────────────────────────

export interface IntegrationProviderInfo {
  providerId: string;
  displayName: string;
  icon: string;
  description: string;
  status: 'CONNECTED' | 'DISCONNECTED' | 'EXPIRED';
  accountEmail: string | null;
  accountName: string | null;
  connectedSince: string | null;
  scopes: string | null;
  actions: IntegrationActionInfo[];
}

export interface IntegrationActionInfo {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

// ── Notifications ─────────────────────────────────────────

export type NotificationChannelType = 'WEBHOOK' | 'DESKTOP';

export interface NotificationConfig {
  id: string;
  name: string;
  enabled: boolean;
  channelType: NotificationChannelType;
  webhookUrl: string | null;
  workflowId: string | null;
  triggerOnSuccess: boolean;
  triggerOnFailure: boolean;
  triggerAlways: boolean;
  messageTemplate: string | null;
  createdAt: string;
}

export interface CreateNotificationConfigRequest {
  name: string;
  channelType: NotificationChannelType;
  webhookUrl?: string;
  workflowId?: string;
  triggerOnSuccess: boolean;
  triggerOnFailure: boolean;
  triggerAlways: boolean;
  messageTemplate?: string;
}

// ── Completion Trigger ────────────────────────────────────

export interface CompletionTrigger {
  id: string;
  sourceWorkflowId: string;
  sourceWorkflowName: string;
  targetWorkflowId: string;
  targetWorkflowName: string;
  enabled: boolean;
  triggerOnSuccess: boolean;
  triggerOnFailure: boolean;
  triggerAlways: boolean;
  description: string;
  contextVariables: Record<string, unknown>;
  lastTriggeredAt: string | null;
  triggerCount: number;
  createdAt: string;
}

export interface CreateCompletionTriggerRequest {
  sourceWorkflowId: string;
  targetWorkflowId: string;
  triggerOnSuccess: boolean;
  triggerOnFailure: boolean;
  triggerAlways: boolean;
  description?: string;
  contextVariables?: Record<string, unknown>;
}

// ── Todo Trigger ─────────────────────────────────────────

export interface TodoTrigger {
  id: string;
  tagPattern: string;
  projectId: string | null;
  sourceFilter: string | null;
  minPriority: string | null;
  targetWorkflowId: string;
  targetWorkflowName: string;
  contextTemplate: Record<string, unknown>;
  enabled: boolean;
  maxAttempts: number;
  autoClose: boolean;
  description: string | null;
  lastTriggeredAt: string | null;
  triggerCount: number;
  createdAt: string;
}

export interface CreateTodoTriggerRequest {
  tagPattern: string;
  projectId?: string;
  sourceFilter?: string;
  minPriority?: string;
  targetWorkflowId: string;
  contextTemplate?: Record<string, unknown>;
  description?: string;
  maxAttempts?: number;
  autoClose?: boolean;
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

export interface WorkflowDiff {
  changedFields: string[];
  modelBefore: string | null;
  modelAfter: string | null;
  blocksAdded: number;
  blocksRemoved: number;
}

export interface SyncPreviewItem {
  id: string;
  name: string;
  projectName?: string;
  action: 'NEW' | 'UPDATE' | 'SKIP';
  diff?: WorkflowDiff | null;
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
  failedCount: number;
  avgDurationMs: number;
  totalCostUsd: number;
  totalTokens: number;
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

export interface GateStatusEntry { status: string; count: number; }
export interface FailureReasonEntry { reason: string; count: number; }
export interface CostByWorkflowEntry { workflowId: string; workflowName: string; totalCostUsd: number; runCount: number; }
export interface BlockTypeUsageEntry { blockType: string; stepCount: number; failedCount: number; failureRate: number; }
export interface TriggerDistribution { manual: number; scheduled: number; webhook: number; completionTrigger: number; todoTrigger: number; }

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
  totalInputTokens: number;
  totalOutputTokens: number;
  topWorkflows: Array<{ workflowId: string; workflowName: string; runCount: number }>;
  recentRuns: WorkflowRun[];
  timeSeries?: TimeSeriesPoint[];
  providerStats?: ProviderStats[];
  modelStats?: ModelStats[];
  gateStatusSummary?: GateStatusEntry[];
  failureReasons?: FailureReasonEntry[];
  costByWorkflow?: CostByWorkflowEntry[];
  blockTypeUsage?: BlockTypeUsageEntry[];
  triggerDistribution?: TriggerDistribution;
}

// ── Platform Stats ───────────────────────────────────────

export interface SkillUsageEntry { skillId: string; skillName: string; category: string; workflowCount: number; }
export interface ChainStats { totalChains: number; totalRuns: number; successfulRuns: number; failedRuns: number; }
export interface TodoStats { open: number; inProgress: number; done: number; backlog: number; fromUser: number; fromAi: number; fromWorkflow: number; }
export interface WishStats { total: number; completed: number; failed: number; inProgress: number; }
export interface AssistantStats { totalSessions: number; avgMessagesPerSession: number; }

export interface PlatformStats {
  activeProjects: number;
  archivedProjects: number;
  totalWorkflows: number;
  workflowPoolSize: number;
  totalSkills: number;
  builtinSkills: number;
  customSkills: number;
  topSkillsByUsage: SkillUsageEntry[];
  totalTemplates: number;
  builtinTemplates: number;
  customTemplates: number;
  activeSchedules: number;
  totalSchedules: number;
  activeWebhooks: number;
  totalWebhooks: number;
  chainStats: ChainStats;
  activeTodoTriggers: number;
  activeCompletionTriggers: number;
  activeMemoryEntries: number;
  todoStats: TodoStats;
  wishStats: WishStats;
  assistantStats: AssistantStats;
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
export type TodoPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
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
  fixAttempts: number;
  lastFixRunId: string | null;
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
  triggeredByScheduleId?: string | null;
}

export interface ScheduleRunsResponse {
  workflowRuns: WorkflowRun[];
  chainRuns: ChainRun[];
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

// ── Dialog Import ────────────────────────────────────────

export interface DialogSessionInfo {
  sessionId: string;
  filePath: string;
  projectPath: string;
  startedAt: string | null;
  endedAt: string | null;
  turnCount: number;
  toolCallCount: number;
  dominantTools: string[];
  firstUserMessage: string | null;
  substantial: boolean;
}

export interface DialogWorkflowSuggestion {
  suggestedName: string | null;
  suggestedDescription: string | null;
  blocks: BlockDefinition[];
  sessionId: string | null;
  sourceFilePath: string | null;
  projectPath: string | null;
  confidence: number;
  patternType: string | null;
  notAvailableReason: string | null;
  toolCallsAnalyzed: number;
  available: boolean;
}

// ── API Error ────────────────────────────────────────────

export interface ApiError {
  error: string;
  timestamp: string;
  fieldErrors?: Record<string, string>;
}

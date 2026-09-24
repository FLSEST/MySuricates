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

export interface AutoMemoryConfig {
  enabled: boolean;
  interval?: number;
}

export interface ContextVariableSpec {
  required?: boolean;
  description?: string;
  placeholder?: string;
}

export interface WorkflowMetadata {
  autoTodo?: AutoTodoConfig;
  autoRepair?: AutoRepairConfig;
  autoMemoryExtraction?: AutoMemoryConfig;
  contextSchema?: Record<string, ContextVariableSpec>;
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
  effort: string | null;
  /** SHA-256 of the current definition (detail responses with provenance on) */
  definitionHash?: string | null;
  /** Sealed definitions reject changes and refuse to run if modified (automation.hardening.sealing) */
  sealed?: boolean;
  sealedHash?: string | null;
  sealedAt?: string | null;
  metadata?: WorkflowMetadata;
  defaultContext?: Record<string, string>;
  testStatus?: 'UNTESTED' | 'PASSED' | 'FAILED';
  lastTestedAt?: string | null;
  createdAt: string;
  updatedAt: string;
  stableSyncedAt?: string | null;
  tags?: string[];
  category?: string | null;
  tagsGeneratedAt?: string | null;
  tagsUserEdited?: boolean;
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
  effort?: EffortLevel | string;
  templateId?: string;
  metadata?: WorkflowMetadata;
}

/** Unified reasoning-effort levels (Claude --effort ∩ Pi --thinking). Empty string clears. */
export type EffortLevel = 'low' | 'medium' | 'high' | 'xhigh';

// ── Workflow Version ─────────────────────────────────────

export interface WorkflowVersion {
  id: string;
  workflowId: string;
  versionNumber: number;
  snapshot: string;
  changeSource: string;
  changeMessage: string | null;
  createdAt: string;
  definitionHash?: string | null;
}

export interface DashboardLayoutVersionSummary {
  id: string;
  createdAt: string;
  deviceLabel: string;
  name?: string | null;
  formatVersion: number;
  viewCount: number;
  contentHash: string;
}

export interface DashboardLayoutVersion extends DashboardLayoutVersionSummary {
  /** Stored layout; always run it through migrateLayout + sanitizeLayout before use. */
  layout: unknown;
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
  /** TEMPLATE = finished task; BLUEPRINT = empty scaffold showing how block types connect */
  kind?: 'TEMPLATE' | 'BLUEPRINT';
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
  effort?: EffortLevel | string;
  metadata?: Record<string, unknown>;
  defaultContext?: Record<string, string>;
  tags?: string[];
  category?: string;
}

/**
 * All block types the backend registers (one per BlockExecutor). Guarded on both sides:
 * backend `BlockTypeListsGuardTest` (executors vs. lists), frontend `types/__tests__/blockTypes.test.ts`.
 *
 * Since TODO 64966be0 the editor asks the backend instead (`useBlockTypes`, fed by the
 * `BlockTypeRegistry` via GET /system/capabilities). This list stays as the fallback for the
 * first paint, a failed request and an older backend — and as the source of the `BlockType`
 * union, so it must keep matching the backend.
 */
export const BLOCK_TYPES = [
  'simple', 'shell', 'retry', 'foreach', 'while', 'conditional', 'switch',
  'skill-capability', 'approval', 'agentic', 'sub-workflow', 'human-input',
  'integration', 'document', 'todo-create', 'todo-update', 'todo-close',
  'compress', 'compress-ai', 'dashboard-report',
] as const;

export type BlockType = (typeof BLOCK_TYPES)[number];

/**
 * Block types that issue AI calls and therefore honour the block-level `provider`/`model`
 * override in their config (precedence block > workflow > system default). Wrapper blocks
 * are included because their inline `command` runs through the AI provider unless shell mode.
 */
export const AI_BLOCK_TYPES: readonly BlockType[] = [
  'simple', 'agentic', 'skill-capability', 'retry', 'foreach', 'while', 'conditional', 'switch', 'compress-ai',
];

export interface BlockDefinition {
  type: BlockType;
  name?: string;
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
  /**
   * Kategorie des Eintrags: `repo` greift auf die Struktur dieses Repositories zu,
   * `portable` ist über `project_path` parametrisiert. `null` = nicht deklariert.
   * Regelwerk: docs/workflow-pool-kuratierung.md
   */
  scope: PoolScope | null;
  tags: string[];
  blocks: BlockDefinition[];
  instructions: string | null;
  model: string | null;
  version: string;
  author: string | null;
  defaultContext: Record<string, unknown> | null;
  filename: string;
}

export type PoolScope = 'repo' | 'portable';

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
  /** SHA-256 of the executed definition (provenance); null for old runs or provenance off */
  definitionHash?: string | null;
  definitionVersionId?: string | null;
  triggerSource?: TriggerSource | null;
  /** Seal state recorded at start: true = sealed and verified, false = not sealed, null/absent = older run */
  sealed?: boolean | null;
  /** Skill content hashes verified at start (detail route only) */
  sealedSkillPins?: Record<string, string> | null;
}

/** Seal state of a workflow, checked without a run (`GET /workflows/seal-status`). */
export interface WorkflowSealStatus {
  workflowId: string;
  workflowName: string;
  state: 'INTACT' | 'VIOLATED' | 'NOT_SEALED';
  problem: 'NONE' | 'DEFINITION_CHANGED' | 'SKILLS_CHANGED' | 'SIGNATURE_INVALID' | 'SIGNATURE_MISSING' | 'UNVERIFIABLE';
  detail: string | null;
  changedSkills: { id: string; name: string | null }[];
  /** false for seals made before skills were pinned */
  skillsPinned: boolean;
  /** whether the seal carries a signature (key outside the database) */
  signed: boolean;
  pinnedSkillCount: number;
  sealedAt: string | null;
  /** Starts refused in the last seven days — they leave no run row */
  refusedStarts: number;
  lastRefusedAt: string | null;
  lastRefusal: string | null;
}

/**
 * Eine Run-Zeile aus einer Listen-Route (`/executions`, `/workflows/{id}/runs`, `/schedules/{id}/runs`,
 * `/schedules/calendar`) — ohne die Schritte.
 *
 * Die Liste trug vorher jeden Step-Output mit: 3 MB Antwort bei 60 Runs, gemessen vom
 * RequestCostRatchetTest (MySuricates-TODO a05c9504). Statt der Schritte kommen jetzt ihre Anzahl
 * und die gekürzte Ausgabe des letzten. Wer den Volltext braucht, lädt den Run einzeln —
 * `/executions/{id}` liefert weiter alles.
 */
export interface WorkflowRunListItem extends Omit<WorkflowRun, 'steps'> {
  stepCount: number;
  lastStepStatus: StepStatus | null;
  /** Auf 500 Zeichen gekürzt; der Volltext steht in der Detail-Route. */
  lastStepPreview: string | null;
}

export type TriggerSource =
  | 'MANUAL' | 'RETRY' | 'RESUME' | 'TODO_RESOLUTION' | 'ASSISTANT'
  | 'SCHEDULE' | 'WEBHOOK' | 'TODO_TRIGGER' | 'COMPLETION_TRIGGER' | 'AUTO_REPAIR' | 'WISH'
  | 'CHAIN' | 'SUB_WORKFLOW';

export interface StepResult {
  id: string;
  stepIndex: number;
  /** Lifted from `metadata.blockType` by the backend; read it via `stepBlockType(step)` (older backends omit it). */
  blockType?: string | null;
  blockName?: string | null;
  input: string;
  output: string;
  status: StepStatus;
  durationMs: number | null;
  error: string | null;
  metadata?: Record<string, unknown>;
}

/**
 * A workflow whose most recent run failed — one entry per workflow, for the /debug page.
 * Lean: the error overview (message/step/blockType) comes from the run's error map; no steps.
 */
export interface BrokenWorkflow {
  workflowId: string | null;
  workflowName: string | null;
  projectName: string | null;
  runId: string;
  status: RunStatus;
  errorMessage: string | null;
  failedStepIndex: number | null;
  failedBlockType: string | null;
  startedAt: string;
  completedAt: string | null;
  durationMs: number | null;
  triggeredByScheduleId: string | null;
}

// ── Skill ────────────────────────────────────────────────

export type SkillCategory = 'DOCUMENT' | 'CODE' | 'DATA' | 'CONTENT' | 'RESEARCH' | 'BUSINESS' | 'PRODUCTIVITY';

export interface Skill {
  id: string;
  name: string;
  description: string | null;
  icon: string | null;
  category: SkillCategory;
  aiContext: string | null;
  configSchema: Record<string, unknown> | null;
  builtin: boolean;
  version: string;
  capabilities?: Capability[];
  createdAt: string;
}

/** The editable fields of a custom skill (`POST /skills`, `PUT /skills/{id}`). */
export interface SkillInput {
  name: string;
  description: string | null;
  icon: string | null;
  category: SkillCategory;
  aiContext: string | null;
}

/** One row of `GET /skills/{id}/versions`, newest first; the snapshot itself is not exposed. */
export interface SkillVersion {
  id: string;
  skillId: string;
  versionNumber: number;
  operation: 'CREATE' | 'UPDATE' | 'DELETE';
  changeSource: 'user' | 'import' | 'builtin-seed' | string;
  contentHash: string;
  createdAt: string;
}

/** A skill export file (`GET /skills/export/{id}`) — also the body of `POST /skills/import`. */
export interface SkillExportData {
  name: string;
  description?: string | null;
  icon?: string | null;
  category: SkillCategory;
  aiContext?: string | null;
  configSchema?: Record<string, unknown> | null;
  version?: string | null;
  capabilities?: Array<Record<string, unknown>>;
}

export interface Capability {
  id: string;
  skillId: string;
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown>;
  async: boolean;
  /**
   * If true, this capability writes to the project filesystem. Workflow blocks must set
   * `allow_writes: true` in the skill-capability block config to enable execution; otherwise
   * the backend fails the block fast.
   */
  writes: boolean;
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
  catchUpOnResume?: boolean;
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
  catchUpOnResume?: boolean;
}

export interface UpdateScheduleRequest {
  cronExpression?: string;
  description?: string;
  inputContext?: Record<string, unknown>;
  scheduledAt?: string;
  endDate?: string | null;
  maxRuns?: number | null;
  catchUpOnResume?: boolean;
  /** Editable after creation since ade3fef8; omitted = unchanged. */
  conflictBehavior?: 'SKIP' | 'OVERLAP';
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
  /** Listenzeilen ohne Schritte (MySuricates-TODO 168e3d7f). */
  pastRuns: WorkflowRunListItem[];
}

// ── Upcoming Schedule ────────────────────────────────────

/** Sequential catch-up queue of missed schedule occurrences (GET /schedules/catch-up). */
export interface CatchUpQueueStatus {
  active: { scheduleId: string; targetName: string; runId: string; startedAt: string } | null;
  pending: { scheduleId: string; targetName: string; missedAt: string | null }[];
}

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
  tokenVisible: boolean;
  description: string;
  defaultContext: Record<string, unknown>;
  enabled: boolean;
  webhookUrl: string;
  triggerCount: number;
  lastTriggeredAt: string | null;
  createdAt: string;
  /** Calls must carry an HMAC-SHA256 signature (the secret itself is never returned) */
  signatureRequired?: boolean;
}

/** Freshly generated signing secret — returned exactly once */
export interface WebhookSigningSecret {
  secret: string;
  header: string;
}

export interface CreateWebhookRequest {
  workflowId: string;
  description?: string;
  defaultContext?: Record<string, unknown>;
}

// ── Integrations ──────────────────────────────────────────

export interface ScopeOption {
  scope: string;
  label: string;
  granted: boolean;
}

export interface IntegrationProviderInfo {
  providerId: string;
  displayName: string;
  icon: string;
  description: string;
  status: 'CONNECTED' | 'DISCONNECTED' | 'EXPIRED';
  configured: boolean;
  /** Whether connecting requires non-empty credentials. Local providers (e.g. Taskwarrior) may be false. */
  requiresCredentials?: boolean;
  accountEmail: string | null;
  accountName: string | null;
  connectedSince: string | null;
  scopes: string | null;
  optionalScopes: ScopeOption[];
  actions: IntegrationActionInfo[];
  /** Whether this provider can receive a TODO as a task (GitHub issue, Jira issue, Trello card, …). */
  supportsTaskExport?: boolean;
}

/** A selectable export target (GitHub repo, Jira project, Trello list). */
export interface TaskTarget {
  id: string;
  label: string;
}

export interface IntegrationActionInfo {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  available: boolean;
}

// ── Unified Calendar ─────────────────────────────────────

export interface UnifiedCalendarEvent {
  id: string;
  title: string;
  start: Date;
  end?: Date;
  allDay: boolean;
  source: string;
  sourceLabel: string;
  color: string;
  status?: string;
  location?: string;
  link?: string;
}

export interface CalendarSourceConfig {
  sourceId: string;
  label: string;
  color: string;
  enabled: boolean;
}

export interface GoogleCalendarEventResponse {
  id: string;
  summary: string;
  description?: string;
  location?: string;
  status: string;
  start: { dateTime?: string; date?: string; timeZone?: string };
  end: { dateTime?: string; date?: string; timeZone?: string };
  attendees?: { email: string; displayName?: string; responseStatus: string }[];
  htmlLink?: string;
}

export interface GoogleEventListResponse {
  items: GoogleCalendarEventResponse[];
  nextPageToken?: string;
  summary?: string;
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
  /** Rate limit of this instance — the client's 429 backoff takes its parameters from here (37b98c97). */
  rateLimit?: {
    enabled: boolean;
    requestsPerMinute: number;
    globalBackoffSeconds: number;
    defaultRetryAfterSeconds: number;
  };
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
  chainsSynced?: number;
  skipped: number;
  errors: string[];
  preSyncBackup: string | null;
}

// ── Sync Preview / Selective Sync ─────────────────────────

export interface SyncPreview {
  projects: SyncPreviewItem[];
  workflows: SyncPreviewItem[];
  skills: SyncPreviewItem[];
  chains?: SyncPreviewItem[];
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
  chainIds?: string[];
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

export interface IntegrationStats {
  provider: string;
  runCount: number;
  totalTokens: number;
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
export interface TriggerDistribution {
  manual: number; scheduled: number; webhook: number; completionTrigger: number; todoTrigger: number;
  /** Chain, sub-workflow, wish, auto-repair — optional because older backends do not send it */
  otherAutomatic?: number;
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
  totalInputTokens: number;
  totalOutputTokens: number;
  topWorkflows: Array<{ workflowId: string; workflowName: string; runCount: number }>;
  recentRuns: WorkflowRun[];
  timeSeries?: TimeSeriesPoint[];
  providerStats?: ProviderStats[];
  integrationStats?: IntegrationStats[];
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
export type ActivityLogCategory = 'SYSTEM' | 'EXECUTION' | 'SCHEDULE' | 'WEBHOOK' | 'SECURITY' | 'CONTAINER' | 'PLUGIN';

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

export type TodoStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'BACKLOG' | 'ARCHIVED';
export type TodoPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type TodoSource = 'USER' | 'AI' | 'WORKFLOW';
export type TodoResolutionState = 'IDLE' | 'RUNNING' | 'FAILED' | 'SUCCEEDED';

export interface Todo {
  id: string;
  title: string;
  description: string | null;
  status: TodoStatus;
  priority: TodoPriority;
  tags: string[];
  projectId: string | null;
  projectName: string | null;
  linkedWorkflowId: string | null;
  linkedWorkflowName: string | null;
  source: TodoSource;
  sourceAgent: string | null;
  sessionId: string | null;
  /** UUID der Agenten-Konversation (`claude --resume <uuid>`) — nicht die Shell-Session. */
  agentSessionRef: string | null;
  workflowRunId: string | null;
  referenceType: string | null;
  referenceId: string | null;
  exportedProvider: string | null;
  exportedRef: string | null;
  exportedUrl: string | null;
  fixAttempts: number;
  lastFixRunId: string | null;
  resolutionState: TodoResolutionState | null;
  dueDate: string | null;
  createdAt: string;
  updatedAt: string;
  /** Ziel und Einschätzung (TODO dc0566f8); null/fehlt, solange nichts davon gesetzt ist. */
  planning?: TodoPlanning | null;
}

// ── Ziele (TODO dc0566f8) ─────────────────────────────────────────────────────

export type TodoComplexity = 'S' | 'M' | 'L' | 'XL';
export type ReasoningEffortLevel = 'low' | 'medium' | 'high' | 'xhigh';
export type RatedBy = 'USER' | 'AI';
export type GoalStatus = 'ACTIVE' | 'DONE' | 'ARCHIVED';

export interface TodoPlanning {
  goalId: string | null;
  goalTitle: string | null;
  goalRank: number | null;
  complexity: TodoComplexity | null;
  suggestedEffort: ReasoningEffortLevel | null;
  ratingNote: string | null;
  ratedBy: RatedBy | null;
  ratedAt: string | null;
  openQuestion: string | null;
}

/** `null`/fehlt = unverändert, leerer Text = leeren (`goalId: ''` löst das TODO vom Ziel). */
export interface UpdateTodoPlanningRequest {
  goalId?: string;
  goalRank?: number;
  complexity?: TodoComplexity | '';
  suggestedEffort?: ReasoningEffortLevel | '';
  ratingNote?: string;
  openQuestion?: string;
  ratedBy?: RatedBy;
}

export interface GoalProgress {
  total: number;
  open: number;
  inProgress: number;
  backlog: number;
  done: number;
  openQuestions: number;
  percentDone: number;
}

export interface Goal {
  id: string;
  title: string;
  description: string | null;
  status: GoalStatus;
  targetDate: string | null;
  createdAt: string;
  updatedAt: string;
  progress: GoalProgress;
}

export interface GoalTodoItem {
  id: string;
  title: string;
  status: TodoStatus;
  priority: TodoPriority;
  projectId: string | null;
  projectName: string | null;
  tags: string[];
  goalRank: number | null;
  complexity: TodoComplexity | null;
  suggestedEffort: ReasoningEffortLevel | null;
  ratingNote: string | null;
  ratedBy: RatedBy | null;
  ratedAt: string | null;
  openQuestion: string | null;
  updatedAt: string;
}

export interface GoalDetail {
  goal: Goal;
  /** Arbeitsreihenfolge: Rang, dann Priorität, dann Alter. */
  todos: GoalTodoItem[];
  byProject: { projectId: string | null; projectName: string | null; total: number; done: number }[];
}

export interface CreateGoalRequest {
  title: string;
  description?: string;
  /** `YYYY-MM-DD` oder ISO-Zeitpunkt. */
  targetDate?: string;
}

export interface UpdateGoalRequest {
  title?: string;
  description?: string;
  status?: GoalStatus;
  targetDate?: string;
}

export interface CreateTodoRequest {
  title: string;
  description?: string;
  status?: TodoStatus;
  priority?: TodoPriority;
  tags?: string[];
  projectId?: string;
  linkedWorkflowId?: string;
  source?: TodoSource;
  sourceAgent?: string;
  sessionId?: string;
  workflowRunId?: string;
  dueDate?: string;
}

export interface UpdateTodoRequest {
  title?: string;
  description?: string;
  status?: TodoStatus;
  priority?: TodoPriority;
  tags?: string[];
  projectId?: string;
  linkedWorkflowId?: string;
  dueDate?: string;
}

export interface TodoCountResponse {
  open: number;
  inProgress: number;
  done: number;
  backlog: number;
  archived: number;
}

// ── Todo Cockpit (cross-project overview) ───────────────
export interface NeedsYouItem {
  id: string;
  title: string;
  projectId: string | null;
  projectName: string | null;
  priority: TodoPriority;
  dueDate: string | null;
  source: TodoSource;
  createdAt: string;
  updatedAt: string;
  ageDays: number;
  overdue: boolean;
}

export interface ProjectRollup {
  projectId: string | null;
  projectName: string | null;
  open: number;
  inProgress: number;
  done: number;
  backlog: number;
  archived: number;
  staleCount: number;
  userActive: number;
  machineActive: number;
  needsYouCount: number;
}

export interface TodoOverviewResponse {
  projects: ProjectRollup[];
  needsYou: NeedsYouItem[];
  generatedAt: string;
  staleThresholdDays: number;
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
  /** Listenzeilen ohne Schritte (MySuricates-TODO 168e3d7f). */
  workflowRuns: WorkflowRunListItem[];
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

export type HumanInputFieldType = 'text' | 'number' | 'textarea' | 'select' | 'checkbox' | 'date';

export interface HumanInputField {
  variable_name: string;
  label?: string;
  input_type?: HumanInputFieldType;
  required?: boolean;
  default_value?: string;
  options?: string[];
  placeholder?: string;
  description?: string;
  validation_pattern?: string;
}

export interface PendingHumanInput {
  blockId: string;
  prompt: string;
  inputType: HumanInputFieldType;
  options: string[];
  defaultValue: string;
  required: boolean;
  validationPattern: string | null;
  variableName: string;
  stepIndex: number;
  fields?: HumanInputField[];
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
  autoRefine: boolean;
}

export interface CreateWishRequest {
  text: string;
  projectId?: string;
  maxIterations?: number;
  referenceWorkflowIds?: string[];
  autoRefine?: boolean;
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
  status: 'VERIFIED' | 'FAILED' | 'CREATED' | 'PROMOTED' | 'EXPORTED';
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

export interface ExportToPoolRequest {
  nameEn?: string;
  nameDe?: string;
  descriptionEn?: string;
  descriptionDe?: string;
  category?: string;
  tags?: string[];
  author?: string;
}

export interface ExportToPoolResponse {
  poolId: string;
  filename: string;
  /**
   * Stellen, die der Export als Instanz-Bindung entfernt hat (z. B. die `workflowId` eines
   * sub-workflow-Blocks). Leer, wenn nichts zu bereinigen war.
   */
  sanitizedPaths: string[];
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

// ── Modules (startup configuration, read-only) ──────────

export type FeatureModuleName =
  | 'dashboard-report'
  | 'chains' | 'assistant' | 'learning' | 'memory' | 'audit' | 'bpmn'
  | 'integrations' | 'webhooks' | 'remote-access' | 'terminal' | 'staging'
  | 'conversion' | 'dialog-import' | 'onboarding-profiles' | 'skills' | 'debug' | 'factory' | 'wishes';

export type HardeningModuleName =
  | 'provenance' | 'sealing' | 'require-sealed-for-unattended' | 'require-signed-seal' | 'seal-immutable'
  | 'webhook-signatures';

export type ModuleName = FeatureModuleName | HardeningModuleName;

export type ModulePreset = 'core' | 'research' | 'full';
export type ModuleTier = 'standard' | 'research' | 'advanced' | 'hardening';
export type ModuleMaturity = 'stable' | 'experimental' | 'legacy';
export type ModuleRisk = 'credentials' | 'network' | 'host-access';

export interface ModuleInfo {
  name: ModuleName;
  kind: 'feature' | 'hardening';
  enabled: boolean;
  tier: ModuleTier;
  maturity: ModuleMaturity;
  risks: ModuleRisk[];
  requires: ModuleName[];
  /** e.g. automation.modules.webhooks */
  property: string;
  /** e.g. AUTOMATION_MODULES_WEBHOOKS */
  envVar: string;
  description: string;
  /** State from the startup configuration alone */
  configured?: boolean;
  /** A settings-UI switch decides the state (only with automation.modules.ui-editable) */
  overridden?: boolean;
}

export interface SystemModules {
  preset: ModulePreset;
  /** automation.modules.ui-editable: feature modules can be switched in the settings */
  uiEditable: boolean;
  modules: ModuleInfo[];
}

// ── Request Metrics ──────────────────────────────────────

/**
 * What one API route cost over the current window. Percentiles, not averages: an average hides
 * the one slow request in fifty, which is the one people notice.
 */
export interface RequestMetricRoute {
  /** Route template with method, e.g. "GET /api/v1/todos" — never the URL with ids in it. */
  route: string;
  calls: number;
  p50Ms: number;
  p95Ms: number;
  maxMs: number;
  /** 95th percentile of the time spent queuing for the single pooled DB connection. */
  p95WaitMs: number;
  /** Share of total time spent waiting rather than working, 0..1. */
  waitShare: number;
  maxSqlStatements: number;
  avgSqlStatements: number;
  maxResponseBytes: number;
  avgResponseBytes: number;
}

// ── API Error ────────────────────────────────────────────

export interface ApiError {
  error: string;
  timestamp: string;
  fieldErrors?: Record<string, string>;
}

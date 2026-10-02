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

// ── Project Bundle ───────────────────────────────────────
// One project (workflows, chains, schedules) exported as a portable JSON file. Absolute paths are
// replaced by `@@path:N@@` tokens so the bundle can be re-imported under a different directory.
// `GET /projects/{id}/export`, `POST /projects/import/preview`, `POST /projects/import`.

export interface BundleProject {
  name: string;
  subtitle?: string | null;
  description?: string;
  type: ProjectType;
  aiContext?: string;
  config?: Record<string, unknown>;
}

/** Workflow definition inside a bundle — like a workflow export, minus id/projectId/seal fields. */
export interface BundleWorkflowDefinition {
  name: string;
  description?: string;
  icon?: string;
  blocks: BlockDefinition[];
  instructions?: string;
  model?: string;
  provider?: string;
  effort?: string;
  version?: string;
  defaultContext?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  /** Skills referenced by name (`activatedSkillIds` don't survive the move); missing on import → warning. */
  activatedSkillNames?: string[];
}

export interface BundleWorkflowEntry {
  /** Stable slug within the bundle, referenced by chain entries and `@@workflow:<key>@@` tokens. */
  key: string;
  definition: BundleWorkflowDefinition;
}

export interface BundleChainEntryStep {
  workflowKey: string;
  label?: string | null;
  sortOrder: number;
  stopOnFailure?: boolean | null;
  inputContext?: Record<string, unknown>;
  timeoutMinutes?: number | null;
}

export interface BundleChain {
  key: string;
  name: string;
  description?: string | null;
  icon?: string | null;
  stopOnFailure?: boolean | null;
  entries: BundleChainEntryStep[];
}

/** Exactly one of both is set (backend's `BundleTarget`); the preview turns it into `workflow:<key>`/`chain:<key>`. */
export interface BundleScheduleTarget {
  workflowKey: string | null;
  chainKey: string | null;
}

export interface BundleScheduleEntry {
  target: BundleScheduleTarget;
  cronExpression?: string | null;
  scheduleType: string;
  scheduledAt?: string | null;
  delayExpression?: string | null;
  description?: string;
  inputContext?: Record<string, unknown>;
  conflictBehavior?: string;
  catchUpOnResume?: boolean;
  maxRuns?: number | null;
  endDate?: string | null;
}

export interface BundlePathToken {
  /** `@@path:N@@` */
  token: string;
  /** Original absolute path on the exporting machine — kept as an editing suggestion, not scrubbed. */
  original: string;
  occurrences: number;
  /** First place the token was found, e.g. "Workflow-Name + Feld". */
  hint: string;
}

export interface ProjectBundle {
  format: 'mysuricates-project-bundle';
  formatVersion: number;
  exportedAt: string;
  appVersion: string;
  project: BundleProject;
  workflows: BundleWorkflowEntry[];
  chains: BundleChain[];
  schedules: BundleScheduleEntry[];
  paths: BundlePathToken[];
  skillNames: string[];
  /** Dangling `sub-workflow` references to workflows outside the bundle — left as-is on export. */
  warnings?: string[];
}

// ── Project Bundle: Import Preview ───────────────────────

export interface ProjectBundlePreviewWorkflow {
  key: string;
  name: string;
  blockCount: number;
  blockTypes: string[];
  providers: string[];
  models: string[];
  /** First 200 characters of every shell field, for the "these run with your rights" preview. */
  shellCommands: string[];
  nameTakenInTarget: boolean;
}

export interface ProjectBundlePreviewChain {
  key: string;
  name: string;
  entries: number;
}

export interface ProjectBundlePreviewSchedule {
  /** `workflow:<key>` or `chain:<key>` */
  target: string;
  cronExpression?: string | null;
  willBeDisabled: boolean;
}

export interface ProjectBundlePreview {
  project: { name: string; nameTaken: boolean };
  workflows: ProjectBundlePreviewWorkflow[];
  chains: ProjectBundlePreviewChain[];
  schedules: ProjectBundlePreviewSchedule[];
  paths: BundlePathToken[];
  warnings: string[];
}

/**
 * `POST /projects/import/preview` body. `targetProjectId` is optional: without it every
 * workflow's `nameTakenInTarget` reports `false` — once an existing target project is chosen in
 * the dialog, the preview is re-requested with it to get real per-workflow conflicts.
 */
export interface ProjectBundlePreviewRequest {
  bundle: ProjectBundle;
  targetProjectId?: string;
}

// ── Project Bundle: Import ───────────────────────────────

export type ProjectBundleImportTarget =
  | { mode: 'new'; name: string; path?: string }
  | { mode: 'existing'; projectId: string };

export type ProjectBundleConflictBehavior = 'skip' | 'rename';

export interface ProjectBundleImportRequest {
  bundle: ProjectBundle;
  project: ProjectBundleImportTarget;
  pathMappings: Record<string, string>;
  onConflict: ProjectBundleConflictBehavior;
}

export type ProjectBundleItemKind = 'workflow' | 'chain' | 'schedule';

export interface ProjectBundleSkippedItem {
  kind: ProjectBundleItemKind;
  key: string;
  reason: string;
}

export interface ProjectBundleRenamedItem {
  kind: ProjectBundleItemKind;
  key: string;
  from: string;
  to: string;
}

export interface ProjectBundleImportResult {
  projectId: string;
  /** Counts only what was newly created — not skipped or renamed items. */
  created: { workflows: number; chains: number; schedules: number };
  skipped: ProjectBundleSkippedItem[];
  renamed: ProjectBundleRenamedItem[];
  warnings: string[];
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
  /** Resume a run interrupted by a restart or standby without a click (TODO 90d10ba1). Part of the definition hash. */
  autoResume?: { enabled?: boolean };
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
  /** Optional modules its blocks need; the picker hides it while one is off. Absent on older backends. */
  requiredModules?: ModuleName[];
  /** Whether running it can call an AI model (server-side rule, see `getExecutionModes`). */
  needsAi?: boolean;
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
   * `portable` ist über `project_path` parametrisiert, `external` braucht ein Werkzeug außerhalb
   * von Repo und Projekt (steht in der Beschreibung). `null` = nicht deklariert.
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

export type PoolScope = 'repo' | 'portable' | 'external';

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
  /** Chain run this run is an entry of; null outside a chain and for runs from before V89 */
  chainRunId?: string | null;
  /** Seal state recorded at start: true = sealed and verified, false = not sealed, null/absent = older run */
  sealed?: boolean | null;
  /** Skill content hashes verified at start (detail route only) */
  sealedSkillPins?: Record<string, string> | null;
}

/** Seal state of a workflow, checked without a run (`GET /workflows/seal-status`). */
/** Severity of a definition finding: HIGH breaks the run or costs money, MEDIUM costs time or context, LOW is hygiene. */
export type AnalyzerSeverity = 'HIGH' | 'MEDIUM' | 'LOW';

/** One finding of the deterministic definition check (`GET /workflows/{id}/analyze`). */
export interface AnalyzerFinding {
  rule: string;
  severity: AnalyzerSeverity;
  /** 0-based index of the top-level block, -1 for the definition as a whole */
  blockIndex: number;
  blockName: string | null;
  /** What was found, in German (the field older backends send). */
  detail: string;
  /** The same sentence in English (TODO 81773d7b); absent on older backends — then `detail`. */
  detailEn?: string | null;
}

export interface WorkflowAnalysis {
  workflowId: string;
  workflowName: string;
  findingCount: number;
  bySeverity?: Partial<Record<AnalyzerSeverity, number>>;
  findings: AnalyzerFinding[];
}

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
  | 'SCHEDULE' | 'WEBHOOK' | 'TODO_TRIGGER' | 'COMPLETION_TRIGGER' | 'AUTO_REPAIR' | 'AUTO_RESUME' | 'WISH'
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
  /**
   * End of an active skip-until pause that holds the stored next run; `nextRunAt` is then the first
   * run after the pause (null for a one-off, which expires). Null otherwise (a776fd2a).
   */
  pausedUntil?: string | null;
  /**
   * The maintenance switch holds the stored next run: nothing starts until it is switched off, so
   * `nextRunAt` is null (7704b1e9).
   */
  maintenance?: boolean;
}

/** GET/POST /schedules/maintenance: the maintenance switch and an active skip-until pause. */
export interface SchedulerPauseStatus {
  enabled: boolean;
  pausedUntil?: string | null;
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
  /**
   * Alle Runs des Zeitraums; `pastRuns` sind die neuesten bis zum Limit (86777fb4). Fehlt bei älteren
   * Backends, die alle schicken.
   */
  pastRunsTotal?: number;
}

// ── Upcoming Schedule ────────────────────────────────────

/** Sequential catch-up queue of missed schedule occurrences (GET /schedules/catch-up). */
export interface CatchUpQueueStatus {
  active: { scheduleId: string; targetName: string; runId: string; startedAt: string } | null;
  pending: { scheduleId: string; targetName: string; missedAt: string | null }[];
}

export interface UpcomingScheduleData {
  count: number;
  /** End of an active skip-until pause; runs inside it are left out (a776fd2a). */
  pausedUntil?: string | null;
  /** The maintenance switch is on: nothing starts, so no run is listed (7704b1e9). */
  maintenance?: boolean;
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
  /** VERIFIED = checked against the real service; SIMULATED = only against simulated responses. */
  verification?: 'VERIFIED' | 'SIMULATED';
  /** What a VERIFIED provider was checked against; null for SIMULATED. */
  verifiedAgainst?: string | null;
  /**
   * Every stored connection of this provider, `default` first. The top-level status/account
   * fields describe the `default` connection. Missing on backends without named connections.
   */
  connections?: IntegrationConnectionInfo[];
  /** true: connecting runs the OAuth consent; false: the saved credentials are the auth (PAT/API key). */
  requiresOAuthFlow?: boolean;
  /**
   * true: works in workflows without a stored connection (cdccc1e5). Missing on older backends —
   * read it via `isConnectionOptional`.
   */
  connectionOptional?: boolean;
}

/** One named connection of an integration provider. */
export interface IntegrationConnectionInfo {
  name: string;
  status: 'CONNECTED' | 'DISCONNECTED' | 'EXPIRED';
  configured: boolean;
  accountEmail: string | null;
  accountName: string | null;
  connectedSince: string | null;
  scopes: string | null;
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
  /** Missing on an older backend (frontend deploys ahead of it) — treat as absent/false, never required. */
  inContainer?: boolean;
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
  model: string | null;
  requestedModel?: string | null;
  provider?: string | null;
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
  /** Input served from / written to Claude's prompt cache, apart from inputTokens; absent for other providers and older backends. */
  cacheReadInputTokens?: number;
  cacheCreationInputTokens?: number;
  /** Part of outputTokens spent on extended thinking (Claude); absent for other providers and older backends. */
  thinkingTokens?: number;
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
  /** Absent from backends before the cache figures (TODO 20adda30). */
  totalCacheReadInputTokens?: number;
  totalCacheCreationInputTokens?: number;
  /** Absent from backends before the thinking figures (TODO 20861200). */
  totalThinkingTokens?: number;
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
  /** Build-check findings from MEDIUM up, HIGH first (TODO 326d5987); German, absent on older backends. */
  buildFindings?: string[];
  /** The same lines in English (TODO 81773d7b); absent or empty on older backends — then `buildFindings`. */
  buildFindingsEn?: string[];
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
  /** Arbeitspaket im Ziel (c60bf914); null = keins. Fehlt bei einem älteren Backend. */
  workPackageId?: string | null;
  /** Dasselbe unter dem Namen eines älteren Backends (vor c60bf914 hieß das Arbeitspaket „milestone“). */
  milestoneId?: string | null;
  /** Entscheidung zur offenen Frage (68a5e24e). Fehlen die Felder, ist das Backend älter. */
  decision?: string | null;
  decidedAt?: string | null;
  decidedBy?: RatedBy | null;
  decisionState?: DecisionState;
}

/**
 * Zustand der Klärung eines TODOs (68a5e24e): QUESTION = eine offene Frage steht an (auch, wenn es
 * schon eine frühere Entscheidung gibt), DECIDED = entschieden und keine neue Frage, NONE = nichts.
 */
export type DecisionState = 'QUESTION' | 'DECIDED' | 'NONE';

/** `null`/fehlt = unverändert, leerer Text = leeren (`goalId: ''` löst das TODO vom Ziel). */
export interface UpdateTodoPlanningRequest {
  goalId?: string;
  goalRank?: number;
  complexity?: TodoComplexity | '';
  suggestedEffort?: ReasoningEffortLevel | '';
  ratingNote?: string;
  openQuestion?: string;
  ratedBy?: RatedBy;
  /** Arbeitspaket desselben Ziels; `''` nimmt es weg. */
  workPackageId?: string;
  /** Nur für ein älteres Backend (vor c60bf914): dort heißt das Feld noch so. */
  milestoneId?: string;
  /** Entscheidung (68a5e24e): Text leert die offene Frage, `''` nimmt die Entscheidung weg. */
  decision?: string;
}

export interface GoalProgress {
  total: number;
  open: number;
  inProgress: number;
  backlog: number;
  done: number;
  openQuestions: number;
  /** Entschiedene TODOs ohne neue Frage (68a5e24e). Fehlt bei einem älteren Backend. */
  decided?: number;
  percentDone: number;
}

/**
 * Ziel-Wächter (TODO 259f4f66): ein Workflow aus der Vorlage `goal-guard` mit täglichem Zeitplan.
 * `enabled` = der Zeitplan läuft; ausgeschaltet bleibt der Workflow (`workflowId`), nur pausiert.
 */
export interface GoalGuard {
  enabled: boolean;
  workflowId: string | null;
  scheduleId: string | null;
  nextRunAt: string | null;
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
  /** Nie null; `useGoal` ergänzt ihn für ein älteres Backend. */
  guard: GoalGuard;
  /** Ausdrücklich zugeordnete Projekte, nach Position (c60bf914). Fehlt bei einem älteren Backend. */
  projects?: GoalProjectRef[];
  /** Nächster nicht erreichter Meilenstein – für die Zielkarte. Fehlt bei einem älteren Backend. */
  nextMilestone?: GoalMilestoneRef | null;
}

/** Ein Meilenstein in der Zielliste: das Nötigste für die Karte. */
export interface GoalMilestoneRef {
  id: string;
  title: string;
  dueDate: string;
  state: MilestoneState;
}

/** Projekt eines Ziels in der Zielliste: nur Name und ID. */
export interface GoalProjectRef {
  id: string;
  name: string;
}

/** Projekt eines Ziels im Detail (c60bf914), mit Zählern aus den nicht archivierten TODOs des Ziels darin. */
export interface GoalProject {
  projectId: string;
  name: string;
  archived: boolean;
  position: number;
  total: number;
  done: number;
  openQuestions: number;
  /** Entschiedene TODOs (68a5e24e); `useGoal` ergänzt 0 bei einem älteren Backend. */
  decided: number;
}

/**
 * Zustand eines Arbeitspakets bzw. Meilensteins, beim Lesen berechnet: bald fällig = in höchstens
 * 3 Tagen. Ein Arbeitspaket ohne Zeitraum ist nur OPEN oder REACHED (Oberfläche: „abgeschlossen“).
 */
export type MilestoneState = 'OPEN' | 'DUE_SOON' | 'OVERDUE' | 'REACHED';
export type WorkPackageState = MilestoneState;

/**
 * Arbeitspaket eines Ziels (c60bf914): gehört genau einem Projekt des Ziels und bündelt dessen
 * TODOs; Reihenfolge im Projekt über `position`. Mit Zeitraum (`startDate` und `endDate`, beide oder
 * keins) steht es als Balken auf dem Zeitstrahl. Abgeschlossen, wenn alle seine TODOs erledigt sind.
 */
export interface GoalWorkPackage {
  id: string;
  goalId: string;
  /** null nur bei Altdaten oder wenn das Projekt gelöscht wurde. */
  projectId: string | null;
  title: string;
  /** Beginn (lokaler Tagesbeginn); null = ohne Zeitraum. */
  startDate: string | null;
  /** Ende (lokales Tagesende); null = ohne Zeitraum. */
  endDate: string | null;
  /** Reihenfolge innerhalb des Projekts. */
  position: number;
  /** Basis ist das Ende: bald fällig, überfällig, abgeschlossen. */
  state: WorkPackageState;
  /** Zugeordnete TODOs ohne ARCHIVED. */
  total: number;
  done: number;
  /** Nicht erledigte TODOs mit offener Frage. */
  openQuestions: number;
  /** Entschiedene TODOs (68a5e24e). */
  decided: number;
  /** Erster Abschluss; bleibt stehen. */
  completedAt: string | null;
  /** War schon abgeschlossen, ist es wegen eines neuen TODOs nicht mehr. */
  reopened: boolean;
  createdAt: string;
  updatedAt: string;
}

/** Ein Arbeitspaket mit Zeitraum – nur solche stehen als Balken auf dem Zeitstrahl. */
export type ScheduledWorkPackage = GoalWorkPackage & { startDate: string; endDate: string };

/**
 * Meilenstein eines Ziels (c60bf914): ein Termin fürs ganze Ziel (kein Projekt), als Raute oben im
 * Zeitstrahl. Mit verknüpften Arbeitspaketen erreicht, wenn alle abgeschlossen sind; ohne von Hand.
 */
export interface GoalMilestone {
  id: string;
  goalId: string;
  title: string;
  /** Fälligkeit (lokales Tagesende). */
  dueDate: string;
  state: MilestoneState;
  workPackageIds: string[];
  packagesTotal: number;
  packagesDone: number;
  /** Erstes Erreichen; bleibt stehen. */
  reachedAt: string | null;
  reopened: boolean;
  createdAt: string;
  updatedAt: string;
}

/** Zeitraum `startDate`/`endDate` als ISO-Zeitpunkt; ohne beide = ohne Zeitraum. `projectId` Pflicht, außer das Ziel hat genau ein Projekt. */
export interface CreateGoalWorkPackageRequest {
  title: string;
  projectId?: string;
  startDate?: string;
  endDate?: string;
}

/** Fehlendes Feld = unverändert, `''` leert (Beginn und Ende nur zusammen); `projectId` nur ohne TODOs. */
export interface UpdateGoalWorkPackageRequest {
  title?: string;
  projectId?: string;
  startDate?: string;
  endDate?: string;
}

export interface CreateGoalMilestoneRequest {
  title: string;
  dueDate: string;
  workPackageIds?: string[];
}

/** Fehlendes Feld = unverändert; `workPackageIds` ersetzt die Menge; `reached` nur ohne verknüpfte Pakete. */
export interface UpdateGoalMilestoneRequest {
  title?: string;
  dueDate?: string;
  workPackageIds?: string[];
  reached?: boolean;
}

/** `POST /goals/{id}/guard`; Projekt, Zeitplan und Sprache zählen nur beim ersten Einschalten. */
export interface GoalGuardRequest {
  enabled: boolean;
  projectId?: string;
  /** Spring-CRON mit Sekunden, Vorgabe `0 0 8 * * *`. */
  cron?: string;
  locale?: string;
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
  /** Fälligkeit (TODO 259f4f66); null = ungeplant. */
  dueDate: string | null;
  /** Arbeitspaket des Ziels (c60bf914); null = keins. */
  workPackageId: string | null;
  /** Entscheidung zur (früheren) offenen Frage (68a5e24e). */
  decision: string | null;
  decidedAt: string | null;
  decidedBy: RatedBy | null;
  decisionState: DecisionState;
}

export interface GoalProjectProgress {
  projectId: string | null;
  projectName: string | null;
  total: number;
  done: number;
}

export interface GoalDetail {
  goal: Goal;
  /** Arbeitsreihenfolge: Rang, dann Priorität, dann Alter. */
  todos: GoalTodoItem[];
  byProject: GoalProjectProgress[];
  /** Ausdrücklich zugeordnete Projekte nach Position; `normalizeGoalDetail` leitet sie bei einem älteren Backend aus `byProject` ab. */
  projects: GoalProject[];
  /** Nach Projekt-Position, dann Paket-Position. */
  workPackages: GoalWorkPackage[];
  /** Nach Fälligkeit, dann Titel. */
  milestones: GoalMilestone[];
  /**
   * Gesetzt von `normalizeGoalDetail`: Die Antwort kam von einem Backend vor c60bf914 (ohne
   * `workPackages`). Meilensteine gibt es dort noch nicht, die Oberfläche blendet sie aus.
   */
  legacyBackend?: boolean;
  /**
   * Gesetzt von `normalizeGoalDetail`: Das Backend kennt Entscheidungen (68a5e24e). Ohne sie gibt es
   * kein „Entscheiden“ – ein älteres Backend ließe das Feld stillschweigend fallen.
   */
  decisionsSupported?: boolean;
}

/**
 * Rolle eines Workflows am Ziel (52bc1e8c): WORKER „arbeitet daran“, STATUS „liefert den Stand“
 * (sein Ergebnis ist eine Ampel mit Kennzahlen). Anzeigen und Starten gehen bei beiden.
 */
export type GoalWorkflowRole = 'WORKER' | 'STATUS';

/**
 * Ein Workflow am Ziel oder an einem Arbeitspaket (52bc1e8c, `GET /goals/{id}/workflows`). Der
 * Wächter steht als erster Eintrag mit `id: "guard"` und `guard: true` darin – lösen lässt er sich
 * nur über seinen Schalter.
 */
export interface GoalWorkflow {
  id: string;
  goalId: string;
  /** null = am ganzen Ziel. */
  workPackageId: string | null;
  workflowId: string;
  workflowName: string;
  workflowProjectId: string | null;
  role: GoalWorkflowRole;
  guard: boolean;
  createdAt?: string;
}

/** Relationskarte eines Ziels (TODO c6ea8f5c): Herkunft → TODO → Bearbeitung → Ergebnis. */
export type GoalOriginKind = 'AUDIT' | 'HEALTH' | 'WORKFLOW_RUN' | 'AGENT' | 'USER';

export interface GoalTodoOrigin {
  kind: GoalOriginKind;
  workflowId: string | null;
  workflowName: string | null;
  runId: string | null;
  runStatus: RunStatus | null;
  findingIds: string[];
  agent: string | null;
}

export interface GoalTodoWorker {
  /** `LINKED`: Fix-Workflow am TODO; `TRIGGER`: ein aktiver TodoTrigger passt. */
  kind: 'LINKED' | 'TRIGGER';
  workflowId: string;
  workflowName: string;
  triggerId: string | null;
  triggerLabel: string | null;
}

export interface GoalTodoResult {
  runId: string | null;
  workflowId: string | null;
  runStatus: RunStatus | null;
  /** Ende des Laufs, solange er läuft sein Start. */
  at: string | null;
  fixAttempts: number;
  resolutionState: TodoResolutionState | null;
}

export interface GoalTodoRelation {
  id: string;
  title: string;
  status: TodoStatus;
  priority: TodoPriority;
  projectId: string | null;
  projectName: string | null;
  tags: string[];
  goalRank: number | null;
  /** Noch keinem Ziel zugeordnet, nur als Vorschlag in der Karte. */
  candidate: boolean;
  origin: GoalTodoOrigin;
  workers: GoalTodoWorker[];
  /** Fehlt, solange nie ein Fix-Lauf gestartet wurde. */
  result: GoalTodoResult | null;
}

export interface GoalRelations {
  goalId: string;
  /** Aktive TodoTrigger insgesamt; 0 heißt, kein TODO kann per Trigger andocken. */
  activeTriggers: number;
  /** TODOs des Ziels in Arbeitsreihenfolge, danach die Kandidaten. */
  todos: GoalTodoRelation[];
}

export interface CreateGoalRequest {
  title: string;
  description?: string;
  /** `YYYY-MM-DD` oder ISO-Zeitpunkt. */
  targetDate?: string;
  /** Projekte, denen das Ziel gleich zugeordnet wird. */
  projectIds?: string[];
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

/**
 * Ein Termin in „Braucht dich“ (Feldname `milestonesDue` bleibt): ein Meilenstein, bald fällig oder
 * überfällig, oder ein Arbeitspaket, dessen Ende vorbei ist – mit dem Ziel für den Link. `kind`
 * fehlt bei einem älteren Backend; dessen Einträge sind Arbeitspakete.
 */
export interface MilestoneAttentionItem {
  id: string;
  kind?: 'MILESTONE' | 'WORK_PACKAGE';
  title: string;
  goalId: string;
  goalTitle: string;
  dueDate: string;
  state: MilestoneState;
  total: number;
  done: number;
}

export interface TodoOverviewResponse {
  projects: ProjectRollup[];
  needsYou: NeedsYouItem[];
  generatedAt: string;
  staleThresholdDays: number;
  /** Meilensteine aktiver Ziele, bald fällig oder überfällig (TODO 259f4f66); fehlt bei einem älteren Backend. */
  milestonesDue?: MilestoneAttentionItem[];
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
  timeoutMinutes?: number | null;
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
  timeoutMinutes?: number;
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
  /** What started the chain (V96); null for older chain runs, absent on older backends */
  triggerSource?: TriggerSource | null;
}

export interface ScheduleRunsResponse {
  /** Listenzeilen ohne Schritte (MySuricates-TODO 168e3d7f). */
  workflowRuns: WorkflowRunListItem[];
  chainRuns: ChainRun[];
  /** Alle Runs des Zeitplans; die Listen sind die neuesten bis zum Limit (86777fb4). Fehlen bei älteren Backends. */
  workflowRunsTotal?: number;
  chainRunsTotal?: number;
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
  /** Build-check findings from MEDIUM up, HIGH first (TODO 326d5987); German, absent on older backends. */
  buildFindings?: string[];
  /** The same lines in English (TODO 81773d7b); absent or empty on older backends — then `buildFindings`. */
  buildFindingsEn?: string[];
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
  /** Build-check findings from MEDIUM up, HIGH first (TODO 326d5987); German, absent on older backends. */
  buildFindings?: string[];
  /** The same lines in English (TODO 81773d7b); absent or empty on older backends — then `buildFindings`. */
  buildFindingsEn?: string[];
}

// ── Modules (startup configuration, read-only) ──────────

export type FeatureModuleName =
  | 'dashboard-report' | 'goals'
  | 'chains' | 'assistant' | 'learning' | 'memory' | 'audit' | 'bpmn'
  | 'integrations' | 'webhooks' | 'remote-access' | 'terminal' | 'staging'
  | 'conversion' | 'dialog-import' | 'onboarding-profiles' | 'skills' | 'debug' | 'factory' | 'wishes';

export type HardeningModuleName =
  | 'provenance' | 'sealing' | 'require-sealed-for-unattended' | 'require-signed-seal' | 'seal-immutable'
  | 'webhook-signatures' | 'private-network-guard' | 'private-network-guard-http-api';

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
  /** Stable error code of the backend (`ErrorCode`), e.g. `WORKFLOW_SEALED`. */
  code?: string;
  /** Present when code + params describe the error completely — the UI then translates the code. */
  params?: Record<string, string>;
  /** Backend `ErrorMessage` key when the code alone does not tell which message this is. */
  messageKey?: string;
  fieldErrors?: Record<string, string>;
}

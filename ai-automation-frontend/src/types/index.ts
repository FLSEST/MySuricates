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
}

export interface UpdateWorkflowRequest {
  name?: string;
  description?: string;
  icon?: string;
  blocks?: BlockDefinition[];
}

export type BlockType = 'simple' | 'retry' | 'foreach' | 'while' | 'conditional' | 'skill-capability';

export interface BlockDefinition {
  type: BlockType;
  config: Record<string, unknown>;
  requiredCapabilities?: string[];
}

// ── Workflow Run ─────────────────────────────────────────

export type RunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type StepStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';

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
  cronExpression: string;
  description?: string;
  inputContext?: Record<string, unknown>;
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
  java: string;
  tailscale: string;
}

export interface RemoteAccessStatus {
  enabled: boolean;
  ip: string | null;
  hostname: string | null;
}

// ── Learning / Analytics ─────────────────────────────────

export interface DashboardData {
  totalWorkflows: number;
  totalRuns: number;
  successfulRuns: number;
  failedRuns: number;
  averageDurationMs: number;
  successRate: number;
  topWorkflows: Array<{ workflowId: string; workflowName: string; runCount: number }>;
  recentRuns: WorkflowRun[];
}

export interface WorkflowStats {
  workflowId: string;
  totalRuns: number;
  successfulRuns: number;
  failedRuns: number;
  averageDurationMs: number;
  successRate: number;
}

// ── API Error ────────────────────────────────────────────

export interface ApiError {
  error: string;
  timestamp: string;
  fieldErrors?: Record<string, string>;
}

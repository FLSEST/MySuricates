# @mysuricates/sdk

Typed TypeScript client SDK for the MySuricates AI Automation API. Provides full coverage of every REST endpoint exposed by the Spring Boot backend, with strongly-typed request/response DTOs, enum values, and structured error classes.

## Installation

```bash
npm install @mysuricates/sdk
```

Or with yarn:

```bash
yarn add @mysuricates/sdk
```

## Configuration

```typescript
import { MySuricatesClient } from "@mysuricates/sdk";

const client = new MySuricatesClient({
  baseUrl: "http://localhost:9090",
  apiKey: "your-api-key",       // optional, only needed when security is enabled
  timeout: 30000,               // optional, default 30s
  headers: {                    // optional extra headers
    "X-Custom-Header": "value",
  },
});
```

### Configuration Options

| Option     | Type                     | Required | Default | Description                            |
|------------|--------------------------|----------|---------|----------------------------------------|
| `baseUrl`  | `string`                 | yes      | --      | Backend URL, e.g. `http://localhost:9090` |
| `apiKey`   | `string`                 | no       | --      | API key sent as `X-API-Key` header     |
| `timeout`  | `number`                 | no       | 30000   | Request timeout in milliseconds        |
| `headers`  | `Record<string, string>` | no       | `{}`    | Additional headers for every request   |

## Usage Examples

### Todos

```typescript
import {
  MySuricatesClient,
  TodoStatus,
  TodoPriority,
  TodoSource,
} from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// List all todos (with optional filters)
const todos = await client.listTodos({
  status: TodoStatus.OPEN,
  priority: TodoPriority.HIGH,
  source: TodoSource.AI,
  projectId: "proj-123",
  tag: "urgent",
  q: "search term",
});

// Create a todo
const todo = await client.createTodo({
  title: "Review pull request",
  description: "Review PR #42 for the auth module",
  priority: TodoPriority.HIGH,
  tags: ["review", "auth"],
  projectId: "proj-123",
  source: TodoSource.USER,
});

// Get a single todo
const fetched = await client.getTodo(todo.id);

// Update a todo
const updated = await client.updateTodo(todo.id, {
  title: "Review pull request #42",
  priority: TodoPriority.MEDIUM,
});

// Update only the status
await client.updateTodoStatus(todo.id, { status: TodoStatus.DONE });

// Delete a todo
await client.deleteTodo(todo.id);

// Get todo counts by status
const counts = await client.countTodos();
console.log(`Open: ${counts.open}, In Progress: ${counts.inProgress}, Done: ${counts.done}`);
```

### Projects

```typescript
import { MySuricatesClient, ProjectType } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// List all projects
const projects = await client.listProjects();

// Create a project
const project = await client.createProject({
  name: "My Web App",
  subtitle: "Next.js frontend",
  description: "Production web application",
  type: ProjectType.CODE,
  path: "/home/user/projects/my-web-app",
  config: { language: "typescript" },
});

// Get a single project
const fetched = await client.getProject(project.id);

// Update a project
const updated = await client.updateProject(project.id, {
  name: "My Web App v2",
  description: "Updated description",
});

// Start / stop a project (manages Docker container)
await client.startProject(project.id);
await client.stopProject(project.id);

// Get all workflows belonging to a project
const workflows = await client.getProjectWorkflows(project.id);

// Delete a project
await client.deleteProject(project.id);
```

### Workflows

```typescript
import { MySuricatesClient } from "@mysuricates/sdk";
import type { BlockDefinition } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// List all workflows
const workflows = await client.listWorkflows();

// Create a workflow with blocks
const blocks: BlockDefinition[] = [
  {
    type: "simple",
    config: { prompt: "Analyze the codebase structure" },
    requiredCapabilities: [],
  },
  {
    type: "conditional",
    config: {
      condition: "{{ previous_output }} contains 'error'",
      thenPrompt: "Fix the errors found",
      elsePrompt: "Generate a summary report",
    },
    requiredCapabilities: [],
  },
];

const workflow = await client.createWorkflow({
  projectId: "proj-123",
  name: "Code Analysis Pipeline",
  description: "Analyze and report on code quality",
  icon: "magnifying-glass",
  blocks,
  instructions: "Focus on TypeScript files only",
  model: "claude-sonnet-4-6",
  activatedSkillIds: ["builtin-code-review"],
});

// Get a single workflow
const fetched = await client.getWorkflow(workflow.id);

// Update a workflow
const updated = await client.updateWorkflow(workflow.id, {
  name: "Code Analysis Pipeline v2",
  blocks: [...blocks, {
    type: "simple",
    config: { prompt: "Generate final report" },
    requiredCapabilities: [],
  }],
});

// Execute a workflow (optionally with context variables)
const run = await client.executeWorkflow(workflow.id, {
  context: { targetDir: "src/", maxFiles: 50 },
});

// Get all runs for a workflow
const runs = await client.getWorkflowRuns(workflow.id);

// Get a specific run
const runDetail = await client.getWorkflowRun(workflow.id, run.id);

// Optimize a workflow (AI-powered suggestions)
const optimizations = await client.optimizeWorkflow(workflow.id);

// Activate skills on a workflow
await client.activateSkills(workflow.id, {
  skillIds: ["builtin-code-review", "builtin-test-generator"],
});

// Deactivate a skill from a workflow
await client.deactivateSkill(workflow.id, "builtin-code-review");

// Export a workflow (portable JSON)
const exported = await client.exportWorkflow(workflow.id);

// Import a workflow
const imported = await client.importWorkflow({
  projectId: "proj-456",
  name: "Imported Workflow",
  description: exported.description ?? undefined,
  blocks: exported.blocks,
  instructions: exported.instructions ?? undefined,
  model: exported.model ?? undefined,
});

// Delete a workflow
await client.deleteWorkflow(workflow.id);
```

### Skills

```typescript
import { MySuricatesClient, SkillCategory } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// List all skills (built-in + custom)
const skills = await client.listSkills();

// Get a single skill
const skill = await client.getSkill("builtin-code-review");

// Get capabilities of a skill
const capabilities = await client.getSkillCapabilities("builtin-code-review");

// Create a custom skill
const custom = await client.createSkill({
  name: "My Custom Linter",
  description: "Custom linting skill for our codebase",
  icon: "wand",
  category: SkillCategory.CODE,
  aiContext: "You are a code linter specialized in our internal style guide.",
  configSchema: { severity: { type: "string", default: "warning" } },
});

// Export a skill (portable JSON)
const exported = await client.exportSkill(custom.id);

// Import a skill (with capabilities)
const imported = await client.importSkill({
  name: "Imported Linter",
  description: "Re-imported linting skill",
  category: SkillCategory.CODE,
  version: "1.0.0",
  capabilities: [
    {
      name: "lint",
      description: "Run linting on source files",
      handlerClass: "LintHandler",
      inputSchema: { files: { type: "array" } },
      outputSchema: { issues: { type: "array" } },
      async: false,
    },
  ],
});

// Delete a custom skill (built-in skills cannot be deleted)
await client.deleteSkill(custom.id);
```

### Executions

```typescript
import { MySuricatesClient, RunStatus } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// List all executions (with optional filters)
const executions = await client.listExecutions({
  status: RunStatus.RUNNING,
  workflowId: "wf-123",
  projectId: "proj-123",
});

// Get a single execution
const execution = await client.getExecution("run-456");

// Pause / resume / cancel an execution
await client.pauseExecution("run-456");
await client.resumeExecution("run-456");
await client.cancelExecution("run-456");

// Check for pending approval gate
const approval = await client.getPendingApproval("run-456");
if (approval.pending) {
  console.log(`Approval needed: ${approval.question}`);
  console.log(`Options: ${approval.options?.join(", ")}`);

  // Approve with an answer
  await client.approveExecution("run-456", "yes");
}

// Execute a command inside a running execution's container
const result = await client.executeCommand("run-456", "ls -la /workspace");
console.log(result.output);
```

### Schedules

```typescript
import { MySuricatesClient } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// List all schedules
const schedules = await client.listSchedules();

// Create a cron schedule (runs daily at 8 AM)
const schedule = await client.createSchedule({
  workflowId: "wf-123",
  cronExpression: "0 8 * * *",
  description: "Daily code analysis",
  inputContext: { scope: "full" },
  scheduleType: "CRON",
});

// Create a one-time schedule
const once = await client.createSchedule({
  workflowId: "wf-123",
  description: "One-time deployment check",
  scheduleType: "ONCE",
  scheduledAt: "2026-03-01T10:00:00Z",
});

// Get a single schedule
const fetched = await client.getSchedule(schedule.id);

// Get schedules for a specific workflow
const wfSchedules = await client.getSchedulesByWorkflow("wf-123");

// Enable / disable a schedule
await client.disableSchedule(schedule.id);
await client.enableSchedule(schedule.id);

// Get upcoming runs
const today = await client.getRemainingToday();
const week = await client.getRemainingThisWeek();
console.log(`${today.count} runs remaining today`);
console.log(`${week.count} runs remaining this week`);

// Get calendar data for a date range
const calendar = await client.getCalendarData({
  from: "2026-02-01",
  to: "2026-02-28",
});
console.log(`Upcoming: ${calendar.upcoming.length}, Past: ${calendar.pastRuns.length}`);

// Delete a schedule
await client.deleteSchedule(schedule.id);
```

### Webhooks

```typescript
import { MySuricatesClient } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// List all webhooks
const webhooks = await client.listWebhooks();

// Create a webhook
const webhook = await client.createWebhook({
  workflowId: "wf-123",
  description: "GitHub push trigger",
  defaultContext: { branch: "main" },
});

console.log(`Webhook URL: ${webhook.webhookUrl}`);
console.log(`Secret token: ${webhook.secretToken}`);

// Get a single webhook
const fetched = await client.getWebhook(webhook.id);

// Get webhooks for a workflow
const wfWebhooks = await client.getWebhooksByWorkflow("wf-123");

// Enable / disable a webhook
await client.disableWebhook(webhook.id);
await client.enableWebhook(webhook.id);

// Trigger a webhook externally (using the secret token)
const run = await client.triggerWebhook(webhook.secretToken, {
  commit: "abc123",
  author: "dev@example.com",
});

// Delete a webhook
await client.deleteWebhook(webhook.id);
```

### Workflow Templates

```typescript
import { MySuricatesClient } from "@mysuricates/sdk";
import type { BlockDefinition } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// List all templates (built-in + custom)
const templates = await client.listWorkflowTemplates();

// Get a single template
const template = await client.getWorkflowTemplate("tmpl-123");

// Create a custom template (i18n name/description)
const custom = await client.createWorkflowTemplate({
  name: { en: "Quick Deploy", de: "Schnelles Deployment" },
  description: {
    en: "Deploy application to staging",
    de: "Anwendung auf Staging deployen",
  },
  icon: "rocket",
  category: "deployment",
  blocks: [
    {
      type: "simple",
      config: { prompt: "Run deployment script" },
      requiredCapabilities: [],
    },
  ],
  instructions: "Ensure all tests pass before deploying",
  model: "claude-sonnet-4-6",
});

// Update a template
await client.updateWorkflowTemplate(custom.id, {
  category: "ci-cd",
  sortOrder: 5,
});

// Create a template from an existing workflow
const fromWorkflow = await client.createTemplateFromWorkflow("wf-123");

// Delete a custom template (built-in templates cannot be deleted)
await client.deleteWorkflowTemplate(custom.id);
```

### Learning / Analytics

```typescript
import { MySuricatesClient } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// Get the main dashboard
const dashboard = await client.getDashboard();
console.log(`Total workflows: ${dashboard.totalWorkflows}`);
console.log(`Success rate: ${dashboard.successRate}%`);
console.log(`Runs today: ${dashboard.runsToday}`);
console.log(`Cost (30d): $${dashboard.totalCostUsd30d}`);

// Get stats for a specific workflow
const stats = await client.getWorkflowStats("wf-123");

// Get step-level analytics
const stepAnalytics = await client.getStepAnalytics("wf-123");

// Get AI-powered optimization suggestions
const suggestions = await client.getOptimizationSuggestions("wf-123");

// Find similar workflows
const similar = await client.getSimilarWorkflows("wf-123");

// Get agentic block analytics
const agenticHistory = await client.getAgenticStrategyHistory("wf-123");
const agenticAnalytics = await client.getAgenticAnalytics("wf-123");

// Get tool usage patterns
const toolPatterns = await client.getToolPatterns("wf-123");

// Get execution insights
const insights = await client.getExecutionInsights("wf-123");
const runInsights = await client.getRunInsights("run-456");

// Rate a run (1-5)
await client.rateRun("run-456", 5);
```

### Conversions

```typescript
import { MySuricatesClient } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// Get a workflow suggestion from an agentic run
const suggestion = await client.suggestWorkflow("run-456");

// Create a deterministic workflow from a successful agentic run
const workflow = await client.createWorkflowFromRun(
  "run-456",
  "proj-123",
  "Converted Workflow",
);
```

### System

```typescript
import { MySuricatesClient } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// Check if API key authentication is enabled
const authStatus = await client.getAuthStatus();
console.log(`Security enabled: ${authStatus.securityEnabled}`);

// Health check
const health = await client.getHealth();
console.log(`Status: ${health.status}, Docker: ${health.docker}`);

// System info
const info = await client.getSystemInfo();
console.log(`${info.name} v${info.version} (${info.buildType})`);

// Remote access (Tailscale)
const remoteStatus = await client.getRemoteAccessStatus();
await client.enableRemoteAccess();
await client.disableRemoteAccess();

// Browse server directories
const dir = await client.browseDirectory("/home/user/projects");
console.log(`Current: ${dir.current}`);
dir.directories.forEach((d) => console.log(`  ${d.name} -> ${d.path}`));
```

### Activity Log

```typescript
import { MySuricatesClient, LogLevel, LogCategory } from "@mysuricates/sdk";

const client = new MySuricatesClient({ baseUrl: "http://localhost:9090" });

// Get paginated activity logs (with optional filters)
const page = await client.getActivityLogs({
  page: 0,
  size: 25,
  level: LogLevel.WARN,
  category: LogCategory.EXECUTION,
});

console.log(`Page ${page.page + 1} of ${page.totalPages} (${page.totalElements} total)`);
page.content.forEach((log) => {
  console.log(`[${log.level}] ${log.category}: ${log.message}`);
});
```

## Error Handling

The SDK throws structured errors for different failure scenarios:

```typescript
import {
  MySuricatesClient,
  ApiError,
  NotFoundError,
  ValidationError,
  UnauthorizedError,
  NetworkError,
  TimeoutError,
} from "@mysuricates/sdk";

const client = new MySuricatesClient({
  baseUrl: "http://localhost:9090",
  timeout: 10000,
});

try {
  const workflow = await client.getWorkflow("non-existent-id");
} catch (err) {
  if (err instanceof NotFoundError) {
    // 404 - Resource not found
    console.error(`Not found: ${err.url}`);
    console.error(`Response body:`, err.body);
  } else if (err instanceof ValidationError) {
    // 400 - Bad request / validation failure
    console.error(`Validation error:`, err.body);
  } else if (err instanceof UnauthorizedError) {
    // 401/403 - Authentication/authorization failure
    console.error(`Unauthorized: ${err.url}`);
  } else if (err instanceof TimeoutError) {
    // Request exceeded the configured timeout
    console.error(`Timed out after ${err.timeoutMs}ms: ${err.url}`);
  } else if (err instanceof NetworkError) {
    // Network-level failure (DNS, connection refused, etc.)
    console.error(`Network error: ${err.message}`);
    if (err.cause) {
      console.error(`Cause:`, err.cause);
    }
  } else if (err instanceof ApiError) {
    // Any other HTTP error (5xx, etc.)
    console.error(`API error ${err.status}: ${err.statusText}`);
    console.error(`URL: ${err.url}`);
    console.error(`Body:`, err.body);
  }
}
```

### Error Hierarchy

| Error Class        | HTTP Status | Description                        |
|--------------------|-------------|------------------------------------|
| `ValidationError`  | 400         | Bad request / validation failure   |
| `UnauthorizedError`| 401, 403    | Missing or invalid API key         |
| `NotFoundError`    | 404         | Resource not found                 |
| `ApiError`         | any         | Base class for all HTTP errors     |
| `NetworkError`     | --          | Connection/DNS/network failure     |
| `TimeoutError`     | --          | Request exceeded timeout           |

## TypeScript Support

All request and response types are exported from the package:

```typescript
import type {
  WorkflowResponse,
  WorkflowRunResponse,
  TodoResponse,
  ProjectResponse,
  SkillResponse,
  BlockDefinition,
} from "@mysuricates/sdk";
```

Enums are exported as both values and types:

```typescript
import {
  TodoStatus,
  TodoPriority,
  RunStatus,
  ProjectType,
  SkillCategory,
  LogLevel,
  LogCategory,
} from "@mysuricates/sdk";
```

## License

MIT

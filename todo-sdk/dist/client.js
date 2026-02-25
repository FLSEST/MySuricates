"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MySuricatesClient = void 0;
const errors_1 = require("./errors");
// ── Helpers ────────────────────────────────────────────────────────────────────
function buildQuery(params) {
    const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null);
    if (entries.length === 0)
        return "";
    const qs = entries
        .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
        .join("&");
    return `?${qs}`;
}
// ── Client ─────────────────────────────────────────────────────────────────────
class MySuricatesClient {
    constructor(config) {
        this.baseUrl = config.baseUrl.replace(/\/+$/, "");
        this.apiKey = config.apiKey;
        this.timeout = config.timeout ?? 30000;
        this.extraHeaders = config.headers ?? {};
    }
    // ── Low-level fetch ────────────────────────────────────────────────────────
    async request(method, path, body) {
        const url = `${this.baseUrl}${path}`;
        const headers = {
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
        let res;
        try {
            res = await fetch(url, {
                method,
                headers,
                body: body !== undefined ? JSON.stringify(body) : undefined,
                signal: controller.signal,
            });
        }
        catch (err) {
            if (err instanceof DOMException && err.name === "AbortError") {
                throw new errors_1.TimeoutError(url, this.timeout);
            }
            throw new errors_1.NetworkError(`Request failed: ${url}`, err instanceof Error ? err : undefined);
        }
        finally {
            clearTimeout(timer);
        }
        if (res.status === 204) {
            return undefined;
        }
        let responseBody;
        const contentType = res.headers.get("content-type") ?? "";
        if (contentType.includes("application/json")) {
            responseBody = await res.json();
        }
        else {
            responseBody = await res.text();
        }
        if (!res.ok) {
            switch (res.status) {
                case 400:
                    throw new errors_1.ValidationError(url, responseBody);
                case 401:
                case 403:
                    throw new errors_1.UnauthorizedError(url, responseBody);
                case 404:
                    throw new errors_1.NotFoundError(url, responseBody);
                default:
                    throw new errors_1.ApiError(res.status, res.statusText, responseBody, url);
            }
        }
        return responseBody;
    }
    get(path) {
        return this.request("GET", path);
    }
    post(path, body) {
        return this.request("POST", path, body);
    }
    put(path, body) {
        return this.request("PUT", path, body);
    }
    patch(path, body) {
        return this.request("PATCH", path, body);
    }
    del(path) {
        return this.request("DELETE", path);
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  TODOS
    // ══════════════════════════════════════════════════════════════════════════════
    listTodos(params = {}) {
        return this.get(`/api/v1/todos${buildQuery(params)}`);
    }
    createTodo(req) {
        return this.post("/api/v1/todos", req);
    }
    getTodo(id) {
        return this.get(`/api/v1/todos/${id}`);
    }
    updateTodo(id, req) {
        return this.put(`/api/v1/todos/${id}`, req);
    }
    updateTodoStatus(id, req) {
        return this.patch(`/api/v1/todos/${id}/status`, req);
    }
    deleteTodo(id) {
        return this.del(`/api/v1/todos/${id}`);
    }
    countTodos() {
        return this.get("/api/v1/todos/count");
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  PROJECTS
    // ══════════════════════════════════════════════════════════════════════════════
    listProjects() {
        return this.get("/api/v1/projects");
    }
    createProject(req) {
        return this.post("/api/v1/projects", req);
    }
    getProject(id) {
        return this.get(`/api/v1/projects/${id}`);
    }
    updateProject(id, req) {
        return this.put(`/api/v1/projects/${id}`, req);
    }
    deleteProject(id) {
        return this.del(`/api/v1/projects/${id}`);
    }
    startProject(id) {
        return this.post(`/api/v1/projects/${id}/start`);
    }
    stopProject(id) {
        return this.post(`/api/v1/projects/${id}/stop`);
    }
    getProjectWorkflows(projectId) {
        return this.get(`/api/v1/projects/${projectId}/workflows`);
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  WORKFLOWS
    // ══════════════════════════════════════════════════════════════════════════════
    listWorkflows() {
        return this.get("/api/v1/workflows");
    }
    createWorkflow(req) {
        return this.post("/api/v1/workflows", req);
    }
    getWorkflow(id) {
        return this.get(`/api/v1/workflows/${id}`);
    }
    updateWorkflow(id, req) {
        return this.put(`/api/v1/workflows/${id}`, req);
    }
    deleteWorkflow(id) {
        return this.del(`/api/v1/workflows/${id}`);
    }
    executeWorkflow(id, req) {
        return this.post(`/api/v1/workflows/${id}/execute`, req ?? {});
    }
    getWorkflowRuns(workflowId) {
        return this.get(`/api/v1/workflows/${workflowId}/runs`);
    }
    getWorkflowRun(workflowId, runId) {
        return this.get(`/api/v1/workflows/${workflowId}/runs/${runId}`);
    }
    optimizeWorkflow(id) {
        return this.post(`/api/v1/workflows/${id}/optimize`);
    }
    activateSkills(workflowId, req) {
        return this.post(`/api/v1/workflows/${workflowId}/skills`, req);
    }
    deactivateSkill(workflowId, skillId) {
        return this.del(`/api/v1/workflows/${workflowId}/skills/${skillId}`);
    }
    exportWorkflow(id) {
        return this.get(`/api/v1/workflows/export/${id}`);
    }
    importWorkflow(req) {
        return this.post("/api/v1/workflows/import", req);
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  SKILLS
    // ══════════════════════════════════════════════════════════════════════════════
    listSkills() {
        return this.get("/api/v1/skills");
    }
    getSkill(id) {
        return this.get(`/api/v1/skills/${id}`);
    }
    getSkillCapabilities(skillId) {
        return this.get(`/api/v1/skills/${skillId}/capabilities`);
    }
    createSkill(req) {
        return this.post("/api/v1/skills", req);
    }
    deleteSkill(id) {
        return this.del(`/api/v1/skills/${id}`);
    }
    exportSkill(id) {
        return this.get(`/api/v1/skills/export/${id}`);
    }
    importSkill(req) {
        return this.post("/api/v1/skills/import", req);
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  EXECUTIONS
    // ══════════════════════════════════════════════════════════════════════════════
    listExecutions(params = {}) {
        return this.get(`/api/v1/executions${buildQuery(params)}`);
    }
    getExecution(id) {
        return this.get(`/api/v1/executions/${id}`);
    }
    pauseExecution(id) {
        return this.post(`/api/v1/executions/${id}/pause`);
    }
    resumeExecution(id) {
        return this.post(`/api/v1/executions/${id}/resume`);
    }
    cancelExecution(id) {
        return this.post(`/api/v1/executions/${id}/cancel`);
    }
    approveExecution(id, answer) {
        return this.post(`/api/v1/executions/${id}/approve`, { answer });
    }
    getPendingApproval(id) {
        return this.get(`/api/v1/executions/${id}/approval`);
    }
    executeCommand(executionId, command) {
        return this.post(`/api/v1/executions/${executionId}/command`, { command });
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  SCHEDULES
    // ══════════════════════════════════════════════════════════════════════════════
    listSchedules() {
        return this.get("/api/v1/schedules");
    }
    getRemainingToday() {
        return this.get("/api/v1/schedules/remaining-today");
    }
    getRemainingThisWeek() {
        return this.get("/api/v1/schedules/remaining-this-week");
    }
    getSchedule(id) {
        return this.get(`/api/v1/schedules/${id}`);
    }
    getSchedulesByWorkflow(workflowId) {
        return this.get(`/api/v1/schedules/workflow/${workflowId}`);
    }
    getCalendarData(params) {
        return this.get(`/api/v1/schedules/calendar${buildQuery(params)}`);
    }
    createSchedule(req) {
        return this.post("/api/v1/schedules", req);
    }
    enableSchedule(id) {
        return this.post(`/api/v1/schedules/${id}/enable`);
    }
    disableSchedule(id) {
        return this.post(`/api/v1/schedules/${id}/disable`);
    }
    deleteSchedule(id) {
        return this.del(`/api/v1/schedules/${id}`);
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  WEBHOOKS
    // ══════════════════════════════════════════════════════════════════════════════
    listWebhooks() {
        return this.get("/api/v1/webhooks");
    }
    getWebhook(id) {
        return this.get(`/api/v1/webhooks/${id}`);
    }
    getWebhooksByWorkflow(workflowId) {
        return this.get(`/api/v1/webhooks/workflow/${workflowId}`);
    }
    createWebhook(req) {
        return this.post("/api/v1/webhooks", req);
    }
    enableWebhook(id) {
        return this.post(`/api/v1/webhooks/${id}/enable`);
    }
    disableWebhook(id) {
        return this.post(`/api/v1/webhooks/${id}/disable`);
    }
    deleteWebhook(id) {
        return this.del(`/api/v1/webhooks/${id}`);
    }
    triggerWebhook(token, payload) {
        return this.post(`/api/v1/webhook/trigger/${token}`, payload ?? {});
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  WORKFLOW TEMPLATES
    // ══════════════════════════════════════════════════════════════════════════════
    listWorkflowTemplates() {
        return this.get("/api/v1/workflow-templates");
    }
    getWorkflowTemplate(id) {
        return this.get(`/api/v1/workflow-templates/${id}`);
    }
    createWorkflowTemplate(req) {
        return this.post("/api/v1/workflow-templates", req);
    }
    updateWorkflowTemplate(id, req) {
        return this.put(`/api/v1/workflow-templates/${id}`, req);
    }
    deleteWorkflowTemplate(id) {
        return this.del(`/api/v1/workflow-templates/${id}`);
    }
    createTemplateFromWorkflow(workflowId) {
        return this.post(`/api/v1/workflow-templates/from-workflow/${workflowId}`);
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  LEARNING / ANALYTICS
    // ══════════════════════════════════════════════════════════════════════════════
    getDashboard() {
        return this.get("/api/v1/learning/dashboard");
    }
    getWorkflowStats(workflowId) {
        return this.get(`/api/v1/learning/workflows/${workflowId}/stats`);
    }
    getStepAnalytics(workflowId) {
        return this.get(`/api/v1/learning/workflows/${workflowId}/step-analytics`);
    }
    getOptimizationSuggestions(workflowId) {
        return this.get(`/api/v1/learning/workflows/${workflowId}/suggestions`);
    }
    getSimilarWorkflows(workflowId) {
        return this.get(`/api/v1/learning/workflows/${workflowId}/similar`);
    }
    getAgenticStrategyHistory(workflowId) {
        return this.get(`/api/v1/learning/workflows/${workflowId}/agentic-history`);
    }
    getAgenticAnalytics(workflowId) {
        return this.get(`/api/v1/learning/workflows/${workflowId}/agentic-analytics`);
    }
    getToolPatterns(workflowId) {
        return this.get(`/api/v1/learning/workflows/${workflowId}/tool-patterns`);
    }
    getExecutionInsights(workflowId) {
        return this.get(`/api/v1/learning/workflows/${workflowId}/execution-insights`);
    }
    getRunInsights(runId) {
        return this.get(`/api/v1/learning/runs/${runId}/insights`);
    }
    rateRun(runId, rating) {
        return this.post(`/api/v1/learning/runs/${runId}/rate?rating=${rating}`);
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  CONVERSIONS
    // ══════════════════════════════════════════════════════════════════════════════
    suggestWorkflow(runId) {
        return this.get(`/api/v1/conversions/runs/${runId}/suggest`);
    }
    createWorkflowFromRun(runId, projectId, name) {
        return this.post(`/api/v1/conversions/runs/${runId}/create-workflow`, {
            projectId,
            name,
        });
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  SYSTEM
    // ══════════════════════════════════════════════════════════════════════════════
    getAuthStatus() {
        return this.get("/api/v1/system/auth-status");
    }
    getHealth() {
        return this.get("/api/v1/system/health");
    }
    getSystemInfo() {
        return this.get("/api/v1/system/info");
    }
    enableRemoteAccess() {
        return this.post("/api/v1/system/remote-access/enable");
    }
    disableRemoteAccess() {
        return this.post("/api/v1/system/remote-access/disable");
    }
    getRemoteAccessStatus() {
        return this.get("/api/v1/system/remote-access/status");
    }
    browseDirectory(path) {
        const qs = path ? buildQuery({ path }) : "";
        return this.get(`/api/v1/system/browse${qs}`);
    }
    // ══════════════════════════════════════════════════════════════════════════════
    //  ACTIVITY LOG
    // ══════════════════════════════════════════════════════════════════════════════
    getActivityLogs(params = {}) {
        return this.get(`/api/v1/activity-log${buildQuery(params)}`);
    }
}
exports.MySuricatesClient = MySuricatesClient;
//# sourceMappingURL=client.js.map
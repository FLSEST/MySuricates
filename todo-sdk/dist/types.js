"use strict";
// ── Enums ──────────────────────────────────────────────────────────────────────
Object.defineProperty(exports, "__esModule", { value: true });
exports.LogCategory = exports.LogLevel = exports.ScheduleType = exports.SkillCategory = exports.ContainerStatus = exports.ProjectStatus = exports.ProjectType = exports.StepStatus = exports.RunStatus = exports.TodoSource = exports.TodoPriority = exports.TodoStatus = void 0;
var TodoStatus;
(function (TodoStatus) {
    TodoStatus["OPEN"] = "OPEN";
    TodoStatus["IN_PROGRESS"] = "IN_PROGRESS";
    TodoStatus["DONE"] = "DONE";
})(TodoStatus || (exports.TodoStatus = TodoStatus = {}));
var TodoPriority;
(function (TodoPriority) {
    TodoPriority["LOW"] = "LOW";
    TodoPriority["MEDIUM"] = "MEDIUM";
    TodoPriority["HIGH"] = "HIGH";
})(TodoPriority || (exports.TodoPriority = TodoPriority = {}));
var TodoSource;
(function (TodoSource) {
    TodoSource["USER"] = "USER";
    TodoSource["AI"] = "AI";
    TodoSource["WORKFLOW"] = "WORKFLOW";
})(TodoSource || (exports.TodoSource = TodoSource = {}));
var RunStatus;
(function (RunStatus) {
    RunStatus["PENDING"] = "PENDING";
    RunStatus["RUNNING"] = "RUNNING";
    RunStatus["PAUSED"] = "PAUSED";
    RunStatus["COMPLETED"] = "COMPLETED";
    RunStatus["FAILED"] = "FAILED";
    RunStatus["CANCELLED"] = "CANCELLED";
})(RunStatus || (exports.RunStatus = RunStatus = {}));
var StepStatus;
(function (StepStatus) {
    StepStatus["PENDING"] = "PENDING";
    StepStatus["RUNNING"] = "RUNNING";
    StepStatus["COMPLETED"] = "COMPLETED";
    StepStatus["FAILED"] = "FAILED";
    StepStatus["SKIPPED"] = "SKIPPED";
    StepStatus["WAITING_APPROVAL"] = "WAITING_APPROVAL";
})(StepStatus || (exports.StepStatus = StepStatus = {}));
var ProjectType;
(function (ProjectType) {
    ProjectType["CODE"] = "CODE";
    ProjectType["CONTENT"] = "CONTENT";
    ProjectType["RESEARCH"] = "RESEARCH";
    ProjectType["BUSINESS"] = "BUSINESS";
    ProjectType["PERSONAL"] = "PERSONAL";
})(ProjectType || (exports.ProjectType = ProjectType = {}));
var ProjectStatus;
(function (ProjectStatus) {
    ProjectStatus["STOPPED"] = "STOPPED";
    ProjectStatus["STARTING"] = "STARTING";
    ProjectStatus["RUNNING"] = "RUNNING";
    ProjectStatus["ERROR"] = "ERROR";
})(ProjectStatus || (exports.ProjectStatus = ProjectStatus = {}));
var ContainerStatus;
(function (ContainerStatus) {
    ContainerStatus["CREATED"] = "CREATED";
    ContainerStatus["RUNNING"] = "RUNNING";
    ContainerStatus["STOPPED"] = "STOPPED";
    ContainerStatus["ERROR"] = "ERROR";
})(ContainerStatus || (exports.ContainerStatus = ContainerStatus = {}));
var SkillCategory;
(function (SkillCategory) {
    SkillCategory["DOCUMENT"] = "DOCUMENT";
    SkillCategory["CODE"] = "CODE";
    SkillCategory["DATA"] = "DATA";
    SkillCategory["CONTENT"] = "CONTENT";
    SkillCategory["RESEARCH"] = "RESEARCH";
    SkillCategory["BUSINESS"] = "BUSINESS";
    SkillCategory["PRODUCTIVITY"] = "PRODUCTIVITY";
})(SkillCategory || (exports.SkillCategory = SkillCategory = {}));
var ScheduleType;
(function (ScheduleType) {
    ScheduleType["CRON"] = "CRON";
    ScheduleType["ONCE"] = "ONCE";
})(ScheduleType || (exports.ScheduleType = ScheduleType = {}));
var LogLevel;
(function (LogLevel) {
    LogLevel["DEBUG"] = "DEBUG";
    LogLevel["INFO"] = "INFO";
    LogLevel["WARN"] = "WARN";
    LogLevel["ERROR"] = "ERROR";
})(LogLevel || (exports.LogLevel = LogLevel = {}));
var LogCategory;
(function (LogCategory) {
    LogCategory["SYSTEM"] = "SYSTEM";
    LogCategory["EXECUTION"] = "EXECUTION";
    LogCategory["SCHEDULE"] = "SCHEDULE";
    LogCategory["WEBHOOK"] = "WEBHOOK";
    LogCategory["SECURITY"] = "SECURITY";
    LogCategory["CONTAINER"] = "CONTAINER";
})(LogCategory || (exports.LogCategory = LogCategory = {}));
//# sourceMappingURL=types.js.map
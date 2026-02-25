#!/usr/bin/env bash
# Creates /tmp/autopilot-testbed/ — a fake npm/TS project for testing workflow templates.
# Idempotent: safe to re-run. Takes < 2 seconds.

set -euo pipefail

BASE="/tmp/autopilot-testbed"
rm -rf "$BASE"
mkdir -p "$BASE/src" "$BASE/logs"

# ── package.json ──
cat > "$BASE/package.json" <<'EOF'
{
  "name": "task-tracker",
  "version": "1.0.0",
  "description": "A simple task tracking CLI tool",
  "main": "src/index.ts",
  "scripts": {
    "test": "jest --config jest.config.js",
    "build": "tsc",
    "start": "node dist/index.js"
  },
  "dependencies": {
    "chalk": "^4.1.2",
    "commander": "^9.0.0",
    "lodash": "^4.17.20"
  },
  "devDependencies": {
    "jest": "^29.7.0",
    "ts-jest": "^29.1.0",
    "typescript": "^5.3.0",
    "@types/jest": "^29.5.0",
    "@types/lodash": "^4.14.202"
  }
}
EOF

# ── jest.config.js ──
cat > "$BASE/jest.config.js" <<'EOF'
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  testMatch: ['**/*.test.ts'],
};
EOF

# ── tsconfig.json ──
cat > "$BASE/tsconfig.json" <<'EOF'
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "commonjs",
    "outDir": "dist",
    "strict": true,
    "esModuleInterop": true
  },
  "include": ["src/**/*"]
}
EOF

# ── src/index.ts (with intentional bugs) ──
cat > "$BASE/src/index.ts" <<'EOF'
import { Task, createTask, listTasks, deleteTask } from './utils';

const tasks: Task[] = [];

export function addTask(title: string, priority: number): Task {
  // BUG: no validation — priority can be negative or > 10
  const task = createTask(title, priority);
  tasks.push(task);
  return task;
}

export function getTasks(): Task[] {
  // BUG: returns direct reference instead of copy
  return tasks;
}

export function removeTask(id: string): boolean {
  const index = tasks.findIndex(t => t.id == id); // BUG: loose equality
  if (index >= 0) {
    deleteTask(tasks, index);
    return true;
  }
  return false;
}

export function getHighPriority(): Task[] {
  // BUG: off-by-one — should be >= 7, not > 7
  return tasks.filter(t => t.priority > 7);
}

// BUG: unused import of lodash in utils but used nowhere
export function searchTasks(query: string): Task[] {
  return tasks.filter(t => t.title.toLowerCase().includes(query.toLowerCase()));
}
EOF

# ── src/utils.ts ──
cat > "$BASE/src/utils.ts" <<'EOF'
import _ from 'lodash';  // BUG: imported but barely used

export interface Task {
  id: string;
  title: string;
  priority: number;
  createdAt: Date;
  done: boolean;
}

let counter = 0;

export function createTask(title: string, priority: number): Task {
  counter++;
  return {
    id: String(counter),
    title: title,
    priority: priority,
    createdAt: new Date(),
    done: false,
  };
}

export function deleteTask(tasks: Task[], index: number): void {
  // BUG: uses delete instead of splice — leaves undefined hole
  delete (tasks as any)[index];
}

export function formatTask(task: Task): string {
  // BUG: no null check on task.title
  return `[${task.done ? 'x' : ' '}] #${task.id} (P${task.priority}) ${task.title}`;
}
EOF

# ── src/index.test.ts ──
cat > "$BASE/src/index.test.ts" <<'EOF'
import { addTask, getTasks, removeTask, getHighPriority } from './index';

describe('TaskTracker', () => {
  beforeEach(() => {
    // Clear tasks by removing all
    const tasks = getTasks();
    tasks.length = 0;
  });

  test('addTask creates a task with correct properties', () => {
    const task = addTask('Write tests', 5);
    expect(task.title).toBe('Write tests');
    expect(task.priority).toBe(5);
    expect(task.done).toBe(false);
  });

  test('getTasks returns all tasks', () => {
    addTask('Task 1', 3);
    addTask('Task 2', 7);
    expect(getTasks()).toHaveLength(2);
  });

  test('removeTask removes existing task', () => {
    const task = addTask('To remove', 1);
    expect(removeTask(task.id)).toBe(true);
  });

  test('getHighPriority filters correctly', () => {
    addTask('Low', 2);
    addTask('High', 8);
    addTask('Critical', 10);
    const high = getHighPriority();
    expect(high.length).toBeGreaterThanOrEqual(2);
  });
});
EOF

# ── README.md ──
cat > "$BASE/README.md" <<'EOF'
# TaskTracker

A simple CLI tool for managing tasks with priorities.

## Features
- Add tasks with priority levels (1-10)
- List and filter tasks
- Mark tasks as complete
- Search by keyword

## Architecture
- `src/index.ts` — main module with task CRUD operations
- `src/utils.ts` — shared types, helpers, and formatting

## Getting Started
```bash
npm install
npm test
npm start
```

## Tech Stack
- TypeScript 5.3
- Jest for testing
- Commander.js for CLI parsing
- Lodash for utility functions
EOF

# ── NOTES.md (for notes-to-presentation) ──
cat > "$BASE/NOTES.md" <<'EOF'
# Meeting Notes — Q4 Retro

## What went well
- Shipped v1.0 on time
- Test coverage increased from 40% to 72%
- Onboarding time for new devs reduced to 2 days

## What needs improvement
- Deployment pipeline takes 45 minutes
- No automated rollback
- Documentation is outdated in 3 critical areas

## Action Items
1. Investigate parallel CI stages (owner: Alex)
2. Add canary deployment support (owner: Maria)
3. Update API docs for v2 endpoints (owner: Team)

## Key Metrics
- Sprint velocity: 34 points (up from 28)
- Bug escape rate: 3.2% (target: < 5%)
- Customer satisfaction: 4.1/5
EOF

# ── document.md (with deliberate factual errors for fact-check) ──
cat > "$BASE/document.md" <<'EOF'
# The History of TypeScript

TypeScript was created by Microsoft and first released in October 2012. It was designed by
Anders Hejlsberg, who also created C# and Delphi.

## Key Facts
- TypeScript 1.0 was released in April 2014
- TypeScript compiles to JavaScript and runs in any browser
- As of 2024, TypeScript is used by over 80% of JavaScript developers
- The TypeScript compiler is written in C++ for maximum performance
- TypeScript was originally called "Strada" during internal development
- Google adopted TypeScript as their primary frontend language in 2017, replacing Dart entirely
- TypeScript 5.0 introduced decorators, which were a completely new concept not present in any prior version

## Performance
TypeScript adds zero runtime overhead because it is compiled to machine code via LLVM.
The type system is Turing-complete, which was proven by Alan Turing in 1950.
EOF

# ── cv.md ──
cat > "$BASE/cv.md" <<'EOF'
# Max Mustermann — Software Engineer

## Experience
**Senior Developer** at TechCorp (2020–present)
- Led migration from monolith to microservices (12 services)
- Reduced API latency by 40% through caching strategy
- Mentored 4 junior developers

**Developer** at StartupXY (2017–2020)
- Built real-time dashboard with React and WebSockets
- Implemented CI/CD pipeline reducing deploy time from 2h to 15min

## Skills
TypeScript, React, Node.js, PostgreSQL, Docker, Kubernetes, AWS

## Education
B.Sc. Computer Science, TU Munich (2017)
EOF

# ── api-spec.yaml ──
cat > "$BASE/api-spec.yaml" <<'EOF'
openapi: "3.0.3"
info:
  title: TaskTracker API
  version: "1.0.0"
  description: REST API for managing tasks
paths:
  /tasks:
    get:
      summary: List all tasks
      parameters:
        - name: priority_min
          in: query
          schema:
            type: integer
      responses:
        "200":
          description: Array of tasks
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: "#/components/schemas/Task"
    post:
      summary: Create a new task
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/CreateTask"
      responses:
        "201":
          description: Created task
  /tasks/{id}:
    delete:
      summary: Delete a task
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
      responses:
        "204":
          description: Task deleted
components:
  schemas:
    Task:
      type: object
      properties:
        id:
          type: string
        title:
          type: string
        priority:
          type: integer
          minimum: 1
          maximum: 10
        done:
          type: boolean
        createdAt:
          type: string
          format: date-time
    CreateTask:
      type: object
      required: [title, priority]
      properties:
        title:
          type: string
        priority:
          type: integer
EOF

# ── data.csv (with anomalies) ──
cat > "$BASE/data.csv" <<'EOF'
date,product,region,revenue,units,cost
2024-01-15,Widget A,North,12500,250,8750
2024-01-15,Widget A,South,8900,178,6230
2024-01-15,Widget B,North,15600,120,10920
2024-02-15,Widget A,North,13100,262,9170
2024-02-15,Widget A,South,9200,184,6440
2024-02-15,Widget B,North,14800,114,10360
2024-03-15,Widget A,North,45000,900,31500
2024-03-15,Widget A,South,9500,190,6650
2024-03-15,Widget B,North,16200,125,11340
2024-04-15,Widget A,North,12800,256,8960
2024-04-15,Widget A,South,-500,10,350
2024-04-15,Widget B,North,15900,122,11130
2024-05-15,Widget A,North,13400,268,9380
2024-05-15,Widget A,South,9800,196,6860
2024-05-15,Widget B,North,0,0,0
2024-06-15,Widget A,North,14200,284,9940
2024-06-15,Widget A,South,10100,202,7070
2024-06-15,Widget B,North,16800,129,11760
EOF

# ── chapter1.md, chapter2.md (for markdown-to-ebook) ──
cat > "$BASE/chapter1.md" <<'EOF'
# Chapter 1: Getting Started

Every project begins with a single commit. In this chapter, we explore how to set up
a modern TypeScript project from scratch.

## Prerequisites
- Node.js 18 or later
- A code editor (VS Code recommended)
- Basic familiarity with JavaScript

## Project Setup
First, create a new directory and initialize the project:
```bash
mkdir my-project && cd my-project
npm init -y
npm install typescript --save-dev
npx tsc --init
```

## Your First Type
```typescript
interface User {
  name: string;
  age: number;
  email: string;
}

const user: User = {
  name: "Alice",
  age: 30,
  email: "alice@example.com",
};
```
EOF

cat > "$BASE/chapter2.md" <<'EOF'
# Chapter 2: Advanced Types

TypeScript's type system goes far beyond simple annotations. Let's explore
utility types, generics, and conditional types.

## Generics
```typescript
function first<T>(arr: T[]): T | undefined {
  return arr[0];
}
```

## Utility Types
```typescript
type ReadonlyUser = Readonly<User>;
type PartialUser = Partial<User>;
type UserName = Pick<User, 'name'>;
```

## Conditional Types
```typescript
type IsString<T> = T extends string ? true : false;
type A = IsString<"hello">; // true
type B = IsString<42>;      // false
```
EOF

# ── logs/app.log ──
cat > "$BASE/logs/app.log" <<'EOF'
2024-06-15T08:00:12.345Z INFO  [main] Application started on port 3000
2024-06-15T08:01:45.123Z INFO  [http] GET /tasks 200 12ms
2024-06-15T08:02:10.456Z WARN  [http] GET /tasks?priority_min=abc 400 2ms — Invalid parameter
2024-06-15T08:05:33.789Z INFO  [http] POST /tasks 201 45ms
2024-06-15T08:10:00.001Z ERROR [db] Connection pool exhausted — max_connections=10, active=10, waiting=3
2024-06-15T08:10:00.502Z ERROR [db] Connection pool exhausted — max_connections=10, active=10, waiting=5
2024-06-15T08:10:01.003Z ERROR [db] Connection pool exhausted — max_connections=10, active=10, waiting=8
2024-06-15T08:10:05.100Z WARN  [db] Connection recovered after 5s
2024-06-15T08:15:22.234Z INFO  [http] DELETE /tasks/42 204 8ms
2024-06-15T08:20:00.000Z ERROR [auth] JWT verification failed: token expired — user_id=user_17, ip=192.168.1.50
2024-06-15T08:20:00.100Z ERROR [auth] JWT verification failed: token expired — user_id=user_17, ip=192.168.1.50
2024-06-15T08:20:00.200Z ERROR [auth] JWT verification failed: token expired — user_id=user_17, ip=192.168.1.50
2024-06-15T08:25:44.567Z WARN  [http] Response time exceeded threshold: GET /tasks 2340ms (threshold: 1000ms)
2024-06-15T08:30:12.890Z INFO  [scheduler] Cron job 'cleanup-old-tasks' completed in 120ms, removed 15 tasks
2024-06-15T08:45:00.000Z ERROR [http] POST /tasks 500 — NullPointerException at TaskService.java:42
2024-06-15T08:45:00.001Z ERROR [http] Stacktrace: java.lang.NullPointerException: Cannot invoke "String.length()" on null reference
2024-06-15T09:00:00.000Z INFO  [metrics] Hourly report: requests=342, errors=8, p95_latency=245ms, p99_latency=1200ms
2024-06-15T09:15:33.456Z WARN  [disk] Disk usage at 87% on /var/data — threshold: 85%
2024-06-15T09:30:00.000Z ERROR [http] GET /tasks/999 404 3ms — Task not found
2024-06-15T10:00:00.000Z INFO  [main] Health check: OK — uptime=2h, memory=456MB/1024MB
EOF

# ── .git with 3 commits + tag ──
cd "$BASE"
git init -q
git checkout -q -b main
git add README.md package.json jest.config.js tsconfig.json src/utils.ts
git -c user.name="Test" -c user.email="test@test.com" commit -q -m "Initial commit: project setup and utilities"
git tag v1.0.0

git add src/index.ts src/index.test.ts
git -c user.name="Test" -c user.email="test@test.com" commit -q -m "feat: add task CRUD operations and tests"

git add document.md cv.md api-spec.yaml data.csv NOTES.md chapter1.md chapter2.md logs/app.log
git -c user.name="Test" -c user.email="test@test.com" commit -q -m "docs: add project documents, data, and logs"

echo ""
echo "=== Test workspace created at $BASE ==="
echo ""
ls -la "$BASE"
echo ""
echo "Git log:"
git log --oneline
echo ""
echo "Done. Create a project 'Template Testbed' in AutoPilot UI with path: $BASE"

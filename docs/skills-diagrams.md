# AutoPilot — Skills & Capabilities

Skill-Lebenszyklus, Aktivierung pro Workflow, Ausfuehrung im Skill-Capability Block

## Lifecycle — *CRUD*

```mermaid
sequenceDiagram
    participant UI as Skills Page
    participant CTR as SkillController
    participant SVC as SkillService
    participant DB as skills/capabilities

    UI->>CTR: POST /api/v1/skills (name, category, aiContext, configSchema)
    CTR->>SVC: createSkill()
    SVC->>DB: INSERT skill + optional capabilities
    DB-->>SVC: saved
    SVC-->>CTR: SkillResponse
    CTR-->>UI: 201 Created
```

## Aktivierung pro Workflow — */workflows/{id}/skills*

```mermaid
flowchart LR
    SK["Skill"] --> CAP["Capabilities"]
    WF["Workflow"] --> SKW["workflow_skills"]
    SKW --> SK
```

Aktivierte Skills werden vor Ausfuehrung in das Projekt injiziert (`injectSkillContextOnHost`).

## Ausfuehrung einer Capability — *skill-capability Block*

```mermaid
sequenceDiagram
    participant BE as SkillCapabilityBlockExecutor
    participant CTX as ExecutionContext
    participant CES as CommandExecutorService
    participant CLI as claude -p

    BE->>CTX: resolveTemplate(input, params)
    BE->>CES: executeCommandOnHost(projectPath, prompt, model, systemPrompt)
    CES->>CLI: run prompt (includes capability + params)
    CLI-->>CES: output
    CES-->>BE: result
    BE-->>CTX: StepResult COMPLETED/FAILED
```

### Prompt-Aufbau

`"Use your '<capability>' capability..."` + Params + Input

SystemPrompt = Workflow Instruction Prefix

### Kontext

Skill-Kontext (aiContext) zuvor injiziert in CLI-Session.

Template-Variablen: `{{ previous_output }}`, `{{ vars }}`

## Datenmodell — *skills & capabilities*

| Tabelle | Wichtig |
|---|---|
| `skills` | name, description, category, ai_context, config_schema, builtin, version |
| `capabilities` | skill_id, name, description, handler_class, input_schema, output_schema, async |
| `workflow_skills` | workflow_id, skill_id (Aktivierung) |

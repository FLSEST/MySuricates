<!-- Generated from docs/skills-diagrams.md and docs/i18n/skills-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# AutoPilot — Skills & Capabilities

Skill lifecycle, activation per workflow, execution in the skill-capability block

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

## Activation per Workflow — */workflows/{id}/skills*

```mermaid
flowchart LR
    SK["Skill"] --> CAP["Capabilities"]
    WF["Workflow"] --> SKW["workflow_skills"]
    SKW --> SK
```

Activated skills are injected into the project before execution (`injectSkillContextOnHost`).

## Executing a Capability — *skill-capability Block*

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

### Prompt Structure

`"Use your '<capability>' capability..."` + params + input

SystemPrompt = workflow instruction prefix

### Context

Skill context (aiContext) injected into the CLI session beforehand.

Template variables: `{{ previous_output }}`, `{{ vars }}`

## Data Model — *skills & capabilities*

| Table | Key columns |
|---|---|
| `skills` | name, description, category, ai_context, config_schema, builtin, version |
| `capabilities` | skill_id, name, description, handler_class, input_schema, output_schema, async |
| `workflow_skills` | workflow_id, skill_id (activation) |

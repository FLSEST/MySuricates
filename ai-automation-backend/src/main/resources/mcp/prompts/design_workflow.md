Design a MySuricates workflow for this goal: {{goal}}
{{project_line}}
Work in this order and keep the user involved:

1. Clarify in one or two questions what the result is, who reads it, how often it runs and what must not happen.
2. Look for a starting point: list_templates (also kind "blueprint" for single patterns) and list_workflows. Prefer installing and adapting over building from scratch.
3. Read the patterns before you design: read_guide topic "workflow-guide", sections "Frugality Ladder" and "Proven Workflow Patterns"; config keys in topic "block-reference".
4. Propose the steps as a short list — for each: block type, what it does, why this type. Deterministic first: shell where the step can be computed, simple for one AI judgement, agentic only when the steps are unknown up front and the result is verifiable. An approval block before anything with consequences.
5. After the user agrees: create_workflow (or install_template + update_workflow), then analyze_workflow and fix every HIGH and MEDIUM finding.
6. Offer a test run with run_workflow and walk through the result with get_run.

Answer in the user's language.

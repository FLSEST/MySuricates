Set up a MySuricates audit workflow for: {{subject}}
{{project_line}}
An audit measures first and judges second:

1. Read read_guide topic "workflow-guide", section "Audit Pipeline", and look at the audit templates (list_templates, query "audit").
2. Collect facts with shell blocks — tools, greps, counts, test or scanner output. Every finding later needs evidence from this step.
3. Let one simple block judge only the collected candidates with fixed criteria and a severity per finding (HIGH / MEDIUM / LOW). Use a strong model for judgement steps — weaker ones invent findings.
4. Finish with a report: findings table with evidence and a short action plan. Create TODOs only for HIGH findings with evidence (read_guide section "TODO markers").
5. Propose the steps to the user, then create_workflow, analyze_workflow, and a test run with run_workflow.

Answer in the user's language.

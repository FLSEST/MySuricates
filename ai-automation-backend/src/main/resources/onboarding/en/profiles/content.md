## Content workflows

- **Generate:** `simple` with a prompt; several pieces with `foreach`; tone, audience and format as variables.
- **Review:** `simple` for copy-editing, `approval` before publishing.
- **Files:** rename and convert with `shell`, which costs no tokens.
- **Translate:** `foreach` over the list of languages.
- **Draft → review → revision** as a chain (only with the `chains` module).

Suggested variables: `content_type`, `target_audience`, `tone`, `output_format`.

### Earlier versions

- Runs of a workflow: `GET $MS/executions?workflowId=<id>&page=0&size=10`.
- Text of a run: `GET $MS/executions/<runId>` → `steps[].output`.

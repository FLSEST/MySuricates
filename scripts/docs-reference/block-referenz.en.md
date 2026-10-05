# Block Reference

> **Scope:** Every config key of every block type, generated from “Common fields” on from
> `ai-automation-frontend/src/data/blockConfigSchema.json` (`python3 scripts/docs-reference.py --write`,
> see [Development → Generated references](entwicklung.md#generated-references)). The block editor takes
> its suggestions and the analyzer its checks from the same file. What a type is good for, an example
> and the pitfalls are in the [Workflow Guide, chapter 2](WORKFLOW-GUIDE.md#2-block-type-reference).

A block is `{ "type": …, "name": …, "config": { … } }`; the tables describe the keys in `config`.
**Required** “yes” means it must be set; “one of”: exactly one of these keys, an empty text counts as
not set. **Values** lists the allowed values with their meaning, plus lower bounds (“≥ 1”). “Read only
with …” means the executor reads the key only when the other keys named are set that way. The backend
ignores unknown keys; editor and analyzer report them.

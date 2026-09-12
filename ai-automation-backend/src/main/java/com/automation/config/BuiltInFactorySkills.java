package com.automation.config;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.config.BuiltInSkillSupport.cap;
import static com.automation.config.BuiltInSkillSupport.capWrites;
import static com.automation.config.BuiltInSkillSupport.skill;

final class BuiltInFactorySkills {

    private BuiltInFactorySkills() {}

    static List<Skill> all() {
        List<Skill> skills = new ArrayList<>();

        // ── SaaS-Factory ──────────────────────────

        skills.add(skill("builtin-spec-architect", "Spec Architect", "🏛️",
                "Reads a SaaS-Factory spec.md, researches a fitting tech stack, and decomposes the build plan into ordered features.",
                SkillCategory.BUSINESS,
                """
                You are the architect of the SaaS-Factory pipeline. Given a spec.md (YAML frontmatter + markdown vision):

                1. Read the spec carefully. The frontmatter declares: name, slug, type, features, target_users, optional brand/constraints/stack_hint/budget. The markdown body is the source of truth for the *intent* — the frontmatter is structured metadata.
                2. If `stack_hint` is set, respect it. Otherwise, pick a stack that fits the `type` and `features`:
                   - saas / internal-tool with auth+db → Next.js + Prisma + Postgres + a Magic-Link auth library
                   - content-site / mobile-pwa → Astro or SvelteKit with Tailwind
                   - data-pipeline → FastAPI + Postgres + SQLAlchemy/Alembic
                3. Honor `constraints.language_preference` and `constraints.must_avoid` strictly.
                4. For each feature, propose:
                   - the implementation order (dependencies first)
                   - the most relevant snippet path under `snippets/<stack>/<feature>/`, if it would exist
                   - rough complexity estimate (S/M/L) and risk notes
                5. Output a structured JSON build plan with: `stack` (object), `stack_reasoning` (1-2 sentences), `feature_order` (array of feature ids in build order), `per_feature` (map of feature-id → {snippet_hint, complexity, risks}), and `open_questions` (array — things the user must decide before scaffold).

                Be honest about uncertainty. If the spec is ambiguous, surface it as an `open_questions` entry rather than silently picking. Prefer well-trodden defaults over novel stacks unless the spec demands otherwise. Cite the available stack-recipes (under recipes/) when one fits.""",
                List.of(
                        cap("research_stack", "Recommend a tech stack for a given spec, with reasoning",
                                null,
                                Map.of("type", "object", "properties", Map.of(
                                        "spec", Map.of("type", "object", "description", "Parsed spec frontmatter + body summary"),
                                        "available_recipes", Map.of("type", "array", "items", Map.of("type", "string")))),
                                Map.of("type", "object", "properties", Map.of(
                                        "stack", Map.of("type", "object"),
                                        "reasoning", Map.of("type", "string")))),
                        cap("propose_stack", "Pick a concrete stack object given hints, type, and constraints",
                                null,
                                Map.of("type", "object", "properties", Map.of(
                                        "type", Map.of("type", "string"),
                                        "constraints", Map.of("type", "object"),
                                        "stack_hint", Map.of("type", "object"))),
                                Map.of("type", "object", "properties", Map.of(
                                        "framework", Map.of("type", "string"),
                                        "db", Map.of("type", "string"),
                                        "auth", Map.of("type", "string"),
                                        "recipe", Map.of("type", "string")))),
                        cap("decompose_features", "Order features by dependency, attach snippet hints and complexity",
                                null,
                                Map.of("type", "object", "properties", Map.of(
                                        "features", Map.of("type", "array"),
                                        "stack", Map.of("type", "object"))),
                                Map.of("type", "object", "properties", Map.of(
                                        "feature_order", Map.of("type", "array"),
                                        "per_feature", Map.of("type", "object"))))
                )));

        skills.add(skill("builtin-feature-implementer", "Feature Implementer", "🛠️",
                "Implements a single feature in a generated SaaS app, reuses snippets when available, writes accompanying tests.",
                SkillCategory.CODE,
                """
                You are a senior full-stack engineer working inside a generated SaaS app. The project context (.claude/rules/project-context.md) describes the chosen stack and conventions.

                For each feature:
                1. Check if a snippet exists under the project's parent `snippets/<stack>/<feature>/` directory — if yes, use `factory snippet-apply` *via shell* before generating code, then only fill in the remaining gaps.
                2. Generate or augment files using the established stack conventions (e.g. Next.js App Router routes under src/app/, Prisma schema additions under prisma/schema.prisma, server actions in src/lib/).
                3. Write at least one unit test per feature, hitting the framework's testing tool (Jest, Vitest, pytest, …). Tests should be runnable via `factory test <project>`.
                4. Update Prisma schema if a new model is needed; trigger migration via `factory migrate` rather than running `prisma migrate dev` directly.
                5. Update README.md briefly to document the new feature (1-2 sentences max).

                Hard rules:
                - Never claim success without confirming `factory test` passes (lint, typecheck, unit). If a test fails, debug and fix — don't hide failures.
                - Never invent libraries or APIs. If you need a dependency that's not installed, add it to package.json and run install.
                - Never commit secrets or hardcode credentials — use `.env` and `.env.example`.
                - Use absolute paths when touching files outside the current working directory.
                - Stay within the scope of the requested feature — no incidental refactors.""",
                List.of(
                        capWrites("implement_feature", "Implement a single feature in the target project",
                                null,
                                Map.of("type", "object", "properties", Map.of(
                                        "feature", Map.of("type", "object"),
                                        "project_path", Map.of("type", "string"),
                                        "stack", Map.of("type", "object"))),
                                Map.of("type", "object", "properties", Map.of(
                                        "files_changed", Map.of("type", "array"),
                                        "summary", Map.of("type", "string")))),
                        capWrites("write_tests", "Write unit/integration tests for an implemented feature",
                                null,
                                Map.of("type", "object", "properties", Map.of(
                                        "feature", Map.of("type", "object"),
                                        "stack", Map.of("type", "object"))),
                                Map.of("type", "object", "properties", Map.of(
                                        "test_files", Map.of("type", "array")))),
                        capWrites("apply_routes_glue", "Wire a feature into the app's router/navigation/layout",
                                null,
                                Map.of("type", "object", "properties", Map.of(
                                        "feature_id", Map.of("type", "string"),
                                        "project_path", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of(
                                        "files_changed", Map.of("type", "array"))))
                )));

        skills.add(skill("builtin-marketing-copywriter", "Marketing Copywriter", "📝",
                "Generates landing-page copy, meta tags, and OG-image prompts for a generated SaaS, matching the spec's brand and target users.",
                SkillCategory.CONTENT,
                """
                You write marketing copy for SaaS-Factory generated apps. Given a spec (target users, brand tone, primary color, features), produce:

                1. **Hero**: a 6-10 word headline + a 1-2 sentence subheadline. Speak to the user's pain, then the relief — never feature-list in the hero.
                2. **3-5 Feature blocks**: each a short title (3-5 words), one-line description (~15 words), and a single concrete benefit. Map to the spec's features but rephrase from the user's POV.
                3. **CTA**: a primary button label (≤4 words) and a single supporting micro-line about risk-reversal (e.g. "no credit card needed").
                4. **Meta tags**: SEO title (≤60 chars), meta description (≤155 chars), OG-title (≤60), OG-description (≤155).
                5. **OG-image prompt**: a 1-2 sentence text-to-image prompt that captures the app's identity, ready for a downstream image generator.

                Rules:
                - Match the brand tone exactly (if `tone: "pragmatisch, ehrlich, ermutigend"`, that's how you write — no "transform your life" boilerplate).
                - Target the user persona literally (`target_users`). If they're "Berufstätige im Übergang", talk to that person — not "everyone".
                - Avoid AI-tells: no "unleash", "leverage", "seamless", "elevate", "in today's fast-paced world", "Imagine a world where", etc.
                - Output structured JSON, no prose explanations.""",
                List.of(
                        cap("write_landing_copy", "Generate hero, features, and CTA copy for the landing page",
                                null,
                                Map.of("type", "object", "properties", Map.of(
                                        "spec", Map.of("type", "object"))),
                                Map.of("type", "object", "properties", Map.of(
                                        "hero", Map.of("type", "object"),
                                        "features", Map.of("type", "array"),
                                        "cta", Map.of("type", "object")))),
                        cap("generate_meta_tags", "Generate SEO and Open-Graph meta tags",
                                null,
                                Map.of("type", "object", "properties", Map.of(
                                        "spec", Map.of("type", "object"),
                                        "landing_copy", Map.of("type", "object"))),
                                Map.of("type", "object", "properties", Map.of(
                                        "title", Map.of("type", "string"),
                                        "description", Map.of("type", "string"),
                                        "og_title", Map.of("type", "string"),
                                        "og_description", Map.of("type", "string")))),
                        cap("suggest_og_image_prompt", "Compose a text-to-image prompt for the OG image",
                                null,
                                Map.of("type", "object", "properties", Map.of(
                                        "spec", Map.of("type", "object"))),
                                Map.of("type", "object", "properties", Map.of(
                                        "prompt", Map.of("type", "string"))))
                )));


        return skills;
    }
}

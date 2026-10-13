# Java review: build & dependencies

Expertise for judging measured candidates from Maven (versions and dependency plugin), POM analysis and
trivy. Source: skill `maven-dependency-audit` from decebals/claude-code-java (MIT), shortened and extended
with experience from MySuricates.

## Basic stance
- An available update is not a finding by itself. A finding means there is a reason to act: a known
  vulnerability, an end of support, a conflict with runtime consequences or a build that is not reproducible.
- Read `pom.xml` and, where needed, what you know from release notes, but do not invent CVE numbers: CVEs
  come only from the trivy TREFFER lines.

## Vulnerabilities (trivy)
- The trivy severity mostly comes from NVD/GHSA. Vendors often rate differently: in 2026 Spring rated several
  CVEs LOW that NVD listed as CRITICAL. The `trivy_advisory` line names the severity per source; name
  deviations in the description.
- **Check reachability:** the advisory describes a vulnerable function and conditions (e.g. "XsltView with a
  /** mapping", "SSE with view fragments"). Grep for exactly these classes, annotations and settings in the
  source directory and in `application*.yml`. Verdict: "reachable" (used), "not reachable" (search without
  hits, name the terms) or "unclear" (the advisory names no concrete function).
- Severity: the trivy severity if "reachable" or "unclear"; at most MEDIUM if "not reachable". It still stays a
  finding as long as the dependency is in the build, because a later change can start using the function.
- Scope `test` or `provided` lowers the severity further. If there is a fixed version within the same major,
  the fix is simple; otherwise the major upgrade belongs in the fix.

## Updates (`dependency_update`, `plugin_update`, `parent_update`)
- **Patch/minor**: one collected LOW finding per file (`pom.xml`), not per artifact; the artifacts go into
  the evidence.
- **Major**: a finding only if the line in use no longer receives support or a trivy vulnerability is fixed
  only in the new major. Spring Boot: the framework maintains each minor line with OSS support for about
  13 months. If the project's line (FAKT `spring_boot_version`) is, to your knowledge, out of support or close
  to it, that is a MEDIUM finding with a note that the date needs to be checked. Otherwise no finding, only
  context.
- Artifacts managed by the parent/BOM (Spring Boot starters, Jackson, Hibernate under Boot) follow the
  parent: no finding of their own per artifact, but one for the parent.
- Milestones and release candidates are already filtered out.

## Conflicts (`dependency_conflict`)
- "omitted for conflict" means: Maven picked one version while another one was requested. A finding only if
  the picked version is **older** than a requested one (nearest-wins downgraded) or a major jump lies in
  between. If Maven picks the newest, it is not a finding.

## Reproducibility and hygiene
- `snapshot_dependency`, `version_range` (LATEST/RELEASE/[a,b)): build not reproducible. MEDIUM.
- `plugin_without_version`: the plugin version comes from the Maven defaults and changes with Maven. LOW.
- `insecure_repository` (http://): dependencies over an unencrypted connection. HIGH.
- `test_library_compile_scope`: test library in the production artifact. LOW–MEDIUM.

## Severity
| Severity | Criterion |
|---|---|
| CRITICAL | Exploitable vulnerability in a runtime dependency without a countermeasure |
| HIGH | Vulnerability rated HIGH at runtime, insecure repository |
| MEDIUM | Line without support, downgraded conflict, non-reproducible build |
| LOW | Collected patch/minor updates, hygiene |
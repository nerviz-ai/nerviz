# Roadmap

Status of Nerviz by delivery phase, followed by a checklist of every major
feature and pattern the meta-repo claims to have. This file records the current state;
it is not a norm and is not loaded by any skill or hook.

## Phases

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Skeleton: `CLAUDE.md`, `rules/`, `settings.json`, hooks | ✅ |
| 2 | `project-bootstrap`, `hexagonal` blueprint, agent, skills, exemplars | ✅ run end-to-end, green build |
| 3 | Remaining blueprints, all satisfying `_schema.md` | ✅ `hexagonal`, `clean-architecture-multi-module`, `clean-architecture-single-module`, `layered`, `modular-monolith`, `onion`, `vertical-slice` done |
| 4 | Feature skills (`use-case-design` → `domain-modeling` → `persistence-architect` → `rest-api-architect` → `test-architect`) | ✅ all 5 partials have an owner |
| 5 | `new-feature` orchestrator + `java-spring-boot-developer` executor | ✅ end-to-end run against `demo-app`, gaps remediated in D24; since `v0.22.0` the spec is designed by `/new-feature` and implemented by `/new-feature-implement` in a clean session |
| 6 | Publication: README, LICENSE, `SECURITY.md`, cross-platform CI, a tag per merged PR, GitHub Releases from `v0.15.0` (batched since `v0.22.1`), a reference project with real output ([`nerviz-ai/bank-app`](https://github.com/nerviz-ai/bank-app)) | ✅ |
| 7 | Post-bootstrap extension skills (`docker-architect`, `messaging-architect`, `jobs-architect`, `security-architect`, `http-client-architect`, `transport-security-setup`, `sonarqube-setup`, `git-publish`, `arch-adopt`, `audit-usage`), the issue loop (`sonar-lessons`, `report-issue`, `triage-issue`) and meta-tooling (`claude-code-architect-designer`, now also deciding MCP server placement, hooks, `permissions` and mods) | ✅ done; more triggered by real symptoms as they show up |
| 8 | Multi-agent generated project: the project a team uses daily also guides GitHub Copilot (and other `AGENTS.md` readers), from the same sources, in this repository | ⬜ planned — see § Multi-agent portability |

Deliberate order: rules come **before** skills. Writing skills first leads to rules
copied inside them — exactly the duplication this design exists to avoid.

## Feature checklist

### Architecture blueprints (`.claude/blueprints/`)

- [x] `hexagonal`
- [x] `clean-architecture-multi-module`
- [x] `clean-architecture-single-module`
- [x] `layered`
- [x] `modular-monolith`
- [x] `onion`
- [x] `vertical-slice`
- [x] `custom-template` (contract to write a new one)
- [x] `_schema.md` (contract every blueprint fulfills)

### Norms (`.claude/rules/`)

- [x] `architecture-ddd.md`
- [x] `naming.md`
- [x] `code-quality.md`
- [x] `error-handling.md` (domain exceptions, plus the integration families: 502, 503, 504)
- [x] `api-rest.md`
- [x] `lombok.md`
- [x] `value-objects.md`
- [x] `persistence.md`
- [x] `testing.md`
- [x] `observability.md`
- [x] `logging.md`
- [x] `messaging.md`
- [x] `http-client.md` (outbound HTTP: client and engine choice, timeouts, one retrying layer,
      failures mapped by "was the request sent?", outbound credentials, SSRF)
- [x] `scheduling.md` (trigger shape, coordination across instances, bounded passes, job metrics)
- [x] `personal-data.md` (personal data at rest and in transit)
- [x] `secrets.md` (where any credential, key or keystore may live)
- [x] `transport-security.md` (where TLS terminates, HTTP/2, HSTS with one writer,
      forwarded headers and trusted proxies)
- [x] `authorization.md` (who may call an endpoint, how the caller authenticates, 401/403,
      CORS, Actuator and Swagger exposure — servlet stack only; see 0112)
- [ ] `git-workflow.md` (planned — branches, commit messages, PRs)

### Feature-design pipeline (`/new-feature`)

- [x] `use-case-design`
- [x] `domain-modeling`
- [x] `persistence-architect`
- [x] `rest-api-architect`
- [x] `test-architect`
- [x] `security-architect` (optional step after REST, fires when the use case's `Access` row
      asks for it — `32-seguranca.md`; see 0112)
- [x] `new-feature` orchestrator + consolidation (designs only; ends with an approved spec)
- [x] `new-feature-implement` (implements an approved spec in a clean session: pre-flight,
      then the executor in three chained block groups — `domain`, `adapters`, `tests`; see
      0130 and 0135)
- [x] `http-client-architect` (optional step after security, fires when the domain declares
      an `external HTTP` port — `28-cliente-http.md`; see 0121)
- [x] `messaging-architect` (optional step, fires when an event needs external delivery)
- [x] `jobs-architect` (optional step — scheduling technology, cadence, coordination, the
      outbox relay's schedule and its prune job; see 0087)
- [x] Design patterns decided at design time — each design skill writes a `## Design patterns`
      section into its own partial, consolidated into the spec before approval (see 0089)
- [x] `docker-architect` (chained on demand by persistence/test/messaging)
- [x] `java-spring-boot-developer` (executor agent, called once per block group)
- [x] `git-publish` (offers git init/commit + `gh` create/push, two confirmation
      gates — chained by `/init-project` after a green bootstrap build, by `/new-feature`
      after an approved spec and by `/new-feature-implement` after a green executor run;
      see D34)

### Meta-tooling (stays in this repo, not copied into generated projects)

- [x] `project-bootstrap`
- [x] `init-project`
- [x] `claude-code-architect-designer` (9 forms, including shared/per-agent MCP server
      — Form 6a/6b, decision matrix § 2.1's CLI-first check; see D33 — hooks (Form 7),
      `permissions` rules (Form 8) and mods (Form 9), always with a decision record)
- [x] `nerviz-cockpit` mod (open skill phase and write territory above the prompt,
      `/nerviz-doctor` in a pane, a `guard` refusal held behind two buttons; Claude Code
      2.1.287+; see 0131)
- [x] `project-initializer` (agent driving `/init-project` from the answers it already
      collected; chains `transport-security-setup` and `sonarqube-setup` through `Skill`)
- [x] `triage-issue` (verifies every claim of an issue against `HEAD`, never taking its
      diagnosis or fix as true; comments and labels only after confirmation; see 0103)
- [x] `issue-verifier` (read-only agent of class `verifier`, invoked by `triage-issue`)

### Copied into every generated project

- [x] `arch-doctor`
- [x] `gof-design-patterns` (renamed from `java-patterns`, see 0088) — its catalog is read by
      every design skill at design time (0089), injected into the executor at `SubagentStart`
      (0077), and `/gof-design-patterns` refactors existing code by hand
- [x] `arch-adopt` (installs or updates this `.claude/` in a project, through `export`)
- [x] `audit-usage` (reads the execution trail back, in billable tokens only, with plan-window
      projections; see 0134)
- [x] `git-publish` (see Feature-design pipeline above)
- [x] `sonarqube-setup` (scanner plugin, project key, server URL, CI step, a local
      SonarQube container through `docker-architect`; chained by `project-bootstrap` and
      `arch-adopt`; see 0091)
- [x] `sonar-lessons` (turns the project's SonarQube analysis into a lessons-learned file
      that names the template, norm or Checkstyle setting behind each group; see 0100)
- [x] `http-client-architect`, `transport-security-setup` (see Feature-design pipeline and
      Norms above; `transport-security-setup` is chained by `project-bootstrap` and
      `arch-adopt`; see 0122)
- [x] `new-feature-implement` (see Feature-design pipeline above)
- [x] `report-issue` (files an issue on this repository from a description or a
      lessons-learned file, behind a confirmation; see 0103)
- [x] `archunit-installer` (agent, `test-architect`'s setup mode)
- [x] `commons-logging-installer` (agent, `/new-feature-implement`'s pre-flight)
- [x] `ArchHook.java` + `ArchHook.jar` + `schemas/extensions.json`

### Enforcement

- [x] Hook-based forbidden-import checks derived from the blueprint
- [x] Frontmatter schema validation (`ArchHook.java schema`)
- [x] Skill and agent classes as data (`skill_classes`, `agent_classes`): body shape, required
      `model`, and write territory, enforced by `schema` and `guard`
- [x] `guard` — write territory, shell writes (`guard bash`), force push refused in every
      spelling, the `Stop` sweep, frozen approved specs
- [x] `compose gate` on `Stop` — a published port no host client can reach blocks; a foreign
      container on a declared port only warns while no container of the project exists
- [x] `doctor gate` — the generated `architectural boundaries` CI job fails on a red
      committed check (`doctor.gate.labels`)
- [x] Generated CI fails when any integration test was skipped or none ran (see 0127)
- [x] Precompiled hook jar, committed, verified byte for byte in CI (`build --verify`)
- [x] Cross-platform hook as a single Java file, exec form (no `.sh`/`.ps1` twins)
- [x] CI (`validate.yml`) — OS matrix (`ubuntu-latest`, `macos-latest`, `windows-latest`)
- [x] CI — invariant 1 (rules is a leaf)
- [x] CI — invariant 2 (single owner per norm, fixed phrase list)
- [x] CI — invariant 3 (no code boilerplate inside `rules/`)
- [x] CI — invariant 4 (no new `commands/`)
- [x] CI — invariant 7 (new blueprint doesn't touch prompts)
- [x] CI — invariant 8 (no hardcoded Spring/Java version)
- [x] CI — invariant 10 (recognized frontmatter fields have a single owner)
- [x] CI — exemplar imports resolve against a real `start.spring.io` request
- [x] Secret scan of `.mcp.json` `headers`/`env` (invariant 11, `ArchHook.java schema`)
- [x] CI (`templates.yml`) — the Checkstyle configs reject what they claim, and the Java
      templates copied verbatim compile and pass their tests in a fresh Initializr project
      (see 0099)
- [x] CI (`mods.yml`) — `claude plugin validate` and `claude plugin test` over `.claude/mods`
- [x] `release.yml` — every PR declares its bump level, and its merge leaves an annotated tag
- [x] `release-notes.yml` — every Friday, or by hand, one GitHub Release groups the tags since
      the previous one, in the PRs' own words, with the migrations added in the range
- [ ] CI — invariant 9 (generated project is self-contained) — half automated: `schema` checks the
      `export` manifest against disk and `export-determinism` exports every blueprint; a real
      generated project compiling is still checked by hand
- [ ] `claude plugin validate .claude/skills` in CI — run by hand before a PR; only `mods.yml`
      installs the CLI, and it validates `.claude/mods` alone

### Multi-agent portability (generated project)

Origin: `@.claude/lessons-learned/lessons-learned-019.md`. Decided on 2026-10-02: adapt this
repository, not a separate Copilot repository. A fork would hold a second copy of every
norm, blueprint and `ArchHook.java`, which is invariant 2 broken at repository scale. Copilot
becomes another target of `export`, fed by the same `rules/` and blueprints.

Split: the **generator** (`/init-project`, `project-bootstrap`, `claude-code-architect-designer`)
stays Claude Code only — it relies on `context: fork`, the `Agent` tool, `AskUserQuestion`,
`` !`…` `` injections and `SubagentStart`, and one person runs it once. The **generated
project** is what a mixed team uses daily, so that is what becomes portable.

Every step goes through `/claude-code-architect-designer` with a decision record. Each
Copilot capability below is re-verified against GitHub's documentation at design time.
Cheapest mechanism first: a step starts only after the previous one is shipped and a real
mixed team reports a gap it did not close.

- [ ] 1. Generated `AGENTS.md` — root, plus one per module where the blueprint emits a module
      `CLAUDE.md`; delivered by `project-bootstrap` and `arch-adopt`, listed in `export`,
      with a `migrations` entry. Open: which file owns the portable facts (`AGENTS.md`
      imported by `CLAUDE.md`, or the reverse), and whether Claude Code reads `AGENTS.md`
      natively. Must say plainly which guarantees hold only under Claude Code
- [ ] 2. Measure the development skills under Copilot, which also loads `.claude/skills/` —
      record which work as-is and which break (`context: fork`, agent delegation, `!`
      injections) before rewriting any
- [ ] 3. Path-scoped norms — derive `.github/instructions/*.instructions.md` (`applyTo`) from
      `rules/*.md` `paths`, through the same mechanism as `export.derived_paths`; derived,
      never a hand-kept second copy
- [ ] 4. Executor as a Copilot custom agent (`.github/agents/*.agent.md`), derived from
      `agent_classes`
- [ ] 5. `guard` wired to Copilot hooks (`.github/hooks/*.json`, `preToolUse`) — needs an input
      adapter in `ArchHook` for Copilot's payload; territory keyed on `agent_type` and skill
      phase may have no equivalent
- Out of scope: porting the generator; the audit trail (built on Claude Code transcripts);
  pattern-catalog injection at `SubagentStart`

### Documentation

- [x] README (EN)
- [x] `docs/` PT-BR (`00-visao-geral`, `01-tipos-de-arquivo`, `02-init-project`, `03-new-feature`, `04-arch-doctor`, `05-blueprints`, `06-claude-code-architect-designer`, `07-ci-validate`, `08-audit-usage`, `09-diferenciais`, `10-arch-adopt`, `11-pitfalls`, `12-issues`)
- [x] `docs/en/` mirror of every PT-BR doc
- [x] Claude Code reference under `docs/*/claude-code-docs/`, including mods (`16-mods.md`)
- [x] Comparison with neighboring GitHub projects and the list of differentiators (`docs/pt-br/09-diferenciais.md`, `docs/en/09-differentiators.md`; README § What this is, and what it is not)
- [x] `blueprints/README.md` + `blueprints/README.pt-br.md` (architecture overview)
- [x] LICENSE
- [x] `SECURITY.md` (private vulnerability reporting, linked first in the issue chooser)
- [x] `CONTRIBUTING.md`
- [x] GitHub Releases with notes, from `v0.15.0`; one per batch of tags since `v0.22.1`
- [x] Worked example with real output: [`nerviz-ai/bank-app`](https://github.com/nerviz-ai/bank-app)
      — generation report, approved specs, audit reports — plus the README's demo GIFs

## Future ideas

- [ ] `/refactoring` — new orchestrating, invocable skill
- [ ] `/solution-design-architect` — new orchestrating, invocable skill

Done: GitHub remote-repo creation in `project-initializer`, shipped as the `git-publish`
skill (Form 1, decision matrix § 2.1 CLI-first check — `gh repo create` + `git push`,
no MCP server) — see D34 above and `@.claude/decisions/0034-git-publish-skill.md`.

# Differentiators — what this is, who the neighbors are, and what is actually unique

Primary source for this repository: the `.claude/` itself (`hooks/ArchHook.java`,
`blueprints/*.yaml`, `skills/*/SKILL.md`, `schemas/extensions.json`),
`.github/workflows/validate.yml`, and `CLAUDE.md` § Invariants.

The landscape was mapped on **2026-10-02**. For each project, we read its public README
and repository tree, and took star counts and last-push dates from the GitHub API that
day. Commercial products are described from their vendors' documentation. A claim we
could not verify is marked as such. A "—" in the tables means *not found in its public
README or tree*, not *proven absent*.

## What category this is

The best-fitting label is **agent harness, specialized for one stack**. Three terms are
in use in 2026, and the repository sits where they overlap:

- **Agent harness / harness engineering.** This is the *agent = model + harness* framing.
  The harness has guides, which steer the model before it acts (norms, skills, specs), and
  sensors, which check what it did (hooks, tests, the build). Here, `rules/`, `skills/`,
  `blueprints/` and the use-case specs are the guides. `ArchHook`, ArchUnit, the
  incremental build and CI are the sensors.
- **Spec-driven development (SDD).** `/new-feature` is *spec-first*: one use case is
  designed per run, consolidated into a spec, approved, and frozen before code is written —
  and the code comes from a second run, `/new-feature-implement UC-NNN-slug`, in a clean session.
- **Project generator.** `/init-project` generates through Spring Initializr, in the
  tradition of JHipster and Seed4J. Here, though, generation is the first step, not the
  product.

What it is **not**: a coding assistant (Claude Code is the assistant, and this repository
brings no model); an agent framework such as LangGraph, CrewAI, or Spring AI (those build
agents, while this one constrains one); a static template (nothing is cloned and versions
are not pinned).

## The neighbors

### 1 · Spring and Java on coding agents

| Project | ★ (2026-10-02) | What it ships | Closest to us on | What it does not do (as far as we found) |
|---|---|---|---|---|
| [loiane/specs-driven-development-spring-angular](https://github.com/loiane/specs-driven-development-spring-angular) | 61 | SDD toolkit for Spring Boot + Angular, on Claude Code, Copilot, and Windsurf. Flow `/spec → /plan → /build → /test → /validate → /review → /ship`, role agents, Maven quality harness (ArchUnit, PIT, JaCoCo, SpotBugs, OWASP), onboarding agent for existing projects | **The closest overall.** Spec pipeline, plus bash hooks that enforce scope: `enforce-files-in-scope.sh` blocks edits outside the active task's files, and `block-impl-without-failing-test.sh` gates implementation on a failing test | No project generation and no selectable architecture. Scope comes from the active task, not from a class per skill. Hooks need bash and `jq`. No per-layer design for security, outbound HTTP clients, messaging, or jobs. No update mechanism found |
| [jabrena/plinth](https://github.com/jabrena/plinth) | 442 | "AI-native Java enterprise SDLC": many skills, agents, and commands, an OpenSpec-based workflow, Jira/GitHub/Azure DevOps integration. Spring Boot, Quarkus, Micronaut; Cursor, Claude Code, Codex, Copilot | Spec pipeline at scale; breadth of Java knowledge | — blueprint-driven generation; — write-territory hooks |
| [a-pavithraa/springboot-skills-marketplace](https://github.com/a-pavithraa/springboot-skills-marketplace) | 78 | Claude Code / Codex plugin. Its `creating-springboot-projects` skill interviews, uses Spring Initializr, and scaffolds one of several progressive architectures (Layered, Modular Monolith, Tomato, DDD+Hexagonal) | **Closest on generation.** Initializr-based, with an architecture choice | Architectures are prose in one skill, not data a hook reads. — spec pipeline, — enforcement hooks, — export into the project |
| [jdubois/dr-jskill](https://github.com/jdubois/dr-jskill) | 341 | An Agent Skill by JHipster's creator that generates Spring Boot applications from start.spring.io, with a database, Docker, and a front end | Initializr generation, opinionated defaults | One opinion, no architecture choice. — spec pipeline, — enforcement |
| [piomin/claude-ai-spring-boot](https://github.com/piomin/claude-ai-spring-boot) | 1,303 | Clonable template: `CLAUDE.md`, agents, skills | The most popular in the niche | Static template, pinned versions. — hooks, — pipeline. Last push 2026-04-29 |
| [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills) | 292 | Spring Boot convention skills | Spring knowledge | Generates nothing, verifies nothing |
| [giuseppe-trisciuoglio/developer-kit](https://github.com/giuseppe-trisciuoglio/developer-kit) | 351 | Multi-language plugin marketplace, with a large Java plugin (agents, commands, skills) | Breadth | Library, not a generator or enforcer |
| [ryu-qqq/claude-spring-standards](https://github.com/ryu-qqq/claude-spring-standards) · [altmemy/claude-code-templates](https://github.com/altmemy/claude-code-templates) | 0 · 25 | Fixed-hexagonal template with skills and hooks · role agents with generic hooks | Template + hooks | One fixed architecture; hooks format or block dangerous commands but do not read the architecture |

### 2 · Classic Java generators, now with AI front ends

| Project | ★ | What it is | Difference |
|---|---|---|---|
| [JHipster](https://github.com/jhipster/generator-jhipster) + [jhipster-mcp](https://github.com/jhipster/jhipster-mcp) | 22k | Deterministic, entity-centric generator. The MCP server lets an agent write JDL and drive the CLI | The AI drives a generator. Nothing governs the code the agent writes afterwards |
| [Seed4J](https://github.com/seed4j/seed4j) (successor of JHipster Lite) | 618 | Modular hexagonal generator that deliberately generates no business code; a community MCP server exposes it | Closest in spirit on "structure, not business code". No agent pipeline or write-time enforcement |
| [Bootify](https://bootify.io) | commercial | Web generator marketed as an AI-first JHipster alternative, with an MCP server | Not verified beyond its marketing page |

### 3 · Language-agnostic SDD frameworks and harnesses

| Project | ★ | What it is | Difference |
|---|---|---|---|
| [GitHub Spec Kit](https://github.com/github/spec-kit) | 140k | `specify` CLI: constitution → specify → plan → tasks → implement, for 30+ coding agents, with an upgrade path | The reference SDD toolkit. Gates are prompt checklists; stack-agnostic |
| [OpenSpec](https://github.com/Fission-AI/OpenSpec) | 71k | Lightweight SDD built on change proposals and spec deltas, aimed at existing code; `openspec update` | Deliberately avoids rigid phase gates — the opposite of a frozen spec |
| [BMAD-METHOD](https://github.com/bmad-code-org/BMAD-METHOD) | 54k | Agile personas (analyst, PM, architect, dev) from PRD to stories | Covers product discovery; enforcement is by prompt |
| [obra/superpowers](https://github.com/obra/superpowers) | 294k | Skills framework and methodology (brainstorm → plan → TDD) across many harnesses | The largest in the space; methodology, not stack governance |
| [Spec Kitty](https://github.com/spec-kitty/spec-kitty) | 1.7k | SDD plus a governance "Charter", work-package lanes, review/accept gates, `spec-kitty upgrade` | **Closest on governance** of the workflow; not stack-specialized |
| [Pilot Shell](https://github.com/maxritter/pilot-shell) | 2.1k | "Context and harness engineering" for Claude Code and Codex: hooks, quality gates, updates | **Closest on enforcement**; not stack-specialized |
| [rails_ai_agents](https://github.com/ThibautBaissac/rails_ai_agents) | 665 | Rails-specific skills, agents, rules, hooks, plus an SDD kit | The same idea for another stack |
| [AWS Kiro](https://kiro.dev) | commercial | IDE with specs (requirements → design → tasks), steering files, and event hooks | Closest commercial product. Proprietary IDE, no stack blueprints. Whether its hooks can block a write before it happens was not verified |
| Factory Spec Mode | commercial | Planning phase is read-only until approved, enforced by the runtime | Similar phase guard, inside a closed product |

Two more things matter for context. [AGENTS.md](https://agents.md) is a context-file format
supported by most coding agents and stewarded by the Linux Foundation; it is a standard,
not a competitor. And stars in this space go to simple things: templates and skill packs
reach hundreds or thousands, while enforcement-heavy toolkits stay small.

## What is actually unique here

No project we found combines all of the following. Each item names the neighbor that comes
closest, so the claim can be checked.

### 1 · Architecture is data, and one declaration has three effects

Seven blueprints (`hexagonal`, `clean-architecture-multi-module`,
`clean-architecture-single-module`, `layered`, `onion`, `vertical-slice`,
`modular-monolith`) plus `custom-template`. Each one is a YAML file with modules,
`depends_on`, `forbidden_imports`, `packages.map`, and mandatory `trade_offs`. A module's
`forbidden_imports` feeds, with no manual copy:

1. the module's `CLAUDE.md`, so the model **knows**;
2. `.claude/forbidden-imports.txt`, so the `check` hook **blocks** the write (`exit 2`);
3. `depends_on` in the POMs, so the **compiler** refuses the import in multi-module layouts.

Norm globs (`paths`) are rewritten from `packages.map` at generation time, so a norm loads
for the packages this blueprint actually has. Adding an architecture touches no skill,
agent, or prompt, and the `new blueprint doesn't touch prompts` CI job checks that on
every push.

*Closest:* a-pavithraa offers an architecture choice, but as prose inside one skill. Seed4J
generates hexagonal structure deterministically, but nothing reads the architecture once
an agent starts writing.

### 2 · Generation through the Initializr, then a self-contained project

`/init-project` calls `start.spring.io` at run time, restructures the result per blueprint,
and exports into the project everything the project will cite. Versions never come from
memory (invariant 8), and CI fails if one is written as a fact. No business code is
generated. The project does not need this repository afterwards (invariant 9).

*Closest:* a-pavithraa and dr-jskill also use the Initializr. Neither exports a harness that
keeps governing the code afterwards.

### 3 · Write territory as data, enforced by one hook

Every skill sits in exactly one of seven classes (`design`, `orchestrator`, `build`,
`observer`, `meta`, `ops`, `report`). Every agent sits in one of four (`driver`,
`executor`, `installer`, `verifier`). The class, in `schemas/extensions.json`, declares the
paths it may write, and `guard` refuses everything else, deny by default:

- through `Write`/`Edit`;
- through the shell write shapes it can read (`>`, `tee`, `sed -i`, `sh -c "…"`);
- and again at `Stop`, against what git sees changed during the turn.

A subagent is judged by its own class, whatever phase its caller left open. A `build`-class
skill cannot even be called during a design run. Every one of these rules was added after
a real run broke it. The first: a design run wrote a service into `docker-compose.yml`, a
file no denylist had named.

*Closest:* loiane's `enforce-files-in-scope.sh` enforces scope per active task, which is a
genuinely similar idea. The differences are that territory here is per class and stored as
data, shell writes and the end-of-turn sweep are covered, and the implementation is one
Java file tested on three operating systems instead of bash plus `jq`.

### 4 · A spec pipeline with one owner per layer, and a frozen approved spec

`/new-feature` designs one use case per run: `use-case-design` → `domain-modeling` →
`rest-api-architect` → `security-architect` (conditional) → `http-client-architect`
(conditional) → `messaging-architect` (conditional) → `jobs-architect` (conditional) →
`persistence-architect` → `cache-architect` (conditional) → `test-architect`. Each skill owns one partial and decides the design patterns of its
layer. The partials are consolidated into `UC-NNN-spec.md`, whose status goes `draft →
approved → implemented` (or `implemented-blocked`). Once a spec is approved, `guard`
freezes its folder except for the status line, checklist toggles, and `CHANGELOG.md`. Only
then does the `java-spring-boot-developer` executor write under `src/`.

*Closest:* loiane and plinth have Spring-aware SDD pipelines, and Spec Kit and OpenSpec are
the generic references. None of them has dedicated design steps for Spring Security,
outbound HTTP clients, Kafka, and scheduled jobs. None freezes the approved spec with a hook.

### 5 · Updating the harness inside a project, with provenance and migrations

`/arch-adopt` installs this `.claude/` into a project never generated here, or updates one
that is behind. It refuses a dirty worktree and writes through the `export` mode, from a
manifest that is data. It stamps `.claude/.arch-provenance.json` with the hash of every
file written, so `/arch-doctor` can name the files edited by hand **before** the next
update overwrites them. On an update, it prints the `migrations` notes for convention
changes the project has not seen; it never runs them.

*Closest:* Spec Kit, OpenSpec, BMAD, and Spec Kitty all have an upgrade command. We found
none that detects local edits by hash before overwriting, or that carries notes for code
an older convention left behind.

### 6 · A deterministic, per-run execution trail

In a generated project, the `audit` hook writes one Markdown report per skill or agent
invocation. It holds billable tokens and the model per piece (no double counting), a chain tree with
durations, files touched, permissions requested, failed tools, and `HEAD` before and
after. Prompts are redacted. Two ledgers feed `/audit-usage`. Producing the report costs
zero tokens, and it survives the session dying.

*Closest:* none found among the neighbors above.

### 7 · The harness validates itself

The eleven invariants in `CLAUDE.md` are gated in CI, not just written down:

- `rules/` is a leaf, and each norm has one owner.
- No code in a norm, and no `commands/`.
- No hardcoded version, and no dependency outside the Java ecosystem.
- The export manifest matches the disk.
- `ArchHook.java schema` validates the frontmatter against the fields the runtime
  recognizes (the runtime ignores an unknown field without a word), the body sections each
  class requires, and `.mcp.json` with a secret scan.
- Every `import` in every `.java.example` exemplar resolves against a classpath from a real
  `start.spring.io` request.

*Closest:* none found. The neighbors validate the generated code, not their own
instruction files.

### 8 · Enforcement in one Java file, no shell

`ArchHook.java` holds eleven modes: `check`, `format`, `tests`, `schema`, `guard`, `audit`,
`compose`, `context`, `doctor`, `export`, `build`. The hooks run the committed, precompiled
`ArchHook.jar` in exec form: no shell, no `chmod`, the same on Linux, macOS, and Windows.
CI requires the jar to be byte for byte what the source compiles to under the pinned JDK.
The tests under `.claude/.ci/` inject each violation and require the block, on all three
operating systems. The mode table is in the [README](../../README.md#enforcement-one-java-file-no-shell).

*Closest:* every hook-based neighbor we found uses bash scripts.

### 9 · Smaller pieces that add up

None of these is unique on its own. Together, no neighbor gathers them:

| Concern | Where it lives |
|---|---|
| `Idempotency-Key` from the first endpoint, via AOP and a shared table | `rest-api-architect` + `persistence-architect` |
| Structured logging with sensitive-data masking | `commons-logging-installer`, offered by `/new-feature-implement`'s pre-flight |
| OTLP collector with traces and metrics; Jaeger or Grafana + Tempo + Prometheus | `docker-architect` |
| Compose diagnosis: a container "up" but not answering, a port held by a sibling project, a default the host cannot reach | `ArchHook.java compose` |
| Transport decided once per project, before any endpoint: edge, direct, or mutual TLS, HTTP/2 on, HSTS with exactly one writer | `transport-security-setup` |
| SonarQube or SonarCloud wired in; Sonar issues traced back to the `.claude/` template that caused them | `sonarqube-setup`, `sonar-lessons` |
| Issues filed from a project only with evidence and stripped of the project's code; every claim checked at `HEAD` before a fix is designed | `report-issue`, `triage-issue` + `issue-verifier` |
| A meta-tool that decides the form of the next extension (skill, agent, rule, hook, permission, MCP server, or nothing) and records it | `claude-code-architect-designer` + `decisions/` |
| Secrets: `permissions.deny` on `*.env`, `*.pem`, `application-prod.yml`; a secret scan of `.mcp.json` | `settings.json` + `ArchHook.java schema` |

## Comparison table

Representatives of each family, as found on 2026-10-02. ✅ yes · ◐ partial · — not found.

| Capability | This repo | loiane SDD | plinth | a-pavithraa | Spec Kit / OpenSpec | Spec Kitty / Pilot Shell |
|---|---|---|---|---|---|---|
| Generates the project via the Initializr, live versions | ✅ | — (template) | — | ✅ | — | — |
| Several architectures to choose from | ✅ 7 + custom, as data | — | — | ◐ as prose | — | — |
| Architecture boundary blocked at write time | ✅ hook + compiler + ArchUnit | ◐ ArchUnit in the build | — | — | — | — |
| Multi-phase spec pipeline | ✅ per layer | ✅ | ✅ | — | ✅ | ✅ |
| Write scope enforced by a hook | ✅ per class, as data | ✅ per task | — | — | — (prompt gates) | ◐ workflow and quality gates |
| Approved spec frozen by a hook | ✅ | — | — | — | — | ◐ accept gates |
| Hooks run without a shell, tested on 3 OSes | ✅ Java | — bash + `jq` | — | — | — | — |
| Per-run token and chain trail | ✅ | — | — | — | — | — |
| Validates its own instruction files | ✅ | — | — | — | — | — |
| Update into an existing project | ✅ with provenance + migration notes | ◐ onboarding | — | ◐ plugin reinstall | ✅ upgrade/update | ✅ upgrade |
| Runs on agents other than Claude Code | — | ✅ | ✅ | ◐ Codex | ✅ | ◐ |
| Breadth beyond Spring Boot servlet | — | ◐ + Angular | ✅ Quarkus, Micronaut | — | ✅ any | ✅ any |

## What is not a differentiator — said honestly

- **Spec-driven development itself.** It is the mainstream category of 2026, with Spec Kit
  at 140k stars. What is specific here is the per-layer design for Spring and the frozen
  spec, not the idea of writing a spec first.
- **Scope-enforcing hooks, as an idea.** loiane's toolkit and Pilot Shell have them. What is
  specific here is territory as class data, shell coverage, the sweep, and the
  cross-platform runtime.
- **Initializr-based generation.** a-pavithraa and dr-jskill do it too.
- **Breadth of Java knowledge.** plinth and developer-kit cover more frameworks and more
  topics. This repository goes deep on one stack: Spring Boot on the servlet stack,
  Maven or Gradle, no WebFlux, no front end.
- **Portability.** Hooks, `paths`, `disable-model-invocation`, and `context: fork` belong to
  the Claude Code runtime. Nothing here runs on Codex, Cursor, or Copilot, while most
  neighbors run on several agents.
- **Cost.** The full pipeline is expensive by design. In one real `/new-feature` run on a
  two-field aggregate, the executor alone took 203 turns and re-read 58.8M cache tokens. The audit trail exists to measure that; see
  [03-new-feature.md § Cost discipline](03-new-feature.md).
- **Adoption.** The rigor has a learning curve, and the project is young and small.
  Simpler templates and skill packs are far more popular.
- **Not affiliated with Anthropic.** "Claude" in the name follows the ecosystem's practice.

## Sources

All repositories were checked on 2026-10-02: README, tree, stars, and last push.

- Spring and Java on agents: [loiane/specs-driven-development-spring-angular](https://github.com/loiane/specs-driven-development-spring-angular) ·
  [jabrena/plinth](https://github.com/jabrena/plinth) ·
  [a-pavithraa/springboot-skills-marketplace](https://github.com/a-pavithraa/springboot-skills-marketplace) ·
  [jdubois/dr-jskill](https://github.com/jdubois/dr-jskill) ·
  [piomin/claude-ai-spring-boot](https://github.com/piomin/claude-ai-spring-boot) ·
  [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills) ·
  [giuseppe-trisciuoglio/developer-kit](https://github.com/giuseppe-trisciuoglio/developer-kit) ·
  [ryu-qqq/claude-spring-standards](https://github.com/ryu-qqq/claude-spring-standards) ·
  [altmemy/claude-code-templates](https://github.com/altmemy/claude-code-templates)
- Generators: [jhipster/generator-jhipster](https://github.com/jhipster/generator-jhipster) ·
  [jhipster/jhipster-mcp](https://github.com/jhipster/jhipster-mcp) ·
  [seed4j/seed4j](https://github.com/seed4j/seed4j) · [bootify.io](https://bootify.io)
- SDD and harnesses: [github/spec-kit](https://github.com/github/spec-kit) ·
  [Fission-AI/OpenSpec](https://github.com/Fission-AI/OpenSpec) ·
  [bmad-code-org/BMAD-METHOD](https://github.com/bmad-code-org/BMAD-METHOD) ·
  [obra/superpowers](https://github.com/obra/superpowers) ·
  [spec-kitty/spec-kitty](https://github.com/spec-kitty/spec-kitty) ·
  [maxritter/pilot-shell](https://github.com/maxritter/pilot-shell) ·
  [ThibautBaissac/rails_ai_agents](https://github.com/ThibautBaissac/rails_ai_agents) ·
  [kiro.dev](https://kiro.dev) · [agents.md](https://agents.md)
- Category naming: Birgitta Böckeler's article on spec-driven development tools in the
  "Exploring Gen AI" series on [martinfowler.com](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html).

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="brand/nerviz-banner-dark.png">
    <img alt="Nerviz — an agent harness for Spring Boot" src="brand/nerviz-banner-light.png" width="100%">
  </picture>
</p>

[![CI](https://github.com/nerviz-ai/nerviz/actions/workflows/validate.yml/badge.svg)](https://github.com/nerviz-ai/nerviz/actions/workflows/validate.yml)
[![Release](https://img.shields.io/github/v/tag/nerviz-ai/nerviz?sort=semver&label=release&color=2747D9)](https://github.com/nerviz-ai/nerviz/tags)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](#contributing)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)](#getting-started)
[![Built with Claude Code](https://img.shields.io/badge/Built%20with-Claude%20Code-D97757.svg)](https://claude.com/claude-code)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-GA%20latest-6DB33F.svg?logo=spring)](https://start.spring.io)
[![GitHub stars](https://img.shields.io/github/stars/nerviz-ai/nerviz.svg?style=social)](https://github.com/nerviz-ai/nerviz/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/nerviz-ai/nerviz.svg)](https://github.com/nerviz-ai/nerviz/issues)
[![Last commit](https://img.shields.io/github/last-commit/nerviz-ai/nerviz.svg)](https://github.com/nerviz-ai/nerviz/commits)

**An agent harness for Spring Boot on Claude Code.** It generates the project from an
architecture declared as data, designs every feature as an approved spec before any code
is written, and blocks at write time what that architecture forbids. Generated projects
carry the whole harness with them and can update it later.

Documentation: [`docs/en/`](docs/en/README.md) (English) · [`docs/pt-br/`](docs/pt-br/README.md)
(português). How it compares with similar projects, with sources:
[`docs/en/09-differentiators.md`](docs/en/09-differentiators.md).

What one `/init-project` run produced is public:
[`nerviz-ai/bank-app`](https://github.com/nerviz-ai/bank-app), a
`clean-architecture-single-module` project with a green build. The run took 20 minutes, a figure
read from the session transcripts into its
[`GENESIS.md`](https://github.com/nerviz-ai/bank-app/blob/main/.claude/audit-usage/GENESIS.md).

The generated project, opened as is: it starts against the compose Postgres, and
`mvn clean verify` passes with tests, Spotless, Checkstyle and JaCoCo.

Then the first feature, inside the generated project. `/new-feature` takes a one-line
request, asks only the questions that change the design (who may call it, duplicates, the
age rule, data retention), and has one skill per layer write its part of the spec. The spec
is approved, frozen, committed and pushed before a single line of code exists. The run
took 10 minutes, about 2 of them waiting for answers.

Everything it wrote is public:
[the approved spec](https://github.com/nerviz-ai/bank-app/blob/main/docs/use-cases/UC-001-create-customer/UC-001-spec.md),
[its partials](https://github.com/nerviz-ai/bank-app/tree/main/docs/use-cases/UC-001-create-customer),
and [the run's audit report](https://github.com/nerviz-ai/bank-app/blob/main/.claude/audit-usage/2026-10-07T08-52-11--new-feature.md).
That is the first of `/new-feature`'s two flows. The second, `/new-feature-implement
UC-001-create-customer` after `/clear`, hands the approved spec to the executor agent in a
clean session.

## At a glance

- **Nine architectures as data**, plus your own. A blueprint is a YAML file. Adding one
  changes no prompt.
- **Spec before code.** One use case per run, designed layer by layer by an owner skill
  for each layer, approved by you, then frozen.
- **Blocked at write time.** A forbidden import, a write outside a skill's territory, an
  edit to an approved spec, or a force push is refused by a hook. The model is not asked to
  remember the rule.
- **Versions from Spring Initializr**, never from the model's memory.
- **Every run measured.** Each skill and agent leaves a report with its time, tokens,
  questions asked and files touched.
- **Self-contained projects.** A generated project carries the whole harness and its own
  CI, and `/arch-adopt` updates it later.

---

## What this is, and what it is not

The working definition in 2026 is *agent = model + harness*. Claude Code supplies the
model and the tool loop. This repository supplies the rest of the harness for Spring Boot
work, in two parts:

- **Guides** steer the model before it acts: norms (`rules/`), procedures and exemplars
  (`skills/`), architecture blueprints, and the use-case spec of the feature being built.
- **Sensors** check what the model did, deterministically: hooks that refuse a write
  outside a skill's territory or a forbidden import, the incremental build, ArchUnit, a
  compose health gate, and a CI that validates the harness itself.

It is **not** a coding assistant: it brings no model and no editor. It is **not** an agent
framework like LangGraph or Spring AI: those build agents, and this repository constrains
one. And it is **not** a project template: it generates through Spring Initializr at run
time, and its job continues after generation, on every feature.

## The problem

You start a Java project with a 30-line `CLAUDE.md`. Six months later it is 400 lines, the
model ignores half of it, and the same rule lives in three skills that no longer agree.
The AI "forgets" the architecture because nothing tells it cheaply and reliably what is
true in this project, and nothing stops it when it gets it wrong.

Every new project also starts from zero. The same conventions get written again, and
nothing learned on the previous project survives.

## The approach

Treat the AI files as production code: an explicit architecture, dependencies in one
direction, one owning file per rule, and enforcement by a program rather than by the
model's memory. Concretely:

- **Architecture is data.** Nine blueprints (`hexagonal`, `hexagonal-single-module`, `clean-architecture-multi-module`,
  `clean-architecture-single-module`, `layered`, `onion`, `onion-single-module`, `vertical-slice`,
  `modular-monolith`) plus your own, each a YAML file. One `forbidden_imports` declaration
  feeds the module's `CLAUDE.md`, the hook that blocks the write, and the POM graph. A new
  architecture touches no prompt, and CI checks that.
- **Versions are never written from memory.** The base project comes from
  `start.spring.io` at run time. CI fails if a Spring or Java version appears as a fact.
- **One use case per run, designed before it is built.** `/new-feature` passes the request
  through one owner skill per layer and consolidates an `UC-NNN-spec.md`. Only after
  approval does a restricted executor agent write code under `src/`.
- **Territory is enforced, not promised.** Every skill and agent has a class in
  `schemas/extensions.json`. The class says which paths it may write, and the `guard` hook
  refuses everything else, through file tools and shell commands alike. An approved spec
  folder is frozen.
- **Context stays cheap.** The root `CLAUDE.md` stays under 200 lines. Norms load through
  `paths` only when a file in their territory is touched.
- **Every run is measured.** In a generated project, each skill or agent invocation leaves
  a report with tokens and the model per piece, the call chain, files touched, and failures.

## Getting started

Requirements: JDK 21+, `git`, `curl`. Nothing else: no Python, no Node, no shell scripts,
no `mvn` on the `PATH` (the wrapper comes inside the Initializr's `starter.tgz`).

### New project

A project is generated **from a copy of this repository**, not by copying `.claude/` into an
empty directory. `/init-project` and the blueprint catalog stay in that copy; the project
receives only what it needs to develop, written by the `export` mode. The copy is a clone,
below, or the tree the plugin fetches without one:

```bash
claude plugin marketplace add nerviz-ai/marketplace
claude plugin install nerviz@nerviz
cd ~/dev && claude        # the directory that will hold the projects
```

Then `/nerviz:new-project`: it fetches the latest release into `~/dev/nerviz-<tag>`, records
which tag and commit it fetched, and prints the session to open there for `/init-project`. The
generation is the same one a clone runs. With a clone instead:

```bash
# 1. clone next to where the projects will live
cd ~/dev
git clone --depth 1 https://github.com/nerviz-ai/nerviz

# 2. confirm the tools (nothing to install); gh is only for the optional push
java --version && git --version && curl --version | head -1
gh auth status

# 3. open the session in the clone
cd nerviz
claude
```

Inside that session:

```
/arch-doctor                 # confirms enforcement is active on this machine
/init-project --blueprint hexagonal --build maven --name my-api
```

Claude Code asks before writing outside the directory the session started in. To approve the
generation once, run `mkdir ../my-api` and `/add-dir ../my-api` before `/init-project`; an empty
directory is accepted.

The project is born next to the clone, at `../<name>` (`~/dev/my-api` above). At the end,
`git-publish` asks twice: once to run `git init` and make the first commit, once to create the
GitHub repository and push. One clone generates as many projects as you want: `/clear`
between them.

Then open a new session **inside the generated project** to develop:

```bash
cd ../my-api
claude
```

```
/new-feature <what the first use case should do>
```

Why this order:

- **The generated project carries its own `.claude/`.** The `export` mode writes the rules,
  the development skills, the executor agents, the hook, and the active blueprint. It leaves
  out `init-project`, `project-bootstrap`, and the blueprint catalog, so a project cannot
  regenerate itself on top of live code. It also refuses to write into this repository.
- **`.claude/settings.json` is read once, at session startup.** The session opened in the
  clone runs with this repository's hooks, not the project's. Developing in it means none of
  the project's hooks fire, and everything *looks* like it works.
- **Accept the first `git-publish` gate.** The `tests` hook uses `git diff HEAD`. In a
  repository without commits, `HEAD` does not exist and the hook exits without running a
  single test.

The project's own `CLAUDE.md` is generated during bootstrap, with the resolved versions and
the real module list. This repository's root `CLAUDE.md` describes the meta-repository and
does not travel. The full generation sequence is in
[`docs/en/02-init-project.md`](docs/en/02-init-project.md).

### Existing project

From inside a session in that project, `/arch-adopt` installs this `.claude/` into a
project that was never generated here, or pulls a newer version into one that is behind.
It refuses a dirty worktree and writes through the deterministic `export` mode. It also
stamps `.claude/.arch-provenance.json`, so the next update names every file you edited by
hand before overwriting it. On an update, it prints the `migrations` notes for convention
changes the project has not seen yet; it never runs them. See
[`docs/en/10-arch-adopt.md`](docs/en/10-arch-adopt.md).

A project on a Spring Boot line below this `.claude/`'s floor is refused by `export` and so by
`/arch-adopt`. `/platform-upgrade` takes it up first: the version bump, the renamed starters and
test slices, and the renamed configuration properties, through a recipe the hook writes from data
and from Spring's own metadata and the Apache-licensed OpenRewrite core runs through the project's
wrapper, Maven or Gradle; then a guided pass for what no rename covers, until the build is green. It
never commits, and ends by printing `/arch-adopt`.

### Silent failures to know about

| Symptom | Cause | Check |
|---|---|---|
| Hooks never fire | Developing in the session opened in the clone, or `settings.json` changed with the session already open | Open `claude` inside the generated project; after a change, `/exit`, then `claude -c` |
| Hooks fail on every OS | `java` not on `PATH` | `/arch-doctor` |
| A forbidden import is not blocked | `.claude/forbidden-imports.txt` does not exist yet (it is generated during init) | `/arch-doctor` shows the number of active rules |
| Tests never run on `Stop` | No `HEAD` (first `git-publish` gate declined), or no Maven wrapper | `/arch-doctor` |
| A pipeline skill behaves like someone else's | A personal skill in `~/.claude/skills/` has the same name, and personal wins over project | `/arch-doctor`, the `Skill shadowing` line |
| A plugin you installed does nothing here | `superpowers` and `mattpocock-skills` are set to `false` in `enabledPlugins` on purpose: their skills compete with the pipeline | Re-enable one for yourself in `.claude/settings.local.json` |

The hooks exit 0 when they cannot verify something. That is safe, but it can give false
greens, which is why `/arch-doctor` exists. More traps, runtime and repository alike:
[`docs/en/11-pitfalls.md`](docs/en/11-pitfalls.md).

---

## Flows

### `/init-project` — creating the project

```
/init-project
     │
     ├─ 1. INTERVIEW, in the main session — blueprint · coordinates · build · features · TLS · Sonar
     │
     ▼  agent: project-initializer (isolated context, model: sonnet, asks nothing)
     │
     ├─ 2. VALIDATES blueprint — 6-rule checklist            [fails fast]
     ├─ 3. BASE via Spring Initializr (curl)                 [GA versions, nothing hardcoded]
     ├─ 4. RESTRUCTURES into modules per the blueprint       [exemplars give the shape]
     ├─ 5. GENERATES forbidden-imports.txt + root and module CLAUDE.md/AGENTS.md, CI, Checkstyle,
     │      lombok.config, logback, Dockerfile + docker-compose
     ├─ 6. EXPORTS norms, development skills, executor agents — the project is self-contained
     ├─ 7. INSTALLS hooks (ArchHook.jar + extensions.json + settings.json), opens the audit trail
     ├─ 8. VERIFIES build + smoke + tested boundary block; writes README + GENESIS.md
     ├─ 9. REPORT in the fixed output contract
     ├─ 10. GENESIS.md figures from the session transcripts — Started, Finished, tokens
     └─ if the build is green: OFFERS git-publish — two independent confirmations
```

No business code is generated: no `ExampleController` to delete. The first feature comes
from a real spec.

### `/new-feature` — one use case, spec first, code in a clean session

One command, two flows. The argument decides which, and the two never share a session: the
design leaves the main thread at 250–370k tokens of context, and implementing on top of it
would pay for that on every turn.

**Flow 1 — create the spec.** `/new-feature <feature description>`

```
/new-feature <feature description>     (or UC-NNN-slug to resume a draft, or empty to list)
     │
     use-case-design → domain-modeling → rest-api-architect (unless GraphQL only)
        → graphql-api-architect (conditional)
        → security-architect (conditional) → http-client-architect (conditional)
        → ai-architect (conditional) → search-architect (conditional)
        → messaging-architect (conditional) → jobs-architect (conditional)
        → persistence-architect → cache-architect (conditional) → test-architect
     │  one use case per run; a split goes to docs/use-cases/BACKLOG.md
     │  design skills write only docs/ — never src/, never docker-compose.yml, never git
     │  a missing DB or broker is asked about and recorded: add it, runs elsewhere, or by hand
     │  each one decides the design patterns of its own layer, in its partial
     │
     ▼  consolidates into UC-NNN-spec.md (status: draft)
     ▼  asks approval → status: approved (the guard hook freezes the folder from here)
     ▼  git-publish (the approved spec only)
     ▼  prints the next two commands:  /clear  ·  /new-feature-implement UC-NNN-slug
```

**Flow 2 — implement the spec.** `/new-feature-implement UC-NNN-slug` over an approved spec,
after `/clear`

```
/new-feature-implement UC-NNN-slug     (status: approved — the command is the request)
     │
     │  pre-flight, once per project (ArchUnit, logging/masking aspects)
     │  agent: java-spring-boot-developer, pattern catalog injected at start,
     │         in three chained groups, each starting again from the spec and the disk:
     │
     │    domain (blocks 1–2) → adapters (H, 3, S, M, J) → tests (block 4, ./mvnw verify)
     │
     │  each group ticks its checklist steps when it ends green;
     │  a failed group stops the chain, and the same command resumes there
     │
     ▼  status: implemented (or implemented-blocked)
     ▼  agent: compose-service-installer → docker-architect, for each service the spec
     │         marked materialize — the container lands in the same commit as the code
     ▼  git-publish (feature commit)
```

Each step's output contract is literally the next step's input contract. A free-prose
handoff degrades by the third hop. Details: [`docs/en/03-new-feature.md`](docs/en/03-new-feature.md).

What the consolidation produces: one `UC-NNN-spec.md` that keeps a single decision per
fact, links back to the partial that owns each one, and ends in an implementation order
the executor follows. An example is bank-app's
[`UC-001-spec.md`](https://github.com/nerviz-ai/bank-app/blob/main/docs/use-cases/UC-001-create-customer/UC-001-spec.md).
Conditional layers the use case does not need, such as security, outbound HTTP, messaging
and jobs, are recorded as not applicable instead of being left out silently.

### Edit cycle — what runs on every write in a generated project

```
prompt (UserPromptSubmit)
     └─ guard prompt        baseline of the working tree for this turn's sweep

Write / Edit
     ├─ guard               path outside the active skill's or agent's territory → blocked
     │                      approved spec folder → blocked
     │                      Skill(build class) during a design run → blocked
     ├─ schema              frontmatter + body of .claude/**/*.md, .mcp.json   (blocks)
     ├─ format              spotless on the touched module                    (never blocks)
     ├─ check               forbidden imports + incremental test-compile      (blocks)
     └─ audit               file touched → appended to the run's log          (never blocks)

Bash
     └─ guard bash          force push → refused; a redirect, sed -i, tee… outside
                            the territory or into a frozen folder → blocked

SubagentStart
     └─ context subagent    design-pattern catalog → agents whose class asks for it

Stop
     ├─ guard sweep         what git sees changed this turn, re-checked       (blocks)
     ├─ compose gate        a service down, or published and unreachable      (blocks)
     ├─ audit flush         renders the run's report + ledger line            (never blocks)
     └─ tests               tests of the changed modules                      (blocks)
```

### Audit trail — what every run spent

The report of the bank-app `/new-feature` run described above, as it lands in the project:
[`2026-10-07T08-52-11--new-feature.md`](https://github.com/nerviz-ai/bank-app/blob/main/.claude/audit-usage/2026-10-07T08-52-11--new-feature.md).
It shows the duration with the time spent waiting for the user taken out, the chain of
skills with each one's share, tokens per piece, tool calls and peak context per piece, the
most expensive turns, the files touched, and every question asked with the answer given.

The trail is a hook, not a skill. It survives the model forgetting and the session dying,
and it costs zero tokens to produce. Prompts are redacted before they land in git.
`/audit-usage` aggregates the ledgers across runs. Details:
[`docs/en/08-audit-usage.md`](docs/en/08-audit-usage.md).

### Issues — filed from a project, verified here

`/report-issue`, run inside a generated project, files an issue on this repository. It
refuses a report with no evidence, warns when the project is behind the latest release, and
strips the project's own code and names from the body. Here, `/triage-issue <N>` has a
read-only agent check every claim against `HEAD`, including the reporter's diagnosis and
proposed fix, before anything is commented or designed. Details:
[`docs/en/12-issues.md`](docs/en/12-issues.md).

---

## What is in the box

| Piece | Count | Where |
|---|---|---|
| Architecture blueprints | 7 + a template for your own | `.claude/blueprints/` |
| Norms, loaded on demand through `paths` | 23 | `.claude/rules/` |
| Skills (design, orchestration, build, ops, meta…) | 30 | `.claude/skills/` |
| Agents (driver, executor, installer, verifier) | 6 | `.claude/agents/` |
| Hook modes, in one Java file | 11 | `.claude/hooks/ArchHook.java` |
| Decision records — why each piece has its form | 100+ | `.claude/decisions/` |

**Design skills of the `/new-feature` pipeline:** `use-case-design`, `domain-modeling`,
`rest-api-architect`, `graphql-api-architect` (the inbound GraphQL adapter, schema-first
on the servlet stack: root fields, `userErrors`, connections, `@BatchMapping`, field-level
access — only when the use case's trigger names it), `security-architect` (Spring Security on the servlet stack: who may
call each endpoint, JWT, opaque token, API key, 401/403, CORS), `http-client-architect`
(outbound HTTP client and engine, timeouts, retry, circuit breaker, outbound
authentication), `ai-architect` (a language model or vector store behind a port —
structured output, semantic search, prompts as resources, what the answer may change),
`search-architect` (an Elasticsearch or OpenSearch index kept as a rebuildable projection
of a source of truth: strict mapping, alias, indexer, rebuild, lag — only when the use case
names a search index), `messaging-architect` (Kafka producer and consumer, retry, DLQ, the AsyncAPI contract),
`jobs-architect` (`@Scheduled`, ShedLock, Quartz, Spring Batch, db-scheduler, JobRunr; the
outbox relay), `persistence-architect` (relational with JPA or document with MongoDB, as
the use case's `Storage` row states; stamps or an Envers history when its `Audit` row asks), `cache-architect` (Caffeine or Redis behind
a decorator of the outbound port, keys, expiry, eviction on every write — only when the use case
states how old an answer may be), `test-architect`.

**Cross-cutting concerns that arrive solved:** `Idempotency-Key` through AOP, logging with
sensitive-data masking, an OTLP collector with Jaeger or Grafana + Tempo + Prometheus,
transport security decided once per project through `transport-security-setup` (edge,
direct, or mutual TLS, HSTS with exactly one writer), SonarQube or SonarCloud setup,
compose port-collision and reachability checks, and `permissions.deny` on secrets files.

**What travels into a generated project:** the norms, the development skills, the executor
agents, the hook and its schema, and the active blueprint. The manifest of what travels is
data, the `export` block of `schemas/extensions.json`, and the `schema` hook fails if a
skill or agent on disk is missing from it. Creation-only pieces stay here:
`project-bootstrap`, `init-project`, `new-project`, `claude-code-architect-designer`, `triage-issue`,
`project-initializer`, `issue-verifier`, and the blueprint catalog. `arch-adopt` is the one
creation skill that travels, because it is how the project updates itself once this
repository is out of the picture.

---

## Blueprints

| ID | Layout | Choose if | Cost |
|---|---|---|---|
| `layered` | single-module | CRUD, familiar controller/service/repository vocabulary | ArchUnit is the only boundary, not the compiler |
| `clean-architecture-multi-module` | multi-module | Long-lived system, medium or large team | More ceremony, many mappings |
| `clean-architecture-single-module` | single-module | Clean Architecture without the module count | ArchUnit is the only boundary |
| `hexagonal` | multi-module | Several entry and exit channels | More modules than layered |
| `hexagonal-single-module` | single-module | Several entry and exit channels, without one POM per adapter | ArchUnit and the boundary map are the only boundary, not the compiler |
| `onion` | multi-module | Palermo's terminology; persistence and presentation as independent peers | Larger domain compilation unit |
| `onion-single-module` | single-module | Palermo's terminology without the module count | ArchUnit and the boundary map are the only boundary |
| `vertical-slice` | single-module | Loosely coupled features, fast delivery | Duplication between slices |
| `modular-monolith` | single-module | Several bounded contexts (Spring Modulith) | A second verifier (`ApplicationModules.verify()`) on top of ArchUnit |
| `custom` | your choice | You already have an in-house pattern | You describe and validate it |

Do not default to the fanciest architecture. Over-engineering is paid every day;
under-engineering is paid once, at refactor time.

To add one, copy `.claude/blueprints/custom-template/custom.template.yaml`, fill it in, and
make it pass the 6-rule checklist in `_schema.md`: an acyclic `depends_on` graph, exactly
one `contains_main`, every referenced feature and template existing, and honest
`trade_offs`. No skill, agent, or prompt changes. Contract and pros and cons of each:
[`docs/en/05-blueprints.md`](docs/en/05-blueprints.md) ·
[`.claude/blueprints/README.md`](.claude/blueprints/README.md).

---

## How the harness itself is built

The same Clean Architecture applied to Java code applies to the AI files. Dependencies flow
in one direction, with no cycles:

```
                    ┌──────────────────────────────────────┐
   INFRA/ENFORCE    │  hooks/  ·  settings.json            │  deterministic
                    └──────────────┬───────────────────────┘
                                   │ verifies
                    ┌──────────────▼───────────────────────┐
   ADAPTERS         │  agents/  (isolated execution)       │  entry points
                    └──────────────┬───────────────────────┘
                                   │ invokes
                    ┌──────────────▼───────────────────────┐
   APPLICATION      │  skills/  (procedure + exemplars)    │  the "how"  ·  /name
                    └──────────────┬───────────────────────┘
                                   │ cites (never copies)
                    ┌──────────────▼───────────────────────┐
   DOMAIN           │  rules/   (norms)  ·  blueprints/    │  the "what / what not"
                    └──────────────────────────────────────┘
                              ↑ LEAF — references no one
```

The rules that follow from it, all gated in CI (the full list of eleven invariants is in
[`CLAUDE.md`](CLAUDE.md)):

1. **`rules/` is a leaf.** A norm never mentions a skill, agent, or command.
2. **Each norm has one owning file.** Others cite it by path. A rule written in two places
   will diverge.
3. **`CLAUDE.md` is an index, not a manual.** Invariants and routing, under 200 lines.
4. **Skills own their exemplars.** Boilerplate lives in `templates/*.example` inside the
   skill that emits it, never in a norm.
5. **No `commands/`.** A skill with `disable-model-invocation: true` gives the same `/name`
   plus a support folder, `paths`, and `context: fork`.
6. **An agent exists for one of three reasons only:** preserving context, restricting
   tools, or changing model. Otherwise it is a skill.
7. **Hooks verify, they do not teach.** If a rule must always hold, it is a hook or a
   `permissions.deny` line, not prose.

### Where to write what

| You are writing | Goes to | Why |
|---|---|---|
| "Never use `@Autowired` on a field" | `rules/naming.md` | A norm, single source |
| "How to create a REST adapter, step by step" | `skills/rest-api-architect/SKILL.md` | A procedure |
| A reference `Controller.java` | `skills/rest-api-architect/templates/` | The exemplar of the skill that applies it |
| "The domain does not import Spring" | `rules/architecture-ddd.md` + `blueprints/*.yaml` | A norm plus verifiable data |
| "Run spotless after editing" | `hooks/ArchHook.java` + `settings.json` | Verifiable by command |
| "Never read `application-prod.yml`" | `settings.json` → `permissions.deny` | Compliance is enforced, not requested |
| "This skill may only write under `docs/use-cases/`" | `schemas/extensions.json` → `skill_classes` | Territory is data a hook reads |
| A ritual you trigger by hand (`/release`) | `skills/release/SKILL.md` + `disable-model-invocation: true` | No new `commands/` |

New pieces are designed with `/claude-code-architect-designer`, which picks one of eight
forms (or "create nothing") and records hooks and permissions under `decisions/`. The
frontmatter fields the runtime recognizes are data in `schemas/extensions.json`, explained
in [`frontmatter-fields.md`](.claude/skills/claude-code-architect-designer/references/frontmatter-fields.md).
The runtime ignores an unknown field without a word, so `ArchHook.java schema` fails on one.

### Structure

```
nerviz/
├── CLAUDE.md                      # facts about this meta-repo (not the generated project's)
├── claude-help.md                 # Claude Code runtime reference the docs cite
├── roadmap.md                     # phases and feature checklist
├── docs/en/ · docs/pt-br/         # how the pieces collaborate, in two languages
├── .github/workflows/             # validate.yml (CI) · release.yml (version tagging) · release-notes.yml (batched Releases)
└── .claude/
    ├── settings.json              # hooks + permissions (versioned)
    ├── schemas/extensions.json    # single owner of recognized fields, skill and agent classes,
    │                              #   write territories, hook registrations, the export manifest
    ├── rules/                     # norms, loaded on demand — leaves of the graph
    ├── blueprints/                # architectures as data, plus _schema.md
    ├── skills/                    # procedures + templates/*.example
    ├── agents/                    # isolated execution
    ├── hooks/
    │   ├── ArchHook.java          # enforcement, one file, eleven modes
    │   └── ArchHook.jar           # what every hook runs — committed, rebuilt by `build`
    ├── decisions/                 # why each piece has its form — history, not norms
    ├── lessons-learned/           # what real runs broke — history, not norms
    └── .ci/                       # CI tests, each one runs the jar
```

A file-by-file map of skills and agents is in [`docs/en/00-overview.md`](docs/en/00-overview.md).

---

## Enforcement: one Java file, no shell

Every hook launches `.claude/hooks/ArchHook.jar`, compiled from the single source file
`ArchHook.java` and invoked in exec form:

```json
{ "type": "command", "command": "java",
  "args": ["-jar", "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar", "check"] }
```

No shell is invoked, so bash, Git Bash, WSL, or PowerShell make no difference. There is one
implementation instead of a `.sh` and a `.ps1` that drift apart. And the dependency
already exists, because the audience has a JDK by definition. The jar is committed, and CI
requires it to be byte for byte what the source compiles to under the pinned JDK.
Startup costs about 0.3 s per invocation.

| Mode | Event | Blocks? | What it does |
|---|---|---|---|
| `check` | `PostToolUse` on writes to `.java` | yes | forbidden imports + incremental `test-compile` of the touched module |
| `format` | same | no | `spotless:apply` on the module |
| `tests` | `Stop` | yes | `verify` without ITs and coverage on the modules changed since `HEAD`, deferred while a writer subagent runs |
| `schema` | `PreToolUse` / `PostToolUse` / `Stop` | yes | frontmatter and body of skills, agents, rules; `.mcp.json` with a secret scan; hook registrations; the plugins `enabledPlugins` must keep off; the export manifest |
| `guard` | `UserPromptSubmit`, `PreToolUse` (`Write`/`Edit`, `Bash`, `Skill`/`Agent`), `Stop` | yes | write territory per skill and agent class (allowlist, deny by default); frozen approved specs; no `build`-class skill during a design run; force pushes refused; end-of-turn sweep |
| `compose` | `Stop` (`gate`), manual, inside `doctor` | `gate` only | services running, no foreign container on the ports, reachable published ports, `image:` tags matching `src/test`, `${VAR:default}` that holds on the host and in the `app` container |
| `context` | `SubagentStart` (generated project) | no | hands the design-pattern catalog to agents whose class declares it |
| `audit` | eleven lifecycle events (generated project) | no | execution trail of every skill and agent |
| `doctor` | manual (`/arch-doctor`), generated CI (`gate`) | `gate` only | diagnoses the setup on this machine, a personal skill shadowing a project one included; `gate` fails on a red committed check (`doctor.gate.labels`) |
| `export` | manual (through `/arch-adopt`) | no | writes a target project's `.claude/` from the manifest, with the provenance stamp |
| `upgrade-recipe` | manual (through `/platform-upgrade`) | no | writes the OpenRewrite recipe that takes a project below the Spring Boot floor to it, from `doctor.platform.upgrade` and the resolved jars' own property metadata |
| `build` | `PostToolUse` on `ArchHook.java` (this repo), manual | no | rebuilds the jar; `--verify` compares it byte for byte |

Every list the hook reads is data in `schemas/extensions.json`, never a constant in the
Java. A new skill is validated, audited, and fenced into its territory by adding one entry
to a class.

## CI

The invariants in `CLAUDE.md` are prose until something checks them. `validate.yml` runs on
every push and PR:

- **Hooks on three operating systems.** `doctor`, `build --verify`, and the tests under
  `.claude/.ci/` run on `ubuntu-latest`, `macos-latest`, and `windows-latest`. Each test
  injects a violation (a forbidden import, a write outside a territory, a force push, a
  compose tag mismatch…) and requires the block, against the same jar the hooks run.
- **The harness's own design.** `rules/` is a leaf, each norm has one owner, no code in a
  norm, no `commands/`, a new blueprint touches no prompt, no hardcoded Spring or Java
  version, no dependency outside the Java ecosystem, the export manifest matches the disk.
- **Exemplars that compile.** Every `import` in every `.java.example` resolves against a
  classpath downloaded from a real `start.spring.io` request.
- **Deterministic export.** Two exports per blueprint produce identical trees, and the
  exported `.claude/` validates on its own.

Every job and step, plus the known gaps: [`docs/en/07-ci-validate.md`](docs/en/07-ci-validate.md).

The generated project gets its own `.github/workflows/build.yml`, which runs only what
exists inside the project:

- **`verify`**: tests, Spotless, Checkstyle and the JaCoCo coverage gate. The runner has
  Docker, so Testcontainers-based tests run for real.
- **Integration tests actually ran.** The job fails when any integration test was skipped
  or none ran, so a disabled Docker guard cannot leave the build green.
- **Architectural boundaries.** `doctor gate` fails when a committed check of the harness
  is red: no boundary rules, an invalid schema, a stale hook jar, broken hook
  registrations, or a bad `.mcp.json`.

---

## Limits, said plainly

- **Claude Code only.** Hooks, `paths`, `disable-model-invocation`, and `context: fork`
  belong to the Claude Code runtime. Nothing here runs on Codex, Cursor, or Copilot.
- **Spring Boot only, servlet stack.** No WebFlux, no Quarkus or Micronaut, no front end.
- **The full pipeline is expensive by design.** One `/new-feature` run designs every layer
  before the executor writes code. The audit trail exists to measure that; the levers are
  in [`docs/en/03-new-feature.md`](docs/en/03-new-feature.md).
- **Rigor has a learning curve.** The guards refuse writes on purpose. When one blocks, its
  message names the rule and the fix, and [`docs/en/11-pitfalls.md`](docs/en/11-pitfalls.md)
  explains each one.

## Documentation

| Read | To learn |
|---|---|
| [`09-differentiators.md`](docs/en/09-differentiators.md) | What category this is, who the neighbors are, what is and is not unique here |
| [`00-overview.md`](docs/en/00-overview.md) | The full graph of skills, agents, rules, hooks, and who calls whom |
| [`01-file-types.md`](docs/en/01-file-types.md) | How each piece behaves in the Claude Code runtime |
| [`02-init-project.md`](docs/en/02-init-project.md) · [`03-new-feature.md`](docs/en/03-new-feature.md) · [`04-arch-doctor.md`](docs/en/04-arch-doctor.md) | The main commands, step by step, with example output |
| [`05-blueprints.md`](docs/en/05-blueprints.md) | The blueprint contract and how to write one |
| [`06-claude-code-architect-designer.md`](docs/en/06-claude-code-architect-designer.md) | The nine forms an extension can take, and the decision matrix |
| [`07-ci-validate.md`](docs/en/07-ci-validate.md) | What CI verifies and what it still does not |
| [`08-audit-usage.md`](docs/en/08-audit-usage.md) | The audit trail, the guard hook, and `/audit-usage` |
| [`10-arch-adopt.md`](docs/en/10-arch-adopt.md) | Installing or updating this `.claude/` in an existing project |
| [`11-pitfalls.md`](docs/en/11-pitfalls.md) | Every silent trap, the runtime's and this repository's |
| [`12-issues.md`](docs/en/12-issues.md) | `/report-issue` and `/triage-issue` |
| [`roadmap.md`](roadmap.md) | Delivery phases and the feature checklist |

Portuguese versions of every document live under [`docs/pt-br/`](docs/pt-br/README.md).

## Contributing

Blueprints and norms are the most useful contributions. Full guide, per file type:
[`CONTRIBUTING.md`](CONTRIBUTING.md). Before opening a PR:

- A new blueprint passes the 6-rule checklist in `_schema.md` and declares honest
  `trade_offs`.
- A new norm lives in its own file under `rules/`, is listed in `00-index.md`, and is not
  copied into any skill.
- No PR pins a Java or Spring Boot version.
- No PR adds a dependency outside JDK, git, and curl. `validate.yml` fails if it does.
- `java .claude/hooks/ArchHook.java doctor` and `schema` both pass locally.

Bugs and proposals have forms:
[new issue](https://github.com/nerviz-ai/nerviz/issues/new/choose).

## Name

Nerviz is pronounced *NAIR-viz*, like Nervi plus a z: it is named after Pier Luigi Nervi,
the engineer-architect whose structures are their own form. Formerly
`claude-spring-architect`; the old GitHub address redirects here.

## License

MIT.

Not affiliated with Anthropic, Broadcom or Oracle. Claude Code, Spring and Java are named
only to describe what Nerviz works with; this is not an official product of any of them.

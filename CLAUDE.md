# Nerviz

Meta-repository: generates Spring Boot projects already prepared for AI-assisted
development. **It is not a Java application** — it has no `pom.xml`, does not compile,
has no Maven tests. What gets edited here are instruction files, data, and a hook.

Purpose, features and status: `@README.md`.

## Dependencies

`java` (JDK 21+) · `git` · `curl`. Nothing else. Zero Python, zero shell, zero `mvn` on
PATH — the wrapper comes in the Initializr's `starter.tgz`. The mods under `.claude/mods/`
run inside Claude Code itself; only the `mods` CI workflow installs anything else (the
Claude CLI, from npm, to test them).

## Commands

| Action | Command |
|---|---|
| Diagnose the setup on this machine | `/arch-doctor` |
| Create a project from a blueprint | `/init-project` |
| Design a new extension of this `.claude/` | `/claude-code-architect-designer` |
| Run the hook by hand | `java .claude/hooks/ArchHook.java doctor` |
| The same report as a CI gate — exit 1 when a line `doctor.gate.labels` lists is red (boundary rules, a source file importing what its module forbids, schema, hook jar, hook registrations, `.mcp.json`, audit overrides); runner state never gates. The generated project's `architectural boundaries` job runs it | `java .claude/hooks/ArchHook.java doctor gate` |
| Check one file against the architectural boundary by hand — exit 2 on a forbidden import, 1 when the path is no `.java` file, never a read of stdin | `java .claude/hooks/ArchHook.java check <path>` |
| Check every compose service is up, no foreign container holds its ports (a warning, never a block, while no container of the project exists), every published port is advertised at a host-resolvable address, and every `${VAR:default}` pointing at a service holds on the host and in the `app` container — plus a warning, never a block, when the datasource's database or user disagrees with the database service it reaches, or has no password where the service requires one | `java .claude/hooks/ArchHook.java compose` |
| Check which paths a shell command would write, and whether the guard admits them — and whether it is a force push, which it blocks | `echo '{"tool_input":{"command":"…"}}' \| java .claude/hooks/ArchHook.java guard bash` |
| Sweep what the current turn wrote and no tool-time guard admitted against the open phase's territory and the frozen folders — the audit trail excepted | `echo '{}' \| java .claude/hooks/ArchHook.java guard sweep` |
| Print the open skill phase — its skills, class and write territory — as one JSON line; read-only, what the `nerviz-cockpit` mod draws | `echo '{"session_id":"…"}' \| java .claude/hooks/ArchHook.java guard status` |
| The compose check as a gate — silent while healthy, exit 2 otherwise | `echo '{}' \| java .claude/hooks/ArchHook.java compose gate` |
| Show what an agent receives at `SubagentStart` — the pattern catalog, or nothing | `echo '{"agent_type":"java-spring-boot-developer"}' \| java .claude/hooks/ArchHook.java context subagent` |
| Render the execution trail of a run by hand | `java .claude/hooks/ArchHook.java audit flush` |
| Fill a generated project's `GENESIS.md` — Started, Finished and tokens — from this session's transcripts; `/init-project` runs it, once | `java .claude/hooks/ArchHook.java audit genesis <project> <session-id>` |
| Write a target project's `.claude/` from this one, transformed for a blueprint | `java .claude/hooks/ArchHook.java export <dest> --blueprint <id> [--ref <label>] [--commit <sha>] [--dry-run]` |
| Validate frontmatter of all extension files (a skill named after a native command, a rule without `paths`), `.mcp.json`, every hook registration in `settings.json` and in `project-bootstrap`'s template, every `` !`…` `` injection's paths, and the `export` manifest against what is on disk | `java .claude/hooks/ArchHook.java schema` |
| Rebuild `.claude/hooks/ArchHook.jar` — what every hook launches — from the source, under the JDK `hook_build.javac_feature` pins | `java .claude/hooks/ArchHook.java build` |
| Check the committed jar is byte for byte what the source compiles to (CI runs it) | `java .claude/hooks/ArchHook.java build --verify` |
| List and inspect this repo's MCP servers | `claude mcp list` · `/mcp` |

## Architecture of the AI files

Clean Architecture applied to `.claude/` itself. Dependencies point in one direction:

```
mods/                       interface — draws what hooks/ decide, only where it loads
        ↓ asks (java -jar ArchHook.jar), never restates
hooks/ + settings.json      infra/enforcement — deterministic
        ↓ verifies
skills/                     procedure + exemplars
        ↓ invokes                    ↑ delegates via context: fork
agents/                     isolated execution
        ↓ cites, never copies
rules/ + blueprints/ + .mcp.json   norms and data   ← LEAF

decisions/                  history — nobody reads it at runtime, outside the graph
```

`claude-code-architect-designer` writes upward into `hooks/ + settings.json` after approval —
a design-time edge, like `project-bootstrap` writing `src/`; the runtime direction is unchanged.

## Invariants (non-negotiable)

1. **`rules/` is a leaf.** A norm never mentions a skill, an agent, or a command. If it
   needs to, it's a procedure and belongs in a skill.
2. **Each norm has a single owning file.** Others cite it by path
   (`@.claude/rules/naming.md`). A norm written in two places has diverged — that's a bug.
3. **Code boilerplate lives in `templates/` inside the skill that emits it**, with the
   `.example` suffix. Never inside a norm, never pasted into the body of a `SKILL.md`.
4. **No new `commands/` are created.** Slash commands were merged into skills: write
   `skills/<name>/SKILL.md` and control invocation with `disable-model-invocation`.
5. **An agent exists only for one of three reasons:** preserving context, restricting
   tools, or changing model. If none applies, it's a skill.
6. **If a rule must always hold, it's a hook or `permissions.deny`** — not prose in
   markdown. Owner: `claude-code-architect-designer`, forms 7 and 8, after approval and
   always with a record in `decisions/`. The mirror holds too — a hook against a failure
   nobody observed is a process per event confirming what was already true.
7. **Architectures are data.** Adding a blueprint must never require editing a skill, an
   agent, or a command.
8. **Java and Spring Boot versions are never written from memory.** They are resolved at
   runtime via Spring Initializr; without network access, ask.
9. **The generated project is self-contained.** Whoever clones it does not have
   Nerviz. Everything cited from inside the project must exist inside the
   project: norms, development skills, agents, `ArchHook.java` and
   `schemas/extensions.json`. `ArchHook.java export` writes all of it (step 6.6 of
   `project-bootstrap`), and **what travels is data, in the `export` block of
   `@.claude/schemas/extensions.json`** — creation skills excluded on purpose, and the
   blueprint **catalog** too, with one exception the manifest names: the **active**
   blueprint travels (`export.blueprint_copy`), because the stamp records its id, an
   update has to resolve it, and a blueprint written during adoption exists nowhere
   else. A new norm, skill or agent is
   complete once that block lists it: every skill and agent on disk in `include` or
   `exclude`, a `derived_paths` entry for a rule with a package territory, and
   `ArchHook.java schema` failing by name on either gap.
   **An MCP server has the same obligation, per server, not per file:** it is declared
   for this meta-repo (`.mcp.json`), for the generated project
   (`project-bootstrap/templates/mcp.json.example`, copied by `export.optional_copy`),
   or both — and the file it's written into **is** that declaration. A server useful to both is
   written in both files on purpose; that is not a duplication bug, see invariant 2.
   **A hook, the same:** the registration is per file (`.claude/settings.json`,
   `project-bootstrap/templates/settings.json.example`); a mode inside `ArchHook.java`
   needs nothing, the `export` mode copies that file whole.
   **A skill has a second obligation on top of `export`:** a class in `skill_classes`,
   which is what gives it a body shape and a write territory. A skill in no class has no
   territory — `schema` fails by name on it, in this repo and inside a generated project.
   **An agent has the same second obligation**, in `agent_classes`: its class fixes the
   sections its body carries, the frontmatter fields it must declare (`model` and `tools`
   always — two of the three reasons it is allowed to exist), whether it may write at all
   (`executor`), and the paths it may write. Territory there is keyed on `agent_type`, so it
   holds no matter which skill phase the caller left open.
10. **Recognized frontmatter fields, `.mcp.json`'s server fields, and `settings.json`'s
    hook events and entry fields are data with a single owner.** The list lives in
    `.claude/schemas/extensions.json`, `ArchHook.java schema` is what reads it, and any
    other file that displays it is derived and must match it exactly. A corollary of 2
    and 7, written separately because its failure mode is silent: the runtime ignores an
    unknown field without any error — an unknown *event* too, and a `matcher` on an event
    that never reads one — and `claude plugin validate` lets it all through, looking at
    neither `.mcp.json` nor `settings.json`. Binds the Java too: a mode reads its lists
    from `extensions.json`, never from a constant in the source.
11. **No literal secret in a versioned file.** `.mcp.json` (this repo's or the copy
    inside a generated project) carries only `${VAR}` / `${VAR:-default}` expansion,
    `oauth`, or `headersHelper` — never a token, key, or password spelled out. Verified
    by `ArchHook.java schema`'s secret scan over `headers`/`env`. A leaked token in git
    history is irreversible; that is why this is a hook and not a review checklist.

## Routing — when X, read Y

| If the task involves | Go to |
|---|---|
| Creating a project from scratch | skill `project-bootstrap` |
| Installing this `.claude/` into a project that was never generated here, or pulling a newer version into one that was | skill `arch-adopt` — manual only, runs **inside the target project**, refuses a dirty worktree, and writes through `ArchHook.java export` — plus the root `CLAUDE.md`'s bounded-context line, asked when missing. On an update it ends by printing the note and prompt of every `migrations` entry the project has not seen — a convention change that leaves existing code behind — and never runs them. It travels into the generated project, unlike the other creation skills: that is how a project updates itself once the plugin that delivered it is gone |
| Creating a skill, agent, norm, or `CLAUDE.md` section — and deciding which of the nine | skill `claude-code-architect-designer` |
| A band above the prompt, a pane, a spinner suffix, a dialog over a tool call, a `/command` that spends no turn — a **mod**, TypeScript under `.claude/mods/` | skill `claude-code-architect-designer`, Form 9 — always on top of a hook or `permissions` rule, never instead of one, and meta-repo only (`0131`). The one shipped: `nerviz-cockpit` — open phase and territory above the prompt, `/nerviz-doctor` in a pane. Loads from Claude Code 2.1.287 on; `/arch-doctor` says when it does not |
| Creating a hook, a new `ArchHook.java` mode, or a `permissions.allow`/`deny` line — and deciding whether the answer is a guarantee at all | skill `claude-code-architect-designer`, forms 7 and 8. Writes it after approval, always with a record in `decisions/`, and warns that `settings.json` is read only at startup |
| Designing a use case before implementing it; knowing whether a request is one or several | skill `use-case-design` |
| Aggregate, value object, invariant, ports of an already-designed use case | skill `domain-modeling` |
| Table, JPA mapping, migration, index, slow query, datasource properties | skill `persistence-architect` |
| REST adapter, controller, DTO, status, OpenAPI | skill `rest-api-architect` |
| Who may call an endpoint — roles, groups, only the owner — Spring Security on the servlet stack, JWT/opaque token/local users/API key, 401/403, CORS, Actuator and Swagger exposure | skill `security-architect` — `32-seguranca.md`, runs after REST and before messaging in `/new-feature` when the use case's `Access` row asks for it; norms in `@.claude/rules/authorization.md`. WebFlux is out of scope |
| Calling another system over HTTP — a payment gateway, a partner API, another service — `RestClient`, `@HttpExchange`, OpenAPI Generator, `WebClient` for a stream, OpenFeign kept as legacy; engine and pool, timeouts, retry, circuit breaker, outbound authentication (OAuth2 client credentials, token relay, API key, Basic, mTLS, HMAC), what a provider failure becomes (502/503/504) | skill `http-client-architect` — `28-cliente-http.md`, runs after security and before messaging in `/new-feature` when `10-dominio.md` declares a port of kind `external HTTP`; norms in `@.claude/rules/http-client.md`, failure families in `@.claude/rules/error-handling.md` § Integration families. A WebFlux application is out of scope |
| Tests, coverage, installing ArchUnit | skill `test-architect` |
| Designing a full feature (use case → domain → REST → persistence → tests) up to an approved spec | skill `new-feature` — manual only: the user types `/new-feature <description>`, the model can't invoke it. One use case per run; it designs only, and over an approved case it prints the implement command instead |
| Implementing a spec that is already `approved`, in a clean session | skill `new-feature-implement` — manual only: `/new-feature-implement UC-NNN-<slug>` after `/clear`. Pre-flight, then the executor in three chained block groups, then `compose-service-installer` for every `materialize` row of the spec's `## Infrastructure`, then `git-publish`. Never loads the design procedure, runs on `sonnet`, writes nothing itself (`@.claude/decisions/0135-new-feature-split-spec-and-implement.md`). It is the only supported door to the pre-flight, the four mandated findings of an implement run and its `git-publish` chaining |
| Creating a git repo, committing, or pushing the project just generated or just implemented | skill `git-publish` — chained automatically after `/init-project` and after `java-spring-boot-developer` succeeds; behind two confirmations |
| Docker, docker-compose, adding a service (DB, broker) to a project, Testcontainers image consistency at the compose level, choosing an observability backend (Jaeger or Grafana+Tempo+Prometheus) behind the OTLP collector | skill `docker-architect`. A service a use case needs is decided at design time — `persistence-architect` and `messaging-architect` ask, and record `exists`/`external`/`materialize`/`pending` per `@.claude/skills/docker-architect/references/compose-service-decision.md` — and a `materialize` row is added by `/new-feature-implement` through the `compose-service-installer` agent, which invokes `docker-architect` (`@.claude/decisions/0137-pending-compose-service-decided-at-design-materialized-at-implement.md`) |
| A versioned file citing a `docs/use-cases/UC-NNN-slug/` folder that was deleted or renamed | `ArchHook.java doctor` — the `UC references` line, so `/arch-doctor` reports it. Reads `doctor.uc_references` from `@.claude/schemas/extensions.json`; silent in this meta-repo, which has no `docs/use-cases/` |
| A spec citing a backlog row — `BL-NN` — that `docs/use-cases/BACKLOG.md` does not have, in either its active or its retired table | `ArchHook.java doctor` — the `BL references` line, same shape and same data block (`doctor.bl_references`). `BL-NN` is the backlog's own identifier, assigned by `use-case-design` on append and never a `UC` number: it exists so an impact row can name the case that satisfies a precondition while that case is still backlog |
| Where TLS terminates — at the edge (ingress, load balancer, Caddy) or in the embedded Tomcat, mutual TLS as a recorded variant — HTTPS, certificates and their renewal, HTTP/2 and h2c, HSTS, `X-Forwarded-*` and trusted proxies | skill `transport-security-setup` — chained by `project-bootstrap` step 7.5 and by `arch-adopt` when the root `CLAUDE.md` has no `**Transport:` paragraph; asks the topology, never writes a key or certificate, proves it with an IT against a real server, hands the local Caddy edge and the `Dockerfile` port to `docker-architect`. Norms in `@.claude/rules/transport-security.md`; where any credential, key or keystore may live is `@.claude/rules/secrets.md`. Outbound TLS stays with `http-client-architect` |
| SonarQube or SonarCloud analysis — scanner plugin, project key, server URL, CI step, a local SonarQube container | skill `sonarqube-setup` — chained by `project-bootstrap` step 8.4 and by `arch-adopt` when the build file has no scanner; asks whether a server exists, delegates the local container to `docker-architect`, never writes a token |
| A container that "started" but isn't answering, a port already allocated, OTLP traffic reaching the wrong collector, an OTLP or other default the host run cannot reach, or one `app` never overrides, a compose `image:` tag that disagrees with the one `src/test` pins in `DockerImageName.parse`, a broker published to the host that no host client can reach | `ArchHook.java compose` — hook, not skill. Also folded into `doctor`, so `/arch-doctor` reports it. The tag comparison and the advertised-address check need no Docker: they read files |
| Turning a generated project's SonarQube analysis into a lessons-learned — which `.claude/` template, norm or Checkstyle setting produced each group of issues — and filing it here as an issue | skill `sonar-lessons` — manual only (`/sonar-lessons`), runs **inside the generated project**, stops when the scanner, server or `SONAR_TOKEN` is missing. Writes `docs/lessons-learned/sonar-NNN.md` and ends with the `/report-issue` command that files it |
| Filing an issue on this repository from a generated project — from a description or a `docs/lessons-learned/` file | skill `report-issue` — manual only (`/report-issue`), runs **inside the generated project**. Picks the form from `.github/ISSUE_TEMPLATE/` here, refuses a report with nothing a maintainer can verify, warns when the project is behind the latest release, strips the project from the body, publishes only after confirmation. The single owner of what leaves a project |
| Triaging an issue of this repository — is the problem real, already fixed, decided on purpose, a duplicate | skill `triage-issue` — manual only (`/triage-issue <N>`), here only. The read-only `issue-verifier` agent checks every claim against `HEAD`; the issue's diagnosis and proposed fix are never inputs. Comment + label only after confirmation, behind `permissions.ask` too. A confirmed symptom ends with the `/claude-code-architect-designer` command, typed in the same session |
| Kafka producer/consumer, publishing or consuming a domain event over a broker, topic/partition/DLQ | skill `messaging-architect` |
| Scheduled or background job, cron, choosing between `@Scheduled`/ShedLock/Quartz/Spring Batch/db-scheduler/JobRunr, a job running twice across replicas, the outbox relay's schedule and its prune job | skill `jobs-architect` — `35-jobs.md`, runs after messaging and before persistence in `/new-feature` |
| Design pattern, growing `if`/`switch` chain | Inside `/new-feature`, decided at design time by each design skill for its own layer, in its partial's `## Design patterns`, through `gof-design-patterns` § Design-time use (`@.claude/decisions/0089-design-patterns-decided-at-design-time.md`). On existing code, skill `gof-design-patterns` — manual only (`/gof-design-patterns`). In a generated project, an agent whose `agent_classes` entry declares `pattern_catalog: true` receives its catalog at `SubagentStart` through `ArchHook.java context subagent`, and applies it without invoking the skill (`@.claude/decisions/0077-pattern-catalog-injected-at-subagent-start.md`) |
| Connecting to an external system (Jira, database, GitHub, Figma), a server exposing `mcp__*` tools, `.mcp.json` | skill `claude-code-architect-designer` |
| Auditing what a skill or agent run in a **generated project** spent and chained | `ArchHook.java audit` — hook, wired only into `project-bootstrap/templates/settings.json.example`; off here on purpose (no `.claude/audit-usage/`). Which pieces it skips: the class's `audited: false`, then the project's own `.claude/audit-usage/audited.json` per piece — never `arch-adopt`, fixed off in its class override (`audit` block of `@.claude/schemas/extensions.json`) |
| Reading that trail back — spend per skill and agent, which report to open | skill `audit-usage` — renders `ArchHook.java audit summary`; here it reports the trail is off |
| A plugin whose skills compete with the pipeline for automatic invocation; a personal skill in `~/.claude/skills/` named like one of `.claude/skills/` | `enabledPlugins: false` in both settings files for every id of `settings.blocked_plugins` in `@.claude/schemas/extensions.json` — `schema` fails on a missing one, and `.claude/settings.local.json` is where a person re-enables one. A personal skill wins over the project's with no warning; `ArchHook.java doctor` names it on the `Skill shadowing` line, never gating (`@.claude/decisions/0138-block-colliding-plugins-and-report-shadowed-skills.md`) |
| Which norm covers what | `@.claude/rules/00-index.md` |
| Which class a skill is, what it may write, and what sections its body must carry | `skill_classes` in `@.claude/schemas/extensions.json` — `ArchHook.java schema` validates the body, `guard` enforces the territory |
| The same for an agent — plus which frontmatter fields it owes and whether it may write at all | `agent_classes` in `@.claude/schemas/extensions.json`. Four classes: `driver` (interviews and delegates, writes a tree that doesn't exist yet), `executor` (implements an approved spec), `installer` (one-shot setup, narrow fixed paths — `compose-service-installer`'s are `docker-architect`'s own), `verifier` (reads untrusted text, writes nothing). `executor: true` is the single owner of who may write — the old `guard.executor_agents` list is gone |
| Which frontmatter fields are valid in each file type | `@.claude/skills/claude-code-architect-designer/references/frontmatter-fields.md` |
| Why a skill, norm, or agent exists in the form it's in | `@.claude/decisions/README.md` |
| Contract every blueprint fulfills | `@.claude/blueprints/_schema.md` |
| Frontmatter fields the runtime recognizes | `.claude/schemas/extensions.json` (owner, read by `ArchHook.java schema`) · meaning of each: `@.claude/skills/claude-code-architect-designer/references/frontmatter-fields.md` |
| How each piece of Claude Code works | `@claude-help.md` |
| A skill, hook, injection or MCP server that silently does nothing, a write `guard` refused, a frozen spec folder, who owns `pom.xml` or the outbox — every silent trap, the runtime's and this repository's | `@docs/pt-br/11-pitfalls.md` |
| What a generated project gives a GitHub Copilot user — which of `.claude/` the Copilot CLI, cloud agent and VS Code read as-is, what needs a file or frontmatter translation (`AGENTS.md` nested, `.instructions.md` with `applyTo`, `.agent.md`), what only Claude Code has (`` !` `` injection, `$ARGUMENTS`, `Skill` tool, `permissions.deny`), and which guarantee of this repo still holds there — the input of `roadmap.md` phase 8 and of the `AGENTS.md` design | `@docs/pt-br/13-github-copilot.md` — facts verified against the vendor docs on the date it states, re-verified before a decision uses one |

## Known pitfalls

Every silent trap — the runtime's and this repository's: write territories, frozen spec
folders, who owns `pom.xml` and the outbox, what `guard sweep` and `compose gate` cover — lives
in `@docs/pt-br/11-pitfalls.md`, read when a piece of `.claude/` is designed or edited. Most of
this repository's sit behind `guard`, `schema` or `compose gate`, whose block message names
the rule and the fix. A new pitfall goes there, never here. What stays bites in any session
with no hook behind it:

- **The `CLAUDE.md` at the root of a generated project is not this file.** It comes from
  `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example`.
- **The hooks run `.claude/hooks/ArchHook.jar`, not `ArchHook.java`.** Editing the source
  changes nothing a hook executes until `java .claude/hooks/ArchHook.java build` rewrites the
  jar — under the JDK major `hook_build.javac_feature` pins, or `build` refuses, since only the
  same `javac` reproduces the committed bytes that CI's `build --verify` compares. Here a
  `PostToolUse` entry rebuilds it on every edit of the source. The jar is **committed**:
  `.gitignore` ignores `*.jar` and un-ignores this one path — drop that line and CI fails on
  every OS with the jar "missing". Design: `@.claude/decisions/0075-precompiled-hook-jar.md`.
- **This repository does not run `./mvnw`.** `ArchHook` exits 0 when it finds no wrapper; here
  that is expected, not a failure.

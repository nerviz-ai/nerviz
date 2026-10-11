# File types — runtime mechanics

This document explains, for each type of piece used in `.claude/`, what it is, what
it's for, when it enters context, and who invokes it — the model or the user. Every
claim about runtime behavior cites the corresponding section of `claude-help.md` (root
of this repository), which is the single source on how Claude Code behaves in this
project.

---

## Skill

**Lives at:** `.claude/skills/<name>/SKILL.md`, plus optional `templates/` and
`references/` inside the same folder.

**What it is:** a markdown file with instructions that becomes part of Claude's
repertoire — "the most flexible extension" (`claude-help.md` § 5). The folder name
becomes the command: `.claude/skills/arch-doctor/SKILL.md` creates `/arch-doctor`.

**Purpose:** package a procedure — a checklist or a flow that would otherwise be
pasted repeatedly into chat. In this repository, every design skill
(`use-case-design`, `domain-modeling`, `persistence-architect`, `rest-api-architect`,
`security-architect`, `http-client-architect`, `messaging-architect`, `jobs-architect`,
`test-architect`) owns a single layer of a feature spec; `project-bootstrap` owns the
8-step procedure that generates a project from scratch.

**When it enters context:** only when invoked. Unlike a `CLAUDE.md`, a skill's body
doesn't load at session start — it loads on the turn it's used, and the rendered
content enters as a message and **stays for subsequent turns**; Claude doesn't re-read
the file afterward (`claude-help.md` § Content lifecycle). That's why a skill should
write permanent instructions ("always do X throughout this task"), not a one-time
step.

**Who invokes it — model or user:**

| Frontmatter | Effect | Example in this repo |
|---|---|---|
| (no control field) | The model decides, from `description`, whether the skill is relevant | `project-bootstrap` — description lists triggers like "scaffolding", "new Spring project"; `git-publish` — auto-invocable, but the side effect (commit/push) sits behind two `AskUserQuestion` gates in the body, not the frontmatter — same D17 pattern |
| `disable-model-invocation: true` | Only the user invokes it, by typing `/name` | `init-project`, `new-feature`, `new-feature-implement`, `arch-doctor` — the commands documented here are all side-effect-heavy and **don't** fire on their own |
| `user-invocable: false` | Only the model invokes it — background knowledge, no visible command | Not used in this repository today |

The three central commands in this document are all `disable-model-invocation: true` —
a deliberate decision: generating a project or running the feature pipeline has too
large a side effect to fire from inference over the conversation.

**Recognized frontmatter fields** (single source: `.claude/schemas/extensions.json`,
validated by `ArchHook.java schema` — `CLAUDE.md` § Invariant 10):

```
name, description, when_to_use, argument-hint, arguments,
disable-model-invocation, user-invocable, allowed-tools, disallowed-tools,
model, effort, paths, context, agent, background, hooks, shell
```

A field outside this list (e.g. `metadata:`) doesn't raise an error — the runtime
**silently ignores** unknown fields, and `claude plugin validate` lets it through too
([11-pitfalls.md](11-pitfalls.md)). It's `ArchHook.java schema` that blocks it.

**Dynamic context injection:** the `` !`command` `` syntax runs a command **before**
the content reaches the model and substitutes its output. That's what lets
`init-project` list available blueprints dynamically
(`` !`find .claude/blueprints ...` ``) and `arch-doctor` embed the real output of
`ArchHook.java doctor` inside the skill body (`claude-help.md` § Dynamic context
injection). A command that fails (exit ≠ 0) **aborts the entire invocation** — which
is why [11-pitfalls.md](11-pitfalls.md) warns about pipes inside
`allowed-tools: Bash(command:*)`: each pipe segment needs its own rule, or the whole
command gets blocked before it runs.

**`context: fork`:** runs the skill in an isolated subagent, without seeing the
conversation history — the skill's body becomes the subagent's prompt
(`claude-help.md` § `context: fork`). No skill in this repository uses this field
today: skills here prefer to explicitly delegate via the `Agent tool` to a named
`.claude/agents/*.md` when isolation is needed (see § Agent below).

**The skill's class — the part the runtime knows nothing about.** The runtime has no
notion of "which files this skill may write" or "which sections its body must carry".
Here that is data, in the `skill_classes` block of `.claude/schemas/extensions.json`:
every skill belongs to exactly one of seven classes, and the class declares both.

| Class | Skills | Write territory |
|---|---|---|
| `design` | the 9 that write use-case partials | the UC folder + `BACKLOG.md` |
| `orchestrator` | `new-feature`; `new-feature-implement`; `init-project` | the use case's `docs/**`; `new-feature-implement` and `init-project` write nothing (they delegate) |
| `build` | `project-bootstrap`, `docker-architect`, `arch-adopt`, `gof-design-patterns`, `sonarqube-setup`, `transport-security-setup` | one override per skill — the whole tree, the compose file only, `.claude/` only, `src/` only, the root build file and `.github/workflows/` only, or the main module's `application*.yml` + the HSTS filter + `src/test/**` + the root `CLAUDE.md` |
| `observer` | `arch-doctor`, `audit-usage` | nothing |
| `meta` | `claude-code-architect-designer` | `.claude/**`, `CLAUDE.md`, `.mcp.json`, `docs/**`, `.github/**` |
| `ops` | `git-publish`, `triage-issue` | nothing — its effect is `git`, or a comment on an issue of this repo, over Bash |
| `report` | `sonar-lessons`, `report-issue` | `docs/lessons-learned/**` — and, for `report-issue` only and after a confirmation, an issue outside the tree, over `gh` |

`ArchHook.java schema` enforces the structure (the class declared in the body as
`**Class:** <c>`, every required section present) and `guard` enforces the territory,
with exit 2 on any write outside it. A skill in no class has no territory — and `schema`
fails by its name. Details in
[08-audit-usage.md § The `guard` hook](08-audit-usage.md).

The class also fixes the model: every `SKILL.md` declares `model`, from the class's
`allowed_models` — `design` and `meta` only `opus`, `observer` and `ops` only `sonnet` —
and `schema` fails a skill without it (`.claude/decisions/0081-skill-model-required-per-class.md`).

---

## Rule

**Lives at:** `.claude/rules/<name>.md`.

**What it is:** a norm — "what must be true," never a procedure. `CLAUDE.md` §
Invariant 1 is categorical: *"a norm never mentions a skill, an agent, or a command. If
it needs to, it's a procedure and belongs in a skill."*

**Purpose:** fix a standard that applies whenever certain files are touched — naming
convention (`naming.md`), exception taxonomy (`error-handling.md`), Clean Code limits
(`code-quality.md`), and so on. Each norm has a **single owner**
(`CLAUDE.md` § Invariant 2) — whoever needs it cites the path, never copies the
content.

**When it enters context:** two mechanisms, and both apply to every rule
(`.claude/rules/00-index.md` § How a rule enters context):

1. **Auto-loading via `paths`** — the rule declares globs in the frontmatter, and
   enters context on its own when a matching file is touched. It doesn't depend on
   someone remembering to cite it.
2. **Explicit citation** — whoever designs something the rule governs, before the file
   exists, cites the path.

Without `paths`, the rule loads **at the same priority as the project's `CLAUDE.md`**,
i.e. every session (`claude-help.md` § 4). That is why every rule declares `paths`, with
the narrowest glob that holds it — `00-index.md` loads on `.claude/rules/**`, and Java is
`**/src/**/*.java`, never `**/*.java` (which matches `.claude/hooks/ArchHook.java`).
`architecture-ddd.md` carries example `paths`: `export` replaces them with the active
blueprint's `architecture_paths`.

**Who invokes it:** nobody invokes a rule — it **loads**, either automatically (via
`paths`) or by citation. There's no `/rule-name` command. That's why the
`00-index.md` table lists, for each rule, "Verified by" instead of "Invoked by": a
rule that needs mechanical verification points to a hook, a build plugin
(Checkstyle), or a test (ArchUnit) — never to itself.

**Recognized frontmatter** (`.claude/schemas/extensions.json`):

```
status (required: active | draft | deprecated), paths (optional)
```

No other field is allowed on a rule — the schema for this type is the strictest of
the three.

---

## Agent (subagent)

**Lives at:** `.claude/agents/<name>.md`.

**What it is:** a specialized assistant that runs in an **isolated context window**,
with its own system prompt, tool access, and optionally its own model
(`claude-help.md` § 7). The frontmatter defines the metadata; the markdown body
becomes the subagent's **system prompt**.

**Purpose — and when NOT to become an agent:** `CLAUDE.md` § Invariant 5 sets the
criterion: an agent is only justified for one of three reasons — preserving context
(verbose output that shouldn't pollute the main conversation), restricting tools, or
switching model. If none applies, the piece is a skill.

This repository's five agents each document explicitly, in their own `## Why this is
an agent` (or `## Why this is Form 3`) section, which reason applies:

| Agent | Context | Tools | Model |
|---|---|---|---|
| `project-initializer` | Verbose output from `starter.tgz` extraction, POMs, build output | `Read, Write, Edit, Bash, Glob, Grep, Skill` — restricted. `Skill` invokes `project-bootstrap` and the skills it chains. No `AskUserQuestion`: the agent runs in the background, where the tool does not exist, so `/init-project` runs the whole interview first (decision 0123) | `sonnet` + `effort: high` — the procedure is driven by the blueprint, the Initializr and the templates (decision 0078) |
| `java-spring-boot-developer` | The full spec replaces the interview — the agent only executes | `Read, Write, Edit, Bash` — only reads spec/templates, only writes to `src/` | `sonnet`, `effort: high` — generating ~19 steps of compilable code |
| `archunit-installer` | `test-architect`'s setup mode has no interview — a Maven Central `curl` and up to three `./mvnw` builds would become permanent in the main conversation if run inline | `Read, Write, Edit, Bash` — inside the project only | `sonnet`, `effort: medium` — translating exemplar packages onto the real blueprint layout and diagnosing an ArchUnit rule failure takes judgment, not just mechanical execution |
| `commons-logging-installer` | Same shape as `archunit-installer`: translates thirteen logging/masking exemplars into the real package, edits a POM, compiles until green — eleven writes and a build log that don't need to land in the context of the `/new-feature` run that triggered it | `Read, Write, Edit, Bash` — inside `commons.logging` and the matching POM only | `sonnet`, `effort: medium` |
| `compose-service-installer` | `/new-feature-implement` must add the service its spec decided at design time, and its own thread cannot: the guard refuses a `build`-class `Skill` call there and admits it from a classed agent. Running `docker-architect` here keeps its ~420-line body out of the implement thread | `Read, Write, Edit, Glob, Grep, Bash, Skill` — inside `docker-compose*.yml`, `docker/**`, `Dockerfile`, `.env`, `.env.example`, `.gitignore`, `.dockerignore` only | `opus`, `effort: medium` — the pin of `docker-architect`, kept out of the turns that follow, `git-publish` included |
| `issue-verifier` | Reads the body of a public issue — third-party text — away from the thread that holds write territory over `.claude/` | `Read, Grep, Glob, Bash` — no `Write`/`Edit`; class `verifier` has an empty territory, so `guard` refuses every in-repo write, Bash included | `opus`, `effort: high` — refuting the reporter's narrative is the whole job (decision 0103) |

Each belongs to a **class**, in `extensions.json` → `agent_classes`, and the class is what
fixes its body shape, the frontmatter fields it owes, whether it writes at all (`executor`),
and **which paths**:

| Class | Agents | Territory (`write_allow`) |
|---|---|---|
| `driver` | `project-initializer` | `**` — it writes the tree of a project that does not exist yet |
| `executor` | `java-spring-boot-developer` | `src/**`, the POMs (only for a dependency the spec declares), and its `UC-*-spec.md` — where the frozen-folder rule admits only the `status:` close and checklist toggles |
| `installer` | `archunit-installer`, `commons-logging-installer`, `compose-service-installer` | Per-agent `overrides`: POMs + `ArchitectureTest.java`/`TestcontainersConfiguration.java` for the first; POMs + `**/logging/**` (main and test) + `META-INF/spring/*.imports` for the second; for the third, the paths `docker-architect` writes — `docker-compose*.yml`, `docker/**`, `Dockerfile`, `.env`, `.env.example`, `.gitignore`, `.dockerignore` |
| `verifier` | `issue-verifier` | nothing — `executor: false`; the scratch of a generated-project reproduction lands outside the repo |

Territory is keyed on `agent_type`, which the `PreToolUse` payload of every subagent write
already carries — so no open skill phase widens or narrows it. Before this, all four had an
unconditional bypass and each file's prose promise (`**Does not write:**
docker-compose.yml`) was enforced by nothing (see
[08-audit-usage.md](08-audit-usage.md)).

**When it enters context:** only when invoked — never automatically via `paths`
(agents don't have that field). It **doesn't see** the main conversation's history,
nor the main session's auto memory, nor what was read before (`claude-help.md` § What
the subagent sees). It does see: its own system prompt, the `CLAUDE.md` hierarchy,
git status, the delegation message, skills preloaded via its own frontmatter's `skills:`
field, and whatever a `SubagentStart` hook hands it as `additionalContext` — that's how
`java-spring-boot-developer` receives the `gof-design-patterns` catalog in a generated
project: a skill with `disable-model-invocation: true` cannot be preloaded through `skills:`
(decision 0077).

**Who invokes it:** always another piece of the system, never the user directly via
`/agent-name` — that command doesn't exist. Here, it's always a skill that delegates
via the `Agent tool`: `init-project` delegates to `project-initializer`,
`new-feature-implement` delegates to `java-spring-boot-developer` when `/new-feature-implement UC-NNN-slug` implements
an approved spec — three times, one per block group,
`test-architect` delegates to `archunit-installer` in setup mode (no argument), and
`new-feature-implement`'s pre-flight delegates to `commons-logging-installer` when `commons` is empty.
After the `tests` group ends green and before `git-publish`, `new-feature-implement` delegates to
`compose-service-installer` when the spec has a `materialize` row in its `## Infrastructure`.

**Fork — a different kind of agent:** a *fork* inherits the entire conversation
instead of starting from scratch (`claude-help.md` § Forks). Only the final result
returns to the main conversation — internal tool calls stay out of its context. None
of this repository's agents is a fork — all of them receive only the delegation
message, on purpose, because the point is precisely **not** to inherit the noise of
the design conversation.

**Recognized frontmatter fields** (`.claude/schemas/extensions.json`, `case: camel`):

```
name, description, tools, disallowedTools, model, permissionMode,
maxTurns, skills, mcpServers, hooks, memory, background, effort,
isolation, color, omitClaudeMd, initialPrompt, experimental
```

**Silently ignored files:** an agent with no `name`, with `---` not on the first
line, with `name` starting with `-` or containing `:`, with `name` but no
`description`, or with invalid YAML — Claude Code simply skips the file, with no
visible error (`claude-help.md` § Files silently ignored). That's why this repository
validates with `java .claude/hooks/ArchHook.java schema`, which is strict where the
runtime stays silent.

---

## `CLAUDE.md`

**Lives at:** repository root (or `.claude/CLAUDE.md`), with additional hierarchy at
`~/.claude/CLAUDE.md` (user) and `./CLAUDE.local.md` (personal, outside git).

**What it is:** the project memory file — instructions the team versions and that
everyone (human or model) reads. `claude-help.md` § 3 describes the full hierarchy;
every level is **additive**, never replacing another.

**Purpose in this repository:** index and routing, not a manual. `CLAUDE.md` § How a
rule enters context and this file's own `## Routing` section work as an "if the task
involves X, go to Y" table — the norm itself lives in the cited skill or rule, never
inside `CLAUDE.md`.

**When it enters context:** always, at session start — files in the current
directory **and every directory above it** load at startup; files in subdirectories
load on demand, when Claude reads something in there (`claude-help.md` § File
hierarchy). Unlike a skill, there's no way for a `CLAUDE.md` to load "only when
needed" — it's always paid for, hence the goal of staying under 200 lines
(`claude-help.md` § Writing an effective CLAUDE.md).

**Who invokes it:** nobody — it loads automatically. The `/init` command generates an
initial `CLAUDE.md` from existing code; `/memory` lists and opens memory files
(native to Claude Code, distinct from this repository's own memory).

**Two relevant versions here:**

| `CLAUDE.md` | Role |
|---|---|
| This repository's root | Documents the meta-repository — architecture invariants of `.claude/`, routing table to this repo's skills |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | Template for the `CLAUDE.md` that `project-bootstrap` writes **inside the generated project** — different data (name, modules, resolved versions), same discipline of staying small and citing by path |

`CLAUDE.md` § Known pitfalls is explicit: *"the `CLAUDE.md` at the root of a generated
project is not this file."* Confusing the two is the most common mistake when editing
this repository.

---

## Hook

**Lives at:** `.claude/settings.json` (configuration) + `.claude/hooks/ArchHook.java`
(logic), compiled into the committed `.claude/hooks/ArchHook.jar` that every entry runs.

**What it is:** a script triggered on lifecycle events — "deterministic: it always
happens on the event, regardless of what the model decides. That's the difference
between asking and guaranteeing" (`claude-help.md` § 8). It's the only piece in this
system the model **cannot** opt to skip.

**Purpose:** everything that must always hold, without depending on the model
remembering — `CLAUDE.md` § Invariant 6: *"if a rule must always hold, it's a hook or
`permissions.deny` — not prose in markdown."* `ArchHook.java` has eleven modes, and every
entry launches the precompiled `.claude/hooks/ArchHook.jar` built from it (`java -jar`):

| Mode | Event | Blocks? | What it does | Wired here? |
|---|---|---|---|---|
| `check` | `PostToolUse` (Write\|Edit\|MultiEdit\|NotebookEdit, `if: Edit(**/*.java)`) | Yes (exit 2) | Forbidden imports (via `.claude/forbidden-imports.txt`) + incremental compile of the touched module | Yes |
| `format` | `PostToolUse` (same matcher and `if`) | Never | `spotless:apply` on the touched module | Yes |
| `tests` | `Stop` · `SubagentStart`/`SubagentStop` (`agent-start`, `agent-end`) | Yes (exit 2) | Runs tests of the modules changed since `HEAD`; defers, without blocking, while a writer subagent of the session runs (decision 0116) | Yes |
| `schema` | `PreToolUse` + `PostToolUse` (on `.claude/**/*.md`, `.mcp.json`, `settings.json`) + `Stop` | Yes (exit 2) | Validates skills/agents/rules frontmatter, each `SKILL.md`'s **body** against its class (`skill_classes`), each agent against `agent_classes`, `.mcp.json` fields with a secret scan over `headers`/`env`, `settings.json`'s hook entries, and the `export` manifest against disk | Yes |
| `guard` | `UserPromptSubmit` (`prompt`) + `PreToolUse` (Skill\|Agent\|Task → `call`; Write\|Edit\|MultiEdit\|NotebookEdit → `write`; Bash → `bash`) + `Stop` (`sweep`) | Yes (exit 2) | Every skill writes only its own class's territory, every subagent only its `agent_classes` territory (allowlist, deny by default); a `build`-class skill is unreachable during a design run; a spec folder `approved`/`implemented`/`implemented-blocked` is frozen except for its status close, checklist toggles and `CHANGELOG.md`; shell writes it can read literally get the same checks, and a force push is refused; on `Stop`, what the turn changed on disk is swept against the same rules | Yes, in both |
| `audit` | 11 lifecycle events | Never | Execution trail of every skill and agent: one Markdown report per invocation + ledgers | Generated project only — switched on by the existence of `.claude/audit-usage/` |
| `compose` | Manual; folded into `doctor`; `compose gate` on `Stop` | The gate: yes (exit 2) | Every compose service is `running`; no foreign container publishes a port this project declares (a warning, not a block, while no container of this project exists); no `image:` tag disagrees with the one `src/test` pins; no published port is advertised only at a compose-network name; every `${VAR:default}` pointing at a compose service holds on the host (the default reaches a published port) and in the container (`app` sets the variable) | Yes, in both |
| `context` | `SubagentStart` (`context subagent`) | Never | Hands the sections of the `gof-design-patterns` catalog that `subagent_context` names to every agent whose class declares `pattern_catalog: true`, as `additionalContext` before its first turn | Generated project only |
| `doctor` | Manual (`/arch-doctor`); `gate` in the generated project's CI | `gate` only, exit 1 | Diagnoses the setup on this machine; `gate` fails on a red line `doctor.gate.labels` lists | Yes |
| `export` | Manual (invoked by `/arch-adopt`) | Never | Writes a target project's `.claude/` from the `export` manifest, transformed for the active blueprint, and writes the provenance stamp | Yes — see [10-arch-adopt.md](10-arch-adopt.md) |
| `build` | `PostToolUse` on an edit of `ArchHook.java`; manual | Never | Rebuilds `ArchHook.jar` from the source under the JDK major `hook_build.javac_feature` pins; `build --verify` (run by CI) checks the committed jar is byte for byte what the source compiles to | This repo only |

The `guard` and `audit` modes are detailed in [08-audit-usage.md](08-audit-usage.md).
Every list the hook reads — recognized fields, skills excluded from auditing, redaction
patterns, guarded paths — is data in `extensions.json`, never a constant in the Java.

**When it enters context:** hooks don't "enter context" as text — they run as an
**external process** (here, `java -jar .claude/hooks/ArchHook.jar <mode>`), and what returns to the model
is structured output (stderr becomes the block reason) or JSON (`claude-help.md` §
Structured JSON output). The `if` field in each `settings.json` entry filters by
permission rule **before** spawning the JVM — without it, every `Write` would pay for
starting a JVM for nothing (`_comment` field in `.claude/settings.json`).

**Who invokes it:** the runtime, automatically, on the configured event — never the
model or the user directly. The only way to run `ArchHook.java` "manually" is via
`java .claude/hooks/ArchHook.java doctor` in the terminal, or indirectly through the
`arch-doctor` skill, which embeds that call via `` !`command` `` inside its own body
(see § Skill above).

**Exec form vs. shell form:** `settings.json` uses **exec form** (`command: java` +
`args: [...]`) instead of shell form — no shell, no execute bit, identical across
Linux, macOS, and Windows (`claude-help.md` § Configuration; `CLAUDE.md` §
Commands). That's why no step of `project-bootstrap` runs `chmod`.

**Exit codes:**

| Code | Behavior |
|---|---|
| `0` | Success — if stdout is valid JSON, it's parsed |
| `2` | **Blocks the action** (on events that support blocking) — stderr becomes the reason shown to the model |
| Other | Non-blocking error — the action proceeds |

In `PostToolUse`, exit `2` doesn't undo the action (the tool already ran), but stderr
is shown to Claude — that's how `check` reports a boundary violation after the file
has already been written: the write happened, but the model is warned to fix it.

**Where to configure:** `.claude/settings.json` (of this project, versioned) is the
only location used here — there's no personal `~/.claude/settings.json` nor
`.claude/settings.local.json` involved in this flow (`claude-help.md` § Where to
configure lists the other options, including hooks via skill/subagent frontmatter,
not used in this repository). In a generated project the same file is written by
`project-bootstrap` from `templates/settings.json.example`, with `audit` and `context`
wired on top.

---

## Comparison summary

| | Skill | Rule | Agent | `CLAUDE.md` | Hook |
|---|---|---|---|---|---|
| Format | `SKILL.md` + folder | standalone `.md` | standalone `.md` | standalone `.md` | `.json` + script |
| Contains | Procedure | Norm | System prompt + config | Index/routing | Deterministic logic |
| Enters context | On invocation | Auto (`paths`) or always | On invocation, isolated | Always, at startup | Never as text — runs as a process |
| Invoked by | Model and/or user (`disable-model-invocation`) | Nobody — loads | Another skill/agent, via Agent tool | Nobody — loads | Runtime, on the event |
| Can cite | Rules, blueprints, other skills, agents | Nothing (leaf) | Rules, skills it follows | Skills, rules, agents (by path) | Nothing — it's code |
| Blocks something? | Not by itself | Not by itself | Not by itself | Not by itself | Yes, with exit 2 |

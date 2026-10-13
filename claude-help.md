# Claude Code ecosystem guide

A practical reference to everything Claude Code offers for extending and controlling
the agent: memory, skills, commands, subagents, hooks, MCP, plugins, and model
configuration. Written with a focus on software development.

Source: official documentation at <https://code.claude.com/docs/en/overview>, checked
against the snapshot in `docs/pt-br/claude-code-docs/` (accessed 2026-09-27). Where the
docs and this guide diverge, the official docs win. Items the official docs do **not**
state are marked *"not in official docs"* — they are kept only where this repository
depends on them.

---

## Summary

1. [Mental model: the pieces of the ecosystem](#1-mental-model-the-pieces-of-the-ecosystem)
2. [Which piece to use for what](#2-which-piece-to-use-for-what)
3. [CLAUDE.md and memory](#3-claudemd-and-memory)
4. [Rules (`.claude/rules/`)](#4-rules-claudrules)
5. [Skills](#5-skills)
6. [Commands (slash commands)](#6-commands-slash-commands)
7. [Subagents](#7-subagents)
8. [Hooks](#8-hooks)
9. [MCP](#9-mcp)
10. [Plugins and marketplaces](#10-plugins-and-marketplaces)
11. [Workflows that work](#11-workflows-that-work)
12. [Context management](#12-context-management)
13. [Anti-patterns](#13-anti-patterns)
14. [Model, effort, thinking, output styles](#14-model-effort-thinking-output-styles)
15. [How this repository uses all of this](#15-how-this-repository-uses-all-of-this)
16. [Quick command reference](#16-quick-command-reference)

---

## 1. Mental model: the pieces of the ecosystem

Claude Code is an agent: it reads files, edits, runs commands, verifies the result, and
iterates. The pieces below are the extension layer — they change what it knows, what it
can reach, and what happens automatically.

| Piece | What it is | Where it lives |
|---|---|---|
| **CLAUDE.md** | Persistent context, loaded every session | `./CLAUDE.md`, `~/.claude/CLAUDE.md` |
| **Auto memory** | Notes Claude writes itself between sessions | `~/.claude/projects/<proj>/memory/` |
| **Rules** | Modular instructions, optionally scoped by path glob | `.claude/rules/*.md` |
| **Skills** | Knowledge and workflows loaded on demand; become `/name` | `.claude/skills/<name>/SKILL.md` |
| **Commands** | Older single-file skill format, still recognized | `.claude/commands/<name>.md` |
| **Subagents** | Isolated workers with their own context | `.claude/agents/<name>.md` |
| **Hooks** | Scripts triggered on lifecycle events | `.claude/settings.json` |
| **MCP** | Protocol for connecting external services | `.mcp.json`, `claude mcp add` |
| **Plugins** | Packaging of everything above, distributable | directory with `.claude-plugin/plugin.json` |
| **Output styles** | Replace the coding-focused part of the system prompt | `~/.claude/output-styles/`, `.claude/output-styles/` |

One principle runs through everything else: **the context window is the scarce
resource**. It holds the entire conversation — every message, every file read, every
command output. Performance degrades as it fills up. Practically every good practice
below exists to spend less context, or to spend it better.

Both `CLAUDE.md` and auto memory are **context, not applied configuration**. To block
an action regardless of what the model decides, use a `PreToolUse` hook.

---

## 2. Which piece to use for what

### Decision table

| Goal | Use |
|---|---|
| Convention that always holds ("use pnpm, not npm") | CLAUDE.md |
| Rule that only applies to certain files (`src/api/**`) | Rule with `paths:` |
| Playbook you've pasted into chat for the third time | Skill |
| Action with side effects you want to trigger by hand (`/deploy`) | Skill with `disable-model-invocation: true` |
| Task that reads 40 files and you just want the summary | Subagent |
| Something that must happen **every time, no exceptions** | Hook |
| Data that lives in an external system (Jira, database, Figma) | MCP |
| Same setup in a second repository | Plugin |
| Change how Claude talks and works for every prompt (teaching mode, terse mode) | Output style |

### Triggers: when to add each thing

Don't configure everything up front. Each piece has a recognizable trigger:

| Trigger | Add |
|---|---|
| Claude makes the same mistake about a convention twice | A line in CLAUDE.md |
| You type the same prompt to start a task | An invocable skill |
| You paste the same N-step procedure for the third time | A skill |
| You copy data from a browser tab Claude can't see | An MCP server |
| Claude reads many files just to find where a symbol is defined | A code-intelligence plugin (LSP) |
| A side task floods the conversation with output you won't re-read | A subagent |
| You want something to happen always, without asking | A hook |
| A second repository needs the same setup | A plugin |

### Distinctions that confuse people

**Skill vs. Subagent** — a skill is *reusable content* that enters your context; a
subagent is an *isolated worker* with its own context that returns only a summary.
Skills cost context; subagents save it. They combine: a subagent can preload skills
(the `skills:` field), and a skill can run isolated (`context: fork`).

**CLAUDE.md vs. Skill** — CLAUDE.md loads *every session* (fixed cost); a skill loads
*on demand*. If it's "always do X," it goes in CLAUDE.md. If it's reference material
that only matters sometimes, it becomes a skill.

**Hook vs. Skill** — a hook is deterministic: it always fires on the event, without the
model deciding. A skill is interpreted: the model decides how to apply it. An
instruction like "never edit `.env`" in a CLAUDE.md is a *request*; a `PreToolUse` hook
that blocks the edit is *enforcement*. If the rule must always hold, make it a hook.

**MCP vs. Skill** — MCP provides the tools (the connection and authentication); a skill
provides the knowledge of how to use them well. They combine: MCP connects to the
database, the skill documents the schema and query patterns.

**Output style vs. CLAUDE.md** — CLAUDE.md is added *on top of* the default system
prompt as a user message; an output style *replaces* the coding-focused part of the
system prompt. See [§14](#14-model-effort-thinking-output-styles).

---

## 3. CLAUDE.md and memory

### File hierarchy

Loaded in order, from broadest scope to most specific. All are **additive**, not
replaced:

| Scope | Location | For |
|---|---|---|
| Managed policy | macOS `/Library/Application Support/ClaudeCode/CLAUDE.md`; Linux/WSL `/etc/claude-code/CLAUDE.md`; Windows `C:\Program Files\ClaudeCode\CLAUDE.md` | Organization standards, via MDM. Can also be embedded in the `claudeMd` key of `managed-settings.json` |
| User | `~/.claude/CLAUDE.md` | Your preferences, all projects |
| Project | `./CLAUDE.md` or `./.claude/CLAUDE.md` | Team, versioned in git |
| Local | `./CLAUDE.local.md` | Personal to the project, in `.gitignore` |

How they load:

- `CLAUDE.md` and `CLAUDE.local.md` in the current directory **and every directory
  above it** load at startup, concatenated from the filesystem root down to the working
  directory — the closest file is read last. Within one directory, `CLAUDE.local.md`
  comes after `CLAUDE.md`.
- Files in subdirectories below the working directory load on demand, when Claude reads
  something in there.
- HTML block comments (`<!-- note -->`) are stripped before injection — maintenance
  notes at zero token cost.
- `--add-dir` does **not** load the extra directory's `CLAUDE.md` unless
  `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1`.
- A file above 4 MiB is ignored entirely.
- `claudeMdExcludes` (globs against absolute paths) skips other teams' `CLAUDE.md` in a
  monorepo; the arrays add up across settings layers. A managed `CLAUDE.md` can't be
  excluded by individual settings.

### Writing an effective CLAUDE.md

Target: **under 200 lines**. A bloated file makes Claude ignore half of it — the
important rules get lost in the noise. For every line, ask: *"would removing this make
Claude make a mistake?"* If not, cut it.

| ✅ Include | ❌ Exclude |
|---|---|
| Build commands it can't guess | Anything it can discover by reading the code |
| Style rules that diverge from the standard | Standard language conventions |
| Test instructions and the preferred runner | Detailed API documentation (link instead) |
| Repo etiquette (branches, PRs) | Information that changes frequently |
| Project-specific architectural decisions | Long explanations or tutorials |
| Environment quirks (required env vars) | File-by-file code description |
| Pitfalls and non-obvious behaviors | Obvious statements like "write clean code" |

Diagnostic signs:

- Claude repeats a mistake despite the rule existing → the file is too long, the rule
  got lost. Check `/context` → **Memory files** to confirm it loaded at all.
- Claude asks something that's already in CLAUDE.md → the phrasing is ambiguous, or two
  rules contradict each other and it picked one arbitrarily.
- Need to emphasize? Use `IMPORTANT` **on a single line**. Emphasize ten things and
  none stand out.
- The content enters as a user message after the system prompt — there is no
  guarantee of compliance. If it must happen every time, make it a hook.
- After `/compact`, the root `CLAUDE.md` is re-read from disk and reinjected; nested
  files and rules with `paths` come back when Claude reads a matching file. What only
  existed in the conversation is lost.
- Too large: warning at startup and in `/status`; `/doctor` proposes cuts for a
  committed `CLAUDE.md`.
- The `InstructionsLoaded` hook logs which instruction files loaded, when, and why.

Treat it like code: review when something goes wrong, review regularly, version it in
git.

### Imports

```markdown
See @README for an overview and @package.json for the npm commands.

# Additional instructions
- git workflow @docs/git-instructions.md
```

Relative paths resolve from the file that imports them. Maximum depth: 4 hops. To cite
a path **without** importing it, use backticks: `` `@README` `` — imports inside code
spans and code blocks are ignored.

Import doesn't reduce context — the imported file loads alongside it at startup. It's
for organization, not savings. To save context, use rules with `paths:` or skills.

An import pointing **outside** the working directory from a project file opens an
approval dialog the first time. User-scope files (`~/.claude/...`) are trusted without
a dialog, except in Cowork desktop sessions.

### AGENTS.md

Since v2.1.277 Claude Code reads `AGENTS.md` directly:

| Repository has | Claude reads |
|---|---|
| `AGENTS.md`, no `CLAUDE.md`/`CLAUDE.local.md` on the path | `AGENTS.md` |
| Both | Only the `CLAUDE.md` files |
| `CLAUDE.md` that imports `AGENTS.md` | `CLAUDE.md`, with the import expanded |

To change it: `/config` → **Project instructions**: `claude-md-or-agents-md`
(default), `claude-md-and-agents-md`, `claude-md`, `managed-only`. Also configurable
through `pluginConfigs` of the built-in `agents-md@builtin` plugin (ignored in project
and local settings).

Differences from `CLAUDE.md`: `InstructionsLoaded` hooks don't fire for an `AGENTS.md`
read through this setting; `--add-dir` doesn't load `AGENTS.md`; `AGENTS.local.md`,
`AGENTS.override.md`, and `.agents/` are never read.

To share one file across tools, prefer `@AGENTS.md` inside a `CLAUDE.md` over a
symlink — on Windows a committed symlink becomes a one-line text file without
`core.symlinks`:

```markdown
@AGENTS.md

## Claude Code
Use plan mode for changes to `src/billing/`.
```

### Auto memory

Claude writes its own notes between sessions, in
`~/.claude/projects/<project>/memory/`. Four types, marked in the note's frontmatter:
`user` (who you are), `feedback` (corrections you've given and confirmed approaches),
`project` (work in progress, deadlines, decisions not derivable from code), `reference`
(where to find information outside the project). It skips whatever can be derived from
the codebase and whatever `CLAUDE.md` already says.

- `MEMORY.md` is the index — the first 200 lines (or 25 KB) load every session. Topic
  files load on demand.
- The directory is derived from the git repository, so **all worktrees share it**.
- Machine-local: not synced between machines or into cloud sessions.
- Excluded from the retention sweep (`cleanupPeriodDays`) that deletes old transcripts.
- Subagents can have their own memory through the `memory` frontmatter field.

Enabled by default. To disable: `/memory` → toggle, `autoMemoryEnabled: false` in
settings, or `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`. `autoMemoryDirectory` relocates it.

### Useful commands

- `/init` — generates an initial CLAUDE.md from the existing code.
- `/memory` — lists and opens the memory files, toggles auto memory.
- `/context` — shows what **actually** loaded in this session. Use it to diagnose.
- `/doctor` — checkup of the Claude Code setup: diagnoses and fixes configuration
  issues. Native; unrelated to this repo's `/arch-doctor`.

---

## 4. Rules (`.claude/rules/`)

For large projects, break instructions into topic files:

```
.claude/
├── CLAUDE.md
└── rules/
    ├── code-style.md
    ├── testing.md
    └── security.md
```

All `.md` files are discovered recursively (`rules/frontend/react.md` works). Rules
**without** a `paths` frontmatter load at startup, at the same priority as
`.claude/CLAUDE.md`.

### Path-scoped rules

The real gain is here: the rule only enters context when Claude reads a file that
matches the glob.

```markdown
---
paths:
  - "src/api/**/*.ts"
  - "lib/**/*.{ts,tsx}"
---

# API rules

- Every endpoint validates its input
- Use the standard error response format
```

| Pattern | Matches |
|---|---|
| `**/*.ts` | Every TypeScript file, any directory |
| `src/**/*` | Everything under `src/` |
| `*.md` | Markdown at the root |
| `src/components/*.tsx` | Components in a specific directory |

Details that bite:

- `paths` is the **only** field Claude Code reads from a rule. Any other field is
  ignored without error. This repository's `status` field is validated by its own hook,
  not by the runtime.
- Invalid YAML makes the rule load as if it had no `paths` — always on. `claude --debug`
  shows the parse error.
- Brace expansion (`{ts,tsx}`) has a budget of 1,000 expanded patterns and 4 MiB per
  rule.
- `[` starts a bracket expression; escape a literal one: `photos \[2024/**`.
- Symlinks are supported (circular ones handled safely). A symlink pointing outside the
  working directory follows the external-import rule. Network paths (UNC
  `\\server\share`, `/net`, `/Network`) are not followed.

```bash
ln -s ~/company-standards/security.md .claude/rules/security.md
```

Personal rules in `~/.claude/rules/` apply to every project and load **before** the
project's own. Neither overrides the other — keep them consistent.

---

## 5. Skills

The most flexible extension. A markdown file with instructions that becomes part of
Claude's repertoire. It uses it when relevant, or you invoke it with
`/skill-name`.

Create a skill when you find yourself pasting the same checklist or procedure into
chat repeatedly, or when a CLAUDE.md section has turned into a procedure rather than a
fact. Unlike CLAUDE.md, the skill's body only loads when it's used — long reference
material costs almost nothing until it's needed.

### Structure

```
.claude/skills/deploy-staging/
├── SKILL.md          # required
├── reference.md      # loaded only when needed
├── examples.md
└── scripts/
    └── helper.py     # executed, not loaded into context
```

The directory name becomes the command: `/deploy-staging`. Keep `SKILL.md` under
**500 lines**; move detailed reference material to separate files and cite them:

```markdown
## Additional resources
- Full API details: [reference.md](reference.md)
- Usage examples: [examples.md](examples.md)
```

### Code boilerplate (scaffolders)

Example source code — the base class a skill emits, a reference `pom.xml`, an
exception handler — goes in `templates/`, **inside the skill's directory**. Never in
`rules/`, never pasted into the body of `SKILL.md`.

The reason is mechanical, not aesthetic:

| | `.claude/rules/` | `.claude/skills/<name>/` | `SKILL.md` body |
|---|---|---|---|
| What the runtime discovers there | Only `.md`, loaded **as instruction** | The whole directory, any file type | — |
| When it enters context | Without `paths`: every session. With `paths`: whenever a matching file is touched | Only when the skill reads the file | Every skill invocation |
| Nature | Norm — what must be true | Procedure + supporting material | Permanent task instruction |

A `.java` file inside `rules/` is dead code: it's not `.md`, nothing loads it.
Renaming it to `.md` and pasting the code inside is worse — it becomes a permanent
instruction every time the glob matches, for code that only matters at generation
time.

The resulting split has two distinct owners:

- **Rule** declares the norm: *"exceptions inherit from `DomainException`; never a
  bare `RuntimeException`"*. Applies when someone **writes or reviews** code.
- **Skill template** carries the exemplar: `DomainException.java.example`, real,
  compilable code. Applies when someone **generates** code.

The norm doesn't repeat the code; the exemplar doesn't repeat the norm. Each cites the
other by path.

| Convention | Why |
|---|---|
| `.example` (or `.template`) suffix in the name | The file isn't compiled or tested as part of this repository. Without the suffix, the build and linter try to process it |
| A single naming pattern across all exemplars | `X.java.example` and `pom.example.xml` side by side turn a precondition `ls` into a false negative |
| A real, compilable exemplar, not a mold with `${placeholder}` | Claude reads the shape and writes the adapted equivalent. Mechanical substitution breaks on the case the mold didn't anticipate — a `domain` module's `pom.xml` shouldn't get `spring-boot-starter-web` just because another module's exemplar shows it |
| One file per concept | Updating one exemplar doesn't force reviewing the others, and the precondition check points exactly at what's missing |
| Cited by `SKILL.md` via path | The skill body loads on every invocation; the exemplar only at the step that needs it |

A third case, outside skills: boilerplate that no skill generates and a human copies
by hand (archetype, module seed). It's neither skill nor rule — it's `templates/` at
the project root, versioned as regular code, cited in `CLAUDE.md` between backticks
(no `@`, so it doesn't get imported).

### Where they live

| Location | Path | Applies to |
|---|---|---|
| Enterprise (managed) | Managed settings directory, `skills/<name>/SKILL.md` | Everyone in the organization |
| Personal | `~/.claude/skills/<name>/SKILL.md` | All your projects |
| Project | `.claude/skills/<name>/SKILL.md` | Only this project |
| Nested | `<subdir>/.claude/skills/<name>/SKILL.md` | Monorepo: available once Claude reads or edits a file in that subdirectory |
| `--add-dir` | `<dir>/.claude/skills/` | The added directory |
| Plugin | `<plugin>/skills/<name>/SKILL.md` | Wherever the plugin is active |
| claude.ai synced | Skills synced from claude.ai | `/anthropic-skills:<name>` |

Name conflict resolution: **enterprise > personal > project**. A skill beats a
`.claude/commands/` file with the same name; a project skill also overrides a bundled
command of the same name (aliases excluded). Plugin skills are namespaced
(`/my-plugin:deploy`), so they never collide. In a monorepo the nested skill is
`/apps/web:deploy` and the root one is `/deploy`. The names `synced` and
`anthropic-skills` are reserved.

**Live detection:** editing a `SKILL.md` is picked up in the current session, no
restart needed (except in `--bare` mode). A `skills/` directory that didn't exist at
session start needs `/reload-skills`. Plugin hooks, `.mcp.json`, and agents need
`/reload-plugins`.

### Frontmatter

All fields are optional; only `description` is recommended. Frontmatter is read only
when `---` is the **first line** of the file. Unknown fields are ignored silently.
Booleans accept `true/false`, `yes/no`, `on/off`, `1/0` (v2.1.218+).

```yaml
---
name: deploy
description: Deploys the application to production. Use when the user asks for a deploy.
argument-hint: "[environment]"
disable-model-invocation: true
allowed-tools: Bash(git *) Read
context: fork
agent: general-purpose
paths: "src/**/*.java"
model: sonnet
---
```

Main fields:

| Field | Effect |
|---|---|
| `description` | How Claude decides to use the skill. **Put the main use case first** — `description` + `when_to_use` are truncated at 1,536 characters in the listing |
| `when_to_use` | Extra context: trigger phrases, example requests |
| `argument-hint` | Autocomplete hint, e.g. `[issue-number]` |
| `arguments` | Positional names for `$name` substitution |
| `disable-model-invocation` | `true` = only you invoke it. Use for side effects (`/deploy`, `/commit`) |
| `user-invocable` | `false` = only Claude invokes it. Use for background knowledge |
| `allowed-tools` | Pre-approves tools **during the turn** that invokes the skill |
| `disallowed-tools` | Removes tools from the pool while the skill is active |
| `model` / `effort` | Overrides model/effort while the skill is active. ⚠️ Today only when typed as `/name`: invoked through the `Skill` tool, the turn keeps the caller's model and effort ([#98898](https://github.com/anthropics/claude-code/issues/98898)) |
| `context: fork` | Runs the skill in an isolated subagent |
| `agent` | Which subagent type to use with `context: fork` |
| `background` | With `fork`, `false` = wait for the result in the same turn |
| `paths` | Globs that limit when the skill auto-activates |
| `hooks` | Hooks registered when the skill is invoked |
| `shell` | `bash` (default) or `powershell` — the shell that runs `` !`command` `` injections |

Accepted by the runtime but **not acted on**: `license`, `compatibility`, `metadata`
(Agent Skills spec fields). This repository forbids `metadata` on top of that — it
invites contracts that enforce nothing, see
`@.claude/skills/claude-code-architect-designer/references/frontmatter-fields.md`.

### Arguments

```markdown
---
name: fix-issue
description: Fixes a GitHub issue
disable-model-invocation: true
---

Fix issue $ARGUMENTS following our standards.
```

`/fix-issue 123` → Claude receives "Fix issue 123...".

| Variable | Meaning |
|---|---|
| `$ARGUMENTS` | All arguments, as typed |
| `$0`, `$1`, `$2` | Argument by position (equivalent to `$ARGUMENTS[N]`) |
| `$name` | Named argument, declared in `arguments:` |
| `${CLAUDE_SKILL_DIR}` | Directory of the `SKILL.md` — use for embedded scripts. Also expands in `allowed-tools` Bash rules |
| `${CLAUDE_PROJECT_DIR}` | Project root. Also expands in `allowed-tools` Bash rules |
| `${CLAUDE_SESSION_ID}` | Session ID, useful for logs |
| `${CLAUDE_EFFORT}` | Current effort level |
| `${CLAUDE_PLUGIN_ROOT}`, `${CLAUDE_PLUGIN_DATA}` | Plugin skills only: the plugin's directory and its persistent data directory |

Skills can be stacked: `/write-tests /fix-issue 123` loads both and passes `123` as
the argument to each.

### Dynamic context injection

The `` !`command` `` syntax runs the command **before** the content reaches Claude and
substitutes its output. This is what lets the skill work on real data, not on what the
model imagines:

```markdown
---
name: summarize-changes
description: Summarizes uncommitted changes and flags risks.
---

## Current changes

!`git diff HEAD`

## Instructions

Summarize the above in 2-3 bullets and list risks: missing error handling,
hardcoded values, tests that need to change.
```

Multi-line block:

````markdown
```!
node --version
git status --short
```
````

Watch out for:

- `!` is only recognized at the start of a line or after whitespace.
- Each command runs **once**, at invocation, in the session's working directory, in the
  session's shell (or the one `shell:` names). stderr is merged into the output.
- Default timeout: 2 minutes.
- A command that fails **aborts the entire skill invocation** with
  `Shell command failed for pattern "..."`. Exit 1 is tolerated for search and compare
  commands (`grep` with no matches); elsewhere append `|| true` to commands that
  legitimately exit non-zero.
- The command never asks for permission. If the permission check doesn't return
  "allow," the invocation aborts — pre-approve with `allowed-tools`. A pipeline is
  checked **per segment**: `` !`a | b | c` `` needs a rule for `a`, `b`, and `c`.
- `disableSkillShellExecution: true` in settings turns injection off entirely. Skills
  synced from claude.ai never run shell commands.

### Running the skill isolated (`context: fork`)

```yaml
---
name: research-topic
description: Researches a topic in depth
context: fork
agent: Explore
---

Research $ARGUMENTS in depth:
1. Find files with Glob and Grep
2. Read and analyze the code
3. Summarize with file:line references
```

The skill's content becomes the subagent's prompt. It does **not** see the
conversation history. Without `agent`, the subagent type is `general-purpose`.

Runs in the background by default (v2.1.218+); the result arrives when it's done.
`background: false` blocks the turn until it returns. It also blocks when running in
`-p` mode, when `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1` is set, when another
background task is already running, or when scheduled. A background fork has a reduced
tool set.

⚠️ `context: fork` only makes sense for skills with actionable instructions. A skill
that just says "use these API conventions" without a task gives the subagent nothing
to do.

### Content lifecycle

When invoked, the rendered `SKILL.md` enters the conversation as a message and **stays
there for subsequent turns**. Claude doesn't re-read the file afterward. So write
permanent instructions ("always do X throughout this task"), not one-time steps. And
keep the body lean — every line is a recurring cost.

Invoking the same skill again with identical content gets "already loaded" — nothing
is re-injected. After compaction, skills are re-attached up to 5,000 tokens each and
25,000 tokens combined.

Unlike the content, `allowed-tools` permission **expires** on your next message.

### Bundled skills

Ship with Claude Code, invoked like any skill. `disableBundledSkills: true` turns them
all off:

| Skill | For |
|---|---|
| `/code-review [level] [--fix] [--comment] [pr#]` | Reviews the diff or PR: correctness bugs and simplifications |
| `/simplify` | Reviews the diff for reuse, quality, and efficiency, then fixes what it finds |
| `/verify` | Builds and runs the app to confirm the change actually works |
| `/run` | Launches and drives the app to see the change working |
| `/run-skill-generator` | Teaches `/run` and `/verify` to build and launch this project |
| `/batch <instruction>` | Distributes a large change across 5-30 subagents, each with its own PR |
| `/loop [interval] [prompt]` | Repeats a prompt while the session is open |
| `/debug` | Diagnoses the current session from its debug log |
| `/doctor` | Setup checkup: diagnoses and fixes configuration issues |
| `/fewer-permission-prompts` | Analyzes transcripts and proposes a permission allowlist |
| `/update-config` | Edits settings from a description |
| `/design` | Design and UI work |
| `/dataviz` | Charts and data visualization |
| `/claude-api` | Guidance for building on the Claude API |
| `/workflow-authoring` | Writes reusable workflows, listed by `/workflows` |

`/deep-research` is **not** a bundled skill: it is a dynamic workflow, listed and run
through `/workflows`.

### Diagnostics

| Symptom | Likely cause / fix |
|---|---|
| Skill doesn't fire | `description` missing the words you actually use. Test with "what skills exist?" |
| Skill fires too often | `description` too generic, or missing `disable-model-invocation: true` |
| `/name` works but Claude never picks it | Malformed YAML: the body loads, the metadata doesn't. Run with `--debug` |
| A skill should be hidden from the model or from the menu | `skillOverrides` in settings: `"off"` or `"user-invocable-only"` per skill |
| Deny all skills or one skill | `permissions.deny`: `Skill`, `Skill(commit)`, `Skill(review-pr *)`, `Skill(anthropic-skills:pdf)` |

Validation: `/skill-doctor` (official) reviews a skill's description and triggers.
`claude plugin validate .claude/skills` (*not in official docs*; used in this repo)
catches malformed YAML only — it does not check field names.

---

## 6. Commands (slash commands)

**Custom commands have been merged with skills.** `.claude/commands/deploy.md` and
`.claude/skills/deploy/SKILL.md` both create `/deploy` and work the same way. Files in
`.claude/commands/` keep working; skills add a directory for supporting files and
automatic invocation by the model. When both exist with the same name, the skill wins.

Differences:

| | `.claude/commands/x.md` | `.claude/skills/x/SKILL.md` |
|---|---|---|
| Command name | File name | Directory name |
| Supporting files | No | Yes |
| Frontmatter | Same syntax; the docs don't list fields it ignores — assume the skill set | Full |
| Invoked by the model | Yes | Yes |

**Recommendation:** use `skills/` for new things. Keep `commands/` only for what
already exists as a simple file.

Behavior notes:

- Commands are only recognized **at the start** of the message.
- The text after the name becomes the arguments.
- Up to six skills can be chained in one message.

---

## 7. Subagents

Specialized assistants that run in an **isolated context window**, with their own
system prompt, their own tool access, and an optional model.

The core benefit: keeping exploration, logs, and file content **out** of your main
conversation. The subagent works independently and returns only a summary.

### Format

```markdown
---
name: security-reviewer
description: Reviews code for vulnerabilities. Use after changes to auth.
tools: Read, Grep, Glob, Bash
model: opus
---

You are a senior security engineer. Review the code looking for:
- Injection (SQL, XSS, command)
- Authentication and authorization flaws
- Secrets or credentials in the code

Give specific line references and suggested fixes.
```

The frontmatter defines the metadata; the markdown body becomes the **system prompt**.

### Full frontmatter

| Field | Required | Effect |
|---|---|---|
| `name` | ✅ | Identifier, lowercase and hyphens. Cannot contain `:` |
| `description` | ✅ | When to delegate. Short — all descriptions combined have a 15k-token ceiling |
| `tools` | ➖ | Comma-separated list. Without the field, inherits everything |
| `disallowedTools` | ➖ | Removes from the inherited list. Accepts `mcp__*` |
| `model` | ➖ | `sonnet`, `opus`, `haiku`, `fable`, full ID, or `inherit` |
| `permissionMode` | ➖ | `default`, `acceptEdits`, `auto`, `dontAsk`, `bypassPermissions`, `plan` |
| `maxTurns` | ➖ | Maximum turns before stopping |
| `skills` | ➖ | Skills preloaded **in full** at startup — never one with `disable-model-invocation: true`, which is skipped without a word (decision 0077) |
| `mcpServers` | ➖ | MCP servers only for this subagent |
| `hooks` | ➖ | Hooks only for this subagent |
| `memory` | ➖ | Persistent memory: `user`, `project`, or `local` |
| `background` | ➖ | `true` keeps it in background even if Claude asks for foreground |
| `effort` | ➖ | `low`…`max` |
| `isolation` | ➖ | `worktree` = runs in an isolated git worktree |
| `color` | ➖ | Display color |
| `omitClaudeMd` | ➖ | `true` = starts without the `CLAUDE.md` hierarchy and git status, like `Explore`/`Plan` |
| `initialPrompt` | ➖ | Text sent as the subagent's first user message before the delegation |
| `experimental.cacheTtl` | ➖ | Prompt-cache lifetime for the subagent: `5m` or `1h` |

### Where they live (priority order)

1. Managed settings (organization)
2. `--agents` flag (current session only)
3. `.claude/agents/` (project — version it in git for the team)
4. `~/.claude/agents/` (personal, all projects)
5. Plugin `agents/` (namespaced: `plugin:folder:name`)

Both directories are scanned recursively; subdirectories don't affect the name. The
identity is the `name` field: two files with the same `name` load only one, and
`/doctor` reports the duplicate. Plugin subagents ignore `hooks`, `mcpServers`, and
`permissionMode`.

Changes are detected within seconds, no restart needed — except when **creating** the
`agents/` directory for the first time, for directories added with `--add-dir`, and
when running with `--disable-slash-commands`.

### How to invoke

```text
# 1. Automatic delegation — Claude decides from the description
Use the code-improver agent to suggest improvements to this project

# 2. @-mention — guarantees execution
@agent-security-reviewer audit the auth changes

# 3. Entire session as the subagent
claude --agent code-reviewer

# 4. One-off definition
claude --agents '{"reviewer": {"description": "...", "prompt": "...", "tools": ["Read"]}}'
```

`Ctrl+B` sends a running foreground subagent to the background.

### What the subagent sees (and doesn't)

**Doesn't see:** conversation history, main session's auto memory, what you read
before.

**Sees:** its own system prompt, the CLAUDE.md hierarchy and git status (except
`Explore`, `Plan`, and any subagent with `omitClaudeMd: true`), the delegation
message, preloaded skills.

### Forks

A *fork* is a subagent that **inherits the entire conversation** instead of starting
from scratch:

```text
/subtask write unit tests for the parser changes so far
```

Sees the full history, system prompt, tools, and model. Only the final result returns
to the main conversation — tool calls stay out of your context.

Forking is on by default in interactive sessions and off in `-p` and the SDK.
`CLAUDE_CODE_FORK_SUBAGENT=1` / `=0` forces it either way. To forbid it:
`permissions.deny` → `Agent(fork)`.

### Bundled subagents

| Name | Model | For |
|---|---|---|
| `Explore` | Inherits the conversation's model; capped at Opus on the API | Fast, read-only search and analysis. Skips CLAUDE.md and git status |
| `Plan` | — | Research for plan mode. Read-only |
| `general-purpose` | `CLAUDE_CODE_SUBAGENT_MODEL`, else the conversation's | Complex multi-step tasks, all tools |
| `claude` | — | Catch-all with every tool |
| `statusline-setup` | Sonnet | Configures the status line (`/statusline`) |
| `claude-code-guide` | Haiku | Answers questions about Claude Code, the SDK, and the API |

To restrict built-ins: `permissions.deny` on `Agent` or on a specific type,
`CLAUDE_CODE_DISABLE_EXPLORE_PLAN_AGENTS=1`, or in the SDK
`CLAUDE_AGENT_SDK_DISABLE_BUILTIN_AGENTS=1`. Which agents an agent may spawn:
`tools: Agent(worker, researcher), Read, Bash`.

### Limits

- **Concurrency:** 20 simultaneous. Beyond that: `Concurrent subagent limit reached`.
- **Descriptions:** combined ceiling of 15,000 tokens for custom subagents.
- A background subagent has a smaller tool set than a foreground one.

### Cost control

```json
{
  "env": {
    "CLAUDE_CODE_SUBAGENT_MODEL": "haiku"
  }
}
```

Sets the model for `general-purpose` and for any subagent without its own `model`.
Haiku for read-only work (search, reading, summarizing) is cheap and effective.
Reserve Opus for complex reasoning.

### Persistent memory

```yaml
---
name: code-reviewer
description: Reviews code
memory: project
---

While reviewing, update your memory with patterns, conventions, and recurring issues.
```

Three scopes: `user` (all projects), `project` (shareable via git), `local` (this
machine only). The exact directories are not stated in the official docs; check
`/memory` or the subagent's own output rather than assuming a path.

### Files silently ignored

Claude Code **skips** a subagent file when: it has no `name` field (treated as
documentation); the opening `---` isn't on the first line; `name` starts with `-` or
contains `:`; it has `name` but no `description`; the YAML doesn't parse.

Check with `/doctor` (reports duplicates and parse problems) or `/agents`. In this
repository `java .claude/hooks/ArchHook.java schema` also validates the field names.

---

## 8. Hooks

Scripts (or HTTP requests, MCP calls, prompts, subagents) triggered on lifecycle
events. **Deterministic**: they always happen on the event, regardless of what the
model decides. That's the difference between asking and guaranteeing.

### Events

- **Session:** `SessionStart`, `Setup`, `SessionEnd`
- **Per turn:** `UserPromptSubmit`, `UserPromptExpansion`, `Stop`, `StopFailure`
- **Per tool call:** `PreToolUse`, `PermissionRequest`, `PermissionDenied`,
  `PostToolUse`, `PostToolUseFailure`, `PostToolBatch`
- **Subagents and tasks:** `SubagentStart`, `SubagentStop`, `TaskCreated`,
  `TaskCompleted`, `TeammateIdle`
- **Context:** `PreCompact`, `PostCompact`, `InstructionsLoaded`
- **Environment:** `ConfigChange`, `CwdChanged`, `DirectoryAdded`, `FileChanged`,
  `WorktreeCreate`, `WorktreeRemove`
- **Model and UI:** `PreModelSwitch`, `PostModelSwitch`, `Notification`,
  `MessageDisplay`, `Elicitation`, `ElicitationResult`

### Types

| `type` | Runs | Notes |
|---|---|---|
| `command` | A process | `command` + optional `args` (exec form) |
| `http` | An HTTP request | Body is the hook input JSON |
| `mcp_tool` | An MCP tool | |
| `prompt` | A single model call | Returns `{"ok": ..., "reason": ...}`; `"impossible": true` and `continueOnBlock` refine blocking |
| `agent` | A subagent (experimental) | 60 s, up to 50 turns |

### Configuration

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Write|Edit",
        "hooks": [
          {
            "type": "command",
            "command": "prettier",
            "args": ["--write", "${tool_input.file_path}"],
            "timeout": 30
          }
        ]
      }
    ]
  }
}
```

Matchers are **case-sensitive**: `"*"` or omitted matches everything; plain text
matches exactly or a list (`Edit|Write`, `Edit, Write`); other characters become a
JavaScript regex (`^Notebook`, `mcp__.*`).

**Exec form** (with `args`, even `"args": []`): direct executable, no shell — portable
across Linux, macOS, and Windows, and doesn't need an execute bit. **Shell form**
(without `args`): string passed to the shell, with expansion and quoting.

Hooks from every settings layer **add up** — a project hook doesn't replace a user
hook. Matching hooks run in parallel; when they disagree, the most restrictive answer
wins (`deny > defer > ask > allow` on `PreToolUse`). A `PreToolUse` hook fires before
the permission-mode check, but cannot override a settings `deny` rule or a tool's
`requiresUserInteraction`. When several hooks return `updatedInput`, the last one wins.
`disableAllHooks: true` turns everything off.

### Exit codes

| Code | Behavior |
|---|---|
| `0` | No objection. Output JSON is parsed if valid. On `PreToolUse` it does **not** approve — the normal permission flow continues. On `UserPromptSubmit`, `UserPromptExpansion`, `SessionStart`, and `PostModelSwitch` plain stdout is added to context |
| `2` | **Blocks the action** (on events that support it). stderr becomes the reason shown to Claude |
| Other | Non-blocking error; the action proceeds, stderr shown to the user |

Where `2` blocks: `PreToolUse` (blocks the call), `UserPromptSubmit` (rejects the
prompt), `Stop` (forces continuation), `PostToolBatch` (stops the loop). In
`PostToolUse` it doesn't block — the tool already ran — but stderr is shown to Claude.

`Stop` hooks are capped at 8 consecutive blocks per turn
(`CLAUDE_CODE_STOP_HOOK_BLOCK_CAP`); the input carries `stop_hook_active` so a hook can
tell it is being re-run.

### Structured JSON output

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "deny",
    "permissionDecisionReason": "Blocked by policy",
    "additionalContext": "Extra context for Claude"
  }
}
```

`permissionDecision` accepts `allow`, `deny`, `ask`, `defer`. The shape changes by
event: `UserPromptSubmit` returns `hookSpecificOutput.additionalContext`;
`PostToolUse` and `Stop` block with a top-level `"decision": "block"` plus `reason`;
`PermissionRequest` answers with `hookSpecificOutput.decision.behavior`. There is no
`systemMessage` field in the documented output.

### Timeouts

| Type | Default |
|---|---|
| `command`, `http`, `mcp_tool` | 10 min — 30 s on `UserPromptSubmit`, `PreModelSwitch`, `PostModelSwitch`; 10 s on `MessageDisplay` |
| `prompt` | 30 s |
| `agent` | 60 s |
| any, on `SessionEnd` | 1.5 s |

### Example: blocking edits to protected files

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "if": "Edit(**/db/migration/*.sql)",
            "command": "bash",
            "args": ["-c", "echo 'Applied migrations are not edited' >&2; exit 2"]
          }
        ]
      }
    ]
  }
}
```

The `if` field filters by **permission-rule syntax** before running the hook — avoids
spawning a process for every edit. It only applies to tool events and is best-effort:
a filter the runtime can't evaluate lets the hook run.

### Where to configure

| Location | Scope | Shareable |
|---|---|---|
| `~/.claude/settings.json` | All projects | No |
| `.claude/settings.json` | This project | Yes (version it) |
| `.claude/settings.local.json` | This project | No (gitignore) |
| `<plugin>/hooks/hooks.json` | Wherever the plugin is active | Yes |
| Skill/subagent frontmatter | During invocation | Yes |

### Tips

- Ask Claude to write the hook: *"write a hook that runs eslint after every file
  edit"*.
- `/hooks` shows what's configured (read-only). Settings are read at startup: after
  editing them, restart.
- Debug: `claude --debug` (or `claude --debug=hooks`), `--debug-file <path>`, and
  `/debug` inside the session.
- Path placeholders: `${CLAUDE_PROJECT_DIR}`, `${CLAUDE_PLUGIN_ROOT}`.
- A `SessionStart` hook can persist environment variables for the session by writing
  `export` lines to the file named in `$CLAUDE_ENV_FILE`.

---

## 9. MCP

Model Context Protocol: an open standard that connects Claude to external tools,
databases, and APIs — without you copying and pasting data into the chat.

### Adding servers

```bash
# Remote HTTP
claude mcp add --transport http notion https://mcp.notion.com/mcp

# With an auth header
claude mcp add --transport http github https://api.githubcopilot.com/mcp/ \
  --header "Authorization: Bearer YOUR_TOKEN"

# Local process (stdio). The -- separates the option flags from the server command
claude mcp add --env API_KEY=value --transport stdio myserver \
  -- npx -y @example/mcp-server

# From a JSON definition
claude mcp add-json myserver '{"type":"http","url":"https://example.com/mcp"}'
```

Transports: `stdio`, `http` (alias `streamable-http`), `sse`, `ws`; `sdk` exists only
for the Agent SDK. A `stdio` server receives `CLAUDE_PROJECT_DIR` in its environment.

### Scopes

| Scope | Location | Shared |
|---|---|---|
| `local` (default) | `~/.claude.json` | No |
| `project` | `.mcp.json` | Yes, via git |
| `user` | `~/.claude.json` | No, but applies to all projects |

Precedence when names collide, silently: **managed > local > project > user > plugin >
claude.ai connectors**.

```json
{
  "mcpServers": {
    "database": {
      "type": "stdio",
      "command": "python",
      "args": ["db-server.py"],
      "env": { "DB_URL": "${DB_URL:-localhost:5432}" }
    }
  }
}
```

Variable expansion (`${VAR}`, `${VAR:-default}`) works in `command`, `args`, `env`,
`url`, and `headers`. An unset variable with no default doesn't fail startup: the server
loads with the literal `${VAR}` text and fails to connect; `claude mcp list` surfaces the
warning.

### Credentials beyond a static header

- **OAuth** — `claude mcp add` then `/mcp` → authenticate drives the OAuth 2.0 flow;
  `claude mcp logout <name>` and `claude mcp remove` drop the tokens. The `oauth`
  sub-keys (`clientId`, `callbackPort`, `authServerMetadataUrl`, `scopes`) are *not in
  official docs*; this repository's `extensions.json` still accepts the key.
- **`headersHelper`** — path to a script; its stdout (JSON) supplies headers at connect
  time. For Kerberos, SSO, or any credential that can't sit still in a file. For a
  `project`- or `local`-scope server this only runs after the workspace-trust prompt is
  accepted.
- **`alwaysLoad`** — *not in official docs*; kept in `extensions.json` so an existing
  declaration doesn't fail validation. Don't rely on it.

### Managing

```bash
claude mcp list                    # servers and status, including pending approval
claude mcp get notion              # details
claude mcp remove notion           # also deletes stored OAuth tokens
claude mcp logout sentry           # drop OAuth tokens, keep the server
```

`claude mcp reset-project-choices` is *not in official docs*. In-session: `/mcp` to
view status, toggle, and authenticate.

### Project-scope approval

The first time Claude Code sees a server declared in a project's `.mcp.json`, it prompts
for approval — the trust boundary that stops a cloned repository from launching
processes on your machine without consent. The prompt only appears after the
workspace itself is trusted. `-p` mode, the SDK, and cloud sessions never prompt: they
load only what was already approved, or nothing. Settings that affect this:

| Key | For what |
|---|---|
| `enabledMcpjsonServers` | Array — pre-approves specific `.mcp.json` servers by name |
| `disabledMcpjsonServers` | Array — rejects specific `.mcp.json` servers by name |
| `enableAllProjectMcpServers` | Boolean — approves every `.mcp.json` server with no prompt at all. Removes the one human checkpoint the mechanism exists for |
| `--strict-mcp-config` | Flag — load only the servers from `--mcp-config`, ignore every file |
| `--setting-sources` without `project` | Flag — `.mcp.json` is not read at all |
| `allowedMcpServers` / `deniedMcpServers` | *Not in official docs*; kept in `extensions.json` for existing managed setups |
| `disableClaudeAiConnectors` | Boolean — don't load connectors from claude.ai |

### Tool names

```
mcp__<server>__<tool>                     e.g.: mcp__github__create_pr
mcp__plugin_<plugin>_<server>__<tool>     e.g.: mcp__plugin_my-plugin_db__query
```

Use this pattern in permission rules and in `disallowedTools`. Globs are allowed after
the server prefix (`mcp__github__*`); `deny` and `ask` accept `mcp__*` for every
server. A server can flag a tool `requiresUserInteraction`, which no hook can bypass.
Reserved server names: `workspace`, `claude-in-chrome`, `computer-use`, `Claude
Preview`, `Claude Browser`.

### Cost and security

Tool search is on by default: only the **names** load at startup; full schemas are
deferred until use (`ENABLE_TOOL_SEARCH` controls it;
`CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS` disables the beta behind it). Tool
descriptions are truncated at 2,048 characters
(`CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH`). `MCP_DISCOVERY_CACHE=1` caches discovery
between sessions. `/context all` shows how many tokens each loaded MCP tool consumes.

⚠️ An MCP server can read files, call APIs, and **inject prompts**. Only connect
servers you trust. Prefer OAuth over a token pasted into config.

### Cheaper alternative: CLI

Before connecting an MCP, consider whether a CLI solves it. `gh`, `aws`, `gcloud`,
`sentry-cli` are the **most context-efficient** way to talk to external services, and
Claude already knows how to use them. For a CLI it doesn't know:
*"use `foo-cli --help` to learn the tool, then solve A, B, C"*.

---

## 10. Plugins and marketplaces

Packaging: a plugin bundles skills, subagents, hooks, and MCP servers into a single
installable, versioned unit.

| Approach | Skill name | Best for |
|---|---|---|
| Standalone (`.claude/`) | `/deploy` | Personal workflow, customizing this project, quick experiment |
| Plugin | `/plugin:deploy` | Sharing with the team, distributing, versioning, reusing across repos |

Start standalone; convert to a plugin when a **second repository** needs the same
setup.

### Structure

```
my-plugin/
├── .claude-plugin/
│   └── plugin.json      # only the manifest goes here
├── skills/
│   └── review/SKILL.md
├── agents/
├── hooks/hooks.json
└── .mcp.json
```

⚠️ **Common mistake:** don't put `skills/`, `agents/`, or `hooks/` **inside**
`.claude-plugin/`. Only `plugin.json` goes there; everything else lives at the
plugin's root.

```json
{
  "name": "my-plugin",
  "description": "What this plugin does",
  "version": "1.0.0",
  "author": { "name": "Your Name" }
}
```

Only `name` is required. Other component types (`.lsp.json`, `monitors/`, `bin/`,
`settings.json`) circulate in third-party examples but are *not in the official
plugins docs* — don't count on them. Plugin subagents ignore `hooks`, `mcpServers`,
and `permissionMode`.

Inside a plugin, `${CLAUDE_PLUGIN_ROOT}` is the plugin's directory and
`${CLAUDE_PLUGIN_DATA}` a persistent data directory — both plugin-only.

### What a plugin costs

Every enabled plugin costs per session: its skills' descriptions and its subagents'
descriptions in context, its MCP servers as running processes, its hooks running as
you, with your permissions. The **Marketplaces** tab of `/plugin` shows a *Context
cost* per plugin. Disable what you don't use: `claude plugin disable <plugin>`.

### Developing and testing

```bash
claude --plugin-dir ./my-plugin     # load without installing
claude --plugin-dir ./p1 --plugin-dir ./p2
claude --plugin-dir ./skills-dir    # a bare skills directory, loaded as <name>@skills-dir
claude plugin eval ./my-plugin      # run the plugin's eval suite
```

`/skill-doctor` reviews a skill's triggering. `/reload-plugins [--force]` reloads
without restarting the session. `claude plugin init` and `claude plugin validate` are
*not in official docs* — this repository uses `validate` for YAML syntax only.

### Installing

```
/plugin                                          # browse the marketplace
/plugin marketplace add anthropics/claude-plugins-official
/plugin install mcp-server-dev@claude-plugins-official
/plugin uninstall <plugin>@<marketplace>
```

A marketplace is a repository with `.claude-plugin/marketplace.json`. The official
marketplace (`claude-plugins-official`) is added on the first interactive session.
Three tiers: **Official** and **Community** (only for marketplaces under
`github.com/anthropics/`) and **Third-party**. For your team: host the marketplace in a
**private repository**.

Scopes: **User** (default, all projects), **Project** (`.claude/settings.json`,
shared via git), **Local**. Cloud sessions don't load locally installed plugins. Three
layers must agree: settings (enabled), disk (`~/.claude/plugins/`), and the session
(loaded). Organizations can lock this down with `strictPluginOnlyCustomization`.

---

## 11. Workflows that work

### Give Claude a way to verify its own work

**The highest-impact practice.** Claude stops when the work *looks* done. Without a
check, "looks done" is the only signal available — and you become the verification
loop: every error waits for you to notice.

Give it something that returns pass/fail and the loop closes itself: a test suite, a
build exit code, a linter, a script that compares output to a fixture, a screenshot
compared against the design.

| Strategy | Before | After |
|---|---|---|
| Verification criterion | *"implement email validation"* | *"write validateEmail. cases: user@example.com true, invalid false, user@.com false. run the tests after implementing"* |
| Visual verification | *"make the dashboard better"* | *"[screenshot] implement this design. take a screenshot of the result, compare it to the original, list differences and fix them"* |
| Root cause | *"the build is broken"* | *"the build fails with: [error]. fix it and verify. attack the root cause, don't suppress the error"* |

Escalation by rigor:

1. **In the prompt** — ask it to run the check and iterate in the same message.
2. **In the session** — `/goal <condition>`: a separate evaluator re-checks every
   turn.
3. **As a deterministic gate** — a `Stop` hook that runs the script and blocks the end
   of the turn until it passes.
4. **Second opinion** — a verification subagent: whoever did the work isn't who checks
   the proof.

Ask for **evidence**, not a claim: the test output, the command run, and what it
returned. Reading evidence is faster than re-running the check yourself.

### Explore → plan → code → commit

Letting Claude jump straight into code produces code that solves the wrong problem.

1. **Explore** — `Shift+Tab` until `⏸ plan mode on`, or `claude --permission-mode plan`.
   *"read /src/auth and understand how we handle sessions and login"*.
2. **Plan** — *"I want to add Google OAuth. which files change? what's the session
   flow? create a plan."* `Ctrl+G` opens the plan in your editor to edit.
3. **Implement** — exit plan mode and execute, checking against the plan.
4. **Commit** — *"commit with a descriptive message and open a PR"*.

Plan mode has a cost. For a clear scope and a small fix (typo, log, rename), ask
directly. **If you can describe the diff in one sentence, skip the plan.**

### Let Claude interview you

For large features, starting with a minimal prompt and letting it ask questions
produces better specs than writing the spec yourself:

```text
I want to build [brief description]. Interview me in detail using the
AskUserQuestion tool.

Ask about technical implementation, UI/UX, edge cases, concerns, and trade-offs.
Don't ask obvious questions, dig into the hard parts I might not have considered.

Keep interviewing until we've covered everything, then write the full spec into SPEC.md.
```

Then **open a new session** to execute: clean context, focused only on the
implementation, with the spec written down to consult.

A good spec names the files and interfaces involved, states what's **out** of scope,
and ends with an end-to-end verification step.

### Specific context in the prompt

| Strategy | Before | After |
|---|---|---|
| Scope the task | *"add tests for foo.py"* | *"write a test for foo.py covering the logged-out user case. avoid mocks."* |
| Point to the source | *"why is this API weird?"* | *"look at ExecutionFactory's git history and summarize how the API got to this state"* |
| Reference patterns | *"add a calendar widget"* | *"see how the home page widgets are implemented. HotDogWidget.php is a good example. follow the pattern for..."* |
| Describe the symptom | *"fix the login bug"* | *"login fails after session timeout. look at src/auth/, especially token refresh. write a test that reproduces it, then fix it"* |

A vague prompt has its place when you're exploring: *"what would you improve in this
file?"* surfaces things you wouldn't have thought to ask.

### Writer/Reviewer pattern

Fresh context improves review — Claude isn't biased by code it just finished writing.

| Session A (Writer) | Session B (Reviewer) |
|---|---|
| `Implement rate limiting on the endpoints` | |
| | `Review @src/middleware/rateLimiter.ts. Look for edge cases, race conditions, and consistency with existing middleware.` |
| `Here's the feedback: [B's output]. Address it.` | |

Works the same way for tests: one session writes the tests, another writes the code
that passes them.

### Adversarial review before calling it done

```text
Use a subagent to review the rate limiter diff against PLAN.md. Verify that every
requirement was implemented, that the listed edge cases have tests, and that nothing
outside the scope changed. Report gaps, not style preferences.
```

⚠️ A reviewer instructed to find gaps will almost always find one, even in solid work
— it's what you asked for. Chasing every observation leads to over-engineering: extra
abstraction layers, defensive code, tests for impossible cases. Instruct the reviewer
to flag **only** what affects correctness or the stated requirements.

### Non-interactive mode and CI

```bash
claude -p "Explain what this project does"
claude -p "List all endpoints" --output-format json
claude -p "Analyze this log" --output-format stream-json --verbose

# Pipes
tail -200 app.log | claude -p "let me know if there are anomalies"
git diff main --name-only | claude -p "review these files for security issues"
```

### Fan-out across many files

For large migrations, in a git repository use `/batch <instruction>` — Claude
splits the change across 5 to 30 subagents, each in its own worktree opening a PR.

To drive by script:

```bash
for file in $(cat files.txt); do
  claude -p "Migrate $file from Python 2 to 3. Answer OK or FAIL." \
    --allowedTools "Edit,Bash(git commit *)"
done
```

Refine the prompt on the first 2-3 files before releasing it on the whole set.
`--allowedTools` limits the damage in an unsupervised run.

### Parallel sessions

- **Worktrees** — CLI sessions in isolated git checkouts, no edit collisions
  (`claude --worktree`).
- **Desktop app** — multiple local sessions, each in its own worktree, visually.
- **Claude Code on the web** — cloud sessions, for long-running tasks.
- **Cross-session messaging** — sessions pass findings to each other (`/list-agents`).

---

## 12. Context management

The window holds the entire conversation. A single debugging session can consume tens
of thousands of tokens, and performance drops as it fills up.

### Tools

| Command | Effect |
|---|---|
| `/clear` | Resets the context. Use **between unrelated tasks** |
| `/compact [instructions]` | Summarizes the conversation to free up space. `/compact focus on the API changes` |
| `/context` | Shows current usage as a colored grid |
| `/context all` | Breaks down cost per loaded MCP tool |
| `Esc` | Stops Claude mid-action, preserving context |
| `Esc Esc` or `/rewind` | Rewinds conversation and/or code to a checkpoint |
| `/btw <question>` | Side question whose answer does **not** enter the history |
| `/recap` | Summary of the session so far, without compacting |
| `/usage` | Token statistics |

### Practical rules

- **`/clear` between unrelated tasks.** Junk-drawer session is the most common
  anti-pattern.
- **After two failed fixes at the same spot, `/clear`.** The context is polluted with
  the approaches that didn't work. A clean session with a better prompt almost always
  beats a long session with accumulated fixes.
- **Delegate investigation to subagents.** *"use subagents to investigate how token
  refresh works"* — they read dozens of files in their own context, you get the
  summary.
- **Customize compaction** through CLAUDE.md: *"when compacting, always preserve the
  full list of modified files and the test commands"*.

### Context cost per piece

| Piece | When it loads | Cost |
|---|---|---|
| CLAUDE.md | Session start | **Every request** |
| Rules without `paths` | Session start | Every request |
| Rules with `paths` | When matching files are touched | Only when relevant |
| Skills | Descriptions at start; content when used | Low |
| Skills with `disable-model-invocation` | Only when you invoke it | **Zero** until invoked |
| MCP | Names at start; schemas on demand | Low |
| Subagents | When created | **Isolated** from the session |
| Hooks | On the event | **Zero**, unless they return output |

### Prompt cache

Each request reuses the cached prefix of the previous one. Actions that **invalidate**
the cache (the next request pays full price): switching model, changing effort,
toggling fast mode, connecting or removing an MCP server, enabling or disabling a
plugin, denying a whole tool, compaction, sending many images, updating Claude Code.
Actions that **preserve** it: editing repository files, editing `CLAUDE.md`, switching
permission mode, changing output style, invoking skills, `/recap`, rewinding.

Cache lifetime is `5m` by default, `1h` when configured; a subagent can pin its own
with `experimental.cacheTtl`.

### Checkpoints

Every prompt creates a checkpoint. `Esc Esc` or `/rewind` restores conversation, code,
or both. This changes the posture: instead of planning every move, tell it to try
something risky — if it doesn't work, roll back. Checkpoints are independent of git
and survive `--resume`.

⚠️ Checkpoints only track changes made through Claude's own editing tools. Changes via
Bash, external processes, or background subagents are **not** captured; symlinks and
hard links are not followed; remote actions (API calls, deploys) can't be undone. It
doesn't replace git.

---

## 13. Anti-patterns

| Pattern | Symptom | Fix |
|---|---|---|
| **Junk-drawer session** | Started with one task, asked about something else, came back. Context full of irrelevant stuff | `/clear` between tasks |
| **Fixing without stopping** | Fixed it, still wrong, fixed it again. Context polluted with failed attempts | After 2 fixes, `/clear` and rewrite the initial prompt incorporating what you learned |
| **Bloated CLAUDE.md** | Important rules get lost in the noise; Claude ignores half of it | Prune freely. If it already gets it right without the instruction, delete it or turn it into a hook |
| **Trusting without verifying** | Plausible implementation that doesn't handle edge cases | Always provide a way to verify. If you can't verify it, don't merge it |
| **Infinite exploration** | "Investigate X" with no scope; it reads hundreds of files | Bound the investigation or delegate to a subagent |

---

## 14. Model, effort, thinking, output styles

### Choosing a model

Aliases: `default`, `best`, `fable`, `sonnet`, `opus`, `haiku`, `sonnet[1m]` and
`opus[1m]` (1M-token context), `opusplan` (Opus in plan mode, Sonnet otherwise). Pin
a full id to freeze it (`claude-opus-5-5`). `ANTHROPIC_BASE_URL` changes the
destination, not the model.

Where to set it, in precedence order: `/model` in the session (`Enter` sets the
default, `s` sets only this session), `--model` flag, `model` in settings,
`ANTHROPIC_MODEL` env var. The cache is per model — switching mid-session pays a cold
prefix. Related settings: `availableModels`, `modelPicker`, `modelOverrides`,
`fallbackModel`; `/autocompact` and `--autocompact`.

### Effort

`low`, `medium`, `high`, `xhigh`, `max` — supported on Fable 5.1/5, Opus 5.5/5,
Sonnet 5, Opus 4.8/4.7. Opus 4.6 and Sonnet 4.6 have no `xhigh`. An unsupported level
falls back to the highest level below it.

Precedence: `CLAUDE_CODE_EFFORT_LEVEL` / `--effort` / `/effort` → settings
(`modelSettings`, `effortLevel`) → default (`high`; `medium` on Opus 5.5; `xhigh` on
Opus 4.7). `max` is session-only unless set through the env var. `ultracode` is a
separate switch: `/effort ultracode`, `--effort ultracode`, or `"ultracode": true`.

### Thinking, fast mode, advisor

- Toggle extended thinking with `Option+T` / `Alt+T`; `alwaysThinkingEnabled` in
  settings; `MAX_THINKING_TOKENS=0` disables it — except on Opus 5.5 and Fable, where
  thinking can't be turned off. `Ctrl+O` shows or hides the thinking.
- Fast mode: `/fast`, `--fast`. Same Opus model with faster output; toggling it
  invalidates the cache.
- Advisor: `/advisor <model|off>`, `--advisor` — a second model consulted on hard
  steps.

### Output styles

Replace the coding-focused part of the system prompt while keeping the tools.
Built-in: **Default**, **Proactive**, **Concise**, **Explanatory**, **Learning**.
Switch with `/output-style [name]` or `outputStyle` in settings (case-sensitive).
Custom styles are markdown files in `~/.claude/output-styles/` or
`.claude/output-styles/`.

| | CLAUDE.md | Output style | Hook | Skill |
|---|---|---|---|---|
| Changes | Adds a user message after the system prompt | Replaces the coding part of the system prompt | Runs code on an event | Adds content on demand |
| Applies | Every session | Every session, once selected | Deterministically | When invoked |
| Use for | Project facts and rules | How Claude talks and works | Enforcement | Procedures |

---

## 15. How this repository uses all of this

Nerviz is a live example of the ecosystem applied to Spring Boot project
scaffolding. Mapping between the theory above and the files here:

```
CLAUDE.md                          # always-on context: invariants + routing
claude-help.md                     # this guide; cited from CLAUDE.md, not imported
.claude/
├── rules/                         # modular norms, loaded by paths
│   ├── 00-index.md                # index: which norm covers what
│   ├── architecture-ddd.md        # paths come from the blueprint at generation time
│   ├── naming.md                  # paths: **/src/**/*.java — never **/*.java (decision 0082)
│   ├── code-quality.md
│   ├── error-handling.md
│   ├── api-rest.md                # paths rewritten from the blueprint at generation
│   ├── lombok.md
│   ├── value-objects.md
│   ├── persistence.md
│   ├── testing.md
│   ├── observability.md
│   ├── logging.md
│   ├── messaging.md
│   ├── personal-data.md
│   └── scheduling.md
├── skills/
│   ├── init-project/SKILL.md              # /init-project — disable-model-invocation
│   ├── arch-doctor/SKILL.md               # /arch-doctor  — disable-model-invocation
│   ├── arch-adopt/SKILL.md                # /arch-adopt   — installs/updates this .claude/ via export
│   ├── audit-usage/SKILL.md               # /audit-usage  — reads the execution trail
│   ├── claude-code-architect-designer/    # designs this .claude/ itself; references/, templates/
│   ├── project-bootstrap/                 # SKILL.md + templates/ + references/
│   ├── use-case-design/                   # pipeline 1
│   ├── domain-modeling/                   # pipeline 2
│   ├── rest-api-architect/                # pipeline 3
│   ├── graphql-api-architect/             # pipeline 3g, conditional — inbound GraphQL adapter (schema-first)
│   ├── ai-architect/                      # pipeline, conditional — a language model or vector store behind a port
│   ├── search-architect/                   # pipeline 3e, conditional — Elasticsearch/OpenSearch index as a projection
│   ├── messaging-architect/               # pipeline 3b, conditional — Kafka producer/consumer
│   ├── jobs-architect/                    # pipeline 3c, conditional — scheduled jobs, outbox relay
│   ├── persistence-architect/             # pipeline 4 — relational (JPA) or document (MongoDB)
│   ├── cache-architect/                   # pipeline 4b, conditional — Caffeine or Redis behind a port
│   ├── test-architect/                    # pipeline 5
│   ├── new-feature/                       # orchestrates the pipeline above
│   ├── gof-design-patterns/               # catalog: read at design time, injected into the executor
│   ├── docker-architect/                  # extends docker-compose after bootstrap
│   └── git-publish/                       # chained after /init-project and the executor
├── agents/
│   ├── project-initializer.md         # subagent: interviews, validates, delegates
│   ├── java-spring-boot-developer.md  # /new-feature's executor
│   ├── archunit-installer.md          # test-architect's setup mode, isolated context
│   └── commons-logging-installer.md   # /new-feature's pre-flight, isolated context
├── hooks/
│   ├── ArchHook.java              # check · format · tests · schema · audit · guard · compose
│   │                              #   · context · export · doctor · build
│   └── ArchHook.jar               # what every hook launches — committed, verified in CI
├── schemas/
│   └── extensions.json            # single owner of recognized frontmatter, .mcp.json fields, hook
│                                  #   registrations, skill and agent classes, the export manifest
├── blueprints/                    # declarative data, not instructions
│   ├── _schema.md
│   ├── README.md · README.pt-br.md
│   ├── references/
│   ├── hexagonal/                 # hexagonal.yaml + references/
│   ├── clean-architecture-multi-module/
│   ├── clean-architecture-single-module/
│   ├── layered/
│   ├── modular-monolith/
│   ├── onion/
│   ├── vertical-slice/
│   └── custom-template/custom.template.yaml
├── decisions/                     # design records — history, versioned, never copied out
├── lessons-learned/               # symptoms observed in real runs — history, versioned
├── .ci/*Test.java                 # ten CI tests, each injecting a violation into the jar
└── settings.json                  # hooks + permissions + enabledPlugins
```


There's no `.claude/commands/`: slash commands and skills were merged, and keeping
both would duplicate the concept. `/init-project`, `/arch-doctor`, and `/audit-usage`
are skills with `disable-model-invocation: true` — same `/name`, plus a support folder.
`CLAUDE.md` cites `.claude/decisions/` for history; that directory is versioned, is not
read by the runtime, and is not copied into a generated project.

Decisions in this repository that illustrate the guide's principles well:

- **CLAUDE.md as a router, not an encyclopedia.** The "when X, read Y" table points to
  skills; the norms live in `.claude/rules/`, loaded by `paths` when the matching file
  is touched. CLAUDE.md doesn't repeat any norm.
- **One norm, one owning file.** Skills and agents **cite** it by path
  (`@.claude/rules/x.md`) and never reproduce the content. A norm written in two
  places has diverged — that's a bug.
- **Recognized fields are data with one owner.** `.claude/schemas/extensions.json`
  lists the frontmatter fields and `.mcp.json` server fields the hook accepts;
  `frontmatter-fields.md` and §5/§7 here are derived from it. The runtime ignores an
  unknown field silently, and `claude plugin validate` lets it through — so the check
  is a hook (invariant 10 of `CLAUDE.md`).
- **Enforcement in a hook, not in a prompt.** The prohibition on importing Spring in
  the `domain` module isn't a request in markdown: `ArchHook.java check` reads
  `.claude/forbidden-imports.txt` — written into the generated project by
  `project-bootstrap` from the blueprint's `depends_on` — and blocks. Here that file
  doesn't exist and the hook exits 0, by design. `.claude/.ci/BoundaryTest.java`
  proves the block on every OS.
- **Hook in exec form, no shell.** `command: java` + `args: ["-jar", ".claude/hooks/ArchHook.jar", <mode>]`,
  the jar precompiled from one Java source (a source launch recompiled the file on every
  call, ~3.3 s against ~0.3 s) — identical on Linux, macOS, and Windows, no `chmod`.
- **`if` instead of a matcher regex.** `schema` runs on `PreToolUse` and `PostToolUse`,
  filtered by `if: "Edit(.claude/**/*.md)"`, `if: "Edit(.mcp.json)"` and the two settings
  files — no process spawned for a Java edit. A path in `if` only matches through
  `Edit(...)`/`Read(...)`, and `Edit` covers every built-in tool that writes a file, `Write`
  included. `format` and `check` run on `PostToolUse` with matcher
  `Write|Edit|MultiEdit|NotebookEdit` and `if: "Edit(**/*.java)"`.
- **`Stop` hook as a verification gate.** `ArchHook.java tests` (and `schema`) run on
  the `Stop` event: the turn doesn't end without the tests passing. It's level 3 of the
  escalation described in
  [Give Claude a way to verify its own work](#give-claude-a-way-to-verify-its-own-work).
- **Secrets denied by permission, not by convention.** `permissions.deny` blocks
  `Read(./**/*.env)`, `Read(./**/secrets/**)`, `Read(./**/application-prod.yml)`,
  `Read(./**/application-prod.yaml)`, `Read(./**/*.pem)`, `Read(./**/*.p12)`, and
  `Bash(git push --force:*)` — and `guard bash` refuses the force-push spellings that
  prefix misses (`-f`, `--force-with-lease`, `+ref`) — the CLAUDE.md invariant "no literal secret in a
  versioned file" has a real gate behind it, and `schema` scans `.mcp.json`'s
  `headers`/`env` for spelled-out tokens.
- **Data outside the instructions.** The blueprints are declarative YAML. Adding a new
  architecture doesn't require touching any skill, agent, or command — if it did, the
  design would be broken.
- **Command with dynamic context.** `skills/init-project/SKILL.md` uses
  `` !`find .claude/blueprints -mindepth 2 -maxdepth 2 -name '*.yaml' 2>/dev/null | xargs -n1 basename | sed 's/\.yaml$//'` ``
  to list the available architectures at invocation time, instead of a fixed list that
  goes stale. It has no `allowed-tools` Bash filter, so the pipeline passes as a whole.
- **Skill with exemplars, not molds.** `project-bootstrap/templates/` holds real,
  compilable files; `SKILL.md` says to read the shape and write the equivalent, not
  mechanically substitute placeholders.
- **The exemplar lives in the skill; the norm, in `rules/`.** `rules/error-handling.md`
  declares the typed family of exceptions in prose and auto-loads on every `**/*.java`;
  `skills/domain-modeling/templates/DomainException.java.example` is the code, read
  only when that skill runs. Swapping the two around would put Java code into context
  on every file touched — see [Code boilerplate](#code-boilerplate-scaffolders).
  `references/` in the same skill directory holds what's reading material for the
  model (`blueprint-selection.md`, `dependency-catalog.md`), not code to emit.
- **Versions resolved at runtime.** Spring Initializr is the oracle for versions; the
  skill explicitly forbids writing versions from memory. The model's knowledge is
  out of date by construction.
- **Observers don't leave a trail.** `audit` mode records every skill and agent run in
  the *generated* project; `/audit-usage` and `/arch-doctor` sit in the `observer` class,
  which declares `audited: false`, so reading the trail doesn't append to it.
  `/arch-adopt` is never recorded (its own class override); any other piece can be
  switched per project in `.claude/audit-usage/audited.json`, which `export` never writes.

---

## 16. Quick command reference

### Session and context

| Command | Effect |
|---|---|
| `/clear [name]` | New conversation, empty context |
| `/compact [instructions]` | Summarizes to free up context |
| `/context [all]` | Context usage as a grid |
| `/recap` | Summary of the session so far |
| `/rewind` | Rewinds code and conversation to a checkpoint |
| `/resume` | Resumes a previous conversation |
| `/rename [name]` | Names the session |
| `/branch [name]` | Branches the conversation to try another direction |
| `/btw [question]` | Side question, outside the history |
| `/export [file]` | Exports the conversation |
| `/add-dir <path>` · `/cd <path>` | Adds a working directory · changes the current one |
| `/usage` · `/status` | Token statistics · session, model, and setup summary |

### Configuration

| Command | Effect |
|---|---|
| `/init` | Generates a CLAUDE.md from the project |
| `/memory` | Edits CLAUDE.md and toggles auto memory |
| `/skills` · `/reload-skills` · `/skill-doctor` | Lists skills · picks up a new skills directory · reviews a skill |
| `/agents` | Manages subagents |
| `/hooks` | Views configured hooks |
| `/plugin` · `/reload-plugins` | Manages plugins · reloads them without restarting |
| `/mcp` | Status, authentication, and toggling of MCP servers |
| `/permissions` | Allow/ask/deny rules |
| `/config` | Settings interface |
| `/model` · `/effort` · `/fast` · `/advisor` | Model, effort, fast mode, advisor model |
| `/output-style` · `/statusline` | Output style · status line setup |
| `/doctor` · `/debug` | Setup checkup · diagnoses the session from its debug log |

### Work

| Command | Effect |
|---|---|
| `/plan [description]` | Enters plan mode directly from the prompt |
| `/goal <condition>` | Claude works until the condition is met |
| `/loop [interval] [prompt]` | Repeats a prompt while the session is open |
| `/code-review` · `/security-review` · `/simplify` | Reviews the diff: bugs · vulnerabilities · simplifications |
| `/verify` · `/run` | Builds and runs the app to confirm the change · launches and drives it |
| `/batch <instruction>` | Fans out a large change across subagents |
| `/subtask <task>` | Forks a subagent that inherits the conversation |
| `/workflows` | Lists and runs dynamic workflows (e.g. `/deep-research`) |
| `/diff` | Reviews the working tree |
| `/tasks` | Session's background work |
| `/list-agents` | Subagents and sessions Claude can message |

### Keyboard

| Shortcut | Effect |
|---|---|
| `Shift+Tab` | Cycles permission modes (includes plan mode) |
| `Esc` | Interrupts Claude, preserving context |
| `Esc Esc` | Rewind menu |
| `Ctrl+B` | Sends the running subagent to the background |
| `Ctrl+G` | Opens the plan in a text editor |
| `Ctrl+O` | Shows or hides thinking |
| `Ctrl+R` | Searches the prompt history |
| `Option+T` / `Alt+T` | Toggles extended thinking |
| `@` | References a file or subagent |
| `!` | Runs a shell command directly in the session |

### CLI

```bash
claude                                  # interactive session
claude -p "prompt"                      # non-interactive
claude --continue                       # resumes the last session
claude --resume                         # choose from a list
claude --fork-session                   # resume into a copy
claude --worktree                       # isolated git worktree
claude --permission-mode plan           # starts in plan mode
claude --model opus --effort high       # model and effort for the session
claude --fast                           # fast mode
claude --agent code-reviewer            # entire session as a subagent
claude --add-dir ../shared              # access to an extra directory
claude --plugin-dir ./my-plugin         # loads a local plugin
claude --allowedTools "Edit,Bash(git commit *)"
claude --setting-sources user,project   # which settings layers load
claude --mcp-config ./mcp.json --strict-mcp-config
claude --bare                           # no hooks, skills, or plugins
claude --debug[=hooks] --debug-file ./claude.log
claude doctor                           # setup diagnosis from the shell
claude project purge [path]             # deletes a project's local data
claude ultrareview [target]             # multi-agent cloud review of a branch or PR
claude mcp add|add-json|list|get|remove|logout
claude plugin eval|disable
```

Print-mode flags: `--output-format text|json|stream-json`, `--input-format`,
`--include-partial-messages`, `--json-schema`, `--max-turns`, `--max-budget-usd`,
`--verbose`. Permission flags: `--dangerously-skip-permissions`, `--disallowedTools`,
`--tools`, `--permission-prompt-tool`, `--restricted`, `--safe-mode`.

---

## Further reading

- Overview — <https://code.claude.com/docs/en/overview>
- Best practices — <https://code.claude.com/docs/en/best-practices>
- Extending Claude Code (which piece to use) — <https://code.claude.com/docs/en/features-overview>
- Skills — <https://code.claude.com/docs/en/skills>
- Subagents — <https://code.claude.com/docs/en/sub-agents>
- Hooks — <https://code.claude.com/docs/en/hooks>
- MCP — <https://code.claude.com/docs/en/mcp>
- Plugins — <https://code.claude.com/docs/en/plugins>
- Memory and CLAUDE.md — <https://code.claude.com/docs/en/memory>
- Commands and bundled skills — <https://code.claude.com/docs/en/commands>
- Model configuration — <https://code.claude.com/docs/en/model-config>
- Output styles — <https://code.claude.com/docs/en/output-styles>
- Prompt caching — <https://code.claude.com/docs/en/prompt-caching>
- Full documentation index — <https://code.claude.com/docs/llms.txt>

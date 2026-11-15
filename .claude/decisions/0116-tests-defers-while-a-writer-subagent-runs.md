# 0116 · `tests` defers on `Stop` while a writer subagent still runs

- **Date:** 2026-10-03
- **Scenario:** Issue #76, triaged at 1ebd1f2 — the `tests` Stop hook runs and blocks on a
  background build agent's half-written work.
- **Decision:** Form 7c + 7a — phases `tests agent-start` and `tests agent-end` in `.claude/hooks/ArchHook.java`, registered at `SubagentStart`/`SubagentStop` in `.claude/skills/project-bootstrap/templates/settings.json.example`; the cap in `tests.writer_agent_max_minutes` of `.claude/schemas/extensions.json`
- **State:** approved by Lucas Fernandes, on 2026-10-03

## Reproduced on disk before classifying

From `issue-verifier`'s table at `1ebd1f2` (layer: static), confirmed rows only:

| # | Fact | Evidence |
|---|---|---|
| 1 | `tests` is a blocking `Stop` mode | `.claude/hooks/ArchHook.java:14` |
| 2 | It is the last `Stop` entry of the generated project's settings, timeout 600 | `.claude/skills/project-bootstrap/templates/settings.json.example:264-270` |
| 3 | `tests()` diffs `HEAD` + untracked `*.java` and runs `mvnw -q -o -pl <modules> -am test` | `ArchHook.java:165-178` |
| 4 | Its only early exits are `stop_hook_active`, no wrapper, no git, no changed `.java` — nothing checks for a running subagent | `ArchHook.java:160,163,167,174` |
| 5 | `Stop` fires on the main thread while a background subagent is still working | `0041` § Context, observed in the demo project |
| 6 | `/new-feature` *Implement now* delegates to `java-spring-boot-developer` | `.claude/skills/new-feature/SKILL.md:242,739` |
| 7 | The executor runs in the background for dozens of minutes | `new-feature/SKILL.md:836-838` |
| 8 | The executor's own `./mvnw verify` is the verdict the skill trusts | `new-feature/SKILL.md:740`; `.claude/agents/java-spring-boot-developer.md:704` |
| 9 | The main thread that receives the exit 2 owns `docs/use-cases/**` and `docs/lessons-learned/**` only | `skill_classes.classes.orchestrator.write_allow` |

Not inputs: row 10 (concurrent Maven corrupting `target/`) is unproven; row 11 (the
`ArchitectureTest` failure the reporter hit) is a separate symptom; the issue's proposed fix
was judged by the verifier and is not carried over.

`0041` saw the same window — `Stop` during a background subagent — and fixed it for `audit`
only. `0107` turned `tests` on for single-module projects ("On, same contract as
multi-module") without weighing background agents. Neither chose this behavior.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Issue #76: main-thread `Stop` runs Maven over the executor's half-written tree and exits 2 on whatever is red | create nothing |
| 7 — Mandatoriness | The gate already is a hook; the fix must hold every time an agent runs in the background | 1–6 |
| 8 — Destination | Generated project (`project-bootstrap/templates/settings.json.example`); `ArchHook.java` travels whole. This repo has no wrapper, `tests` returns before any of this | — |
| 14 — Lifecycle event | `SubagentStart` and `SubagentStop` mark the window; `Stop` reads it | — |
| 15 — Reaction | Never block while deferring — one stderr line, exit 0 (user's choice). Blocking would force the main thread to continue with nothing to do | Form 8 |
| 16 — Existing mode | `tests` owns the gate; no mode records which agents are running outside `audit`, which can be switched off per project | 7a alone |
| — Who pauses | Every agent whose class in `agent_classes` has `executor: true` — driver, executor, installer; the flag already owns "who writes" (user's choice) | a hardcoded name |
| — Stale marker | Age cap read from `extensions.json`; an older marker is ignored (user's choice) | session-only scope · transcript liveness |
| 17 — CI | `hooks-cross-platform` has no case for `tests` deferring; a new test is needed | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Two new phases of `tests` (`agent-start`, `agent-end`) keep a per-`agent_id` marker; `tests` defers while a live one exists; cap in `extensions.json` | 8 | **Approved** |
| 2 | Same marker, written by `context subagent` and cleared by `audit agent` — no new hook entries | 5 | Rejected — three modes share one state with no owner, and `audit` can be switched off per piece |
| 3 | Move the test gate to `SubagentStop` for writer agents | 3 | Rejected — a second Maven run over a verified tree, and still needs option 1 |
| 4 | Drop `tests` from the generated project's `Stop` | 2 | Rejected — loses the gate on main-thread `src/` edits, reverts 0107 |
| 5 | Create nothing — a pitfall entry | 1 | Rejected — invariant 6 |

### Option 1 — phases `tests agent-start` / `tests agent-end` (score 8)

**Motivator:** axes 7 and 16 — the gate is a hook and must stay one; `tests` is the mode
that owns it, so the state it reads belongs to it.

**Shape:**

- `SubagentStart` → `tests agent-start`: when the agent's class has `executor: true`, write
  `<tmp>/archhook-tests/<session>/<agent_id>` holding the agent type and the start time.
  Anything else — a verifier, an agent no class lists — writes nothing.
- `SubagentStop` → `tests agent-end`: delete the marker of **that** `agent_id`. Keyed per
  id because `SubagentStop` also fires for internal agents, interleaved with the real one
  (`0041` cause 3); an id with no marker deletes nothing.
- `Stop` → `tests`: after `stop_hook_active` and the wrapper check, before `git diff`: a
  marker younger than `tests.writer_agent_max_minutes` → print
  `⏸ Tests deferred: <agent_type> still running in background`, exit 0. Older markers
  are ignored (and removed). The executor's completion opens a new main turn, whose `Stop`
  runs the tests as today.

**Pros:** one owner of the state (`tests`); who pauses comes from `agent_classes`
(invariant 10), the cap from a new `tests` block in `extensions.json`; session-scoped
like `guardState`, so two sessions don't see each other's agents; a foreground agent
starts and stops inside one turn and never leaves a marker at `Stop`.

**Cons:** two more JVM launches per subagent (`SubagentStart`, `SubagentStop`), internal
agents included — no `if` applies to those events and a `matcher` would hardcode agent
names. An executor alive past the cap gets today's behavior back. Two registrations in
the template that a project which hand-edited its `settings.json` won't get until
`/arch-adopt`.

**Points cut in the rubric:** criterion 9 (cost per event) — two processes per subagent.

**CI:** new `.claude/.ci/TestsDeferTest.java`, step in `validate · hooks-cross-platform`,
stub wrapper as in `ModuleMapTest`: writer marker → no Maven call and the deferred line;
verifier agent → Maven called; start + end → Maven called; end of a different `agent_id`
→ still deferred; cap 0 in the copied `extensions.json` → Maven called. `schema` already
covers the two template registrations.

### Option 2 — piggyback on `context subagent` and `audit agent` (score 5)

Saves the two processes. Rejected-leaning: the marker would be written by the pattern
catalog's mode and cleared by the audit trail's — three modes sharing one state with no
owner, and `audit` is the mode a project switches off per piece (`0111`), which a cleared
marker must never depend on.

### Option 3 — gate on `SubagentStop` (score 3)

Runs Maven a second time over the tree the executor just verified with `./mvnw verify`, and
still leaves the main-thread `Stop` firing mid-run unless it is also suppressed — so it
needs option 1 anyway.

### Option 4 — no `tests` on `Stop` (score 2)

Outside a skill the main thread writes `src/` freely, and that is what the gate catches.
Reverts `0107`'s "On" for every case to fix one.

### Option 5 — create nothing (score 1)

A blocking hook that misfires on every background run is not fixed by a paragraph; invariant 6.

## References

| Claim | Source |
|---|---|
| `Stop` fires while a background subagent runs; `SubagentStop` fires for internal agents too | `.claude/decisions/0041-audit-background-subagent-tracking.md` |
| `SubagentStart`/`SubagentStop` read a `matcher` on agent type; `SubagentStart` cannot block | `.claude/skills/claude-code-architect-designer/references/hook-events.md` |
| `SubagentStart` and `SubagentStop` payloads carry `session_id`, `agent_id` and `agent_type` | <https://code.claude.com/docs/en/hooks>; `ArchHook.java` `context()` already reads `agent_type` at start, `audit()` reads `agent_id` at stop |
| `executor: true` is the single owner of which agents write | `agent_classes.$comment` in `.claude/schemas/extensions.json` |
| Lists read by a mode live in `extensions.json` | `@CLAUDE.md` invariant 10 |
| Session state in the system temp directory | `ArchHook.java` `guardState()` |
| Executor killed by sleep never reaches `SubagentStop` | `.claude/skills/new-feature/SKILL.md:838-842` |
| Stub wrapper for a `tests` CI case | `.claude/.ci/ModuleMapTest.java` |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` | `tests` takes a phase: `agent-start`, `agent-end`, default `stop`. New `testsAgentStart`, `testsAgentEnd`, `testsWriterRunning`, `testsState`, `safeName`; the `Stop` path defers after the wrapper check. Header line updated |
| `.claude/hooks/ArchHook.jar` | Rebuilt under JDK 21; `build --verify` passes |
| `.claude/schemas/extensions.json` | New `tests` block: `writer_agent_max_minutes: 120` |
| `.claude/skills/project-bootstrap/templates/settings.json.example` | `tests agent-start` at `SubagentStart`, `tests agent-end` at `SubagentStop`, after the existing entries |
| `.claude/.ci/TestsDeferTest.java` | New, seven cases |
| `.github/workflows/validate.yml` | Step in `hooks-cross-platform` |
| `docs/pt-br/07-ci-validate.md` · `docs/en/07-ci-validate.md` | Diagram node, step row, test count, local command |
| `docs/pt-br/11-pitfalls.md` · `docs/en/11-pitfalls.md` | Part 2, *Ownership inside a feature run*: the deferred gate |
| `docs/pt-br/01-tipos-de-arquivo.md` · `docs/en/01-file-types.md` · `README.md` | `tests` row of the hook-mode table |
| `.claude/skills/claude-code-architect-designer/references/hook-events.md` | `tests` row of *This repo's modes* |

Goes to the generated project: **yes** — `export` copies `ArchHook.java`, the jar and
`extensions.json` whole, and overwrites `.claude/settings.json` from the template, so
`/arch-adopt` delivers both registrations to an existing project. `.claude/settings.json`
of this repository is unchanged: it has no wrapper, and `tests` returns before reading any
marker.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › tests defers while a writer subagent of the session runs, and only then` | Writer marker → no Maven call and the `Tests deferred` line; read-only agent, unlisted agent, writer started and stopped, another session's writer, marker past the cap → Maven called; an internal agent's `SubagentStop` leaves the writer's marker | Green on the tree, 7/7. Red with `testsWriterRunning` bypassed: 2 cases fail by name (writer running, internal agent stopped). Red with the cap check removed: 1 case fails by name (marker past the cap) |

`schema` (step `frontmatter schema`) already covers the two template registrations; the
exported tree's `doctor` reports 28 registrations across 12 events, two more than before.

> **Note (0210):** The marker is now also read by `guard sweep`, through the neutral helper `executorsOpen` (was `testsWriterRunning`); `schema` requires the `tests agent-start`/`agent-end` pair wherever `guard sweep` and an executor class coexist. See `0210-guard-sweep-judges-executor-writes-by-agent-class.md`.

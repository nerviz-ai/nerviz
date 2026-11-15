# 0210 · An unseen write a running or finished executor's class admits is the executor's, not the phase's

- **Date:** 2026-11-15
- **Scenario:** Issue #209, triaged at d11db07: `guard sweep`, at the main thread's `Stop`, judges a classed executor's unresolved shell writes (targets holding `$VAR`) against the orchestrator's phase (`write_allow: []`) and tells the model to revert them.
- **Decision:** Form 7a + 7c — option 1: a `guard agent-end` sub-mode on `SubagentStop`, and `guard sweep` skipping what a still-running executor's class admits.
- **State:** approved by Lucas (user), on 2026-11-15

## Reproduced on disk

Confirmed rows of the `issue-verifier` table for #209 at `d11db07` (triage comment
https://github.com/nerviz-ai/nerviz/issues/209#issuecomment-6886941676, label
`triage:confirmed`). Unproven rows (the reporter's executor wrote through Bash with `$B`; Stop
fired ~90 s after launch) and the issue's proposed fixes are not inputs.

| # | Claim | Where |
|---|---|---|
| 1 | `guard bash` skips a `$B/…` target in silence, so nothing reaches `.admitted` — reproduced with `agent_type: java-spring-boot-developer` | `extensions.json:822` (`$` in `unresolvable_markers`) |
| 2 | `guardSweep` passes the Stop payload to `guardViolations(…, true)`; the agent branch needs `agent_type`, which `Stop` never carries, so the path is judged against the phase. Only `sweep_exempt`, admitted and `project_config` paths are skipped | `ArchHook.java:10944`, `:10419-10421`, `:10433-10449`, `:10942-10943` |
| 3 | `new-feature-implement` is class `orchestrator`, `write_allow: []` | `extensions.json:505` |
| 4 | `agent_classes.executor.write_allow` holds `src/**` | `extensions.json:615` |
| 5 | Three places promise a classed agent is judged by its own class, never the phase | `extensions.json:803`, `ArchHook.java:10246-10247`, `new-feature-implement/SKILL.md:35-37` |
| 6 | The sweep's message says "Revert them, or report them and stop" | `ArchHook.java:10948-10950` |
| 7 | The template's `SubagentStart` runs `context subagent` and `tests agent-start`; `SubagentStop` runs `audit agent` and `tests agent-end`; `guard sweep` is on `Stop` only | `project-bootstrap/templates/settings.json.example:125-158`, `:264` |
| 8 | A background executor is a supported shape | `new-feature-implement/SKILL.md:29`, `:237-252` |
| 11-12 | Reporter on `a5638d1` (v0.51.4); not fixed since — `0205` left `$` unresolvable and widened the population (a target after a non-literal `cd`) | `4fd0dd3` |

A foreground executor hits it too: the main `Stop` runs after `SubagentStop`, with the
executor's unseen writes still in the porcelain and no `agent_type` to judge them by.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 7 — Mandatoriness | An existing guarantee misfiring (`0065`, `0114`); its advice destroys approved work | 1-5, 9 |
| 8 — Destination | Both: `ArchHook.java`/jar and `extensions.json` travel via `export`; the settings template needs the registration | — |
| 14 — Event | `SubagentStop` carries `agent_type` (the one point an executor's unseen write can be attributed); `Stop` is where the misfire is reported | — |
| 15 — Reaction | At `SubagentStop`: admit an in-class write, exit 2 to the agent on an out-of-class one. At `Stop`: skip what a still-open executor's class admits | 8 (a `deny` cannot attribute) |
| 16 — Existing mode | `guard` — a new sub-mode, plus one new `SubagentStop` entry. No new mode | new top-level 7c |
| 17 — CI | `validate` › `hooks-cross-platform` › `SweepTest` runs the sweep through the jar | — |
| Gap accepted | An unseen write that an open or just-finished executor's class admits is treated as the executor's. A main-thread `src/` write in that window passes; under an orchestrator the main thread never legitimately writes `src/` | — |
| Q2 | A — an executor's unseen write outside its own class → exit 2 **to the agent**, naming `agent_classes.<cls>.write_allow`, never the phase. Accepted risk: a main-thread write in the same interval can be reported to the agent | — |
| Q3 | A — the main `Stop` reads the existing writer marker of `0116` (`tests agent-start`), no new `SubagentStart` spawn. The helper moves to a neutral name; `schema` requires the `tests agent-start`/`agent-end` pair wherever `guard sweep` and an `executor: true` class coexist | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `guard agent-end` at `SubagentStop` + `Stop` sweep defers while a writer marker is open | 8 | **Approved** |
| 2 | `SubagentStop` only — admit in-class writes into `.admitted` at the agent's end | 5 | Rejected — a still-running background executor stays misreported |
| 3 | `Stop` only — defer while the `0116` marker is open | 4 | Rejected — foreground and post-end writes are judged against the phase again |
| 4 | create nothing | 1 | Rejected — an observed misfire whose advice destroys work, as in `0114` |

### Option 1 — `guard agent-end` + deferral at `Stop` (score 8)

**Motivator:** axes 14-15 — the only event carrying `agent_type` is `SubagentStop`; the only
place the misfire is printed is `Stop`, which can fire while a background executor still runs.

**Shape:**
1. `ArchHook.java guard agent-end`, registered on `SubagentStop` in
   `project-bootstrap/templates/settings.json.example` (shell form, `powershell` field,
   timeout). Reads `agent_type`; a class with `executor: true` only, anything else exits 0.
   For every porcelain path new since the baseline, not admitted, not `sweep_exempt`, not a
   `project_config` path: the class's `write_allow` admits it → appended to `.admitted`;
   otherwise → exit 2, stderr naming `agent_classes.<cls>.write_allow`. `stop_hook_active`
   bounds it to one block. Frozen-folder check still applies (a frozen file is never admitted).
2. `guardSweep` at `Stop`: while the writer marker of `0116` is open (same TTL,
   `tests.writer_agent_max_minutes`), an unseen path the running agent's class admits is
   skipped — not admitted, so a later `guard agent-end` still judges it.
3. `extensions.json` `guard.$comment` (invariant 10); the marker helper renamed to a neutral
   name (it stops being `tests`'s alone); `schema` fails when an `executor: true` class exists
   and a settings file registering `guard sweep` lacks the `tests agent-start`/`agent-end` pair.

**Pros:** closes background and foreground; keeps `0114`'s strict rule for every write no
executor can own; the `$VAR` population of `0205` lands on the agent that wrote it, which can
still fix it; no new `SubagentStart` spawn.

**Cons:** one more JVM per `SubagentStop` (parallel with `audit agent` and `tests agent-end`,
so it reads `agent_type` from the payload, never the marker); accepted gap and Q2 risk above;
couples `guard` to a marker another mode writes — mitigated by the neutral helper and the
`schema` pair check. Strains nothing.

**Points cut in the rubric:** cost per event (−1), cross-mode coupling (−1).

**CI:** new cases in `.claude/.ci/SweepTest.java` (`validate` › `hooks-cross-platform`), each
proven red on `d11db07` and green after: (a) a background executor's `$VAR` `src/` write is
silent at the main `Stop` while the marker is open; (b) `guard agent-end` admits it, and the
main `Stop` after the marker closes is silent; (c) an executor's write outside its class →
exit 2 at `guard agent-end`, message naming `agent_classes`; (d) no marker, no agent-end →
today's report kept; (e) `stop_hook_active` → exit 0. `schema`'s pair check: a case in the
schema test with the `tests` pair removed from a template copy.

### Option 2 — `SubagentStop` only (score 5)

Admits at the agent's end. Fixes the foreground executor; a background executor still
running when the main `Stop` fires is reported, with the same "Revert them". Half of the
observed shape (row 8) stays broken.

### Option 3 — `Stop` only, defer on the marker (score 4)

Fixes the background case while the executor runs. `tests agent-end` deletes the marker at
`SubagentStop`, so a foreground executor's writes, and a background one's after it ends, are
judged against the phase again at the next `Stop`. Moves the misfire, does not remove it.

### Option 4 — create nothing (score 1)

`0114` rejected this for the same class of failure: an observed misfire whose advice
destroys legitimate work. Row 5 is a broken promise in three files.

## References

| Claim | Source |
|---|---|
| An unseen write is judged against the phase open at `Stop`; union of phases rejected and "still fails on executor src/ writes" | `@.claude/decisions/0114-guard-sweep-judges-only-unseen-writes.md` |
| Writer marker per `agent_id`, executor classes only, TTL against sleep; marker must not depend on a mode a project can switch off | `@.claude/decisions/0116-tests-defers-while-a-writer-subagent-runs.md` |
| A target after a non-literal `cd` handed to the sweep | `@.claude/decisions/0205-guard-bash-tracks-cd-and-newlines-executor-on-gradle.md` (`4fd0dd3`) |
| `SubagentStop` reads a matcher, exit 2 blocks the stop and feeds stderr to the subagent, bounded by `stop_hook_active`; same-event hooks run in parallel | `.claude/skills/claude-code-architect-designer/references/hook-events.md:54` |
| A classed agent is judged by its own class | `extensions.json:803`, `ArchHook.java:10246-10247` |
| Rule that must always hold is a hook; a mode reads its lists from `extensions.json` | `@CLAUDE.md` invariants 6, 10 |
| A hook mode travels with the jar; the registration is per settings file | `@CLAUDE.md` invariant 9 |

## Propagation

- `ArchHook.java`: `guard agent-end` (`guardAgentEnd`); `guardSweep` skips a path an open executor's class admits; `executorsOpen` replaces `testsWriterRunning` as the neutral marker reader (`tests` Stop gate uses it too); `checkAgentEndPair` in `schema`.
- `extensions.json`: `guard.agent_end_required_in` (the template only — this repo registers no executor hooks), `guard.$comment` and `tests.$comment`.
- `project-bootstrap/templates/settings.json.example`: `SubagentStop` entry `guard agent-end` and the `_comment`.
- `CLAUDE.md` mode table, `docs/{en,pt-br}/11-pitfalls.md`, notes in `0114` and `0116`.
- `settings.json` is read only at startup: a generated project picks the entry up through `/arch-adopt` and a restart.

Goes to the generated project: **yes** — jar and `extensions.json` via `export`, the
`SubagentStop` entry via `project-bootstrap/templates/settings.json.example`.

## CI coverage

| Job | Proves | Evidence |
|---|---|---|
| `validate · hooks-cross-platform › SweepTest` | A running executor's unseen `src/` write is silent at `Stop`; `guard agent-end` admits it and the later `Stop` is silent; an out-of-class write exits 2 naming `agent_classes`; `stop_hook_active` exits 0; with no executor the report is kept | Green on the tree. Red at `d11db07` (old jar, new test): the cases for the admitted write, the out-of-class write and the stop-hook bound fail by name |
| `validate › schema` (`checkAgentEndPair`) | A template registering `guard sweep` and an `executor: true` class without the `tests` pair or `guard agent-end` fails by name | `guard.agent_end_required_in` lists the template |

# 0201 · The audit trail is exempt from the sweeps that lint it, the close-time reading is no node's event, a falling plan delta is not valid, the toggle message names Edit, and `doctor`'s Schema line runs the whole `schema`

- **Date:** 2026-11-07
- **Scenario:** Issue #201, triaged at afac545: the audit/schema hooks lint their own report (shell_globs and uc_references sweep `.claude/audit-usage/`), `dur()` bills the close-time `plan_usage` tick to the last node, `planUsageReadings` accepts a negative delta, and `guard bash` denies a checklist toggle that its own message lists as writable
- **State:** approved by the maintainer (Option 1), on 2026-11-07
- **Decision:** Form 7c + data — `.claude/hooks/ArchHook.java` (`auditRender` ticks, `planUsageReadings`, the frozen-folder denial message, `doctor`'s Schema line), `.claude/schemas/extensions.json` (`shell_globs.exclude`, `doctor.uc_references.exempt_paths`, `doctor.bl_references.exempt_paths`), `.claude/agents/java-spring-boot-developer.md` (one line)

## Reproduced on disk

The `issue-verifier` table at `afac545`, confirmed rows only. No commit since touches a cited file.

| # | Claim | Evidence |
|---|---|---|
| A1 | The `schema` Stop hook flags the audit report's § Rework line | `extensions.json` `shell_globs.scope` is `.claude/**/*.md` and `exclude` has no `.claude/audit-usage/`; `ArchHook.java:7659-7668` quotes a failed command verbatim; `:4614` runs `sweepShellGlobs` at Stop |
| A2 | `doctor` UC references fails on a Target cut with `…` | `:8117` cuts at 80 chars; `:815` sweeps `git ls-files`, and the trail is versioned (0056, 0105); `uc_references.exempt_paths` has no `.claude/audit-usage` |
| A3 | `doctor`'s Schema passes while the Stop hook flags the same file | `:488` `doctor` calls only `sweep(...)`; the class, export, source, migration, mods, shell-glob and spec-checklist checks run only inside `schema()` (`:4612-4616`) |
| B1 | The idle tail is billed to the last node | `:7161-7162` appends the close reading before it is read; `:7216` counts every event but `run_start` and `compact` as a tick; `:7835` lets a tick at `endMs` extend the last node — against `:7439` ("its own last event") |
| B2 | A negative delta is stored and averaged | `:8314` guards only `bReset != aReset`; `:8317` takes `a - b` with no sign check; `:8370-8372` averages every non-null delta |
| C1 | `guard bash` denies a `sed -i` toggle | `:9945` `isChecklistToggle` accepts only `tool_name` `Edit` |
| C2 | The denial message lists toggles as writable and names no form | `:9436-9437`; the Stop sweep's `isSpecEditLegalOnDisk` (`:9712-9732`) accepts the same toggle on disk |

Unproven, not an input: why the 5 h usage fell with an equal `resets_at` (B2′ — the raw events are deleted at close). The issue's proposed fix is not an input either.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | All four observed in one generated-project run (issue #201) | create nothing |
| 7 — Mandatoriness | Already guaranteed (hooks exist); the defect is the guarantee misfiring or misreporting | 1–6 (prose cannot fix a hook's output) |
| 16 — Existing mode | `schema`, `doctor`, `audit`, `guard` already run each check | 7a (no new registration), a new mode |
| 8 — Destination | Both: every change sits in `ArchHook.java` / `extensions.json` / an exported agent, which `export` copies whole | — |
| C — Toggle via Bash | Fix the message and name `Edit` in the executor; `guard bash` stays strict (maintainer, 2026-11-07) | admitting Bash writes into a frozen spec |
| A3 — Doctor's Schema line | Run the whole `schema` sweep (maintainer, 2026-11-07, after the numbers below) | renaming the line to `Frontmatter`; deferring |

Measured for A3, at `afac545`: `schema` 0.78 s, `doctor` 0.39 s on this repo; an `export --blueprint hexagonal` into an empty scratch repository passes the whole `schema` (exit 0) and `doctor` shows `Schema ✅`. The generated project's CI (`project-bootstrap/templates/ci.yml.example:95`) runs `doctor gate` and no `schema` step, so today shell globs, classes, the export manifest and the spec checklist never gate a generated project's CI.

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Data exemptions (A) + tick filter (B1) + sign check (B2) + message and executor line (C) + `doctor` runs the whole `schema` sweep (A3) | 8 | **Approved** |
| 2 | Option 1 with C as "`guard bash` admits writes to a frozen spec, the Stop sweep judges on disk" | 4 | Rejected — turns the tool-time guarantee into a Stop-time one |
| 3 | Option 1 with A3 as "rename the line `Frontmatter`" | 5 | Rejected — leaves the generated project's CI gate weaker than `schema` |
| 4 | create nothing | 1 | Rejected — every symptom reproduces at `HEAD` |

### Option 1 (score 8)

**Motivator:** axis 16 — every check exists; each misfires on a fact its design record never weighed (0196 never weighed `.claude/audit-usage/`, 0194 never a decrease, 0066 never Bash).

**Pros:** no new mode, no new registration, no new file. A is data only (invariant 10). B1 matches the existing `compact` exclusion. B2 matches 0194's vocabulary (`not valid`). C keeps the tool-time guarantee and makes the message true. A3 makes the gated label `Schema` mean what `schema` means, and closes the generated-project CI gap at +0.4 s.

**Cons:** A3 reddens the next CI of an existing project that already drifted (an unquoted glob, a skill in no class) once `/arch-adopt` pulls this — the intended effect, arriving without a migration note. A3 needs `schema()` refactored to return its error list instead of printing and exiting.

**Points cut in the rubric:** cost of a blocking change (A3 widens what a gate fails on) → −1; a second `doctor` check for the jar would double-count → solved by keeping `hookJarProblem` out of the shared list (−0); the B2′ cause stays unknown → −1.

**CI:** existing tests gain cases — `ShellGlobSchemaTest` (A1), `AuditRenderTest` (B1, B2), `SweepTest` (C), `DoctorGateTest` (A2, A3). All in `validate` › `hooks-cross-platform`, already registered.

### Option 2 (score 4)

`guard bash` cannot see what a `sed` writes; admitting any Bash write to a frozen spec turns the tool-time guarantee into a Stop-time one and lets a non-toggle edit land until the turn ends. Rejected by the maintainer for C.

### Option 3 (score 5)

Honest and free, but leaves the measured gap: the only gate a generated project's CI runs keeps skipping half of `schema`.

### Option 4 (score 1)

Every symptom reproduces at `HEAD`; the trail keeps failing its own project's Stop hook and `doctor`.

## Design (Option 1) — what Phase 4 writes

### A — exemptions (data only)

`.claude/schemas/extensions.json`:
- `shell_globs.exclude` gains `".claude/audit-usage/"`.
- `doctor.uc_references.exempt_paths` and `doctor.bl_references.exempt_paths` gain `".claude/audit-usage"` (no trailing slash — the `$comment_exempt` reason).
- Append to the `shell_globs.$comment`: `` `.claude/audit-usage/` is the audit trail: the hooks write it, it quotes failed commands verbatim, nobody edits it (0105, 0201). `` Append the same reason, one sentence, to `doctor.$comment` after the `exempt_paths` sentence: `` `.claude/audit-usage` too: the trail cuts a Target at 80 characters, which leaves a slug prefix that resolves to nothing (0201). ``

### B1 — the close-time reading is no node's event

`ArchHook.java`, the ticks loop in `auditRender` (`:7215-7216`): `plan_usage` joins `run_start` and `compact` as an event that is not a tick. Comment above the line becomes:
`// A compaction is the runtime's, not the node's: it doesn't extend whoever ran before it. Nor does a plan_usage reading: it is the hook's bookkeeping, appended at flush and close (0201).`

The run's own total is unchanged (it is `endMs − start`); only the last node's duration and its `Longest steps` share stop absorbing the idle tail.

### B2 — a falling usage within one window is not valid

`ArchHook.java`, `planUsageReadings` (`:8314`): after the `bReset != aReset` branch, before `provisional`:
```java
else if (a < b) cell = "not valid — usage fell within the same window";
```
`delta` stays `null`, so `planUsageSummary` (`:8370`) never averages it and the history row records no delta for that window. Add `not valid — usage fell within the same window` to the list of cells in the report's footnote if the footnote enumerates them.

### C — the message names the form; the executor names the tool

`ArchHook.java` `:9437`: `"; the spec's checklist toggles; and its `status:` line, "` becomes `"; the spec's checklist toggles (`[ ]` ↔ `[x]`, through the Edit tool — a shell command is refused); and its `status:` line, "`.

Javadoc of `isChecklistToggle` gains one sentence: `Edit only: a shell write carries no old/new text to compare, so `guard bash` refuses it and the denial message names Edit (0201).`

`.claude/agents/java-spring-boot-developer.md` `:442`: `steps `[ ]` → `[x]`` becomes `steps `[ ]` → `[x]` with the Edit tool, one line per call`.

### A3 — `doctor`'s Schema line runs the whole sweep

`ArchHook.java`:
- Extract from `schema()` (`:4610-4617`) a method `static List<String> schemaSweep(Map<String, Object> sch) throws IOException` returning the errors of: `sweep`, `checkSkillClasses`, `checkAgentClasses`, `checkSubagentContext`, `checkExportManifest`, `checkSourceBlock`, `checkMigrations`, `checkMods`, `sweepShellGlobs`, `checkSpecChecklist` — in that order. `hookJarProblem` stays in `schema()` only: `doctor` has its own `Hook jar` line, and the gate would count it twice.
- `schema()` with `file == null` calls `schemaSweep(sch)` then appends the jar problem; output unchanged.
- `doctor()` (`:483-495`): `badSchema = schemaSweep(sch).size()`; the red text becomes `badSchema + " problem(s) — `java .claude/hooks/ArchHook.java schema` lists them"`.
- Javadoc on `schemaSweep`: `What `schema` checks at Stop and `doctor` reports on its Schema line — one list, so the gated label means the whole check (0201). Invoked by `schema` with no file and by `doctor`.`

## CI coverage (cases written in Phase 4, each red with its defect)

> Job `hooks-cross-platform` of workflow `validate`; the four tests were already steps there.

| Test › case | Defect that makes it fail |
|---|---|
| `ShellGlobSchemaTest` › "unquoted glob in the audit trail ignored" — append `grep -rn X --include=*.java src/main` to `.claude/audit-usage/probe.md`, expect exit 0 (`copyTree` already skips `audit-usage`, so the case writes the file after the copy) | `.claude/audit-usage/` removed from `shell_globs.exclude` |
| `DoctorGateTest` › healthy fixture plus `.claude/audit-usage/UC-001.md` holding `docs/use-cases/UC-001-some-case-cut…`, with a `docs/use-cases/` directory present: `UC references` stays green | the exempt path removed |
| `DoctorGateTest` › `broken("Schema", …)` with a skill under `.claude/skills/x/SKILL.md` in no `skill_classes` class and valid frontmatter (or an unquoted `--include=*.java` in a rule): gate exits 1 on `Schema` | `doctor` back to `sweep(...)` alone |
| `AuditRenderTest` › idle tail: open a run, record one skill event, sleep 1.5 s, close with a plan-usage snapshot present; the last node's duration in `Longest steps` is under 1 s | `plan_usage` removed from the tick exclusion |
| `AuditRenderTest` › `planUsage` new case: snapshot 23.5 % before, 22.0 % after, same `resets_at`: the cell is `not valid — usage fell within the same window` and the history row has no `five_hour` delta | the `a < b` branch removed |
| `SweepTest` › frozen `approved` spec, `guard bash` with `sed -i '' 's/- \[ \] 1\./- [x] 1./' <spec>`: exit 2, stderr contains `through the Edit tool` | the message reverted |

Run record, workflow `validate` › job `hooks-cross-platform` (red = the defect injected by hand, jar rebuilt, the case seen failing by name, then restored; green = the shipped tree):

| Job · step | Case | Proves | Green | Red (defect) |
|---|---|---|---|---|
| `hooks-cross-platform` · "schema blocks a shell glob zsh aborts on" | `ShellGlobSchemaTest` › unquoted glob in the audit trail ignored | `shell_globs.exclude` exempts `.claude/audit-usage/` | `✅ unquoted glob in the audit trail ignored (exit 0)` | `.claude/audit-usage/` out of `exclude`: `❌ unquoted glob in the audit trail ignored — expected exit 0, got 2` |
| `hooks-cross-platform` · "doctor gate fails on every gated line and only on those" | `DoctorGateTest` › UC citation cut in the audit trail | `UC references` stays green over a cut `docs/use-cases/UC-001-…` in `.claude/audit-usage/` (fixture: git repo, file tracked, `docs/use-cases/` present) | `✅ UC citation cut in the audit trail — UC references stays green` | `.claude/audit-usage` out of `uc_references.exempt_paths`: `❌ a cut UC citation inside .claude/audit-usage reddens UC references` |
| same | `DoctorGateTest` › `broken("Schema")` with an unquoted `--include=*.java` in a root `CLAUDE.md` | `doctor` runs the whole `schemaSweep`: the shell-glob check, absent from the frontmatter sweep, reddens the gated `Schema` line (a skill in no class would not isolate it — `sweep` flags that too) | `✅ Schema broken — gate fails` | `doctor` back to `sweep(...)` alone: `❌ Schema broken — gate fails — expected exit 1, got 0` and `❌ Schema broken — the gate line does not name Schema` |
| `hooks-cross-platform` · "audit renders where the run spent, redacts tool errors, skips what class or project turns off" | `AuditRenderTest` › 8b idle tail | `plan_usage` is no tick: skill node, `flush`, 1.5 s idle, `close` leaves the node at `0s` in `Longest steps` | `✅ plan usage: the idle tail before close is not billed to the last node` | `plan_usage` back among the ticks: `❌ plan usage: the idle tail before close is not billed to the last node — missing: …0s…` |
| same | `AuditRenderTest` › 8 falling usage | 23.5 % then 22.0 % with one `resets_at`: cell `not valid — usage fell within the same window`, no `-1.5 pp`, history `"five_hour":null` | three `✅ plan usage:` lines | `a < b` branch removed: `❌ plan usage: a falling usage is not valid`, `❌ … no negative delta is printed — found: -1.5 pp`, `❌ … history holds no delta for the fallen window` |
| `hooks-cross-platform` · "guard sweep reports this turn's writes, never pre-existing dirt" | `SweepTest` › checklist toggle by `sed -i` | `guard bash` still refuses the shell toggle in an approved spec and the denial says `through the Edit tool` | `✅ checklist toggle by sed -i — refused, the message names the Edit tool` | message reverted: `❌ checklist toggle by sed -i — refused, the message names the Edit tool — output does not name through the Edit tool` |

Verification commands: `java .claude/hooks/ArchHook.java build`, `java .claude/hooks/ArchHook.java schema`, `java .claude/hooks/ArchHook.java doctor`, and each of `java .claude/.ci/{ShellGlobSchemaTest,DoctorGateTest,AuditRenderTest,SweepTest}.java` — green, then red once per row above.

## Propagation (planned)

| File | Change |
|---|---|
| `.claude/schemas/extensions.json` | A exemptions and comments |
| `.claude/hooks/ArchHook.java` + rebuilt `ArchHook.jar` | B1, B2, C message, A3 extraction |
| `.claude/agents/java-spring-boot-developer.md` | C, one line |
| `.claude/.ci/ShellGlobSchemaTest.java`, `DoctorGateTest.java`, `AuditRenderTest.java`, `SweepTest.java` | the cases above |
| `CLAUDE.md` § Commands, `doctor gate` row | "schema" in its parenthesis already says it; no change unless the wording names frontmatter only |

Goes to the generated project: **yes** — `export` copies `ArchHook.java`, the jar, `extensions.json` and the executor whole.

## References

| Claim | Source |
|---|---|
| The audit trail is exempt from `guard sweep` for the same reason | `@.claude/decisions/0105-guard-sweep-exempts-audit-trail.md` |
| `shell_globs` excluded only files quoting the defect; designed where the trail is off | `@.claude/decisions/0196-new-feature-body-cut-quoted-globs-and-audit-effort.md` |
| The plan-usage cells and their meaning | `@.claude/decisions/0194-audit-plan-usage-delta-from-a-personal-status-line-snapshot.md` |
| Toggles legal on every frozen status, weighed for Edit and the sweep only | `@.claude/decisions/0066-spec-state-machine.md` |
| A mode reads its lists from `extensions.json` | `@CLAUDE.md` invariant 10 |
| The generated project's CI gate is `doctor gate` | `project-bootstrap/templates/ci.yml.example:95` |

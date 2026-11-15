# 0114 · `guard sweep` judges only the writes no tool-time guard admitted

- **Date:** 2026-10-02
- **Scenario:** Issue #74, triaged at `14c0931` — `guard sweep` judges every write of the turn
  against the phase still open at `Stop`, so `/new-feature` ending on `git-publish` (class
  `ops`) flags its own approved spec.
- **Decision:** Form 7c, a change inside the existing `guard` mode — `.claude/hooks/ArchHook.java` (`guardPrompt`, `guardPath`, `guardSweep`, new `guardAdmittedFile`). No registration, no `extensions.json` change.
- **State:** approved by Lucas Fernandes, on 2026-10-02.

## Reproduced on disk

The `issue-verifier` table from `/triage-issue 74` at `14c0931`, confirmed rows only. The
issue's proposed fixes are not inputs; they appear below as options because the interview
weighed them, not because the issue proposed them.

| # | Claim | Evidence at `14c0931` |
|---|---|---|
| 1 | A `Skill` call of a different class replaces the phase | `ArchHook.java:5381-5386` — `guardJoin` only on the same class, `guardOpen` otherwise |
| 2 | The baseline is taken once, at `UserPromptSubmit` | single call site of `guardBaseline`, `ArchHook.java:5332` |
| 3 | The sweep judges every new porcelain path against the phase open at `Stop` | `ArchHook.java:5711-5717`, `:5498-5503`; nothing records which phase was open at write time |
| 4 | `git-publish` is class `ops`, `write_allow: []` | `extensions.json:225-231` |
| 5 | `/new-feature` § End of flow chains `git-publish` | `new-feature/SKILL.md:742-745` |
| 6 | `git-publish` gate 1 "No, skip" touches nothing | `git-publish/SKILL.md:130-131` |
| 7 | The baseline exists to separate this turn's writes from pre-existing dirt | `guardBaseline` Javadoc, `ArchHook.java:5341-5342` |
| 8 | The reported output reproduces | `guard prompt /new-feature` → `guard call use-case-design` → `guard call git-publish` → `guard write docs/use-cases/UC-006-x/UC-006-spec.md`: exit 2, "`git-publish` is class `ops` — … outside its territory", "write_allow: (nothing — this class writes no file)" |
| 10 | Executor `src/` writes hit the same check — the `Stop` payload has no `agent_type` | `guardSweep` passes the `Stop` payload as `in`; `guardViolations:5486-5487` falls through to the phase. Broader than reported: on executor failure the open phase is `orchestrator`/`design`, neither admits `src/**` |
| — | A second defect sits behind it, on the frozen axis | `guardViolations` returns at the first territory violation (`:5512`), so the frozen check never ran in the report. Once territory passes: "Approve" writes `status: approved` the same turn, `isStatusClose`/`isChecklistToggle` require `tool_name == "Edit"` (absent at `Stop`), and `isSpecEditLegalOnDisk` fails on an untracked spec — the just-approved folder is reported immutable |

Row 9 ("hidden on a gate-1 Yes") was refuted: "Not now" stages only the new folder and
`BACKLOG.md`, so other cases' `CHANGELOG.md` stay uncommitted and are still flagged.

**Root cause, one sentence:** the sweep reads end-of-turn state — the phase open at `Stop`, the
spec status on disk at `Stop` — to judge writes whose legality was decided at write time, by
guards that already decided it.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Triage rows 1-8, 10 and the frozen-axis finding; seen once, in the reporter's generated project | 9 (create nothing) as an option that holds |
| 7 — mandatoriness | An existing guarantee (0065) misfiring; it cannot become optional | 1 · 2 · 3 · 4 · 5 |
| 16 — existing mode | `guard` — `prompt`, `write`, `bash`, `sweep` already hold every check; no registration changes | A new mode, a new registration |
| scope | Fix the frozen axis in the same change — fixing territory alone unmasks it on the same run | A territory-only fix |
| unseen writes | A write no tool-time guard saw is still judged against the phase open at `Stop` — strict, today's rule | Option 3 (union of the turn's phases) |
| 10 — cost | Minutes: the model usually reports instead of reverting | — (but a guard that cries on the legitimate path is the guard people route around — 0065's own words) |
| 8 — destination | Both: `ArchHook.java`, the jar and `extensions.json` travel whole through `export` | — |
| 17 — CI | `validate` · `hooks-cross-platform` › `SweepTest` already runs the sweep through the jar | A new test file |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Ledger of admitted paths — `guard write`/`guard bash` record what they admitted, the sweep skips it | 9 | **Approved** |
| 2 | Re-baseline on a cross-class `guard call`, sweeping the outgoing phase first | 5 | Rejected — misses executor `src/` writes and the frozen axis, and adds a blocking point at a `Skill` call |
| 3 | Judge unseen writes against the union of every phase opened this turn | 4 | Rejected — the interview chose the strict rule for unseen writes; still fails on executor `src/` writes |
| 4 | Exempt class `ops` at the sweep | 3 | Rejected — symptom only; strains invariants 2 and 10 |
| 5 | Create nothing | 2 | Rejected — observed failure whose advice destroys legitimate work |

### Option 1 — admitted-path ledger in `guard` (score 9)

**Motivator:** axis 16 plus the sweep's own stated purpose. Its block message already says "The
tool-time guards did not see these writes" — that is the only population it was built for
(0065 § Why this exists). Every write `guard write` or `guard bash` admitted was judged against
the phase and the frozen state **of its own moment**; re-judging it at `Stop` against later state
is where both defects come from.

**Shape.**

- `guard prompt` deletes `<session>.admitted` along with the phase state and the baseline.
- `guardPath` (the one place `guard write` and `guard bash` both pass through), when
  `guardViolations` returns empty, appends the repo-relative path to `<session>.admitted`.
  Same state directory, same session key the phase file already uses.
- `guardSweep` skips a porcelain path that is in `<session>.admitted`, right where it already
  skips `guard.sweep_exempt`. Everything else is unchanged: an unseen write is judged by the
  same `guardViolations`, against the phase open at `Stop` (the interview's strict answer).

**What it fixes, row by row.** The `/new-feature` → `git-publish` chain (rows 3-8): the spec,
partials and `BACKLOG.md` were admitted under `orchestrator`/`design`. Other cases'
`CHANGELOG.md` (row 9): admitted, and frozen-exempt anyway. Executor `src/` writes on success
and failure (row 10): admitted by `agent_classes` through the write's `agent_type`. The frozen
axis: the partials and the `status: approved` edit were admitted while the spec was still
`draft`. Precedent for the shape: the phase file and the baseline are already per-session state
the same mode writes and reads.

**Pros.** Fixes the owner, not one chain — any cross-class narrowing, any agent, any folder
frozen mid-turn. No new event, no new registration, no new list (invariant 10 untouched). No
new blocking point. Tool-time narrowing (0092) is untouched: the design skill is still held to
docs while it runs.

**Cons.**

- Known gap, accepted: a path admitted earlier in the turn and later rewritten by a spelling no
  tool-time guard reads, under a narrower phase, is skipped. Closing it needs the content of
  the file at admission time, which `PreToolUse` does not have.
- Residual on the frozen axis, deliberate under the strict answer: an *unseen* write into a
  folder approved the same turn is still reported immutable, since the sweep cannot tell
  whether it came before or after the approval.
- `PreToolUse` admission is not proof of the write: a call refused later by a permission prompt
  is recorded and never happens. Harmless — the sweep only reads paths that changed.

**Points cut in the rubric:** criterion 4 (enforcement) — the gap above is a narrower net than
"every changed path".

**CI:** `validate` · `hooks-cross-platform` › `SweepTest` — three new cases in
`.claude/.ci/SweepTest.java`: the `/new-feature` → design skill → `git-publish` chain with the
spec and partials admitted through `guard write` and the folder flipped to `approved` on disk
(silent); an executor `src/` write admitted through `agent_type` under a `design` phase
(silent); the same chain with one file created by plain Java, unseen (still reported, by
name).

### Option 2 — re-baseline on a cross-class `guard call` (score 5)

The issue's fix A. Sweep the outgoing phase at the call, then retake the baseline. Misses
executor `src/` writes — they happen under the design phase and are swept against it at the
`git-publish` call. Leaves the frozen axis as is. Hides a narrower phase's rewrite of a path
already dirty, because its porcelain line does not change. Adds an exit 2 at `PreToolUse` of a
`Skill` call — a new blocking point whose behaviour would itself need design. Cut: criteria 4,
5, 9. **CI:** cases in `SweepTest`.

### Option 3 — union of every phase opened this turn (score 4)

A per-turn phase history; the sweep admits a path if any phase of the turn admits it. Rejected
by the interview's strict answer for unseen writes. Fails row 10 anyway — no phase admits
`src/**`, the executor is an agent — and leaves the frozen axis as is. Cut: criteria 1, 4, 5.
**CI:** cases in `SweepTest`.

### Option 4 — exempt class `ops` at the sweep (score 3)

The issue's fix B. Treats the one chain reported; misses `orchestrator` → `design`, "Implement
now" and the frozen axis. Keying it on `audited: false` reuses a field that answers the audit
question (0086) for a second one — strains invariants 2 and 10 — and a new flag is a second
mechanism for a case Option 1 covers without one. Cut: criteria 1, 2, 4, 5. **CI:** a case in
`SweepTest`.

### Option 5 — create nothing (score 2)

The failure is observed and its advice — "Revert them" — destroys legitimate, uncommitted work;
0105 fixed a sweep false positive of the same shape for the same reason. Cut: criteria 1, 4.
**CI:** nothing to add.

## References

| Claim | Source |
|---|---|
| The sweep exists for writes the tool-time guards did not see | `.claude/decisions/0065-guard-sweep-on-stop.md` § Why this exists; the block message in `guardSweep` |
| Cross-class replacement is deliberate tool-time narrowing | `.claude/decisions/0092-guard-same-class-chain-sums-territories.md`; `guardCall` comment |
| A subagent's write is judged by its own `agent_classes` territory, via `agent_type` | `guardViolations` step 1; `.claude/decisions/0059-agent-classes-territory-schema.md` |
| Subagent hook calls share the main thread's `session_id` state | `guard` comment: an agent no class lists "falls back to the caller's phase" — read from the same session state file |
| A sweep false positive whose advice destroys work is fixed in the sweep | `.claude/decisions/0105-guard-sweep-exempts-audit-trail.md` |
| A `guard` mode reads its lists from `extensions.json` | `@CLAUDE.md` invariant 10 |
| Hooks run the jar; the source changes nothing until `build` | `@CLAUDE.md` § Known pitfalls; `.claude/decisions/0075-precompiled-hook-jar.md` |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` | `guardAdmittedFile`; `guardPrompt` deletes it; `guardPath` appends an admitted path; `guardSweep` skips it. Javadoc on all three |
| `.claude/hooks/ArchHook.jar` | Rebuilt under JDK 21; `build --verify` green |
| `.claude/.ci/SweepTest.java` | Header paragraph and the issue #74 cases (below) |
| `.github/workflows/validate.yml` | Comment on the `guard sweep` step |
| `CLAUDE.md` | § Commands, the `guard sweep` row |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | The `guard sweep` paragraph: admitted paths skipped, and the gap |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | The `SweepTest` row |
| `docs/pt-br/08-audit-usage.md`, `docs/en/08-audit-usage.md` | The `guard sweep` row |

`README.md` lines 536 and 893 describe the sweep as "re-checked" what changed; still true for
what it judges, and the file sits outside the `meta` class territory — left as is.

Goes to the generated project: **yes** — `ArchHook.java` and the rebuilt `ArchHook.jar` travel
whole through `export`; an existing project pulls them with `/arch-adopt`. No registration and
no `extensions.json` change, so no template edit and no `migrations` entry.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › guard sweep reports this turn's writes, never pre-existing dirt` | A `/new-feature` → `use-case-design` → `git-publish` turn whose spec, partial and another case's `CHANGELOG.md` were admitted through `guard write`, whose executor `src/` write was admitted through `agent_type`, and whose folder was flipped to `approved` on disk is silent at `Stop`; a file written in that same turn with no `guard write` is still named, and the admitted spec is not | Green on the tree (17 cases). Red with `admitted.contains(rel)` removed from `guardSweep` and the jar rebuilt: 2 cases fail by name — "admitted writes under an earlier phase, folder approved since — silent" (exit 2) and "a write no tool-time guard saw — still reported, admitted ones are not" (names `UC-006-spec.md`) |

`SkillTerritoryTest`, `AgentTerritoryTest` and `BashGuardTest` stay green: the ledger write is
an append after an admission, and none of them reads it.

> **Note (0210):** An unseen write that an open or just-finished executor's class admits is no longer judged against the phase: `guard agent-end` admits it at `SubagentStop`, and the sweep skips it while the executor runs. See `0210-guard-sweep-judges-executor-writes-by-agent-class.md`.

# 0195 · The audit's Files touched sees Bash writes, and the permission diff compares whole rules

- **Date:** 2026-10-30
- **Scenario:** Issue #187, triaged at d43f108: `audit file` records no file written through
  `Bash`, and `perm_before` is split on a space, so every pre-existing local permission is
  reported as added during the run.
- **Decision:** Form 7c — option 1: `audit` render and flush in `.claude/hooks/ArchHook.java`; no new mode, no new registration.
- **State:** approved by Lucas Fernandes, on 2026-10-31.

## Reproduced on disk

The `issue-verifier` table of `/triage-issue 187`, at `d43f108` (no commit since touches a
cited file). Confirmed rows only; the issue's proposed fix is not an input.

| # | Claim | Evidence |
|---|---|---|
| A1 | The generated project registers `audit file` only on `PostToolUse` › `Write\|Edit\|MultiEdit\|NotebookEdit` | `project-bootstrap/templates/settings.json.example:210,215`; the `Bash` matcher (77) runs `guard bash` only, at `PreToolUse` |
| A2 | `audit file` cannot record a Bash write | `ArchHook.java:6548` — returns when `tool_input.file_path` is null |
| A3 | § Files touched and § Rules loaded come only from `file` events, so a run that writes only through `Bash` reads "None." / "No touched file matches…" | `ArchHook.java:6796-6798`, `:7153`, `:7167-7168` |
| A4 | `guard bash` already parses write targets | `ArchHook.java:8968` — `bashWriteTargets(guard.bash_write_shapes, cmd)` |
| A5 | 0085 records the Bash-written-files gap as still open | `0085-audit-where-the-run-spent-and-english.md:45-47` |
| A6 | 0063 rejected a `PostToolUse` `Bash` matcher for `audit file` on cost | `0063-bash-write-enforcement.md:49` — the claim "not decided against" is partly false |
| B1 | `perm_before` is joined on `" "` and split on `" "` | `ArchHook.java:6347`, `:6914` |
| B2 | Every entry is `k + ": " + v`, so it holds a space and never removes itself | `ArchHook.java:6678` — `"allow: Read"` splits to `["allow:", "Read"]` |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Observed in a real project: a design run wrote 7 partials, the spec, BACKLOG and CHANGELOG through heredocs; Files touched and Rules loaded empty; 28 of 32 reports list pre-existing permissions as added | create nothing |
| 7 — mandatoriness | The audit observes, it enforces nothing; the defect is a wrong report, not a missed block | Form 8; any new blocking path |
| 16 — existing mode | `audit` already scans every transcript of the run (`scan`, 0085), and each `tool_use` carries `input.command` and `id`, each `tool_result` `is_error`; `guard bash` already owns the parser; `guard prompt` already writes a per-turn `git status` baseline | a new mode; a new registration |
| — source (asked) | Both: the parsed targets of each Bash call, and what the working tree shows new against the turn's baseline at `Stop` | parse only · filesystem only |
| — failed or denied command (asked, with trade-offs) | Drop its parsed targets; a real partial write still surfaces through the disk diff | keep, marked · keep, unmarked |
| — origin (asked) | Show how each file was written | path list only |
| 8 — destination | Generated project only in effect (`audit` is off here, no `.claude/audit-usage/`); `ArchHook.java` and the jar travel whole through `export` | — |
| 17 — CI | `AuditRenderTest` (validate › hooks-cross-platform) already drives `audit` over fixture transcripts | a new test class |

The fact 0063 did not weigh, and the reason this reopens its line 49 without contradicting
it: the parse need not run per command at all. The transcript already holds every command the
run issued, and `audit flush` already reads it once per `Stop`. 0063's objection was a JVM per
`ls`; this option adds none.

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Render-time: parse each `Bash` `tool_use` of the run's transcripts with `bashWriteTargets`, drop those whose `tool_result` is an error, add what `git status` shows new against the turn's guard baseline at flush; origin per file; `perm_before` joined on `\n` | 8 | **Approved** |
| 2 | Hook-time: `guard bash` appends a `file` event per admitted target at `PreToolUse`, plus the same disk diff | 6 | Rejected — two writers on the trail; failed or denied calls unresolvable at PreToolUse |
| 3 | The issue's proposal: `PostToolUse` › `Bash` › `audit file` reusing the parser | 3 | Rejected — a JVM per shell command (0063:49), strictly worse than 1 |
| 4 | Fix B only; state in the report that Bash writes are not tracked | 3 | Rejected — leaves the observed symptom |

### Option 1 — render-time parse + disk diff (score 8)

**Motivator:** axis 16 — every input already exists: the transcripts `scan` reads, the parser
`guard bash` uses, the baseline `guard prompt` writes.

**Shape.**

- `scan` keeps, per `Bash` `tool_use`, its `id`, timestamp and `input.command`; after the pass,
  a call whose `tool_use_id` came back `is_error` is dropped (same resolution the error list
  already does). For each remaining call inside the run's window, `bashWriteTargets(
  guard.bash_write_shapes, command)` yields repo-relative paths, origin `Bash`. Subagent
  transcripts the run already scans contribute the same way.
- `audit flush`, before rendering, runs `git status --porcelain --untracked-files=all` and
  compares it with `guardBaselineFile(guardState(session))`; every entry absent from the
  baseline, through `porcelainPath`, is appended as `ev("file", "op", "disk", "path", rel)`.
  Duplicates across `Stop`s are fine: render deduplicates by path. No baseline (no git, guard
  not registered) → no disk events, never an error.
- Render: `touched` is the union; each path keeps its most specific origin
  (`Write`/`Edit`/`MultiEdit`/`NotebookEdit` › `Bash` › `disk`). § Files touched prints the
  origin per file; the per-op count line counts origins. § Rules loaded reads the same union.
- `openRun` writes `perm_before` as `String.join("\n", localPermissions())`; render splits on
  `"\n"`. A run opened by an older jar holds the space-joined value: it splits to one string
  that matches nothing — the old, wrong output, once, for that run only. Accepted: `.state/` is
  per session.

**Pros:** no new process per command (0063's objection holds untouched); the parser stays the
single owner of what a shell write looks like (invariant 2); works on subagent transcripts; the
disk diff catches `python -c`, a generated script and a `$VAR` target; a failed or denied
command's intent drops out while a real partial write does not.

**Cons:** `audit` reads a guard file (the baseline) — a read-only coupling inside one jar.
The disk diff misses a file already dirty at the turn's start and written again by an unparsed
spelling, and attributes to the run an edit made in another editor during the turn — both
marked `disk`, so the reader can tell. The transcript layout is observed, not documented (the
caveat 0085 already took). One `git status` per `Stop`, which `guard sweep` already pays.

**Points cut in the rubric:** coupling between two modes of one jar (−1); best-effort on an
undocumented layout (−1).

**CI:** `AuditRenderTest` gains cases (validate › hooks-cross-platform):

| Case | Defect that makes it fail |
|---|---|
| A transcript with a `Bash` heredoc `cat > docs/x.md <<'EOF'` → § Files touched lists `docs/x.md` with origin `Bash`, § Rules loaded lists a rule whose `paths` match it | render ignores `Bash` `tool_use` |
| The same command whose `tool_result` is `is_error:true` → `docs/x.md` absent | failed calls not dropped |
| A file present in the working tree and absent from the turn baseline, with no tool call → listed with origin `disk` | flush does not diff the baseline |
| A path written by `Write` and by `Bash` → listed once, origin `Write` | no dedupe / wrong origin precedence |
| `settings.local.json` holding `allow: ["Bash(git add *)"]` at run open and unchanged at render → § Permissions added says none; one rule added mid-run → only that rule | `perm_before` split on space |

### Option 2 — `guard bash` writes the audit trail (score 6)

Same disk diff; the Bash half is a `file` event appended by `guard bash` at `PreToolUse`. Cut:
an enforcement mode writes the observation trail (two owners of the trail); recorded before the
write, so dropping a failed command needs `tool_use_id` on `audit fail` and `audit perm`, and a
`PermissionDenied` payload's `tool_use_id` is not verified. It sees nothing option 1 does not,
and it adds a second writer to the trail.

### Option 3 — `PostToolUse` › `Bash` › `audit file` (score 3)

0063:49 — a JVM per shell command, `ls` and `git status` included, for a report. Still limited
to parsed spellings. The fact that tips the cost — the transcript holds every command already —
makes this option strictly worse than 1.

### Option 4 — fix B only, document A (score 3)

Leaves the observed symptom: a run steered to `Bash` reports no files and no rules, in every
report.

## References

| Claim | Source |
|---|---|
| `audit file` matcher is tool-shaped, no Bash | `project-bootstrap/templates/settings.json.example:210-215` |
| A `PostToolUse` Bash matcher costs a JVM per command | `.claude/decisions/0063-bash-write-enforcement.md:49` |
| The gap was left open on purpose | `.claude/decisions/0085-audit-where-the-run-spent-and-english.md:45-47` |
| The run's transcripts are already scanned; `tool_use` carries `id` and `input`, `tool_result` carries `is_error` | `ArchHook.java` `scan` (≈7409-7460), 0085 |
| The parser is data-driven and single-owned | `ArchHook.java` `bashWriteTargets`, `guard.bash_write_shapes` in `.claude/schemas/extensions.json` (invariant 10) |
| A per-turn porcelain baseline exists | `ArchHook.java` `guardBaseline`, `guardBaselineFile`, `porcelainPath` |
| Hooks of one event run in parallel, so `Stop` order between `audit flush` and `guard sweep` is not relied on | `@claude-help.md:896` — "Matching hooks run in parallel" |

## Propagation

Files touched, all in the meta-repo:

- `.claude/hooks/ArchHook.java` — `openRun` writes `perm_before` joined on `\n` and the render splits on `\n`;
  `scan` keeps each `Bash` call's command and drops those whose `tool_result` is `is_error`
  (`Scan` gains a `bash` list); `auditRender` feeds `bashWriteTargets` (shapes read from
  `guard.bash_write_shapes`, no list in the source) into one origin map per path, over the main
  transcript, each subagent's and a root agent's; `audit flush` calls the new `auditDisk`, which
  diffs `git status` against the turn's `guard` baseline and skips `guard.sweep_exempt`;
  § Files touched prints the origin per file and counts origins.
- `.claude/hooks/ArchHook.jar` — rebuilt (`java .claude/hooks/ArchHook.java build`).
- `.claude/.ci/AuditRenderTest.java` — the `bashWrites` case group.
- this record.

Goes to the generated project: **yes** — `export` copies `ArchHook.java`, the rebuilt jar and
`extensions.json` whole; no template changes.

## CI coverage

`validate` › `hooks-cross-platform` › the `AuditRenderTest` step (`java .claude/.ci/AuditRenderTest.java`),
group `bashWrites`, run in a throwaway git project with a real `settings.local.json` and a rule
whose `paths` match `docs/**`. It proves the five cases of the table under Option 1 › CI: a Bash
heredoc listed with origin `Bash` and its rule loaded; the same call answered `is_error` absent;
a file absent from the turn baseline listed with origin `disk` (and the audit trail not listed);
a path written by `Write` and `Bash` listed once with origin `Write`; permissions unchanged
reported as none added, one rule added mid-run reported alone. Green on the tree. Red, by name,
with each defect injected and reverted: `perm_before` joined or split on a space (two
permission checks), `is_error` calls kept, `flush` without the disk diff (two checks), origin
precedence removed, and the render ignoring `Bash` calls (three checks).

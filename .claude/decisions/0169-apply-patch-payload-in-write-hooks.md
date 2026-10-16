# 0169 · The write hooks read the Copilot CLI's `apply_patch`, translated into the payloads they already judge

- **Date:** 2026-10-17
- **Scenario:** "O Copilot edita só via apply_patch, e o hook recebe tool_name "Edit" com
  tool_input em STRING de patch, sem file_path. Com isso guard write, check, format e audit file
  saem com 0: spec approved editada, import proibido e arquivo sem formatar passam. Projete a
  extração de caminhos e do conteúdo das linhas "+" como adição ao parse atual: o payload do
  Claude Code com file_path continua igual. Payload ilegível sai com 0" (lessons-learned-022 § 3).
- **Decision:** Form 7c — an extension of five existing modes of `.claude/hooks/ArchHook.java` (`guard write`, `check`, `format`, `schema`, `audit file`) through one translator, `patchPayloads`. No new mode, no registration changed.
- **State:** approved by Lucas Fernandes, on 2026-10-17

## Reproduced on disk

- **The shape.** Copilot CLI 1.0.98, reading `.claude/settings.json`, sends every file write as
  `tool_name: "Edit"` with `tool_input` a **string**: `*** Begin Patch`, then one or more
  `*** Add File: <path>` / `*** Update File: <path>` (optionally followed by `*** Move to: <path>`)
  / `*** Delete File: <path>` sections, then `*** End Patch`. An Update holds hunks opened by
  `@@`, with context lines (` `), removed lines (`-`) and added lines (`+`). An Add holds only `+`
  lines. It never sends `Write`. The path is absolute in some calls and relative to the project
  root in others (`captures/probe-single-shellform/run-D1/pretooluse-pascal.jsonl` absolute,
  `run-D4/edit/pretooluse-pascal.jsonl` relative).
- **The blindness.** Every mode reads `tool_input.file_path`, finds none, and exits 0:
  `guard write` let a line into an `approved` spec, `check` let a forbidden import through, and
  `format` never formatted (`evidence/D4-replay.txt`, `evidence/D1-replay.txt`). `guard sweep`
  named the frozen-spec write at `Stop`, after the agent had answered; it did not revert.
- **`schema` is not blind, it over-checks.** With no `file_path` it takes the call for `Stop` and
  sweeps every extension file: an edit to one `.md` can be denied for a problem in another.
- **The shell path is unaffected.** `guard bash` reads `tool_input.command`, which Copilot sends
  under the same name.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 7 — Mandatoriness | Guarantee: the territory, the frozen folder and the boundary must hold under both runtimes | 1–6, 9 |
| 8 — Destination | Both — the modes travel whole in `ArchHook.java`/jar; no registration changes | — |
| 14 — Lifecycle event | The events already registered: `PreToolUse` (`guard write`), `PostToolUse` (`check`, `format`, `schema`, `audit file`) | — |
| 16 — Existing mode | Yes — five modes extended, no new mode, no new registration | A new mode; a new registration |
| — Constraint | Claude Code unchanged: a payload carrying `file_path` takes exactly today's path. An unreadable payload exits 0 (Copilot denies every non-zero exit) | Any rewrite of the current parse |
| — Envelopes | Only the CLI alias (`tool_input` as a string). VS Code (`tool_input.input`, `tool_name: "apply_patch"`) and the camelCase envelope of `.github/hooks` (`toolName`/`toolArgs`/`sessionId`) stay out, **and must be tested** before a later decision adds them — see *Not verified* | Options reading three envelopes |
| — Frozen spec | Derive `old`/`new` per hunk and run the same `isStatusClose` / `isChecklistToggle` | Refusing every patch into a frozen folder |
| — `schema` | Included: it reads the patch paths too | Four modes only |
| — `if: Edit(**/*.java)` | Kept on `check`/`format`; its effect under Copilot is recorded as not verified, with a live case added to roteiro 15 | Dropping the `if` |
| 17 — CI | New `ApplyPatchTest`, fixtures taken from the real captures | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | One translator in `ArchHook.java`: a patch becomes N Claude-shaped payloads, and each mode runs its current code on each | 8 | **Approved** |
| 2 | Option 1 with the patch markers as data in `extensions.json` | 6 | Rejected — invariant 10 read literally for a format nobody tunes; an edited marker blinds five modes |
| 3 | A separate mode (`guard patch`, `check patch`…) with its own registrations | 4 | Rejected — two JVMs per edit, and the rules called through the same translator or copied |
| 4 | Create nothing — `guard sweep` at `Stop` is the net | 2 | Rejected — `guard sweep` detects after the fact and the agent does not revert (D4) |

### Option 1 — translate the patch at the boundary (score 8)

**Motivator:** axis 7 under the constraint. The rules exist once, written against Claude's
payload; the cheapest way to make them see a patch is to hand them the payload they already
read.

**Shape.** One function, `patchPayloads(in)`, returns an empty list unless `tool_input` is a
string whose first non-blank line is `*** Begin Patch`. Otherwise it returns one synthetic
payload per write the patch makes, each carrying the envelope's `session_id` and `agent_type`:

| Patch section | Synthetic payload(s) |
|---|---|
| `*** Add File: p` | `Write` · `file_path: p` · `content`: the `+` lines, prefix stripped |
| `*** Update File: p`, one per hunk | `Edit` · `file_path: p` · `old_string`: context + `-` lines · `new_string`: context + `+` lines, in order |
| `*** Update File: p` + `*** Move to: q` | the hunks as above on `p`, plus an `Edit` on `p` (its removal) and a `Write` on `q` |
| `*** Delete File: p` | `Edit` · `file_path: p`, no strings |

A relative `p` resolves against `ROOT` (the hook's `CLAUDE_PROJECT_DIR`), never the JVM's working
directory. `@@` header text, `*** End of File` and blank trailing lines are ignored.

| Mode | Today | With a patch |
|---|---|---|
| `guard write` (`PreToolUse`) | one `guardPath` on `file_path` | `guardPath` on every synthetic payload; the first violation exits 2 and names its path. A frozen spec admits a hunk only when `isStatusClose` or `isChecklistToggle` holds **for that hunk** — a hunk mixing a toggle with any other change is refused, as one Claude `Edit` doing the same is |
| `check` (`PostToolUse`) | `check(file_path)` | `check` on every `.java` target (Add, Update, Move destination), from disk |
| `format` (`PostToolUse`) | `format(file_path)` | `format` on every `.java` target |
| `schema` (`PostToolUse`) | `checkOne` on `file_path`, sweep when absent | `checkOne` on every target from disk — no sweep. A patch is never mistaken for `Stop` again |
| `audit file` (`PostToolUse`) | one `file` event | one `file` event per target, `op` the synthetic tool name |

A payload with `file_path` never reaches the translator: the branch is taken only when
`tool_input` is a string. A string that is not a patch, or a patch with no section header,
yields no payload and the mode exits 0 — the same silence it keeps today.

**Pros**
- Each rule keeps one owner (invariant 2). Territory, frozen folder, the two spec exemptions, the
  boundary match and the formatter run exactly the code the Claude path runs.
- Additive: the Claude path is untouched, which is the constraint, and `HookShellFormTest`'s
  `Copilot Edit (patch string)` payload keeps exiting 0 (no phase open, no `.java`).
- Precedent: `guard bash` already turns a foreign shape (a shell command) into paths fed to the
  same `guardPath` (`0063`).
- `schema` stops denying an edit for a problem it did not cause.

**Cons**
- A parser in the hook. A wrong reconstruction of a hunk blocks a legitimate spec close with
  exit 2 — the fixtures from the captures and a constructed close/toggle hunk are what hold it.
- The patch grammar (`*** Begin Patch`, `*** Add File: ` …) is spelled in the Java. It is a
  payload format, like the field names `file_path` and `old_string` already read there, not a
  policy anyone tunes; invariant 10 is read as not applying, and option 2 records the other
  reading.
- Copilot's `PostToolUse` firing for the alias registrations, and its handling of `if`, were not
  observed live (D2 shows `format` did not run, cause not isolated). The parse is necessary, not
  shown sufficient, for `check`/`format`/`schema`/`audit file` under Copilot.
- Until command 3 of lessons-learned-022 lands, a denial under Copilot still reaches the model
  as `hook exited with code 2`, with no reason.

**Points cut in the rubric:** criterion 9 — a hook that blocks on a reconstruction can be wrong
and leave a person stuck. Criterion 2 shaved for the grammar literals, not lost.

**CI:** new `.claude/.ci/ApplyPatchTest.java`, a step of `validate` › `hooks-cross-platform`.
Fixtures under `.claude/.ci/fixtures/apply-patch/`, copied from the captures with the
`~/Documents/GitHub/probe-single-shellform` prefix replaced by a `{ROOT}` token the test fills
with its temp project. Cases, each run through `java -jar .claude/hooks/ArchHook.jar`:

- `guard write`: the D4 patch (relative path) on an `approved` spec → 2; the same patch with an
  absolute path → 2; a hunk closing `status: approved` → `implemented` → 0; a hunk toggling
  `[ ]` → `[x]` → 0; a hunk toggling and changing a word → 2; a `draft` spec → 0; `Delete File`
  of a frozen spec → 2; `Move to` into a frozen folder → 2; a two-file patch whose second file
  is frozen → 2, naming it; a design phase open and `Add File` under `src/` → 2; the Claude
  `Edit` payload on the frozen spec → 2 (unchanged).
- `check`: the D1 `Add File` patch, two files, absolute paths, one with
  `import org.springframework.stereotype.Component;` in `domain/model` → 2; the clean file → 0;
  the relative-path variant → 2.
- `format`: a stub `mvnw` (and `mvnw.cmd`) in the temp project that records its argv; the D1
  `Update File` patch → the stub saw `spotless:apply` for the file's module.
- `schema`: a patch on a `SKILL.md` with an unknown frontmatter field → 2, naming it; a patch on a
  valid file while another extension file is invalid → 0 (no sweep).
- `audit file`: an open run, a two-file patch → two `file` events with the two paths.
- Every mode: `tool_input` = `"not a patch"`, `"*** Begin Patch\n*** End Patch\n"`, and `{}` → 0.

### Option 2 — the grammar as data (score 6)

The same translator, with the six markers in an `extensions.json` block read at start-up. It
takes invariant 10 at its most literal, and buys nothing: the markers are Copilot's format, no
project would change them, and an edited marker silently blinds all five modes again. One more
block for `schema` to validate, for no gain.

### Option 3 — a separate mode per shape (score 4)

`guard patch`, `check patch`… registered beside the current entries. Doubles every write
registration in both settings files, and each Copilot edit pays two JVMs per event — one exiting
at once on a payload it cannot read. Either the rules are called from both modes (the translator
again, behind more registrations) or copied into them (invariant 2).

### Option 4 — create nothing (score 2)

`guard sweep` names the frozen-spec write at `Stop`, after the fact, and the agent does not
revert (D4). Nothing covers `check` or `format` at tool time; the boundary falls to
`./mvnw verify` and `doctor gate` in CI.

## Not verified — required before a later decision extends this

- **VS Code** (`.github/hooks`, and `.claude/settings.json` with `chat.useClaudeHooks: true`):
  snake_case envelope, `tool_name: "apply_patch"`, the patch in `tool_input.input`
  (`captures/probe-single/pretooluse-camel-vscode.jsonl`). Under `settings.json`, which
  `tool_name` reaches the matcher is unknown — `apply_patch` would match none of today's
  `Write|Edit|MultiEdit|NotebookEdit`. **Test:** roteiro 15 with `chat.useClaudeHooks: true`,
  a sentinel `PreToolUse` with matcher `.*` capturing the payload of one edit.
- **Copilot CLI / cloud agent through `.github/hooks`**: camelCase envelope, `toolName:
  "apply_patch"`, the patch in `toolArgs`, `sessionId` instead of `session_id` — the guard's
  phase is keyed by session, so the envelope would need normalizing, not only the patch
  (`captures/probe-single-shellform/run-D4/edit/pretooluse-camel.jsonl`). Rejected as a hook file
  by `0168`, option 3; still the only file the cloud agent reads. **Test:** the same capture on
  the cloud agent, once a decision proposes a `.github/hooks` file.
- **`if` under Copilot**: whether `"if": "Edit(**/*.java)"` is ignored (the hook always runs) or
  evaluated (and against what, with no `file_path`). **Test:** roteiro 15, D1/D2 live with a
  `.java` patch, debug log on, read whether `check`/`format` started.
- **`PostToolUse` under Copilot for the alias registrations**: not captured. Same run.

## References

| Claim | Source |
|---|---|
| Copilot CLI sends every write as `Edit` with a patch string, path absolute or relative | `lessons-learned-022.md` § 3, § 6 · `captures/probe-single-shellform/run-D1/`, `run-D4/edit/` |
| `guard write`, `check` exit 0 on that payload; `format` did not run | `lessons-learned-022/evidence/D1-replay.txt`, `D4-replay.txt` |
| Copilot denies on every non-zero `preToolUse` exit | `0168` § Reproduced on disk · `docs/pt-br/13-github-copilot.md` § 2.5 |
| A foreign shape fed to the same `guardPath` | `.claude/decisions/0063-bash-write-enforcement.md` · `ArchHook.java` `guardBash` |
| The two spec exemptions and why they exist | `ArchHook.java` `isStatusClose`, `isChecklistToggle` · lessons-learned-014 §§ 4-5 |
| One owner per rule; lists as data | `@CLAUDE.md` invariants 2, 10 |
| A new behaviour of a mode needs its own test, run on the jar | `references/ci-coverage.md` row 7c · `0084` |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `patchPayloads`, `patchSection`, `patchPayload`, `patchTargets`, `patchRel`, `editedFiles`; `check` and `format` loop over `editedFiles`; `schema` judges each patch target and never sweeps on a string `tool_input`; `guardWrite` judges every translated payload before admitting any; `auditFile` writes one event per file |
| `.claude/.ci/ApplyPatchTest.java` | New — the cases listed under option 1 |
| `.claude/.ci/fixtures/apply-patch/*.json` | New — the three captured patches (D1 Add of two files, D1 Update, D4 spec Update), the project prefix replaced by `{ROOT}` |
| `.github/workflows/validate.yml` | Step `the write hooks read the Copilot CLI's apply_patch` in `hooks-cross-platform`, after the shell-form step |
| `docs/{pt-br,en}/07-ci-validate.md` | Node `H25` and the step's row |
| `docs/{pt-br,en}/13-github-copilot.md` | § 2.5: the payload the CLI was observed to send, and what reads it since 0169; § 7: *Approved spec frozen* under the CLI is guaranteed in the hook, its reason still mute |
| `.claude/skills/claude-code-architect-designer/references/ci-coverage.md` | Row 7a/7b: another runtime's payload shape is a case in `ApplyPatchTest` |
| `.claude/lessons-learned/lessons-learned-022/15-roteiro-teste-copilot.md` | `D7` — the four live checks of *Not verified* above |

`CLAUDE.md` § Commands is unchanged: no mode was added and none changed what it is for. Neither
settings file changed: the registrations already match `Edit`, which is what the CLI sends.

Goes to the generated project: **yes** — `ArchHook.java`, the rebuilt jar and `extensions.json`
travel whole through `export`; no registration changes in either settings file.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › the write hooks read the Copilot CLI's apply_patch` (`.claude/.ci/ApplyPatchTest.java`, ubuntu · macos · windows) | The 42 cases of option 1: the three captured patches and the constructed ones judged as Claude Code's payloads are, every mode exits 0 on an unreadable payload | Green on the tree (42 cases, macOS) · red with the jar built from the source before this decision: 14 failures, the four symptoms of lessons-learned-022 § 3 (frozen spec, territory, `check`, `format`, `audit file`) plus `schema` sweeping on a patch |
| `validate · … › every hook registration runs the same under Claude Code and the Copilot CLI` (`HookShellFormTest`) | Its `Copilot Edit (patch string)` payload still exits 0 or 2, the same in all three launches | Green, 25 registrations |
| `validate · … › committed ArchHook.jar is what the source compiles to` | The jar is this source | Green (`build --verify`) |

The neighbours that run the same modes stayed green on the new jar: `BashGuardTest`,
`SweepTest`, `SkillTerritoryTest`, `AgentTerritoryTest`, `BoundaryTest`, `BoundarySweepTest`,
`AuditRenderTest`, `GuardStatusTest`.

A defect of the first draft of the test is worth keeping: a context line written `---` instead
of ` ---` reads as the removal of `--`, and the spec close was refused. The captured patches
prefix every context line with a space, as the grammar requires. A model that drops that space
gets its spec close refused, with the reason mute under Copilot, and that is the failure the
D7 live run should look for first.

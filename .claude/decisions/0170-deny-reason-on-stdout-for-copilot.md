# 0170 · A blocking hook also writes its reason on stdout, in the one shape both runtimes read at exit 2

- **Date:** 2026-10-17
- **Scenario:** "No Copilot CLI, um preToolUse que sai com 2 nega a chamada, mas o modelo vê só
  'Denied by preToolUse hook: hook exited with code 2'. Todo o stderr do guard se perde [...]
  proponha como guard/check emitem o motivo nos dois runtimes sem mudar o exit code do Claude Code"
  (lessons-learned-022 § 4, comando 3).
- **Decision:** Form 7c — one exit path, `blockExit()`, in `.claude/hooks/ArchHook.java`, through which
  every `exit 2` of every mode now goes, plus the `settings.deny_output` block of
  `.claude/schemas/extensions.json` it reads. No new mode, no registration changed.
- **State:** approved by Lucas Fernandes, on 2026-10-17

## Reproduced on disk

Probe `lessons-learned-022/probe-deny-reason/` ran in an empty directory with only the sentinel hook.
It used Claude Code 2.1.296 and Copilot CLI 1.0.98 (`gpt-6-luna`) in `-p` mode. Every Copilot run
loaded the hooks: 12 of 12 `event=pre` lines. Raw captures are in `captures/probe-deny-reason/`, and
the table is in `evidence/D3b-deny-reason.txt`. What each runtime handed the model, read from
`tool_result` (Claude Code) and from `tool.execution_complete` (Copilot):

| Variant | Claude Code | Copilot CLI |
|---|---|---|
| `PreToolUse`, exit 2, stderr only (today's `guard`) | denied · stderr | denied · `hook exited with code 2` |
| exit 2 + flat `{"permissionDecision":"deny","permissionDecisionReason":R}` | denied · **stderr** (stdout ignored) | denied · **R** |
| exit 2 + `hookSpecificOutput` (alone or with the flat one) | denied · R, stderr dropped | denied · `hook exited with code 2` — the flat R is lost too |
| exit 0 + flat | **runs** | denied · R |
| exit 0 + `hookSpecificOutput` | denied · R | denied · R |
| exit 0 + `{"decision":"block","reason":R}` | denied · R | **runs** |
| `PostToolUse`, exit 2, stderr only (today's `check`) | stderr reaches the model | stderr becomes `session.warning`, printed on the screen only |
| `PostToolUse`, exit 0 or 2 + `{"decision":"block","reason":R}` | R, as a "blocking error" (stderr dropped at exit 2) | `Tool result blocked: R` — `success: false`, and the tool's own output is replaced |
| `PostToolUse` + `additionalContext` (flat or nested) | nested reaches | neither reaches; debug log only |

Two consequences:

- **One `PreToolUse` shape works in both runtimes without touching Claude Code.** That shape is exit 2,
  with stderr as today and the **flat** object alone on stdout. Claude Code ignores that stdout at exit
  2, so the model reads the same stderr text, byte for byte. Adding `hookSpecificOutput` breaks it in
  both runtimes.
- **On `PostToolUse` the Copilot has exactly one channel to the model**, and it has a cost. It is
  `decision: "block"`, and it hides the tool's result. The edit is already on disk when `check` runs. A
  model that reads `success: false` may believe the edit failed.

Also on disk: `docs/pt-br/13-github-copilot.md:222` says the `guard` "hoje escreve a forma aninhada".
It does not. `ArchHook.java` emits `hookSpecificOutput` only from `context subagent`
(`SubagentStart`), and every deny is stderr + `System.exit(2)`.

## Interview

The scenario and the probe answered the axes. The user ruled out any change to Claude Code's exit code.

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 7 — Mandatoriness | The deny already holds in both runtimes. The reason is what is missing | 1–6, 9 |
| 14 — Lifecycle event | `PreToolUse` (`guard call/write/bash`, `schema`) and `PostToolUse` (`check`, `format`, `schema`). `Stop` already reaches the model (§ 4) | — |
| 15 — Reaction | Block, as today. Only the channel of the explanation changes | 8 (`permissions.deny` has no reason text and no Copilot equivalent) |
| 16 — Existing mode | Every blocking mode already exists. No new mode | new 7c mode |
| 8 — Destination | Both. `ArchHook.java`, its jar and `extensions.json` travel whole through `export` | — |
| 17 — CI | `validate › hooks-cross-platform` already spawns the jar per mode | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | One exit path in `ArchHook.java`: at exit 2 under a hook, stdout gets the shape the event's `deny_output` entry names. `PreToolUse` → flat. `PostToolUse` → `decision`/`reason` | 8 | **Approved** |
| 2 | As 1 for `PreToolUse`. `PostToolUse` writes `decision`/`reason` only when the environment names the Copilot CLI (`COPILOT_CLI`) | 6 | Rejected — rests on an undocumented variable seen in one run; a rename makes `check` mute again in silence |
| 3 | As 1 for `PreToolUse` only. `PostToolUse` stays mute under Copilot | 5 | Rejected — leaves a forbidden import unexplained to Copilot's model until CI |
| 4 | Exit 0 + flat and nested in one object, in both runtimes | 2 | Rejected — changes Claude Code's exit code, and a malformed object fails open in both |
| 5 | Create nothing | 2 | Rejected — under Copilot every guard deny stays unexplained, and the model retries blind |

### Option 1 — `ArchHook.java` exit path + `hooks.deny_output` in `extensions.json` (score 8)

**Motivator:** axis 15 — the block stays, only the explanation moves — and the probe row that keeps
Claude Code's `PreToolUse` output identical.

**Shape.**
- `err()` keeps writing to stderr and also accumulates the text of this run.
- Each hook-path `System.exit(2)` goes through one helper.
- The helper reads `hook_event_name` from the payload it already parsed and looks the event up in
  `hooks.deny_output` of `extensions.json`:
  - `PreToolUse` → `{"permissionDecision":"deny","permissionDecisionReason":<text>}`;
  - `PostToolUse` → `{"decision":"block","reason":<text>}`.
- The helper prints that one object on stdout, then exits 2.
- An event absent from the map, or no payload (`doctor gate`, `export`, a CLI run), means no stdout:
  exactly today.
- On `PostToolUse` the text opens with one fixed sentence, because Copilot replaces the tool result
  with it: `The tool call itself completed — the file is written; what follows is what must change.`

**Pros:**
- Claude Code `PreToolUse` is unchanged byte for byte (probe row 2).
- Copilot gains the reason on every `guard` deny and on every `check`/`format`/`schema` post-edit
  refusal.
- One code path for every mode, and no runtime detection.
- The shapes are data (invariant 10).

**Cons:**
- Claude Code `PostToolUse` takes the text from `reason` instead of stderr. The text is the same, and
  the header changes from `hook error: [cmd]:` to `hook blocking error from command: "cmd":`.
- Copilot `PostToolUse` hides the edit's result. The fixed sentence is what keeps the model from
  re-applying the edit.
- Strains invariant 10 at its edge. The output keys are runtime protocol, not a list of names. Keeping
  them in `extensions.json` is what lets a third runtime be a data change.

**Points cut in the rubric:**
- § 8.6 precedent: a deny with JSON on stdout is new here. The only stdout object today is
  `SubagentStart`'s context.
- § 8.2, half a point: the strain on invariant 10 named above.

**CI:** a new `.claude/.ci/DenyReasonTest.java` in `validate › hooks-cross-platform`. It spawns
`ArchHook.jar`, as `ApplyPatchTest` does. Cases:
- `guard bash` with a force push, `PreToolUse` → exit 2; stdout parses as the flat object, its reason
  equals stderr, and it holds no `hookSpecificOutput` key.
- `check` on a forbidden import, `PostToolUse` → exit 2; stdout is `decision: block` with the fixed
  opening sentence.
- `doctor gate` red → exit 2 and empty stdout.
- An allowed call → exit 0 and empty stdout.

It is proved red by injecting `hookSpecificOutput` into the `PreToolUse` shape.

### Option 2 — Option 1, `PostToolUse` only under `COPILOT_CLI` (score 6)

Claude Code stays strictly unchanged on both events. Copilot is detected by `COPILOT_CLI` in the
hook's environment. That variable was seen in one run of 1.0.98 and is not documented. It adds a
second branch in a path that runs on every refusal, and a Copilot update that renames the variable
makes `check` mute again, in silence.

### Option 3 — `PreToolUse` only (score 5)

The smallest change, with zero change to Claude Code anywhere. It leaves `check` as mute as today
under Copilot. A forbidden import reaches the model only at CI (`doctor gate`), long after the agent
moved on. That is the D1 hole of § 3 reopened on the reporting side.

## References

| Claim | Source |
|---|---|
| Claude Code ignores a flat object at exit 2 and keeps stderr; Copilot reads it | `evidence/D3b-deny-reason.txt`, `captures/probe-deny-reason/cc-PRE_E2_FLAT.jsonl`, `cop-PRE_E2_FLAT.jsonl` |
| `hookSpecificOutput` at exit 2 drops the reason in Copilot | `cop-PRE_E2_NESTED.jsonl`, `cop-PRE_E2_BOTH.jsonl` |
| `PostToolUse` stderr is screen-only in Copilot; `decision: block` replaces the result | `cop-POST_E2_STDERR.jsonl` (`session.warning`), `cop-POST_E2_JSON.jsonl` (`Tool result blocked`) |
| `COPILOT_CLI` is in the Copilot hook environment, `CLAUDECODE` is not | `captures/probe-deny-reason/deny-probe.cop.log` |
| Every deny today is stderr + exit 2; `hookSpecificOutput` only at `SubagentStart` | `.claude/hooks/ArchHook.java` (`err`, `context subagent`) |
| Shapes the runtime reads are data with one owner | `@CLAUDE.md` invariant 10 |
| A hook under Copilot exits 0 on what it cannot read | `lessons-learned-022.md` § Constraints; `0168`, `0169` |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` | `ERR` also accumulates into `ERR_TEXT`; `HOOK_EVENT` read from the payload in `main`; `blockExit()`, `denyOutput()`, `hookEvent()`; all 38 `System.exit(2)` routed through `blockExit()` |
| `.claude/hooks/ArchHook.jar` | Rebuilt under JDK 21; `build --verify` green |
| `.claude/schemas/extensions.json` | `settings.deny_output` (`PreToolUse`, `PostToolUse`) and `$comment_deny_output` |
| `.claude/.ci/DenyReasonTest.java` | New, 12 checks |
| `.github/workflows/validate.yml` | Step `a hook refusal carries its reason on stdout for the Copilot CLI`, job `hooks-cross-platform` |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | Node H26 and the table row |
| `docs/pt-br/13-github-copilot.md`, `docs/en/13-github-copilot.md` | Observed behaviour on 1.0.98. The false claim that `guard` writes the nested form is corrected. The frozen-spec row now carries the reason |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | Never `hookSpecificOutput` in the deny shape |
| `.claude/lessons-learned/lessons-learned-022.md` | § 4 follow-up probe; open question 3 answered |
| `.claude/lessons-learned/lessons-learned-022/probe-deny-reason/`, `captures/probe-deny-reason/`, `evidence/D3b-deny-reason.txt` | The probe, its script and its results. Home paths are written as `~`, and the Claude Code `init` lines are dropped |

Goes to the generated project: **yes, via step 6.6.** `export` copies `ArchHook.java`, the jar and
`extensions.json` whole. The template's registrations are unchanged.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › a hook refusal carries its reason on stdout for the Copilot CLI` | `PreToolUse` deny writes one line, the flat object, no `hookSpecificOutput`, reason = stderr verbatim. `PostToolUse` `check` writes `decision: block`, with the prefix and then stderr. No `hook_event_name`, an unmapped event, or a missing `deny_output` → empty stdout. An admitted call → exit 0, empty stdout | Green on the tree (12/12). Red with `hookSpecificOutput` injected into the `PreToolUse` `fields`: 2 checks fail by name |

The other 27 tests of `hooks-cross-platform` stay green against the rebuilt jar.
`HookShellFormTest` is among them, and it compares stdout across hook forms.

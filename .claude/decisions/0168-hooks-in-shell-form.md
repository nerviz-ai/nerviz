# 0168 · Hook registrations move from exec form to shell form, so the Copilot CLI can run them

- **Date:** 2026-10-16
- **Scenario:** "Os hooks em forma exec negam todo Edit, Shell e Read no Copilot CLI 1.0.98 —
  decida entre forma shell, um arquivo de hooks por runtime ou um launcher; o que funciona hoje
  para o Claude não muda" (lessons-learned-022 § 2, evidence D0).
- **Decision:** Form 7a, shell form in `.claude/settings.json` and `project-bootstrap/templates/settings.json.example`, plus a change to two existing 7c modes (`schema` enforces the shape, `doctor` warns on the path). No new mode.
- **State:** approved by Lucas Fernandes, on 2026-10-16

## Reproduced on disk

- **The block.** Copilot CLI reads `.claude/settings.json` and runs `command`. It ignores
  `args`, so it runs a bare `java`, which exits 1. On `preToolUse`, every non-zero exit other
  than 2 denies the call: `Denied by preToolUse hook from "repo settings" (hook errored)`.
  Every Edit and Shell was denied, and every Read too once an exec-form `Read` probe existed
  (`lessons-learned-022/evidence/D0-copilot-cli.txt`, `D0-diagnostico.txt`).
- **The controlled proof.** Same CLI, same prompt, only the form changed: shell form
  (`java -jar "$CLAUDE_PROJECT_DIR/.claude/hooks/ArchHook.jar" guard bash`) let `mkdir`,
  create, `git status` and edit pass (`captures/probe-single-shellform/run-1.4/`).
- **The environment.** `CLAUDE_PROJECT_DIR` is set in the hook's environment under Copilot CLI
  in 7 of 7 captures (`captures/probe-single/env-capture-copilot-cli-shellform.log`).

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 7 — Mandatoriness | Guarantee: every hook already registered must run under both runtimes | 1–6, 9 |
| 8 — Destination | Both: `.claude/settings.json` and `project-bootstrap/templates/settings.json.example` | — |
| 14 — Lifecycle event | Every event already registered; no event added or removed | — |
| 16 — Existing mode | No new mode. `schema` changes the form it enforces; `doctor` gains one warning | A new 7c mode |
| — Constraint | What works today under Claude Code does not change: same modes, same exit codes, same stdin | Any option that changes a mode's behaviour |
| — Schema policy | Shell form only, provided it works under both runtimes (macOS/Linux: yes, see References) | Option 2 |
| — Windows, Claude Code | Accept one extra shell per hook. `doctor` warns, never gates, when the project path holds `"`, `$` or a backtick | — |
| — Windows, Copilot | Recorded as not verified (PowerShell does not expand `${CLAUDE_PROJECT_DIR}`) | The `powershell` key |
| 17 — CI | A test now that feeds every registration the real Copilot and Claude payloads, and demands exit 0 or 2 | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Shell form in both settings files, required by `schema` | 8 | **Approved** |
| 2 | Shell form in the template only, exec form kept in this repo | 5 | Rejected — two rules for one kind of entry |
| 3 | `.github/hooks/*.json` per runtime | 3 | Rejected — the CLI still reads the exec entries of `.claude/settings.json` |
| 4 | A launcher, or one entry carrying both `command`+`args` and Copilot's `exec` | 2 | Rejected — collapses into option 1, or a combination Copilot's docs forbid |
| 5 | Create nothing | 0 | Rejected — Copilot keeps denying every call |

### Option 1 — shell form everywhere, `schema` requires it (score 8)

**Motivator:** axis 7. The guarantee has to start before it can hold, and a hook Copilot
cannot launch is a total block there.

**Shape.** Every entry becomes
`"command": "java -jar \"${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar\" <mode> [<sub>]"`,
without `args`. The braces form is used on purpose. Claude Code substitutes the placeholder
itself before the shell runs, so it holds under Git Bash and under PowerShell. A Unix shell
under Copilot expands the same text from the environment, where the variable is set. The
meta-repo's `build` entry keeps the source launch:
`java "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.java" build`.

**`schema`.** `args` leaves `settings.hook_entry.allowed`, and a new
`settings.hook_entry.command_pattern` in `extensions.json` holds the one shape a command may
have. `command_forbidden_chars` keeps `|`, `&`, `;`, `>`, `<`, `` ` `` and `$(`. With those
characters still refused, a shell string carries no pipe, chain or substitution, which is
the risk exec form was chosen against.

**`doctor`.** A warning, never a gate, when the project's absolute path holds `"`, `$` or a
backtick. In shell form those characters would split or expand the substituted path.

**Pros**
- One form that Claude Code (`sh -c`, Git Bash, PowerShell) and the Copilot CLI (`bash`)
  both run.
- `schema` makes the next hook written in exec form fail by name, instead of blocking
  Copilot in silence.
- Under Claude Code the same mode receives the same stdin and returns the same exit code.
  `sh -c` passes the last command's exit status through.

**Cons**
- It reverses the "Exec form, always" rule of this skill, of `hook-events.md` and of the
  `_comment` of both settings files (decisions 0053 and 0075), so the propagation is wide.
- On Windows each hook pays one Git Bash start.
- A project path containing `"`, `$` or a backtick breaks every hook. `doctor` says so.
- The Copilot CLI on Windows still blocks: `command` is copied to `powershell`, which reads
  `${CLAUDE_PROJECT_DIR}` as an unset PowerShell variable. This stays not verified.

**Points cut in the rubric:** criterion 9. A shell process sits in front of every JVM. On
Unix `sh -c` execs its last command, so the cost is mostly on Windows. Criterion 5 is kept: no
new piece is added, only the form of existing ones.

**CI:** new `.claude/.ci/HookShellFormTest.java`, in `validate` › `hooks-cross-platform`. It
covers three claims:
1. Every registration of both settings files matches `command_pattern` and carries no `args`.
2. Each registration of the template is run under `sh -c` in a temp project
   (`CLAUDE_PROJECT_DIR` set) with each fixture payload, and under the old exec form with the
   same payload. The two exit codes must be equal: that is "Claude unchanged".
3. Every exit is 0 or 2, never another code, which is the Copilot fail-closed trap.

The fixtures are the Claude and Copilot payloads of `lessons-learned-022/captures/`, sanitized,
copied under `.claude/.ci/fixtures/hook-payloads/`. `schema` already runs over both settings
files in the same job.

### Option 2 — shell form in the template only (score 5)

Reaches every generated project and leaves this repository's sessions in exec form. Two
rules for one kind of entry: `schema` would need a per-file form, and the next hook written
here and copied into the template would arrive in the wrong form. The user asked for one
form, given that it works under both runtimes.

### Option 3 — `.github/hooks/*.json` per runtime (score 3)

It does not fix the CLI on its own. The Copilot CLI still reads `.claude/settings.json`, and
the exec-form entries there keep denying. It also needs `ArchHook` to read the camelCase
payload (`toolName`, `toolArgs` as a JSON string), which is a 7c change. It is the only file
the Copilot **cloud agent** reads, so it stays a candidate for that surface. That is a later
decision, not this one.

### Option 4 — launcher or dual-key entry (score 2)

A launcher that receives its mode needs either `args`, which Copilot ignores, or a shell
string, which is option 1. Adding Copilot's `exec` key next to `command`+`args` is refused by
Copilot's own docs ("Do not combine `exec` with `bash`, `powershell`, or `command`"). For
Claude Code it is an undocumented field, and `schema` would have to admit it (invariant 10).

### Option 5 — create nothing (score 0)

The Copilot CLI denies every write, shell and read in every generated project.

## References

| Claim | Source |
|---|---|
| Shell form runs under `sh -c` on macOS/Linux, Git Bash on Windows, PowerShell without Git Bash; `shell` is ignored when `args` is set | <https://code.claude.com/docs/en/hooks> § Exec form and shell form (read 2026-10-16) |
| Path placeholders are substituted into `command` in both forms, and should be double-quoted in shell form | Same page, § Reference scripts by path |
| Copilot: `command` is copied to `bash`/`powershell`; `exec` must not combine with `command`; non-zero `preToolUse` exit denies | <https://docs.github.com/en/copilot/reference/hooks-configuration> (read 2026-10-16) · `docs/pt-br/13-github-copilot.md` § 2.5 |
| Copilot CLI reads `.claude/settings.json` and ignores `args` | `lessons-learned-022.md` § 2 |
| Exec form was chosen for quoting on Windows | `.claude/decisions/0053-hooks-in-architect-designer.md` · `0075-precompiled-hook-jar.md` · `_comment` of both settings files |
| Enforcement stays in one executable; no second hook file | `@CLAUDE.md` invariant 6 · this skill § Out of scope |
| The form's data lives in `extensions.json` | `@CLAUDE.md` invariant 10 |

## Propagation

| File | Change |
|---|---|
| `.claude/settings.json` | The 20 entries in shell form; the `build` entry keeps the source launch, `java "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.java" build`; the `_comment` opens with the new form and its reason |
| `.claude/skills/project-bootstrap/templates/settings.json.example` | The 28 entries in shell form; the same `_comment` opening |
| `.claude/schemas/extensions.json` | `settings.archhook_command_pattern`, `settings.project_path_unsafe_chars`, `$comment_archhook_command`; `args` out of `hook_entry.allowed`; the `$comment` sentence on `command_forbidden_chars` |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `checkSettings` names an `args` list as exec form; `checkHookEntry` holds an ArchHook command to the pattern and keeps the metacharacter check; `doctor` gains the `Hook path` line (`doctorHookPath`), and its `OS` line names Git Bash / PowerShell |
| `.claude/skills/claude-code-architect-designer/SKILL.md` | Phase 4 step 6: "Exec form, always" becomes "Shell form, one shape, no `args`" |
| `…/references/hook-events.md`, `decision-matrix.md` (anti-pattern 19), `ci-coverage.md`, `templates/hook-entry.json.example` | The same rule, and the new test in the 7a/7b row |
| `.claude/skills/project-bootstrap/references/project-files.md` | The paragraph on how the hooks run |
| `claude-help.md` § 8 and § 13 | Copilot reads the same file and ignores `args`; the repo's hook line |
| `README.md` | § Enforcement retitled "one Java file, one command shape", example in shell form; anchor followed in `docs/*/09-*` |
| `docs/{en,pt-br}/01-*`, `04-arch-doctor.md`, `07-ci-validate.md`, `09-*`, `11-pitfalls.md`, `13-github-copilot.md` | Form described, `Hook path` line, CI row and node, pitfall, placeholder row with the Windows gap |
| `.github/workflows/validate.yml` | Step `every hook registration runs the same under Claude Code and the Copilot CLI` in `hooks-cross-platform` |

Goes to the generated project: **yes** — the template is copied over `.claude/settings.json` by
`export.overwrite`, and `ArchHook.java`, the jar and `extensions.json` travel whole. A project
updated by `arch-adopt` receives the new form on its next export. Its own hook entries in exec
form are lost by `overwrite` as before, and `schema` names any it adds back.

Decisions 0053, 0075 and 0077 are history and keep their wording; this record supersedes their
"exec form" for the registrations.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › every hook registration runs the same under Claude Code and the Copilot CLI` (`.claude/.ci/HookShellFormTest.java`, ubuntu · macos · windows) | No `args` in either settings file; every ArchHook entry quotes the placeholder; each of the 25 `java -jar` registrations × 5 payloads gives the same exit and stdout as exec form, as Claude Code's shell form and as Copilot's, and the exit is 0 or 2 | Green on the tree (125 cases, ~29 s on macOS) · red with the template's `guard bash` entry put back in exec form (`still holds an args list`) · red with an entry launching a missing jar (`exit 1: under Copilot a preToolUse hook that exits neither 0 nor 2 denies the call`) |
| `validate · … › frontmatter schema` (`ArchHook.java schema`) | The shape, as data | Green on the tree · red with the exec-form entry (`args is exec form, and the Copilot CLI reads this file…`) |
| `doctor`, by hand | The `Hook path` warning | ✅ on this checkout · ⚠️ with the project under a path holding `$` |

End to end, outside CI: a fresh `claude -p` (Claude Code 2.1.296) on this checkout ran `guard
prompt`, `tests`, `compose gate`, `guard sweep` and `schema` from the shell-form registrations,
each `finished with status 0` in the debug log. The Copilot side rests on lessons-learned-022 § 2
(macOS). **Not verified:** Claude Code on Windows (documented: Git Bash, PowerShell without it)
and the Copilot CLI on Windows, where `command` becomes `powershell`.

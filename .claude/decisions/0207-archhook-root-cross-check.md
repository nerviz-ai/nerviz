# 0207 · ArchHook warns when `CLAUDE_PROJECT_DIR` names another Nerviz project than the one it ships in

- **Date:** 2026-11-14
- **Scenario:** "Quero criar algum hook ou regra que toda vez que o claude iniciar uma sessão e identificar
  que o projeto é do nerviz (meta-repo ou gerado) a variável seja setada automaticamente para o projeto
  em questão."
- **Decision:** option A, approved by the user, with one addition: the warning prints the command to change the variable in each OS's syntax (PowerShell, cmd, Git Bash, bash/zsh, fish; macOS and Linux profile hints).
- **State:** approved by the user, 2026-11-14

## Reproduced on disk

- The Windows **User** environment held `CLAUDE_PROJECT_DIR=C:\Users\lucas\git\poupalar-be`, set by hand
  at some point. Every process started from that account inherited it: the Bash and PowerShell tools of a
  Claude Code session opened in `nerviz` read `poupalar-be`.
- The hooks themselves were **not** affected: Claude Code sets `CLAUDE_PROJECT_DIR` for each hook process
  and substitutes it into skill text (this skill's own body showed `C:/Users/lucas/git/nerviz`).
- What was affected: every **manual** run in the tool shell — `java .claude/hooks/ArchHook.java build`,
  `schema`, `doctor` — and the skill injections that expand `${CLAUDE_PROJECT_DIR:-.}` in the tool shell.
  `ArchHook.java:53` resolves `ROOT` from the variable before the cwd, so `build` printed "up to date" and
  `schema` printed nothing, both against `poupalar-be` (side finding of `0206`). The inventory injected
  into this skill listed `poupalar-be`'s skills, and `ls …/decisions` failed there.
- Fixed by hand, outside this record: the User variable was removed
  (`[Environment]::SetEnvironmentVariable('CLAUDE_PROJECT_DIR',$null,'User')`). Effective in new sessions.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | A stale `CLAUDE_PROJECT_DIR` from the OS environment silently redirected manual ArchHook runs to another project | — |
| 2 — Trigger | Asked for "session start"; the defect is per command, wherever the variable is read | 1 · 2 · 4 · 5 |
| 7 — Mandatoriness | Must not pass silently; no hook or guard may be weakened | everything above Form 7 |
| 8 — Destination | Both: this repo and every generated project (the jar travels via `export`) | — |
| 14 — Event | `SessionStart` rejected (option B below) | 7a |
| 15 — Reaction | Report, never block, never change an exit code: on `PreToolUse` under Copilot any exit other than 0 and 2 denies the call | 8 |
| 16 — Existing mode | `doctor` already prints `CLAUDE_PROJECT_DIR · set / NOT set` and `Project root`; no mode compares the two | 7c (behaviour of an existing path, no new mode) |
| 17 — CI | ~25 tests under `.claude/.ci/` run **this** jar with `CLAUDE_PROJECT_DIR` at a temp project on purpose; `platform-upgrade` and `arch-adopt` run the source's jar with the variable at the source and act on the cwd | rules out any override |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | Cross-check in `ArchHook.java`: warn on stderr and on a `doctor` line when the variable names a directory that holds its own `.claude/hooks/ArchHook.jar` and is not the root this jar (or source) ships in; `ROOT` unchanged | 8 | Proposed |
| B | `SessionStart` hook writing `CLAUDE_PROJECT_DIR` into `$CLAUDE_ENV_FILE` | 3 | Rejected — hooks cannot change Claude Code's own environment; the file reaches later Bash calls only (PowerShell tool unproven), Copilot has none, one JVM per session; and the hook processes already get the right value |
| C | Override: when the two roots differ, use the jar's | 2 | Rejected — breaks every CI test that runs this jar against a temp project, and the `export`/`platform-upgrade` cross-tree runs; an explicit variable must win |
| D | Unset fallback: `ROOT` = jar's root instead of `.` when the variable is absent | 4 | Rejected for now — does not touch the observed defect (the variable was *set*); changes behaviour of six tests that remove the variable and rely on the cwd |
| E | Create nothing — the User variable is already removed | 5 | Defensible — the cause is gone; nothing stops it coming back, and the failure mode is silent |

### Option A — `ArchHook.java` root cross-check (score 8)

**Motivator:** axes 1 and 15 — the failure was silent; a warning that never blocks is the whole remedy.

**Rule.** Let `env` = `CLAUDE_PROJECT_DIR` resolved, `home` = the project this code ships in:
the jar's `CodeSource` → `<home>/.claude/hooks/ArchHook.jar`, or in source mode the `.java` path the
launcher names (`sun.java.command`) → `<home>/.claude/hooks/ArchHook.java`. Paths come from `hook_build.jar`
and `hook_build.source` of `extensions.json` (invariant 10). Warn when **all** hold:

1. `env` is set and `home` was resolved (unresolvable → silent);
2. `env` ≠ `home` after `toRealPath`, case-insensitive on Windows;
3. `env` holds its own `hook_build.jar` — another Nerviz project, which would run its own jar.

Condition 3 is what keeps CI quiet: a temp project of a test holds no jar. `platform-upgrade`
(`CLAUDE_PROJECT_DIR=$SRC java -jar $SRC/…`) and `arch-adopt` (`… $SRC/…/ArchHook.java export .`) have
`env` = `home`, so they never warn.

**Where it shows.**
- stderr, one line, once per process, every mode:
  `ArchHook: CLAUDE_PROJECT_DIR=<env> is another Nerviz project; this ArchHook ships in <home>. Using <env>. Unset the variable or open the session in <env>.`
  Exit codes untouched.
- `doctor`: the existing `CLAUDE_PROJECT_DIR` line turns red with the same text. Not added to
  `doctor.gate.labels` — runner state never gates (`DoctorGateTest` already asserts this line is not gated).

**Pros:** covers Claude Code, Copilot and CI with no new registration and no per-session process; the
`build` and `schema` silences of `0206` would have printed the warning; travels with the jar.

**Cons:** `sun.java.command` is a HotSpot property, not a standard one — absent → source mode stays silent;
stderr on an exit-0 hook is not shown to the model in Claude Code (only in verbose), which is fine since hooks
never see a stale value there.

**Points cut in the rubric:** cost of a stat per process (one `Files.exists`), and the source-mode heuristic.

**CI:** new `.claude/.ci/RootCrossCheckTest.java`, registered in `.github/workflows/validate.yml`, job
`validate`: builds two temp projects each holding a copy of the jar; runs project 1's jar with the variable
at project 2 → warning on stderr, exit code equal to the unwarned run; at project 1 → silent; at a temp dir
with no jar → silent; source mode via `java <path>/ArchHook.java doctor` → the `doctor` line red. Proven
green, then red with condition 3 removed (expect a warning in the no-jar case).

### Option E — create nothing (score 5)

The removal already fixed this machine. Nothing prevents another `setx`, a `.bashrc` export or an IDE
launch config from bringing it back, and the next time it is again silent.

## References

| Claim | Source |
|---|---|
| `ROOT` reads the variable before the cwd | `.claude/hooks/ArchHook.java:53` |
| Hooks get `CLAUDE_PROJECT_DIR` set by the runtime | `@claude-help.md` § 8 · Capture 3 and 6 of `0206` |
| `CLAUDE_ENV_FILE` is `SessionStart`-only and reaches Bash only | `code.claude.com/docs/en/hooks` (read for `0206`) |
| Non-zero, non-2 exit denies on Copilot `preToolUse` | `0168` · `0206` Capture 2 |
| Runner state never gates; `CLAUDE_PROJECT_DIR` line is not gated | `.claude/.ci/DoctorGateTest.java:5,38` |
| Tests run this jar at temp projects on purpose | `.claude/.ci/BoundaryTest.java:69`, `ComposeGateTest.java:305`, `ExportProvenanceTest.java:136`, … |
| Cross-tree runs with the variable at the source | `.claude/skills/platform-upgrade/SKILL.md:168,184` · `.claude/skills/arch-adopt/SKILL.md:189` |
| Jar and source paths are data | `hook_build` of `.claude/schemas/extensions.json` · `@CLAUDE.md` invariant 10 |
| Silent side finding of the same cause | `0206` § Decision, last paragraph |

## Propagation

- `ArchHook.java` and the rebuilt `ArchHook.jar` travel to every generated project through `export` (the
  manifest already lists both) — no new `export` entry, no new registration, so no restart is needed for
  the hook itself; a running session loads the jar at the next hook spawn.
- `doctor` gains a `Root cross-check` line, reported and never in `doctor.gate.labels`.
- Message by `os.name`: Windows → PowerShell, cmd.exe, Git Bash, plus the `[Environment]::SetEnvironmentVariable`
  removal of a persistent User variable; macOS/Linux → bash/zsh, fish, plus the profile file to edit.
- Exit codes untouched; the warning goes through a raw stderr stream, never `ERR`, so a refusal does not
  repeat it on stdout as its reason.

## CI coverage

`.claude/.ci/RootCrossCheckTest.java`, step "root cross-check warns on another Nerviz project and nowhere
else" of job `validate` in `.github/workflows/validate.yml`. Two temp projects each hold a copy of the jar:
env at the other project → warning, same exit code as an own-root run; env at own project, at a directory
with no jar, and unset → silent; `doctor` → red `Root cross-check` line, never a gate failure.
`DoctorGateTest`, `HookShellFormTest`, `BashGuardTest` and `ExportProvenanceTest` pass unchanged.

# 0206 · Hook commands that survive an unset `CLAUDE_PROJECT_DIR` under GitHub Copilot

- **Date:** 2026-11-14
- **Scenario:** "Nos projetos gerados é sempre esperado o preenchimento da variável de ambiente
  `CLAUDE_PROJECT_DIR`. Quando ela não está setada, o hook no Copilot bloqueia qualquer comando.
  Ao gerar o projeto e no arch-adopt, substituir o diretório no comando
  `java -jar \"${CLAUDE_PROJECT_DIR}\"/.claude/hooks/ArchHook.jar`."
- **Decision:** option F — every ArchHook entry keeps `command` and adds a `powershell` field with the
  `$env:CLAUDE_PROJECT_DIR` form.
- **State:** approved by the user on 2026-11-14.

## Prior art

- `0168` moved every registration to shell form,
  `java -jar "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar" <mode>`, on the fact that the
  Copilot CLI sets `CLAUDE_PROJECT_DIR` (7 of 7 captures, Copilot CLI 1.0.98,
  `lessons-learned-022`). The symptom of this record contradicts that fact, so reopening `0168`
  is legitimate. Under Copilot an unset variable becomes `java -jar "/.claude/hooks/ArchHook.jar"`,
  which exits 1, and on `preToolUse` any non-zero exit other than 2 denies the call.
- `project-bootstrap/templates/copilot-hooks.json.example` (`.github/hooks/archhook.json`)
  already uses a **relative** path: `java -jar .claude/hooks/ArchHook.jar guard read`.

## Interview

| Axis | Answer |
|---|---|
| 1 — Symptom | Copilot denies every hooked call when `CLAUDE_PROJECT_DIR` is unset |
| Runtimes affected | Copilot CLI, VS Code Copilot, Copilot cloud agent (all three, per the user) |
| 7 — Mandatoriness | Guarantee: the hooks must start under every runtime |
| 8 — Destination | Both: `.claude/settings.json` and `project-bootstrap/templates/settings.json.example`; `export` and `arch-adopt` write the latter |
| Windows | Must work, PowerShell included |
| 16 — Existing mode | `schema` (`settings.archhook_command_pattern`) enforces the command shape; `doctor` reads it |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | `${CLAUDE_PROJECT_DIR:-.}` in `command` | 6 | Open — needs the Copilot cwd probe; PowerShell does not expand it |
| B | Plain relative `.claude/hooks/ArchHook.jar` | 2 | **Refuted by probe** — under Claude Code the hook's cwd follows the session's `cd` |
| C | Absolute path written by `export` / `arch-adopt` (original request) | 2 | Rejected — the versioned file then works only on the machine that generated it: breaks every other clone, CI, a moved folder, and leaks the home path into git (invariant 9) |
| D | Absolute path in `.claude/settings.local.json` written by `export` | 2 | Rejected — hooks merge across files, so the versioned entry with the empty variable still exits 1 and still denies; the cloud agent never sees an unversioned file |
| E | Relative `command`; `ArchHook` resolves the root itself (`CLAUDE_PROJECT_DIR`, else walk up from cwd) | 3 | **Refuted as is** by the same probe as B: `java -jar` with a relative path fails before `ArchHook` runs when cwd is a subdirectory |
| F | `command` unchanged + a `powershell` field (`$env:CLAUDE_PROJECT_DIR`) on every ArchHook entry | 8 | **Approved** — Copilot reads and prefers the field (Capture 5), Claude Code ignores it (Capture 6) |

## Probes run (2026-11-14, macOS)

Claude Code 2.1.319, Copilot CLI 1.0.107. Probe 1: throwaway repo with a `PreToolUse`
`matcher: "Bash"` hook logging `cwd` and `CLAUDE_PROJECT_DIR`; prompt runs `cd sub/deep`, `pwd`, `ls`.

```
--- claude
cwd=<probe>          cpd=<probe>
cwd=<probe>/sub/deep cpd=<probe>
cwd=<probe>/sub/deep cpd=<probe>
--- copilot
(no line)
```

- **Claude Code:** cwd follows `cd`; `CLAUDE_PROJECT_DIR` is always set. Keeping
  `${CLAUDE_PROJECT_DIR}` in the command is correct there; any relative form breaks it.
- **Copilot CLI:** the hook never fired. Either `matcher: "Bash"` does not match the Copilot
  tool name (`bash`/`shell`) or `copilot -p` did not load the hooks. Nothing concluded.

## Capture 2 — Windows, PowerShell, Copilot CLI (reported by the user, version not recorded)

From a Copilot CLI session log; nothing was changed and the jar was not exercised.

- `.claude/settings.json` entry `java -jar "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar" guard bash`
  failed: `Unable to access jarfile /.claude/hooks/ArchHook.jar`. The placeholder expanded to empty.
- The event recorded the correct repository root as its working directory; `.claude\hooks\ArchHook.jar` exists.
- `preToolUse` failed closed (`Error in preToolUse hook from "repo settings"`): both `/arch-doctor`
  commands were denied; the prompt-submit and stop hooks failed the same way. Reads still worked —
  `.github/hooks/archhook.json` uses a relative path.
- **Confirmed:** the path reaching `java` had no root. **Not confirmed:** whether
  `CLAUDE_PROJECT_DIR` was unset in the environment, or set and not expanded by PowerShell
  (`${NAME}` is a plain variable there; the environment form is `$env:NAME`).
- **Proposed by the report, not applied:** relative `.claude\hooks\ArchHook.jar` in every entry plus a
  looser `archhook_command_pattern`. That is option B; the macOS probe above refutes it for Claude Code
  (cwd follows `cd`). It holds only where the cwd stays at the root, which this capture shows once
  and does not show after a `cd`.
- Answers question 3 in part: on Windows/PowerShell under Copilot, `${CLAUDE_PROJECT_DIR}` became empty.
  Questions 1 and 2 stay open.

## Capture 3 — probe 2 on Windows, Copilot CLI (PowerShell, user-run, 2026-11-14)

Probe: a hook script (`powershell -File log.ps1`) reading `$env:CLAUDE_PROJECT_DIR` and the cwd,
registered in `.claude/settings.json`; prompt `cd sub/deep`, `pwd`, `ls`. Three `PreToolUse` events:

```
cwd=…\cwdprobe2 cpd=…\cwdprobe2   (all three; payload "cwd" also …\cwdprobe2)
```

- **Q1 answered:** `CLAUDE_PROJECT_DIR` **is set** in the hook's environment, to the repository root.
  Capture 2's empty path is therefore the shell's expansion, not a missing variable: `command` is copied
  to `powershell`, which reads `${CLAUDE_PROJECT_DIR}` as an unset PowerShell variable (`$env:` is the form).
- **Q2 answered, with a caveat:** the hook's cwd stayed at the root in all three events, `cd sub/deep` included.
  Not shown: whether the `cd` took effect in the Copilot tool shell.
- Consequence: a relative path would run under Copilot (cwd root) and still breaks Claude Code (cwd follows `cd`).
  `${CLAUDE_PROJECT_DIR}` is right for Claude Code and for Copilot's bash, wrong only for Copilot's PowerShell.
  No single `command` string known so far reads the environment in both PowerShell and `sh`.

## Capture 4 — vendor docs (read 2026-11-14)

- **Copilot** (`docs.github.com/en/copilot/reference/hooks-configuration`): the CLI also reads
  `.claude/settings.json` and `.claude/settings.local.json`; entries of every source are combined and
  all run. A command hook takes `bash`, `powershell`, `command` (copied to both when they are absent;
  explicit fields win) and, in the CLI, `exec`/`args`. The page does not say how a `.claude/settings.json`
  entry maps to these fields, never names `CLAUDE_PROJECT_DIR` or `shell`, and states no default `cwd`.
  No switch stops the CLI reading `.claude`; `disableAllHooks` disables every source. The cloud agent
  loads only `.github/hooks/*.json`.
- **Claude Code** (`code.claude.com/docs/en/hooks`): a command hook takes `shell` (`bash`|`powershell`,
  ignored with `args`). No `cwd` field: the hook runs in the current directory, which follows `cd`.
  Shell form: the shell expands `${CLAUDE_PROJECT_DIR}`. Exec form (`args`): substituted as a plain
  string, no shell. Both export the variable. No per-OS command override.
- **Candidates this opens, unverified:** exec form (`args`) in `.claude/settings.json` — Claude Code
  substitutes without a shell; whether Copilot honours `args` there is unknown. Probe 3 tests it, next
  to a `command`+`powershell` pair and the plain form.

## Capture 5 — probe 3 on Windows, Copilot CLI (PowerShell, user-run, 2026-11-14)

Three entries in `.claude/settings.json`, three `PreToolUse` events, log:

```
A-powershell arg=[<root>]   (command + powershell field, "$env:CLAUDE_PROJECT_DIR")
C-plain      arg=[]         (command only, "${CLAUDE_PROJECT_DIR}")
```

- **A works:** the `powershell` field of a `.claude/settings.json` entry is read by Copilot and wins over
  `command`; `$env:CLAUDE_PROJECT_DIR` holds the root. Reproduces Capture 2 for the plain form (empty).
- **B never ran:** `command` + `args` is not honoured from `.claude/settings.json` (no line, in any event).
  Exec form is out.
- **Option F (candidate):** every `.claude/settings.json` entry keeps `command` (Claude Code, Copilot bash)
  and adds `powershell` with the `$env:` form. Open: Claude Code ignores the unknown field silently
  (not yet probed); `settings.archhook_command_pattern` and `ArchHook.java schema` must learn the field;
  the exporter and `settings.json.example` carry it.

## Capture 6 — probe 4 on Windows, Claude Code (PowerShell, user-run, 2026-11-14)

Same settings shape as probe 3, entries `F-command` and `F-powershell` (field `powershell` with the
`$env:` form). Log after the run:

```
F-command cpd=[C:/Users/lucas/AppData/Local/Temp/ccprobe4]   (twice, no F-powershell line, no error)
```

- Claude Code runs `command` and ignores the `powershell` field silently. Option F does not change
  its behaviour.

## Decision

Option F. Propagation:

| Piece | Change |
|---|---|
| `.claude/settings.json`, `project-bootstrap/templates/settings.json.example` | a `powershell` line right after every ArchHook `command` |
| `extensions.json` `settings` | `hook_entry.allowed` gains `powershell`; new `archhook_powershell` (`from`/`to`); `archhook_command_pattern` unchanged |
| `ArchHook.java schema` | an ArchHook entry must carry `powershell` equal to `command` with `from`→`to`; missing, wrong or on a non-ArchHook entry is a named error; `command_forbidden_chars` apply to it; jar rebuilt |
| `.github/hooks/archhook.json` and its `copilot_hooks` block | unchanged — already relative |
| Docs | `hook-events.md` entry-fields table, `SKILL.md` Form 7 rule, `_comment` of both settings files |

Side finding: during this work the session's `CLAUDE_PROJECT_DIR` named another project, so a first
`build` and `schema` ran against it and printed "up to date" and silence. Every command was rerun with
the variable set to this repository.

## CI coverage

- `schema` (job `validate`): the `powershell` pair on every ArchHook entry of `.claude/settings.json` and
  of the template, and the rejection of a missing, wrong or stray field.
- `.claude/.ci/HookShellFormTest.java` (`.github/workflows/validate.yml`, the hook shell-form step): the
  pair is checked in section 1b, and on Windows the `powershell` form is launched through PowerShell
  as the Copilot CLI does, beside the exec, Claude and Copilot-shell launches.
- `build --verify`: the committed jar equals the source with the new rules.
- Not proven in CI: that the Copilot CLI prefers the field — Capture 5, by hand.
- `settings.json` is read only at startup: restart Claude Code.

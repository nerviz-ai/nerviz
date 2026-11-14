# 0206 · Hook commands that survive an unset `CLAUDE_PROJECT_DIR` under GitHub Copilot

- **Date:** 2026-11-14
- **Scenario:** "Nos projetos gerados é sempre esperado o preenchimento da variável de ambiente
  `CLAUDE_PROJECT_DIR`. Quando ela não está setada, o hook no Copilot bloqueia qualquer comando.
  Ao gerar o projeto e no arch-adopt, substituir o diretório no comando
  `java -jar \"${CLAUDE_PROJECT_DIR}\"/.claude/hooks/ArchHook.jar`."
- **Decision:** pending — investigation continues on a machine with Copilot (VS Code and cloud agent).

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

## Next — on the machine with Copilot

Probe 2 (Copilot CLI, no `matcher`, logs cwd, variable and the first 200 bytes of the payload):

```sh
#!/bin/sh
P="$(cd "$(dirname "$0")" && pwd)/cwdprobe2"
rm -rf "$P"; mkdir -p "$P/.claude" "$P/sub/deep"; cd "$P" && git init -q
cat > .claude/settings.json <<EOF
{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"echo \"cwd=\$(pwd) cpd=\${CLAUDE_PROJECT_DIR:-UNSET} in=\$(head -c 200)\" >> $P/hook.log"}]}]}}
EOF
copilot -p "Run these shell commands as separate tool calls, in order: 'cd sub/deep', then 'pwd', then 'ls'." --allow-all-tools > "$P/copilot.out" 2>&1
echo "rc=$?"; echo "--- hook.log"; cat hook.log 2>/dev/null || echo "(no hook.log)"
echo "--- copilot output (tail)"; tail -15 "$P/copilot.out"
```

Repeat the same capture in VS Code Copilot (agent mode) and in the cloud agent, and on Windows
with PowerShell. Questions to answer:

1. Under which runtime and version is `CLAUDE_PROJECT_DIR` unset in the hook's environment?
2. Under Copilot, does the hook's cwd stay at the repo root after a `cd`?
3. Under Windows/PowerShell, what does `${CLAUDE_PROJECT_DIR}` / `${CLAUDE_PROJECT_DIR:-.}` become?

How the answers pick the option:

- Copilot cwd stays at the root, Windows accepted later → A, `archhook_command_pattern` and both
  settings files change, `HookPayloadTest`-style CI case feeds a payload with the variable unset.
- Copilot cwd follows `cd`, or PowerShell must work now → a new option is needed (e.g. the
  Copilot-only `.github/hooks/*.json` carrying every mode, if Copilot can be kept from running
  the `.claude/settings.json` entries — `0168` says it currently reads them).

## CI coverage

Pending — named once an option is approved.

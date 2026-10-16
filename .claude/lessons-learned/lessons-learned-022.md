# Lessons learned 022 — a generated project under GitHub Copilot CLI, measured against Claude Code

Date: 2026-10-16. Scope: the test script `15-roteiro-teste-copilot.md` (copied next to this file),
run on `probe-single` (blueprint `hexagonal-single-module`, generated from this repository's working
tree, provenance 2026-10-16T16:54:55Z) under Claude Code 2.1.296 and GitHub Copilot CLI 1.0.98, with
one capture from VS Code Copilot Chat. JDK 21.0.11. This is steps 2 and 5 of phase 8 of `roadmap.md`
§ Multi-agent portability: measure before rewriting any skill, and prove the hook payload before
wiring `guard` to Copilot hooks. This file records what was observed. It decides nothing. The design
belongs to `/claude-code-architect-designer`.

Sources: `lessons-learned-022/evidence/` (one file per test, Portuguese, as written during the run),
`lessons-learned-022/captures/` (raw hook stdin, per run), `lessons-learned-022/resultados.pt-br.md`
(the full results sheet of § 4 of the script). Home and temporary paths are replaced by `~`,
`<user>`, `<scratchpad>` and `<tmp>`. This repository at commit `dda08d7`.

What was not observed counts as absent. A test that did not run is `not verified`, never `same`.

---

## § 1 · Setup and deviations from the script

- **Two copies of the project.** `probe-single` keeps the generated hooks, which are in exec form
  (`"command": "java", "args": ["-jar", "${CLAUDE_PROJECT_DIR}/…", …]`). `probe-single-shellform` is a
  copy with the 28 hooks of `.claude/settings.json` rewritten to shell form
  (`"command": "java -jar \"$CLAUDE_PROJECT_DIR/.claude/hooks/ArchHook.jar\" guard bash"`). Every
  Copilot test after D0 ran on the copy, because on the original nothing writes or runs a shell (§ 2).
- **Probes.** Every probe hook writes the raw hook stdin to a file. There were three:
  - a PascalCase probe in `.claude/settings.local.json`;
  - a camelCase probe in `.github/hooks/probe.json`;
  - an environment probe that logs `$CLAUDE_PROJECT_DIR` and `$PWD`.
- **Not run.**
  - `probe-multi` was not generated, so A2 and A3 did not run.
  - No remote, so A4, H and I did not run.
  - C2 and C3 need an approved spec.
- **E1 used a fake file** (`.probe/e1.env`, `TOKEN=probe`). The project's real `.env` was not exposed.
- **F1 used a fake MCP server.** The project ships no `.mcp.json` and no template, so a fake server
  that logs its `env` and `args` and exits was written. It was removed after F1.
- **Offline replays.** D1, D3 and D4 were also measured by piping the exact payload Copilot delivers
  into `ArchHook.jar`. Live, the model refused by instruction before the hook was reached (§ 5).

## § 2 · Exec-form hooks deny every write and every shell in Copilot CLI (D0)

On the original project, Copilot CLI denied every `Edit` and every `Shell`. After an exec-form probe
hook was added, it denied every `Read` too. The message was always the same:
`Denied by preToolUse hook from "repo settings" (hook errored)`.

Copilot CLI runs only `command` and ignores `args`:
- `java` with no arguments exits 1.
- `sh` reading the JSON payload as a script exits 127 (`command not found`).

Copilot denies on any non-zero `preToolUse` exit, so one unparsed field becomes a total block.

**Controlled proof.** Same CLI, same prompt, only the hook form changed:
- Exec form: `Edit` and `Shell` are denied, and `notes/probe.txt` is never created.
- Shell form: `mkdir`, create, `git status` and edit all pass, and the file ends `ok2`.

`CLAUDE_PROJECT_DIR` **is set** in the hook's environment under Copilot: 7 of 7 captures carry the
project path. Inside the agent's own shell it is unset, which is what `doctor` reports there.

The `_comment` of `settings.json` chose exec form to avoid quoting problems on Windows. Shell form
brings that cost back.

Evidence: `evidence/D0-copilot-cli.txt`, `evidence/D0-diagnostico.txt`,
`captures/probe-single-shellform/run-1.4/`.

## § 3 · Copilot's edits are invisible to `guard write`, `check` and `format` (D1, D2, D4)

Copilot CLI writes files only through `apply_patch`. A hook in `.claude/settings.json` receives it as
`tool_name: "Edit"`, but `tool_input` is the **patch as a string**:

```
*** Begin Patch
*** Add File: notes/probe.txt
+ok
*** End Patch
```

The payload has no `file_path` and no `content`. The patch path is sometimes absolute and sometimes
relative to the root. Copilot never sends `Write`.

| Payload | `guard write` (frozen spec) | `check` (forbidden import) |
|---|---|---|
| Claude `Edit` or `Write` with `file_path` | exit 2 | exit 2 |
| Copilot `apply_patch` → `Edit`, string `tool_input` | **exit 0** | **exit 0** |

**Live results**
- **D4:** Copilot appended `- probe` to an `approved` spec. `guard sweep` at `Stop` reported it
  (`❌ This turn wrote outside what the open phase and the frozen folders admit…`), and the agent had
  already answered and did not revert.
- **D2:** `format` did not run. The `Stop` test hook then failed on `spotless:check`.
- **Shell path:** the frozen spec stays protected there, because `guard bash` reads `tool_input.command`,
  which arrives under the same name.

Evidence: `evidence/D4-replay.txt`, `evidence/D1-replay.txt`.

## § 4 · Denials reach the model without their reason (D3, D4)

A sentinel hook (`matcher: "Bash"`, exit 2 when the command holds `PROBE_DENY`, explanation on stderr)
was run in a new session.

**What it showed**
- The `matcher` is honoured, and exit 2 **denies**.
- The model sees only `Denied by preToolUse hook: hook exited with code 2`. The stderr text, which is
  `guard`'s whole explanation, is lost.
- After one entry denies, later entries of the same event do not run.

**Side observations**
- At `Stop` the hook output does reach the transcript.
- A hook added mid-session is not loaded. Hooks are reread only in a new session.

Replaying Copilot's payload for `git push -f origin main` into `guard bash` gives exit 2 with the
expected message. By composition, force push would be blocked without a reason. Live, the model refused
twice by instruction, quoting `git-publish`, before any command reached a hook.

Evidence: `evidence/D3-copilot-cli.txt`, `evidence/D3-claude-code.txt`,
`captures/probe-single-shellform/run-D3-sentinel2/`.

## § 5 · Manual skills: held by instruction, and the flag locks the user out (B2, B4, B5)

**B4 — no manual skill invoked by the model**
- `arch-adopt`: the model read its full `SKILL.md` through `view`, then stopped on two pieces of text:
  "manual only" in `CLAUDE.md`, and `arch-adopt`'s refusal of a dirty worktree.
- `arch-doctor` and `new-feature` were not invoked. For "design the registration feature", the model
  called `use-case-design` (auto-invocable) directly, skipping the pipeline.
- `/skills info` shows no field for `disable-model-invocation`, only `userInvocable: true` and
  `capabilities.invokesModel: true`.
- On a clean worktree, only the instruction stands between the model and `arch-adopt`.

**B5 — `/new-feature <request>` does not start while `disable-model-invocation: true` is set**
- Typed alone, `/new-feature` opens an argument box, which shows `argumentHint` is read.
- The command then reaches the model as plain text, which answers "type `/new-feature …`", in a loop.
- With the flag removed from the copy, the same command starts on the third attempt:
  `skill{new-feature}`, the entry guardrail, then `skill{use-case-design}` **chained through the `skill`
  tool**, then `ask_user`. It wrote `UC-901-register-customer/00-use-case.md` with the right number, path
  and template (`evidence/B5-UC-901-00-use-case.md`).
- `/arch-doctor` carries the same flag and starts. Two candidates for the difference were not separated:
  the argument, and the size (`new-feature/SKILL.md` is 62,673 bytes, the largest; the next is 45 KB).

**B2 — `` !`…` `` injections are not run by the runtime**
- The body of `/arch-doctor` arrives literal, and the model runs both commands through `bash`, with
  descriptions it wrote itself.
- The result is correct, but it costs tool calls and passes through the Bash hooks, which on the
  original project would deny them (§ 2).
- Of two sessions with the same `/arch-doctor`, one went through the `skill` tool and ran each command
  twice; the other injected the body directly.

Evidence: `evidence/B4-copilot-cli.txt`, `evidence/B5-copilot-cli.txt`, `evidence/B2-copilot-cli.txt`.

## § 6 · Hook payload, as delivered

| Tool | Claude Code | Copilot CLI → `.claude/settings.json` (PascalCase alias) | Copilot CLI → `.github/hooks` (camelCase) | VS Code → `.github/hooks` |
|---|---|---|---|---|
| Envelope | `session_id`, `tool_name`, `tool_input`, `hook_event_name` | same keys, `hook_event_name: "PreToolUse"` | `sessionId`, `toolName`, `toolArgs`; no event name; `timestamp` in epoch ms | snake_case, `hook_event_name: "PreToolUse"` |
| Shell | `Bash` · `command`, `description` | `Bash` · `command`, `description` | `bash` · `command`, `description` | `run_in_terminal` · `command`, `explanation`, `goal`, `mode` |
| Write | `Write` · `file_path`, `content` | `Edit` · patch string | `apply_patch` · patch string | `apply_patch` · `input` (string), `explanation` |
| Edit | `Edit` · `file_path`, `old_string`, `new_string`, `replace_all` | `Edit` · patch string | `apply_patch` · patch string | `apply_patch` · `input` (string) |
| Read | `Read` · `file_path` | `Read` · `path` | `view` · `path` | `read_file` · `filePath`, `startLine`, `endLine` |
| Question | `AskUserQuestion` | — | `ask_user` · `message`, `requestedSchema` (`oneOf` options or a free `string`) | — |
| Skill | `Skill` | — | `skill` · `skill` | — |

- **VS Code with defaults.** It did not read `.claude/settings.local.json`. The run with
  `chat.useClaudeHooks: true` was not made.
- **`SubagentStart`.** It was not captured on any runtime.

Evidence: `captures/probe-single/`, `captures/probe-single-shellform/run-1.4/`.

## § 7 · What works the same

| Test | Observed under Copilot CLI |
|---|---|
| A1 | The root `CLAUDE.md` is in the initial context. The model answered `./mvnw test` and `Under another agent` with zero tool calls. |
| A5 | `.claude/rules/` **is read**. `Discovered <rule> from .claude/rules/` appears, up to 14 rules in one run. The script assumed it is not. |
| B1 | 25 of 25 project skills load. A "Failed to load 3 skills" notice comes from outside the project. |
| B3 | The argument text reaches the model. Substitution and appending could not be told apart. |
| B6 | `AskUserQuestion` maps to `ask_user`, with structured options or free text. |
| D6 | `Stop` hooks run: `schema`, `guard sweep`, and the test hook (`verify -DskipITs`). |
| F1 | `.mcp.json` with `mcpServers` is read. `${VAR}` expands in `env` **and** in `args` (unlike hooks). An undefined variable stays literal. |
| G1 | The build does not know the author. See § 9 for what the build actually refuses. |

## § 8 · What Copilot does not protect at all (E1)

The `deny` `Read(./**/*.env)` has no file equivalent in Copilot. Asked for a value, the model refused
on its own secrets policy and called no tool. Asked to count lines, it ran `wc -l .probe/e1.env` with no
denial. In Claude Code, `cat` on the same file was denied. The content stays out of the model only
because the model chose not to read it.

Evidence: `evidence/E1-claude-code.txt`.

## § 9 · Defects of the generated project, independent of Copilot

These belong to `/report-issue`, not to the portability design. They are recorded here because this run
found them.

1. **The frontmatter of `agents/mapstruct-installer.md` is invalid YAML.**
   - Its unquoted `description` contains `": "` inside `` `**Mappers: manual.**` ``.
   - Copilot's `/agent` refuses to load it: `failed to parse YAML frontmatter: mapping values are not
     allowed in this context at line 2 column 117`. Ruby's Psych reproduces the error. Claude Code's
     parser is lenient and accepts it.
   - Under Copilot, 4 of 5 agents register.
   - Every generated frontmatter should be checked with a strict parser.
2. **A fully qualified name escapes the boundary check.**
   - `@org.springframework.stereotype.Component` with no `import`, in `domain/model`, passes `check`,
     `doctor gate` (the CI job `architectural boundaries`) and `./mvnw verify`, under Claude Code too.
   - `check` and `doctor gate` read only `import` lines.
   - With no ArchUnit installed, the Maven build has no boundary rule.
3. **`./mvnw verify` accepts a forbidden import.** Only the CI job refuses it. The root `CLAUDE.md`'s
   "Outside Claude Code, only the build is guaranteed" holds for CI, not for the local build.
4. **Ignored agent fields.** Copilot ignores `effort` on 4 agents and `omitClaudeMd` on 2 installers, so
   those installers receive the whole `CLAUDE.md`.

Evidence: `evidence/C1-copilot-cli.txt`, `evidence/G1-build.txt`, `evidence/D1-replay.txt`.

## Not verified

- **A2, A3:** no `probe-multi`.
- **A4, H, I:** no remote.
- **C2, C3:** no approved spec.
- **D5:** phase territory was not run.
- **E2:** no push reached a prompt.
- **VS Code with `chat.useClaudeHooks: true`:** not run.
- **D1 and D3 live:** blocked by instruction before the hook. Their verdicts rest on the replays.

## Constraints any fix must respect

- **Claude Code must stay unchanged.** Its payload for edits is still `file_path`-based. Parsing
  `apply_patch` is an addition, not a replacement.
- **Copilot denies on every non-zero exit.** A hook that fails for any reason under Copilot blocks the
  agent, so a hook must exit 0 on a payload it cannot read.
- **Shell form brings back the quoting risk on Windows** that exec form was chosen to avoid.
- **The flag cannot hold by itself under Copilot.** `disable-model-invocation` is not represented there,
  and the manual skills are held only by instruction.

## Open questions for `/claude-code-architect-designer`

1. **Hook form.** Shell form for every hook, a per-runtime hook file (`.github/hooks/`), or a launcher
   that works in both?
2. **`apply_patch`.** Should `guard write`, `check`, `format` and `audit file` parse it (`Add`, `Update`,
   `Delete`, `Move`), with paths absolute or relative to the root?
3. **Denial reason.** Does Copilot surface a JSON `decision` and `reason` on stdout where it drops stderr?
   This is not tested yet.
4. **The flag.** Should `disable-model-invocation: true` stay on `new-feature`, given that under Copilot
   it locks out the user it exists to serve? And what makes `/arch-doctor`, with the same flag, start?
5. **The `CLAUDE.md` sentence.** Should "Under another agent" be rewritten now? It says the Copilot CLI
   hooks "may run and are not verified". They run, in shell form, and are partly blind (§ 3) and mute (§ 4).

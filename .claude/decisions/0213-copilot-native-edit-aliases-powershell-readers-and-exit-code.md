# 0213 · The guards read the Copilot CLI's native edit payload and PowerShell reads, and the `powershell` hook field keeps exit 2

- **Date:** 2026-11-21
- **Scenario:** "Issue #221, triaged at e2cfb72: guard write/check ignoram payloads com path/old_str/new_str, guard bash não tem leitores PowerShell de segredos, e o campo de hook powershell transforma exit 2 em 1"
- **Decision:** Form 7c + 7a, option 1 — three changes to existing modes of `.claude/hooks/ArchHook.java` and the data they read, plus a live Copilot CLI check (`copilot-live.yml`, § Live check). No new mode.
- **State:** approved by Lucas Fernandes, on 2026-11-21 (option 1, with a live Copilot CLI job in CI added at approval)

## Reproduced on disk

Rows of the `issue-verifier` table of `/triage-issue 221` at `e2cfb72`, confirmed; the issue's proposed fix is not an input.

| Claim | Where it holds |
|---|---|
| `filePath()` reads only `"file_path"`; `guardWrite` returns when `tool_input.file_path` is null | `ArchHook.java:11525`, `:10546-10547` |
| `isStatusClose` / `isChecklistToggle` read only `old_string` / `new_string`; the `Write` content read is `content` only | `ArchHook.java:11485-11486`, `:11513-11514`, `:10700` |
| A `path` / `old_str` / `new_str` payload through `guard write` and `check` exits 0 | verifier replay, exit 0 and no output |
| `guard bash` refuses `cat probe.env` (exit 2) and admits `Get-Content`, `gc`, `type` of it (exit 0) | `extensions.json:872` lists no PowerShell reader; verifier replay |
| The `powershell` hook string launched with `-Command` returns 1 on a deny that Java exits 2 on | verifier replay of `settings.json:49` |
| The Windows CI check of the `powershell` form launches it with `-File` and holds no exit-2 payload, so it cannot see the loss | `.claude/.ci/HookShellFormTest.java:49-58,221` |

### Capture, Copilot CLI 1.0.114, Windows, 2026-11-21 (run in this session, `copilot -p`, throwaway directory)

A hook logging stdin was registered in `.claude/settings.json` and in `.github/hooks/`.

| Call | Envelope read from `.claude/settings.json` | Envelope read from `.github/hooks/*.json` |
|---|---|---|
| Native edit | `tool_name: "Edit"`, `tool_input: {path, old_str, new_str}`, path absolute | — |
| Create a file | `tool_name: "Write"`, `tool_input: {path, file_text}` — **`file_text`, not `content`** | `toolName: "create"`, `toolArgs: {path, file_text}` |
| Shell command | `tool_name: "Bash"`, `tool_input: {command, description}` — the matcher `Bash` fires | `toolName: "powershell"`, `toolArgs: {command, description}` |
| File read | `tool_name: "Read"`, `tool_input: {path}` | `toolName: "view"`, `toolArgs: {path}` |

`PostToolUse` carries the same `tool_input` plus `tool_result`. The apply_patch string of `0169` (1.0.98) did not appear in
any call: 1.0.114 sends the object shape for `Edit`.

Deny probe, a `PreToolUse` hook exiting 2 with the flat `permissionDecision` object on stdout and the reason on stderr:

| `powershell` field | Copilot shows the model |
|---|---|
| `powershell -File deny.ps1` (exit 2) | `Denied by preToolUse hook from "repo settings" (hook errored)` — no reason |
| `powershell -File deny.ps1; exit $LASTEXITCODE` | `Denied by preToolUse hook: PROBE-REASON-B` — the reason |

Both denied the call; the suffix is what lets the reason reach the model. Not captured: a `-Command` launch (what the verifier
replayed), and the `MultiEdit` / `NotebookEdit` shapes.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 7 — Mandatoriness | Guarantee: the territory, the frozen spec, the boundary and the secret list must hold under both runtimes | 1–6, 9 |
| 8 — Destination | Both — the modes travel whole in `ArchHook.java` and the jar; the registrations are settings files | — |
| 15 — Reaction | Block; the reason must reach the model | `PostToolUse`-only forms |
| 16 — Existing mode | Yes — `guard write`, `check`, `format`, `schema`, `audit file`, `guard bash`; no new mode | A new mode |
| Item 1 (user) | Capture first, then design — done above | Designing from the report's word alone |
| Item 2 (user) | Reader names plus backslash as path separator inside a PowerShell segment; the sh parser of the other segments untouched (the bar of `0063`) | Names only; leaving it out |
| Item 3 (user) | Fixed suffix `; exit $LASTEXITCODE` held as data in `archhook_powershell` | Exit 0 + flat JSON; leaving it |
| 17 — CI | `ApplyPatchTest`, `SecretReadTest`, `HookShellFormTest` and `schema` hold it; named per option | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Aliases as data, normalized once at the boundary; `powershell_commands` in `secret_read_shapes`; a fixed suffix as data in `archhook_powershell` | 8 | Recommended |
| 2 | Option 1 with item 3 as exit 0 + flat JSON in the `powershell` form only | 6 | Open — see below |
| 3 | Aliases hard-coded in the Java | 3 | Rejected — invariant 10 |
| 4 | Create nothing; record the three gaps in `13-github-copilot.md` | 3 | Rejected — a guarantee was available and the first two are irreversible leaks or a frozen spec edited |

### Option 1 — three changes, one record (score 8)

**Motivator:** axis 7, with the captures above.

**1 · Aliases.** `guard.tool_input_aliases` in `extensions.json`:
`{"file_path": ["path", "filePath"], "old_string": ["old_str"], "new_string": ["new_str"], "content": ["file_text"]}`.
One function, `canonicalInput(stdin)`, runs where each write mode reads its stdin: when the parsed envelope's `tool_input` is an
object that holds an alias and not its canonical name, it returns the envelope re-serialized with the canonical name; otherwise it
returns `stdin` byte for byte, so a Claude Code payload takes today's path. `editedFiles`, `filePath`, `guardWrite`,
`isStatusClose`, `isChecklistToggle` and the content read of `guard write` then judge the same payload they always judged. Same
move as `0169` (translate once, one owner per rule, invariant 2). The string `tool_input` of the apply_patch alias stays handled by
`patchPayloads`, unchanged. The camelCase envelope of `.github/hooks` (`toolArgs`, `create`) reaches no write registration and is
out of scope, as in `0169`.

**2 · PowerShell readers.** `guard.secret_read_shapes.powershell_commands`: `Get-Content`, `gc`, `type`, `Select-String`, `sls`,
`Format-Hex`, `Import-Csv`, `Get-FileHash` stays out (it reveals a hash, not content). Compared case-insensitively. In a segment
whose head is one of them, `\` is a path separator, not an escape, and the operands are judged by the same `secretOperand` as the
sh readers; `-Path` / `-LiteralPath` values are operands, `-Raw` and other flags are skipped. A target with an unresolvable
marker is skipped, and a write target is never a read, as in `0173`. The sh tokenizer is untouched for every other segment. The
shell call reaches `guard bash` through the existing `Bash` matcher: Copilot sent `tool_name: "Bash"` for its `powershell` tool.

**3 · Exit code.** `archhook_powershell` gains `"suffix": "; exit $LASTEXITCODE"`. The `powershell` field of an ArchHook entry
must equal `command` with `from` → `to` **plus** the suffix; `command_forbidden_chars` still refuses `;` everywhere else, in
`command` and in the field's body before the suffix, so the entry stays one process and one statement of ours. Reopens `0206`: it
chose the field and weighed the placeholder, not the exit code. `HookShellFormTest` launches the `powershell` form the way
Copilot does and adds an exit-2 payload to it (a force push through `guard bash`), asserting exit 2 and the flat object.

**Pros:** closes the three gaps on observed behaviour; the Claude Code path is untouched (payloads without an alias, a field it
ignores); every list is data; no new mode and no registration changed apart from the suffix.

**Cons:** a second shape the write modes must keep reading; the PowerShell tokenizer is a second parser beside the sh one; the
suffix is the one `;` the schema now admits, and a launch under `-Command` is not what was captured.

**Points cut in the rubric:** 5 (a second tokenizer), 6 (no precedent for a second shell grammar in `guard bash`).

**CI:**
- `ApplyPatchTest` (`validate · hooks-cross-platform`): the captured `Edit` and `Write` bodies, raw, absolute `path`; a frozen
  spec admits a status close and a checklist toggle in the object shape and refuses any other change; `check` on a forbidden import
  written through `file_text`.
- `SecretReadTest`: `Get-Content`, `gc`, `type`, `Select-String`, mixed case, `-LiteralPath`, `config\app.env` with a backslash;
  `Get-Content x.env.example`, `Get-ChildItem x.env` and a write target admitted; the list is data.
- `HookShellFormTest`: the `powershell` form launched as Copilot does, an exit-2 payload, exit 2 asserted; `schema` refuses a
  field without the suffix and a `;` anywhere else.
- Red runs: alias list emptied, reader list emptied, suffix dropped from one entry — each fails by name.
- Not provable in CI: that Copilot prefers the field and shows the reason — the capture above, by hand.

### Option 2 — exit 0 + flat JSON, only in the `powershell` field (score 6)

Claude Code ignores the field, so a runtime-specific exit is possible there; `0170` measured Copilot denying on exit 0 with the
flat object. Needs a flag on the `guard` modes to print and exit 0, which no mode has, and covers only events with `deny_output`
(`PostToolUse` keeps its `decision: block`). Rejected for now by the user's answer: it adds a mode flag and a second exit
semantic where the suffix changes one string.

### Options 3 and 4

3 — a list of field names in the Java breaks invariant 10 (a mode reads its lists from `extensions.json`). 4 — the first two gaps
let a frozen spec and a secret file through under the Copilot CLI.

## Live check — the Copilot CLI in CI (added at approval)

Every check above stubs the Copilot CLI with payloads; none proves that it still sends them. This gap is what let 1.0.98 →
1.0.114 change the edit shape unseen. One workflow runs the real CLI.

- **`.github/workflows/copilot-live.yml`**, job `copilot`, matrix `ubuntu-latest` and `windows-latest`. Triggers: `workflow_dispatch`,
  a weekly `schedule`, and `pull_request`/`push` path-filtered to the hook, its jar, `extensions.json`, both settings files, the
  test and the workflow itself (same list under both triggers, as `mods.yml`). Not a required check (a path-filtered required
  check stays pending), `permissions: contents: read`, `timeout-minutes: 15`, `concurrency` per ref.
- **Secret.** The CLI authenticates from `COPILOT_GITHUB_TOKEN` (a fine-grained token with the Copilot Requests permission),
  passed through `env: COPILOT_GITHUB_TOKEN: ${{ secrets.COPILOT_GITHUB_TOKEN }}` and never written to a file (invariant 11).
  A fork PR, or a repository with no secret, gets an empty value: the step prints `skipped: no COPILOT_GITHUB_TOKEN` and exits 0,
  green but visibly skipped. The test never prints the token or the CLI's environment.
- **Version pinned as data.** `copilot.ci_version` in `extensions.json` (`1.0.114`), read with `sed` like `mods.ci_version`;
  `npm install -g @github/copilot@<version>` (Node from the runner image). Raising it is a pull request that must pass the job.
  `schema` holds the key the way it holds `mods.ci_version`.
- **`.claude/.ci/CopilotLiveTest.java`**, a single-file Java program (`java`, `git`, `curl` only, plus the `copilot` it is run
  against). It builds a scratch project **outside the repository**: `ArchHook.java export` of a blueprint into a temp directory
  (the way `export-determinism` does), an approved synthetic spec, a `.env` holding a marker string, a `Domain.java` file. Then,
  per scenario, `copilot -p "<fixed prompt>" --allow-all-tools --log-level debug` with `COPILOT_GITHUB_TOKEN` in the
  environment and a 180 s limit, retried once when the model did not call the tool. It asserts **on the filesystem and on the
  hooks' own trail**, never on the model's words:
  1. *native edit of a frozen spec* → the spec file is byte-for-byte unchanged and `guard write` logged a refusal;
  2. *native create of a file under the open phase's frozen folder* → the file does not exist;
  3. *a shell read of `.env`* (`cat` on ubuntu, `Get-Content` on windows) → the marker string appears nowhere in the CLI's
     output and `guard bash` logged a refusal;
  4. *a native edit of a `.java` file adding a forbidden import* → `check` blocked it (the exported project's `doctor gate`
     would fail on it, or the file is reverted — whichever the hook's contract says; the test reads the contract from the mode);
  5. *the deny reason reaches the model* → the CLI's debug log holds the hook's reason text, not `hook errored`.
  A refusal is read from the `copilot -p` output itself, as captured on 1.0.114: `Denied by preToolUse hook: <reason>` (the
  reason is the hook's own text, which names the rule) and never `(hook errored)`. Scenarios 1–4 assert the filesystem state
  plus "the output holds `Denied by preToolUse hook`"; scenario 5 asserts the output holds the reason text and not `hook errored`.
  Scenario 4 asserts the output holds the refusal or the `check` diagnostic naming the forbidden import, and the exit status of
  `java -jar .claude/hooks/ArchHook.jar check <file>` run by the test afterwards is 2 on the written file.
- **Pitfall the test must not walk into:** a model that declines to call the tool proves nothing. A scenario where the tool was
  never called is `inconclusive`, retried once, and then fails the job — a silent pass is worse than a red one.
- **Cost and privacy.** Five short prompts per OS, synthetic files only, nothing from this repository is sent to the service.
  Credits are spent on the owner's Copilot plan; the schedule is weekly for that reason.
- **Not provable here.** The workflow cannot run until the secret exists; the test itself runs locally against the installed CLI
  and must be run green, and red with one alias removed from `guard.tool_input_aliases`, before the workflow is reported done.

## References

| Claim | Source |
|---|---|
| Translate once at the boundary, one owner per rule | `0169`; `@CLAUDE.md` invariant 2 |
| Lists a mode reads live in `extensions.json` | `@CLAUDE.md` invariant 10 |
| A refusal reaches the Copilot model as the flat object on stdout | `0170` |
| The secret-read list, shapes and the `0063` bar for an unreadable target | `0173`; `0063` |
| The `powershell` field and its form | `0206` |
| A hook is one process, no chain | `0168`; `extensions.json` `command_forbidden_chars` |
| Captures of 1.0.114 | this record, § Capture |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java`, `ArchHook.jar` | `canonicalInput`, called by the write modes; PowerShell branch of `bashSecretRead`; the suffix in the `schema` pair check. Jar rebuilt |
| `.claude/schemas/extensions.json` | `guard.tool_input_aliases`, `guard.secret_read_shapes.powershell_commands`, `settings.archhook_powershell.suffix` and the `$comment` of each |
| `.claude/settings.json`, `project-bootstrap/templates/settings.json.example` | the `powershell` field of every ArchHook entry gains the suffix; `_comment` |
| `.claude/.ci/ApplyPatchTest.java`, `SecretReadTest.java`, `HookShellFormTest.java` | the cases above |
| `CLAUDE.md` | § Commands: the `guard write`, `guard bash` rows name the object shape and the PowerShell readers |
| `docs/pt-br/13-github-copilot.md`, `docs/en/13-github-copilot.md` | the 1.0.114 captures, the suffix, what stays uncovered |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | part 2: a PowerShell read of a secret refused; the `;` of the suffix |
| `.claude/decisions/0206-…md`, `0169-…md`, `0173-…md` | one line each, pointing here |
| `.claude/.ci/CopilotLiveTest.java`, `.github/workflows/copilot-live.yml` | the live check; `.github/workflows/mods.yml` scopes its `ci_version` sed to the `mods` block, since `copilot.ci_version` has the same key |
| `docs/*/07-ci-validate.md`, `.claude/skills/claude-code-architect-designer/references/ci-coverage.md` | the new workflow, step by step |

Goes to the generated project: **yes** — `export` copies `ArchHook.java`, the jar and `extensions.json` whole and writes the settings
template; `arch-adopt` writes it through the same `export`. Not covered, for lack of a capture: `MultiEdit`, `NotebookEdit`, the
camelCase write envelope of `.github/hooks`, VS Code's `run_in_terminal`, `[IO.File]::ReadAllText`, a path built by expansion.

## CI coverage

Run on Windows 11 with JDK 21 and Copilot CLI 1.0.114 (the CI matrix itself, ubuntu and windows, has not run: it needs the `COPILOT_GITHUB_TOKEN` secret).

| Check | Where it runs | Result |
|---|---|---|
| `ArchHook.java schema` (the `powershell` suffix, the `;` check, settings files) | `validate.yml` › `hooks-cross-platform`, existing step | exit 0 |
| `ArchHook.java build --verify` | same | exit 0 |
| `ApplyPatchTest` (native `{path, old_str, new_str}` and `{path, file_text}` through `guard write`, `check`, `schema`, `audit file`) | same job, existing step | green; red (exit 1) with the four `tool_input_aliases` lists emptied |
| `SecretReadTest` (18 PowerShell refusals, 11 admissions) | same | green; red (exit 1) with `powershell_commands` emptied |
| `HookShellFormTest` (the `powershell` field with its suffix, launched with `-EncodedCommand`; `guard bash` force push exits 2; four `schema` cases) | same | green, 26 registrations; red (exit 1) with the suffix dropped from one entry |
| `BashGuardTest`, `DenyReasonTest`, `SweepTest`, `SkillTerritoryTest` | same | green |
| `export` twice for `clean-architecture-single-module` | `validate.yml` › `export-determinism` | identical trees; `schema` green inside the exported tree |
| `CopilotLiveTest` against the real CLI, five scenarios | new workflow `copilot-live.yml`, job `copilot` | green locally with the CLI's stored login (`COPILOT_LIVE_STORED_LOGIN=1`); red (exit 1) with `path` removed from the `file_path` aliases: scenario 1 changed the approved spec and scenario 2 created a file in the frozen folder |

Not run: the workflow `copilot-live.yml` (no secret), and `HookShellFormTest` on ubuntu and macos (`pwsh` branch skipped there when absent).

Findings of the live run, worth a later record: a PowerShell write (`Add-Content`, `Set-Content`, `Out-File`) onto an approved spec is **not** refused by `guard bash`, only `>`/`>>`, `tee`, `sed -i` and the other shapes of `guard.bash_write_shapes` are; scenario 5 therefore uses `echo x >> <spec>`. The `Stop` sweep does catch the written file after the turn. Also, `copilot -p` under `--allow-all-tools` alone denies a native create ("no one to approve write"): the test adds `--allow-all-paths`, inside the scratch project only, and the prompt of the create scenario is one line, because `copilot.cmd` cuts a prompt at its first newline.

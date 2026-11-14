# 0173 · A read of a secret path is refused by `guard`, under the Copilot CLI too

- **Date:** 2026-10-17
- **Scenario:** "Forma 7. lessons-learned-022 § 8: o deny Read(./**/*.env) não tem equivalente em arquivo
  no Copilot, e 'wc -l .probe/e1.env' rodou sem negação. Avalie se o guard (bash e read) deve negar
  leitura de caminhos de @.claude/rules/secrets.md como hook, valendo nos dois runtimes, e com que lista
  (dados em extensions.json, invariante 10). Use o payload Read do Copilot com 'path', não 'file_path'
  (§ 6). Pese o falso positivo (.env.example) e o fato de que, sem o comando 3, a negação chega muda ao
  modelo" (lessons-learned-022 § 8, E1; command 6).
- **Decision:** Form 7c + 7a.
  - `guard read`, a new phase of `guard` in `.claude/hooks/ArchHook.java`, refuses a file read of a
    path `guard.secret_paths` lists; `guard bash` refuses a shell read of one (`bashSecretRead`).
  - `guard.secret_paths` and `guard.secret_read_shapes` in `.claude/schemas/extensions.json` own the
    list and the shapes. `schema` holds the `Read(...)` lines of `permissions.deny` in both settings
    files equal to the list.
  - The `Read` registration exists only in `.github/hooks/archhook.json` of the generated project,
    from `project-bootstrap/templates/copilot-hooks.json.example` (`export.overwrite`), held by the new
    `copilot_hooks` block. Claude Code reads no such file and keeps `permissions.deny` alone for `Read`.
- **State:** approved by Lucas Fernandes, on 2026-10-17. Unverified under Copilot until step E1′ of
  `lessons-learned-022/15-roteiro-teste-copilot.md` runs.

## Reproduced on disk

| Claim | Where it holds, at `9a47647` |
|---|---|
| Under Copilot CLI 1.0.98, `wc -l .probe/e1.env` ran with no denial. Only the model's choice of `wc` kept the content out | `lessons-learned-022/evidence/E1-claude-code.txt`, run 2 |
| Copilot reads no `permissions` block from a project file, only `managed-settings.json` or `--deny-tool` per session | `docs/pt-br/13-github-copilot.md` § 2.7, § 7 row `permissions.deny` |
| The six `Read(...)` globs of `permissions.deny` are written twice, in `.claude/settings.json` and in `project-bootstrap/templates/settings.json.example`, with no owner and no drift check | both files, `permissions.deny` |
| No hook is registered on `Read` in either settings file | both files, `hooks.PreToolUse` |
| `.github/hooks/*.json`, camelCase `preToolUse`: the Copilot CLI sends `toolName: "view"`, `toolArgs.path`, an absolute path | `captures/probe-single/pretooluse-camel-copilot-cli-shellform.jsonl` |
| The VS Code reads the same file and sends `tool_name: "read_file"`, `tool_input.filePath` | `captures/probe-single/pretooluse-camel-vscode.jsonl` |
| The probe in `.github/hooks/probe.json` wrote to a relative `.probe/` path under both: the hook's working directory is the project root | `15-roteiro-teste-copilot.md` § 1.4 step 3; the captures above |
| `ArchHook` resolves `ROOT` from `CLAUDE_PROJECT_DIR`, else `.` | `ArchHook.java:45` |
| The reason of a refusal already reaches the Copilot model: every `exit 2` goes through `blockExit()`, which repeats it on stdout in the flat shape (command 3 of lessons-learned-022) | `0170`; `ArchHook.java` `blockExit` |
| One `guard` spawn with nothing to judge costs 0.07 s, measured three times | `java -jar .claude/hooks/ArchHook.jar guard write`, this session |
| `guard bash` reads only write shapes and the force push. It judges no read | `ArchHook.java` `guardBash`; `extensions.json` `guard.bash_write_shapes` |

### What Claude Code 2.1.296 denies today, under `Read(./**/*.env)`

Measured in this session on a file that does not exist, so nothing could leak:

| Command | Claude Code |
|---|---|
| `cat x.env`, `wc -l x.env`, `ls x.env` | denied |
| `grep -qs '^DB_PASSWORD=' x.env`, relative and absolute | denied |
| `test -f x.env` | runs |
| `docker compose --env-file x.env version` | runs |
| `cat x.env.example` | runs: `*.env` does not match `.env.example` |

**Found on the way, not decided here.** Step 4.5 of `docker-architect` runs
`grep -qs '^DB_PASSWORD=' .env || printf … >> .env` (`.claude/skills/docker-architect/SKILL.md:244`).
Claude Code already denies it. The fix belongs to `docker-architect` and gets its own command.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 7 — mandatoriness | A secret that reaches the model reaches the provider. It is irreversible (`@.claude/rules/secrets.md` § When a secret leaks) | 1–6, 9 |
| 15 — reaction | Block, before the call | `PostToolUse`; Form 8 alone (Copilot reads no project `permissions`) |
| 16 — existing mode | `guard` already runs before every `Bash` call. A read needs a new phase, `guard read` | — |
| Shell shapes (user) | Content readers plus `wc`: `cat`, `head`, `tail`, `less`, `more`, `grep`, `rg`, `awk`, `sed` without `-i`, `cut`, `sort`, `nl`, `od`, `xxd`, `strings`, `base64`, `diff`, `source`, `.`, and the input redirect `<`. `ls` and `test` stay free: they reveal only that the file exists | the "any operand" shape, which denies `docker compose --env-file .env up` |
| Path list (user) | The six current globs, moved to `extensions.json` as their single owner. `permissions.deny` becomes derived, and `schema` fails on drift | widening to `*.key`, `*.jks`, `*.pfx`, `.env.local` now |
| Read registration (user) | Copilot only, in `.github/hooks/`. Claude Code keeps `permissions.deny` alone for `Read`, at no process | `Read` in both settings files |
| 8 — destination | The generated project. The generator stays Claude Code only (`roadmap.md` § Multi-agent) | a `.github/hooks/` file in this meta-repo |
| 17 — CI | A new `SecretReadTest`, in `validate`, beside `BashGuardTest` | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `guard read` + read shapes in `guard bash` + `guard.secret_paths`; `Read` registered in `.github/hooks/archhook.json` of the generated project | 7 | **Approved** |
| 2 | option 1, with `Read` registered in both settings files instead | 8 | Rejected by the user — a spawn per `Read` in Claude Code, where `permissions.deny` already refuses the same paths |
| 3 | read shapes in `guard bash` and the list owner only, no `Read` hook | 5 | Rejected — leaves `view` of `.env` free under Copilot |
| 4 | create nothing; record the fact in `13-github-copilot.md` | 4 | Rejected — a guarantee was available and the leak is irreversible |

### Option 1 — `guard read` under the Copilot runtimes (score 7)

**Motivator:** axis 7, with the user's registration answer.

**What it writes:**

- `guard.secret_paths` in `extensions.json`: the six permission-rule bodies (`./**/*.env`, …). `schema`
  fails when a settings file's `permissions.deny` lacks `Read(<entry>)`, or holds a `Read(...)` the list
  lacks. Same shape as `settings.blocked_plugins` (`0138`).
- `guard.secret_read_shapes` in `extensions.json`: the command names and the input redirect above.
- `guard read` (new phase of `guard`): reads the path from `tool_input.file_path`, `tool_input.path`,
  `tool_input.filePath` or `toolArgs.path` (object or JSON string), resolves it against `ROOT`, and exits
  `2` through `blockExit()` when it matches the list. A path outside `ROOT` is not judged, as in
  `./**`. A path it cannot read exits 0.
- `guard bash`, the same list over every literal operand of a read shape, and the target of `<`. The
  bar of `0063` holds: a target with an unresolvable marker is skipped. A write target (`>`, `>>`, a
  `bash_write_shapes` target) is never a read, so `cp .env.example .env` and `printf … >> .env` pass.
- `project-bootstrap/templates/copilot-hooks.json.example`, copied to `.github/hooks/archhook.json` by
  `export.copy`: `version: 1`, one `preToolUse` entry, `matcher: "view|read_file"`, with `bash` and
  `powershell` both set to `java -jar .claude/hooks/ArchHook.jar guard read`. No `CLAUDE_PROJECT_DIR`.
- `schema` validates that file from a new `copilot_hooks` block (events, entry fields, the command
  pattern), invariant 10.

**Pros:** closes E1 for `view` and for the shell, in the Copilot CLI. Covers the VS Code `read_file` too,
which reads no `.claude/settings.json` by default. No process in Claude Code for a `Read`. One list
for both runtimes, where today two files hold it with no check.

**Cons:**

- A second hook file, with its own event names and a block in `schema` to hold it.
- A false positive by design: `wc -l .env` is refused though it reveals no content (Claude Code
  parity). The way out is in the message.
- The `matcher` under the VS Code is unverified, and so is `toolArgs` as a string.
- Not covered: Copilot `Grep`/`Glob` (payload never captured), the VS Code `run_in_terminal`, a
  recursive `grep -r` with no literal path, a symlink to a secret file.

**Points cut in the rubric:** 5 (a second hook file and its schema block), 6 (no `.github/hooks/`
precedent), 9 (a spawn per `view` under Copilot, and a refusal of `wc` that is sometimes wrong).

**CI:** new `.claude/.ci/SecretReadTest.java`, step in `validate.yml` next to `BashGuardTest`. It spawns
the jar on the captured payloads (`view` + `toolArgs` object and string, `read_file` + `filePath`,
Claude `Read` + `file_path`), on `.env.example`, on the shell cases of the table above, and checks the
flat reason on stdout (`0170`). Plus a `schema` case on a settings file with one `Read(...)` removed.

### Option 2 — `Read` in both settings files (score 8)

Option 1 without the second hook file: `PreToolUse` `matcher: "Read"`, no `if`, in both settings
files. The Copilot CLI sends `Read` + `path` there (§ 6). Rejected by the user: Claude Code would pay a
spawn per `Read`, about 70 ms, for a refusal `permissions.deny` already makes, and the VS Code stays
uncovered.

### Option 3 — shell only (score 5)

Read shapes in `guard bash` and the list owner, no `Read` hook. Leaves `view` of `.env` free under
Copilot, which is the most direct way in. Points cut: 4, 7, 9.

### Option 4 — create nothing (score 4)

Record E1 in `13-github-copilot.md` only. A guarantee was available and the leak is irreversible.
Points cut: 1, 4, 7, 8, 9.

## References

| Claim | Source |
|---|---|
| A rule that must always hold is a hook or a `deny` | `CLAUDE.md` invariant 6 |
| Lists a mode reads live in `extensions.json`; a file that displays one is derived | `CLAUDE.md` invariant 10; `0138` (`blocked_plugins`) |
| A shell parser skips what it cannot read | `0063`; `extensions.json` `guard.bash_write_shapes.$comment` |
| A refusal reaches the Copilot model only as the flat object on stdout | `0170`; `13-github-copilot.md` § 2.5 |
| Copilot loads `.github/hooks/*.json` and `.claude/settings.json` | `13-github-copilot.md` § 2.5 |
| Hooks are shell form, one process per entry | `0168` |
| Where a secret may live; a local `.env` is untracked | `@.claude/rules/secrets.md` |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java`, `.claude/hooks/ArchHook.jar` | `guard read` (`guardRead`), `bashSecretRead` called from `guardBash`, `secretPath` (root-level `./**/`, real path of a symlink), `refuseSecretRead` through `blockExit()`; the camelCase envelope sets the event to `PreToolUse` so the flat reason reaches stdout. `schema`: the `permissions.deny` drift check in `checkSettings`, and `checkCopilotHooks`. Jar rebuilt |
| `.claude/schemas/extensions.json` | `guard.secret_paths`, `guard.secret_read_shapes`, the `copilot_hooks` block, and the `export.overwrite` entry writing `.github/hooks/archhook.json` verbatim |
| `.claude/skills/project-bootstrap/templates/copilot-hooks.json.example` | New: one `preToolUse` entry, `matcher: "view\|read_file"`, `bash` and `powershell` both `java -jar .claude/hooks/ArchHook.jar guard read` |
| `.claude/settings.json`, `project-bootstrap/templates/settings.json.example` | `_comment` only: the `Bash` entry also refuses a shell read, and the `Read(...)` lines are derived. No registration changed |
| `CLAUDE.md` | § Commands: the `guard bash` row names the read refusal; a `guard read` row |
| `docs/pt-br/13-github-copilot.md`, `docs/en/13-github-copilot.md` | § 2.7: the E1 observation and this decision. § 7: the `permissions.deny` row split into read (guaranteed in the CLI for the listed paths, with what passes) and force push |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | Part 2, the guards: a read of `.env` refused by the shell too, and what passes |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | Node H27 and the step row |
| `.github/workflows/validate.yml` | Step `a read of a secret path is refused under the Copilot runtimes too`, job `hooks-cross-platform` |
| `.claude/lessons-learned/lessons-learned-022/15-roteiro-teste-copilot.md` | Step E1′, the manual retest |
| `.claude/lessons-learned/lessons-learned-022/comandos-designer.pt-br.md` | Command 9, the `docker-architect` § 4.5 finding |

Goes to the generated project: **yes**. `export` copies `ArchHook.java`, the jar and `extensions.json`
whole, writes the settings template, and now writes `.github/hooks/archhook.json` (checked by an export
into an empty directory: the file is there, `schema` passes inside it, and `guard read` refuses a `.env`
with no `CLAUDE_PROJECT_DIR`). `arch-adopt` writes it through the same `export`.

Not covered, by design or for lack of a capture: the Copilot `Grep`/`Glob` tools, VS Code's
`run_in_terminal`, `grep -r` with no literal path, `$(cat .env)` inside another command's argument, a
path built by expansion. The VS Code `matcher` and `toolArgs` as a string are unverified live.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › a read of a secret path is refused under the Copilot runtimes too` (`.claude/.ci/SecretReadTest.java`) | `guard read` on the captured `view` (object and string `toolArgs`), `read_file` + `filePath` and `file_path` payloads, run with no `CLAUDE_PROJECT_DIR`, flat reason on stdout; near misses admitted; `guard bash` readers refused and `test -f`, `ls`, `docker compose --env-file`, writes, globs and expansions admitted; the list is data; `schema` refuses a `deny` drift both ways and a malformed Copilot hook file | green, 42 checks · red with `toolArgs` unread and the drift check off: 6 checks failed by name |
| `validate · hooks-cross-platform › BashGuardTest`, `DenyReasonTest`, `HookShellFormTest`, `ApplyPatchTest`; `PluginBlockTest`, `ExportProvenanceTest`, `SkillTerritoryTest`, `AgentTerritoryTest`, `SweepTest`, `InjectionPathTest` | No regression in the neighbours | green on the tree |
| `validate › frontmatter schema` | The shipped settings files and the Copilot template pass the new checks | `ArchHook.java schema` exit 0; `claude plugin validate .claude/skills` passed |

What CI cannot prove: that the Copilot CLI and the VS Code load `.github/hooks/archhook.json` and match
`view|read_file`. That is step E1′.

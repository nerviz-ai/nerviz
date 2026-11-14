# 0171 · A manual skill is refused at the skill call unless the user's prompt named it, in both runtimes

- **Date:** 2026-10-17
- **Scenario:** "No Copilot CLI, `/new-feature <pedido>` com disable-model-invocation: true não inicia: o
  modelo responde 'digite /new-feature' em loop. [...] (1) Desenhe o experimento que isola a causa
  (new-feature encurtado; arch-doctor com argumento) para eu rodar. (2) Decida se o flag fica no
  new-feature e qual guarda determinística substitui o flag sob o Copilot nas skills manuais que
  escrevem ou publicam" (lessons-learned-022 § 5, B4 and B5).
- **Decision:** Form 7c + 7a.
  - `guard call` in `.claude/hooks/ArchHook.java` refuses a skill call to a prompt-only skill when
    the turn's prompt did not open it (`guardRefusePromptOnly`).
  - It reads the skill tool's names (`guard.skill_tools`) and the prompt-only set
    (`guard.prompt_only`) from `.claude/schemas/extensions.json`.
  - The `guard call` matcher in both settings files gains `skill`, and `schema` holds it to
    `guard.skill_tools` (`checkSkillToolMatcher`).
  - The flag stays on `new-feature` until the experiment below settles its cause, through the
    table in § The flag on `new-feature`.
- **State:** approved by Lucas Fernandes, on 2026-10-17

## Reproduced on disk

| Claim | Where it holds, at `ff293aa` |
|---|---|
| Under Copilot, the skill call reaches `.claude/settings.json` hooks as `tool_name: "skill"`, lowercase, with `tool_input.skill` | 7 lines in `lessons-learned-022/captures/probe-single-shellform/run-{B2,B3,B4-3,B5-noflag,D3-1}/pretooluse-pascal.jsonl` |
| `guard call` only recognizes `"Skill"`, so under Copilot it judges nothing. The build-class refusal during a design run is inert there too | `ArchHook.java` `guardCall`: `if ("Skill".equals(tool))` |
| The `guard call` matcher is `Skill\|Task\|Agent`. Whether Copilot matches it case-insensitively against `skill` is not verified | `project-bootstrap/templates/settings.json.example`, `.claude/settings.json` |
| `guard prompt` already opens a phase named after the `/<skill>` the prompt starts with | `ArchHook.java` `guardPrompt` |
| Copilot reads no `permissions` block from a project file. Only `managed-settings.json` (enterprise) or `--deny-tool` per session | `docs/pt-br/13-github-copilot.md` lines 179, 235, 248; lessons-learned-022 § 8 (E1) |
| `new-feature` and `new-feature-implement` tell the model, in their body, to "print the exact command for the user to type — don't attempt the call". `arch-doctor` says only "Explicit invocation only" | `.claude/skills/new-feature/SKILL.md:19-22`, `.claude/skills/new-feature-implement/SKILL.md:20-22`, `.claude/skills/arch-doctor/SKILL.md:8` |
| The generated root `CLAUDE.md` row for `new-feature` says "the model can't invoke it". The `arch-doctor` row says only `/arch-doctor` | `project-bootstrap/templates/root.CLAUDE.md.example:62,64` |
| `/new-feature` with no argument also failed under the flag | `lessons-learned-022/evidence/B5-copilot-cli.txt`, attempt 4 |
| Without the flag, `/new-feature` still refused twice before it started on the third attempt | same file, attempt 5 |
| `new-feature/SKILL.md` is 63,568 bytes. The next largest is `persistence-architect` at 45,486 | `ls -S .claude/skills/*/SKILL.md` |
| `git-publish` carries no flag. It is chained after `/init-project` and after the executor | `.claude/skills/git-publish/SKILL.md`; root `CLAUDE.md` routing |

Two things change the scenario's framing:

- **A third candidate.** The scenario names the argument and the size. The model's own words in the loop
  ("This skill is manual-only here. Type /new-feature to run it.") are the body's instruction, almost
  verbatim. That instruction sits in exactly the two skills where the loop is expected, and not in
  `arch-doctor`.
- **The argument is already weaker.** B5 attempt 4 failed with no argument.

## Experiment — isolates the cause (run by the user)

**Setup**, identical for every cell:

- The copy `probe-single-shellform`, Copilot CLI 1.0.98, and the same model as lessons-learned-022.
- The two `preToolUse` probes of that run.
- One more PascalCase probe in `.claude/settings.local.json`, on `UserPromptSubmit`, that writes its raw
  stdin.
- **Each attempt in a new session.**
- Before each cell, restore with `git checkout -- .claude CLAUDE.md`.

B5 showed the outcome is not deterministic: without the flag, the first start came on the third
attempt. So:

- A cell gets **3 attempts**, and **5** when it is the deciding cell of a row in the reading table.
- A cell **starts** when the probe records `skill{<name>}`, or the skill's step-0 commands.
  - `new-feature`: the entry guardrail's `git status`.
  - `arch-doctor`: the two injected `ArchHook` commands.
- Record the attempt number of the first start, or `none`.

### E0 · What the guard needs (runs first; needed whatever the cause)

| Cell | Do | Read |
|---|---|---|
| E0a | Type `/arch-doctor`, then, in another session, the plain prompt `oi` | Does the `UserPromptSubmit` probe fire? Does `prompt` hold the literal `/arch-doctor`? Is its `session_id` equal to the next `preToolUse`'s? |
| E0b | Type `/new-feature cadastrar cliente`, with the flag removed from the copy, until it starts | Does `prompt` hold `/new-feature cadastrar cliente`? Under the same `session_id` as the `skill{new-feature}` call? |
| E0c | Add two probes on `PreToolUse`, one with `"matcher": "Skill"` and one with `"matcher": "skill"`. Repeat E0b | Which of the two fires on `tool_name: "skill"`? |

### E1 · From the failing side: `new-feature`, flag on, one factor removed

| Cell | Change in the copy | Invoke |
|---|---|---|
| N0 | none (control, repeats B5) | `/new-feature cadastrar cliente` |
| N1 | **Text off.** Delete the `**Manual only.**` paragraph of `new-feature/SKILL.md`. In the root `CLAUDE.md` row, replace "manual only: the user types `/new-feature <description>`, the model can't invoke it" with "`/new-feature <description>`". Size drops by ~400 bytes only | same |
| N2 | **Size off.** Keep the frontmatter, `## Why this is Form 2`, the `**Manual only.**` paragraph, `## Contract` and the entry guardrail step. Delete the procedure after it until the file is under 20 KB. `CLAUDE.md` unchanged | same |
| N3 | **Argument off.** Nothing changed | `/new-feature`, empty argument box, Enter |
| N4 | N1 + N2 together. Only if N1 and N2 both fail | `/new-feature cadastrar cliente` |

### E2 · From the working side: `arch-doctor`, flag on, one factor added

| Cell | Change in the copy | Invoke |
|---|---|---|
| A0 | none (control) | `/arch-doctor` |
| A1 | **Argument on.** Add `argument-hint: "[verbose]"` | `/arch-doctor verbose` |
| A2 | **Text on.** Paste `new-feature`'s `**Manual only.**` paragraph into the body, with the name changed. Make the root `CLAUDE.md` row read "manual only: the user types `/arch-doctor`, the model can't invoke it" | `/arch-doctor` |
| A3 | **Size on.** Append a `## Appendix` of neutral text (copy `references/` tables from any skill) up to 63,600 bytes | `/arch-doctor` |

### E3 · Does Copilot honour the flag on the model's own call

| Cell | Change | Prompt (no slash) | Read |
|---|---|---|---|
| F1 | none (`arch-doctor` keeps the flag) | "Use the skill tool to run the arch-doctor skill." | Is a `skill{arch-doctor}` call recorded? If it is, does it run, or does Copilot refuse it? Or does the model read the file through `view`? |
| F2 | flag removed from `arch-doctor` | same | Control for F1 |

### Reading table

| Observed | Cause | Flag on `new-feature` |
|---|---|---|
| N1 starts, A2 fails, N2 and A3 behave like their controls | **Text** | Stays. Rewrite the paragraph in `new-feature` and `new-feature-implement`, and the two `CLAUDE.md` rows, so a model holding the body after `/<name>` reads that it is already running |
| N2 starts, A3 fails | **Size** | Stays. Move procedure out of `SKILL.md` into `references/`, below the size that failed. Re-measure the limit before choosing it |
| A1 fails, N3 starts | **Argument + flag** | Leaves `new-feature` and every manual skill with an `argument-hint` that runs in a generated project. The guard below holds them, through `guard.prompt_only.extra` |
| N4 alone starts | **Text × size** | Both fixes of rows 1 and 2 |
| F1 records a call that runs | Copilot ignores the flag on the model's call | Confirms the guard is the only thing that holds there |
| F1 records a call that Copilot refuses, or none | Copilot honours the flag somewhere | The guard is still needed for the `view` path. `13-github-copilot.md` is corrected |
| Nothing isolates | Not settled | The flag stays. The guard ships anyway, because it does not depend on the cause |

E0 decides the guard's own conditions:

- **E0a or E0b negative** (no prompt hook, or a different `session_id`): the guard ships fail-open, and
  under Copilot it is inert until a later decision.
- **E0c**: decides whether the matcher needs `skill` spelled out.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 7 — Mandatoriness | A manual skill that writes or publishes must not be started by the model. Under Copilot only text holds that today | 1–6, 9 as the answer |
| 14 — Lifecycle event | `PreToolUse` of the skill tool. That is the same event and mode as today's build-class refusal | — |
| 15 — Reaction | Block, with the reason on stdout as well (0170), and the command for the user in it | 8 (`permissions` is not read by Copilot from a project file, and is redundant with the flag under Claude Code) |
| 16 — Existing mode | `guard call` already runs on every skill call, and `guard prompt` already records `/<skill>` | a new 7c mode |
| 8 — Destination | Both: `ArchHook.java`, its jar and `extensions.json` travel through `export`, and the matcher goes into the template | — |
| 9 — Integration, `git-publish` | Out of the prompt guard. It is auto-chained by design, and a prompt-only rule would break the chain. Option A, chosen by the user | — |
| 9 — Integration, `arch-adopt` | The call only, not the `export` effect. The user chose the same coverage as the flag | option 2 |
| — Runtime without `UserPromptSubmit` | Fail-open. Refuse only when `guard prompt` has run in this session, proven by its baseline file | — |
| — Source of the list | The flag ∪ `guard.prompt_only.extra`. Today the extra list is empty | — |
| 17 — CI | `SkillTerritoryTest` already drives `guard call` through the jar, and gains the cases | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `guard call` refuses a prompt-only skill the prompt did not open; skill tool names as data; matcher gains `skill`; fail-open | 8 | **Approved** |
| 2 | Option 1 + `guard bash` refuses `ArchHook export` outside an `arch-adopt` phase opened by the prompt | 6 | Rejected by the user. Wider than the flag, and it needs an exception for `project-bootstrap` |
| 3 | `permissions.ask` on `Skill(arch-adopt)`, `Skill(new-feature-implement)`, `Bash(git push:*)` | 3 | Rejected. Copilot reads no `permissions` from a project file, and under Claude Code the flag and the existing `ask` already hold |
| 4 | Create nothing: rewrite the text and the root `CLAUDE.md` § Under another agent | 4 | Rejected. A guarantee is available at zero extra process (invariant 6) |

### Option 1 — `guard call` prompt-only (score 8)

**Motivator:** axes 7 and 16. `guard call` already spawns on every skill call, so the new check costs no
process.

**Shape:**
- **Prompt-only set.** The skills whose frontmatter has `disable-model-invocation: true`, plus
  `guard.prompt_only.extra`, which is empty today.
- **Skill tool names.** `guard.skill_tools`, `["Skill", "skill"]`.
- **The refusal.** A call to a skill in the set is refused (`blockExit`, 0170) when all three hold:
  - the call's tool name is in `guard.skill_tools`;
  - this session's `guard prompt` baseline exists, so a prompt hook ran (fail-open);
  - the open phase's opener is not that skill.

  The message names the rule and gives the way out: "`/<skill>` runs only when the user types it.
  Print `/<skill> <args>` for the user and stop."
- **The same name list everywhere in `guardCall`.** The build-class refusal starts holding under Copilot
  too. It runs **before** the prompt-only check, so a flagged build-class skill called mid-design
  (`sonar-lessons`) still gets the build message, and the existing case still proves what it names.
- **The matcher stays a plain list, `Skill|skill|Task|Agent`.**
  - Claude Code matches a matcher of letters and `|` exactly, as a list (`@claude-help.md:887-889`),
    so it never reaches an MCP tool whose name holds `skill`.
  - Copilot reads `matcher` as a regex it anchors itself, `^(?:…)$`
    (`docs/pt-br/13-github-copilot.md:159`).
  - So `^(...)$` was considered and dropped: it would only move Claude Code into regex mode, with
    the same result.

**Pros:**
- Same coverage as the flag gives under Claude Code, in both runtimes.
- One owner of each list.
- The user's own `/new-feature` passes. `guard prompt` opened the phase under that name, and the call
  then joins it.
- It holds even if the experiment removes the flag from `new-feature`, through `extra`.

**Cons:**
- The `view` path stays open: the model reads `SKILL.md` and follows it without a skill call. The flag
  under Claude Code has the same hole. B4 is that path, and text held it there.
- **Mirror of invariant 6.** No model-initiated call to a manual skill was observed under Copilot.
  What was observed is that nothing deterministic stands there. That is accepted only because the check
  rides an existing process.
- Inert where `UserPromptSubmit` does not reach the hook (E0).

**Points cut in the rubric:**
- § 8 criterion 4 (−0.5): bypassable through `view`.
- Criterion 5 (−0.5): one more list, and a cross-check between it and the frontmatter.

**CI:**
- `validate › hooks-cross-platform`, step `SkillTerritoryTest`, gains 14 cases, among them:
  - `skill{arch-adopt}` with no prompt-opened phase is refused (exit 2, stdout carries
    `permissionDecision`);
  - the same call after `guard prompt` with `/arch-adopt` passes;
  - the same refusal under `tool_name: "Skill"`;
  - no baseline (no prompt hook) lets the call pass;
  - the build-class refusal also holds under `tool_name: "skill"`.
- `schema` gains:
  - an `extra` name with no skill on disk fails;
  - an `extra` name that already carries the flag fails.
- The matcher: `schema` holds every group that runs `guard call` to name each `guard.skill_tools` entry.
  It is a claim about files that `extensions.json` can carry, so it is a `schema` check rather than a
  test step, and it also runs inside a generated project.

### Option 2 — call + `export` effect (score 6)

Closes the `view` path for `arch-adopt`, the most destructive skill. It needs a regex in `guard bash` and
an exception list for `project-bootstrap`'s step 6.6 in this meta-repository. A natural-language "run
the export" is refused. The user chose the flag's coverage.

### Option 3 — `permissions.ask` (score 3)

Holds under Claude Code only, where the flag and the existing `ask` on `git push` already hold. It does
nothing under Copilot, which is the runtime the scenario is about. Capped by criterion 1 (wrong form for
the runtime) and criterion 4.

### Option 4 — create nothing (score 4)

The text is what held B4. The scenario is precisely that text is all there is.

## The flag on `new-feature`

Undecided until the experiment, and it **stays** until then. The reading table says what each outcome
changes.

Decision 0007 is respected in every branch:
- the chained pipeline skills (`use-case-design`, `domain-modeling`, the layer architects) keep no flag;
- none of them enters `guard.prompt_only.extra`;
- `/new-feature`'s chaining through the `skill` tool (B5, attempt 5) keeps passing, because the
  callees are not prompt-only.

## References

| Claim | Source |
|---|---|
| `disable-model-invocation: true` hides the skill from the model in Claude Code | `@claude-help.md:458`; 0007 § Verification |
| Pipeline skills stay without the flag so `/new-feature` can chain them | `.claude/decisions/0007-pipeline-skills-invocation.md` |
| A deny's reason reaches Copilot's model only as the flat `permissionDecision` object on stdout | `.claude/decisions/0170-deny-reason-on-stdout-for-copilot.md` |
| `guard call` is the existing place for a call-time refusal | 0092, 0126; `ArchHook.java` `guardRefuseBuildCall` |
| Hooks under Copilot run in shell form from `.claude/settings.json` | `.claude/decisions/0168-hooks-in-shell-form.md` |
| A list a mode reads lives in `extensions.json` | `@CLAUDE.md` invariant 10 |
| Copilot reads no project `permissions` | `docs/pt-br/13-github-copilot.md:179,235,248`; lessons-learned-022 § 8 |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` + rebuilt `ArchHook.jar` | `guardCall` reads `guard.skill_tools`; `guardRefusePromptOnly` and `isPromptOnly`, after the build-class refusal; `checkPromptOnly` and `checkSkillToolMatcher` in `schema`; guard header comment |
| `.claude/schemas/extensions.json` | `guard.skill_tools`, `guard.prompt_only` (`flag`, `extra: []`), each with its `$comment` |
| `.claude/settings.json` | matcher `Skill\|skill\|Agent\|Task` |
| `.claude/skills/project-bootstrap/templates/settings.json.example` | matcher `Skill\|skill\|Task\|Agent` |
| `.claude/.ci/SkillTerritoryTest.java` | 14 cases and a `runSaying` helper that also checks the output |
| `docs/pt-br/13-github-copilot.md`, `docs/en/13-github-copilot.md` | the `disable-model-invocation` row, the surface-table row and item 4: `skill` lowercase, `guard call` as what holds the CLI |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` part 2 | a manual skill the model calls is refused, with the way out |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | `SkillTerritoryTest` row: 76 cases, the new claims |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` § Under another agent | the manual-only bullet names the guard under Claude Code and the Copilot CLI |

Goes to the generated project: **yes**. The jar, the source and `extensions.json` travel through
`export`, and the matcher goes through the template.

Follow-up, not in this decision: `audit call` has the same `"Skill".equals` test (`ArchHook.java` near
line 5458) and could read `guard.skill_tools` too.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › SkillTerritoryTest` | The model's `skill` and `Skill` call to a manual skill is refused, with the reason in the output and `permissionDecisionReason` on stdout. The user's `/arch-adopt` passes under both names. `/new-feature` chains `use-case-design`, and `git-publish` and the pipeline skills are never refused (0007). Another manual skill than the typed one is refused. The build-class refusal holds under `skill`. No prompt hook means fail-open. Every existing case passes unchanged | Green on the tree, 76 of 76. Red with `skill` dropped from `guard.skill_tools`: 3 cases fail by name. Red with `guard.prompt_only.flag` pointing at a field no skill has: 3 cases fail |
| `validate › schema` (`ArchHook.java schema`) | A `guard call` matcher that lacks a `guard.skill_tools` name fails, naming the file and the missing name. An `extra` name with no skill on disk fails, and so does one that already carries the flag | Green on the tree. Red with `skill` dropped from the template matcher: exit 2, by name. Red with `extra: ["no-such-skill", "arch-adopt"]`: both by name |
| `validate › build --verify` | The committed jar is what the source compiles to | Green |

The neighbours were re-run green after the rebuild: `GuardStatusTest`, `SweepTest`, `AuditRenderTest`,
`DenyReasonTest`, `HookShellFormTest`, `ApplyPatchTest`, `BashGuardTest`, `AgentTerritoryTest`,
`SkillShadowTest`, `InjectionPathTest`, `TemplateCommentsTest`, `AgentInstructionsTest`.

Not proven in CI, and cannot be: whether the Copilot CLI delivers `UserPromptSubmit` with the same
`session_id` (E0a, E0b), and whether its matcher needs `skill` spelled out (E0c). The guard is
fail-open on the first, and the matcher spells `skill` either way.

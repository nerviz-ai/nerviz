# 0182 · `/new-feature` and `/new-feature-implement` print `/git-publish` instead of chaining it

- **Date:** 2026-10-23
- **Scenario:** "quero retirar a skill git-publish da orquestracao de new-feature e
  new-feature-implement. Objetivo é diminuir o custo da orquestracao. ate porque o commit pode
  ser feito a qualquer momento pelo usuario. Alem disso ao final da execucao das orquestracoes
  é criado o audit-usage, que acaba ficando em stage do git pois o git-publish foi feito antes.
  Ao final da orquestracao pode adicionar comado de git-publish assim como clear"
- **Decision:** Option 1 — no new file; `/new-feature` and `/new-feature-implement` print `/clear` + `/git-publish <message>[ — paths: …][ — pre-existing: …]` instead of invoking `git-publish`. `/init-project` still chains it.
- **State:** approved by the maintainer, on 2026-10-23

## Reproduced on disk

| # | Fact | Where |
|---|---|---|
| 1 | Both feature flows invoke `git-publish` through the `Skill` tool at their end, in the same turn as the last hand-back | `new-feature/SKILL.md` § Approval; `new-feature-implement/SKILL.md` § 2 step 2 "Success" |
| 2 | The chained `git-publish` costs USD 0.29–0.54 per run: 3–6 calls at a 250–370k context | `0117` § 1 |
| 3 | The run's report is rewritten at `Stop`, and `history.jsonl` is appended when the run closes — at the next prompt or at `SessionEnd` — so a commit made inside the run always precedes its own trail | `ArchHook.java` audit lifecycle comment (`flush` / `close`), `auditRender(…, closing)`; `0094` row 8 |
| 4 | `/clear` fires `SessionEnd`, which runs `audit close`: after it, the whole trail of the run is on disk | `project-bootstrap/templates/settings.json.example` `SessionEnd` |
| 5 | `git-publish`'s class `ops` declares `audited: false`: a typed `/git-publish` opens no run and leaves no report of its own | `extensions.json` `skill_classes.ops`; `AuditRenderTest` |
| 6 | `git-publish`'s `description` tells the model to run it "right after … `/new-feature-implement`" and "when `/new-feature` ends with an approved spec" — left as is, the model keeps chaining it | `git-publish/SKILL.md` frontmatter |
| 7 | The `tests` Stop gate runs while `git diff HEAD` names a `.java`. The chained commit emptied that diff in the implement run's last turn, so the gate never ran there | `ArchHook.java` `tests()` |
| 8 | The guardrail hands the list of pre-existing changes to the chained `git-publish`; a `/git-publish` typed after `/clear` sees nothing of that run | `new-feature/references/entry-guardrail.md` § 2, fourth case |
| 9 | An approved spec left uncommitted is listed as pre-existing work by the implement run's guardrail | same, fourth case: any `git status --porcelain` path outside `.claude/audit-usage/**` |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | The chained publish is the most expensive step per call of both flows (fact 2), and every run ends with its own audit trail dirty (fact 3) | create nothing |
| 2 — Trigger | The person, at any moment after the run; the flow only prints the command | a chained invocation in any form |
| 7 — Mandatoriness | A commit is the person's call; nothing must always hold | Forms 7 · 8 as the answer |
| 8 — Destination | Both: `new-feature`, `new-feature-implement` and `git-publish` travel (`export.skills.include`) | — |
| Order of the printed commands | `/clear` first, then `/git-publish …`, then the next command | `/git-publish` before `/clear` — pays the context of fact 2 |
| Approved spec uncommitted when the implement run starts | Input of the run: out of the pre-existing list, into the `feat(UC-…)` commit | — |
| Pre-existing list | Carried in the printed `/git-publish` line's argument | Report-only — the implement commit would `git add -A` pre-existing work under a `feat` type (lessons-learned-016 § 9) |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Print `/clear` + `/git-publish <context>` at the end of both flows; `git-publish` keeps `/init-project` as its only chaining caller | 9 | **Approved** |
| 2 | Option 1 plus a `guard` refusal of `Skill(git-publish)` while an orchestrator phase is open | 5 | Rejected — a hook against a failure nobody observed |
| 3 | Keep the chain, run `git-publish` with `context: fork` | 3 | Rejected — a fork cannot hold the two gates, trail still dirty |
| 4 | Create nothing | 3 | Rejected — solves neither half |

### Option 1 — print the commit, don't chain it (score 9)

**Motivator:** axes 1 and 2 — the cost and the dirty trail both come from the commit running
inside the run, and the commit is the person's call.

**What changes:**

- `/new-feature` § Approval, on **Approve**: set `status: approved`, no `Skill` call, final report
  ending with
  ```
  /clear
  /git-publish docs(UC-NNN-<slug>): approved spec — paths: docs/use-cases/UC-NNN-<slug>/ docs/use-cases/BACKLOG.md[ — pre-existing: <paths>]
  /new-feature-implement UC-NNN-<slug>
  ```
- `/new-feature-implement` on success: compose installer as today, no `Skill` call, final report
  ending with `/clear`, `/git-publish feat(UC-NNN-<slug>): <summary>[ — pre-existing: <paths>]`,
  and `/new-feature <next description>`.
- `entry-guardrail.md` § 2 fourth case: the list is no longer handed to a chained skill but
  printed into the final `/git-publish` line, collapsed to directories past 20 paths; on an
  implement run, the approved case's own folder and `docs/use-cases/BACKLOG.md` are input, never
  pre-existing.
- `git-publish`: `description` drops the two feature-flow triggers and names the printed line;
  `## Contract` reads the typed argument (`<message> [— paths: …] [— pre-existing: …]`) as the
  same three inputs a caller passed; `## Why this is a skill (Form 1)` keeps Form 1 for its one
  chaining caller, `/init-project`.
- The executor's and `compose-service-installer`'s "before / next: `git-publish`" lines,
  `@CLAUDE.md` routing rows, `README.md` flow diagrams, `docs/*/03-new-feature.md`,
  `docs/*/00-*.md`, `claude-help.md` tree line, `extensions.json` `$comment`s naming the chain.

**Pros:** removes 3–6 calls at 250–370k context per run (fact 2); the commit, when made, carries
the run's whole trail (facts 4–5), so the next run starts clean; the person commits when and
what they choose; same printed-command shape `0135` already uses for `/clear` +
`/new-feature-implement`; no new piece.

**Cons:** the `tests` Stop gate now runs `verify` (without ITs) at the implement run's last turn
and at every later turn until the commit (fact 7) — minutes, zero tokens while green; the
approved spec is no longer committed on its own when the person goes straight to implement
(axis answer: it rides in the `feat` commit); about 20 files of prose; the pre-existing list
travels as text the person copies.

**Points cut in the rubric:** 5 (maintenance) → ~20 files of prose edits, no new piece but a
wide diff.

**CI:** `validate` › `design invariants` › `frontmatter schema` already proves the edited
skills' frontmatter, class and required sections. The change itself is prose ordering in three
skill bodies — nothing testable, because no mode reads whether an orchestrator calls
`git-publish`; Option 2 is what would make it testable.

### Option 2 — Option 1 plus a guard refusal (score 5)

`guard call` refusing `Skill(git-publish)` while a `design_phase` orchestrator is open, data in
`extensions.json`, a case in `SkillTerritoryTest`. Cut: 4 is full, but 5 (a Java mode branch
and data for one call), 9 (a refusal on every model attempt, and a person who asks the model
"commit now" mid-run is blocked) and invariant 6's mirror — a hook against a failure nobody
observed once fact 6 is fixed. Viable only if the model is seen chaining it after Option 1.

### Option 3 — keep the chain, `context: fork` (score 3)

A forked skill starts with a fresh context, so the cost of fact 2 drops. Rejected: a forked skill
runs as a subagent, which `0123` saw without `AskUserQuestion`, and the two gates are the whole
guarantee of `0034`; the trail
stays dirty by construction (fact 3), the second half of the symptom untouched.

### Option 4 — create nothing (score 3)

Declining gate 1 skips the commit, but the skill still loads and reads state at 250–370k context
before asking, and an accepted commit still leaves the trail dirty. Solves neither half.

## References

| Claim | Source |
|---|---|
| Chained publish cost and its cause (context size) | `0117` § 1 |
| Trail written after the run's commit, by construction | `0094` row 8; `ArchHook.java` audit lifecycle comment |
| `/clear` closes the run | `SessionEnd` reason `clear` in `docs/en/claude-code-docs/04-hooks.md`; `SessionEnd` → `audit close` in `project-bootstrap/templates/settings.json.example` |
| `git-publish` opens no run | `extensions.json` `skill_classes.ops` `audited: false`; `AuditRenderTest` |
| Printed next commands as the end of a flow | `0135`; `new-feature/SKILL.md` § Approval |
| "No end of this flow is left without an explicit git instruction" — still held by the printed line | `0037` |
| Two commits for pre-existing work | `0094` row 9a; lessons-learned-016 § 9 |
| A forked skill runs as a subagent, which may lack `AskUserQuestion` | `0123` references table (`@claude-help.md` § Subagents › Limits; the agent reported no `AskUserQuestion`) |
| Form 1 kept: `/init-project` still chains it through the `Skill` tool | `init-project/SKILL.md` step 6; `0034` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/new-feature/SKILL.md` | § Approve prints `/clear` · `/git-publish … — paths: …` · `/new-feature-implement`; no `Skill` call; Contract, Integrates, guardrail pointer, final report and references updated |
| `.claude/skills/new-feature-implement/SKILL.md` | `description`, Contract, Integrates, § 1 (own folder and `BACKLOG.md` never pre-existing), § 2 success path and compose note, § 4 prints `/clear` · `/git-publish feat(…)` · `/new-feature`, references |
| `.claude/skills/new-feature/references/entry-guardrail.md` | `IGNORED` and the pre-existing list go into the printed `— pre-existing:` part; implement leaves out its own input |
| `.claude/skills/git-publish/SKILL.md` | `description`, Why (one chaining caller left), typed-argument grammar, `pre-existing` input, Integration |
| `.claude/agents/java-spring-boot-developer.md` | hand-back note and example no longer say `git-publish` is offered/invoked |
| `.claude/agents/compose-service-installer.md` | `description` and prose: "before the final report", still prints the `/git-publish` line |
| `.claude/skills/docker-architect/references/compose-service-decision.md` | `materialize` row: "before the final report" |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | routing rows for `new-feature-implement` and `git-publish` |
| `CLAUDE.md` | same two routing rows |
| `README.md`, `claude-help.md`, `roadmap.md` | flow diagrams, tree comment, roadmap line |
| `docs/en/03-new-feature.md`, `docs/pt-br/03-new-feature.md` | table, git bullet, both sequence diagrams, pieces table, compose paragraph |
| `docs/en/01-file-types.md`, `docs/pt-br/01-tipos-de-arquivo.md` | compose-installer ordering sentence |

Not touched on purpose: `extensions.json` (its `git-publish` mentions describe `/init-project`,
the `ops` class and the audit trail, all still true; `export.skills.include` keeps the skill),
`settings.json.example` (`Skill(git-publish)` stays allowed for `/init-project`'s chain),
`ArchHook.java` and the jar (comments only describe the past chain), `docs/*/11-pitfalls.md`
(the `opus-5-5` line is a dated measurement).

Goes to the generated project: **yes** — via step 6.6, through `export.skills.include` and the
agents copy; no manifest entry changes.

Goes to the generated project: **yes** — `new-feature`, `new-feature-implement`, `git-publish`
and the executor travel through `export.skills.include` / the agents copy; no new entry.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate` · `design invariants` › `frontmatter schema` | edited skills and agents keep frontmatter, class, required sections and export entries | see the PR |

Nothing testable beyond that: the change is the order of steps in three skill bodies, and no
hook mode reads whether an orchestrator invokes `git-publish`. The `SweepTest` and
`AuditRenderTest` fixtures that name `git-publish` model `/init-project`'s chain and the typed
skill, both unchanged.

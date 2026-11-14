# 0133 · The audit projects how many runs of a piece fit a plan's 5-hour and weekly windows

- **Date:** 2026-10-08
- **Scenario:** "adicione no audit-usage uma expectativa de quantas vezes uma skill auditada pode ser rodada até estourar o limite de tokens: por 5 horas, 1 semana (assim como o usage nativo do claude faz) — agrupado por conta Pro, Max 5x, Max 20x" — narrowed in the interview to "apenas faça um cálculo baseando apenas no uso e execução da skill, comparando como se só ela estivesse rodando"
- **Decision:** option 1 — Form 7c, a change to the existing `audit` mode of `.claude/hooks/ArchHook.java` (render, `summary`, `doctor`), with `audit.plans`, `audit.windows`, `audit.budget_plan`, `audit.plan_limits` and `audit.plans_source` in `.claude/schemas/extensions.json`, and the project-owned `.claude/audit-usage/plan-limits.json`. No new piece
- **State:** approved by Lucas Fernandes, on 2026-10-08 — shipped in the same PR as 0132 (#113)
- **Goes to the generated project:** yes — through `export`, which copies `ArchHook.java`, the jar and `extensions.json` whole; `plan-limits.json` is the project's own and never exported

> **Unit — superseded by `0134-audit-tokens-only-no-usd-pricing.md`.** The budget and the
> division are in billable tokens (`five_hour_tokens`, `weekly_tokens`), not USD: the price
> table blanked every total on each new model, and the projection with it (issue #111). The
> rest of this record stands.

> **Real account usage — reopened by `0194-audit-plan-usage-delta-from-a-personal-status-line-snapshot.md`.**
> Fact 5 no longer excludes it: a status line in the person's own `~/.claude/settings.json`
> writes a snapshot the audit reads, and no project carries a `statusLine`. The projection
> above stands beside the measured delta.

## What the runtime and Anthropic publish

Checked against the official pages, not from memory (a `claude-code-guide` run in this session):

| # | Fact | Source |
|---|---|---|
| 1 | No hook event's input carries plan usage; only `StopFailure` (`rate_limit`) and `Notification` quota types name it, with no number | code.claude.com/docs/en/hooks § Common input fields, § StopFailure input |
| 2 | The status line's stdin is the only place: `rate_limits.five_hour` / `seven_day`, `used_percentage` and `resets_at`, claude.ai Pro/Max logins only | code.claude.com/docs/en/statusline § Available data, § Rate limit usage |
| 3 | No CLI command or flag prints plan usage non-interactively | code.claude.com/docs/en/cli-reference |
| 4 | Limits are not published in tokens or dollars. Max is "5x or 20x the usage of Pro **per 5-hour session**"; paid plans "add weekly limits on top", with no multiplier stated for them | claude.com/pricing |
| 5 | A `statusLine` in a project's `.claude/settings.json` overrides the person's own | code.claude.com/docs/en/statusline § Manually configure |

Facts 2 and 5 are why reading the account's real usage was dropped in the interview: it needs
a status line the generated project would impose on whoever clones it. Fact 4 is why the
window size cannot be written by this repository: there is no published number to write.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | The person wants to plan: how many runs of a pipeline fit a window before the plan stops them. Nothing in the trail answers it today | — |
| Source of usage (user) | **None from the account.** Only what the audit already measures per run, as if the piece were the only thing running | status-line capture of `rate_limits` (snapshots at run start and end) |
| Window size (user) | **The project fills the Pro budget; Max 5x and 20x come from the published multipliers.** Weekly for Max is not published and says so | every plan filled by hand · numbers written into `extensions.json` (not published — the invariant 8 discipline) |
| Unit (user) | **USD**, priced by `pricing.json` — an Opus run weighs more than a Sonnet one, as on the real limit | billable tokens (model-blind) |
| Plans shown (user) | **Pro, Max 5x, Max 20x**, one line each | the person's plan only |
| Where (user) | **The run's report and `/audit-usage`** | — |
| 7 — Mandatoriness | Information, not a guarantee | axes 14–16 do not apply |
| 8 — Destination | Both — `ArchHook.java` and `extensions.json` travel whole; the audit is wired only in the generated project | — |
| 9 — Integration | `pricing.json` is in `export.copy`, so `/arch-adopt` rewrites it: a budget written there is lost on the next update. `audited.json` is the precedent for a trail file the project owns and `export` never names | the budget inside `pricing.json` |
| 16 — Existing mode | `audit` (render and `summary`) already prices every run and piece; this divides | new 7c mode |
| 17 — CI | `validate · hooks-cross-platform › AuditRenderTest` renders a run and runs `summary` in a throwaway project | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `audit` render + `summary` project runs per window from the run's USD; multipliers in `extensions.json`; Pro budget in a project-owned `plan-limits.json` | 8 | **Approved** |
| 2 | The same, with the Pro budget inside `pricing.json` | 4 | Rejected — `export.copy` rewrites `pricing.json`, the budget is lost on update |
| 3 | `/audit-usage` computes the projection from what `summary` prints | 3 | Rejected — the skill never recomputes |
| 4 | Budgets for every plan written into `extensions.json` | 2 | Rejected — no published number to write |
| 5 | Create nothing | 3 | Rejected — the question has no answer in the trail today |

### Option 1 — project the windows from the run's cost (score 8)

**Motivator:** the interview — the person plans against a window, and the trail already
has the run's cost.

**Mechanism.**

- **`extensions.json` › `audit.plans`** — what Anthropic publishes, with the page and the date
  it was read: `pro` ×1, `max-5x` ×5, `max-20x` ×20, each with `five_hour_multiplier` and
  `weekly_multiplier: null` for the two Max plans (not published). Invariant 10: the list of
  plans and their multipliers is data.
- **`.claude/audit-usage/plan-limits.json`** — owned by the project, never named by `export`,
  absent by default, like `audited.json`. Shape: `{"pro": {"five_hour_usd": <n|null>,
  "weekly_usd": <n|null>}}` — the Pro budget in USD at `pricing.json` rates, which the person
  fills from their own observation (the cost at which `/usage` reached 100%). Absent, or a
  `null` → the projection names the field to fill, as the cost cell names an unpriced model.
- **The run's report** — `## 🪟 Plan windows` after the aggregate: for each plan, runs like
  this one per 5-hour window and per week = budget × multiplier ÷ run cost, floored. A
  window is also capped by wall-clock: a 5-hour window holds at most 5 h ÷ the run's active
  duration back to back, and the cell says which limit binds. `—` with the reason where the
  run has no cost (unpriced model), the budget is unset, or the multiplier is not published.
- **`audit summary`** — the same table per audited piece, over its closed runs' mean
  `cost_usd` and mean duration (root runs with `cost_usd`, plus chained pieces' own cost from
  `nodes.jsonl`); `/audit-usage` renders it and never recomputes it.
- **`doctor`** — validates `plan-limits.json` like `audited.json`: only `pro`, only the two
  keys, numbers > 0 or `null`, each bad entry by name.

**Pros:** no new source of truth — the run's cost is already computed and priced; the only
unknown (the window size) is data the person owns, never a number this repository invents.
Fits two precedents: `pricing.json` (a price filled by the project) and `audited.json` (a
trail file `export` never touches).

**Cons:**
- The Pro budget is the person's estimate; every projection inherits its error. The report
  says so in one line.
- "As if only this piece ran": chat use, other sessions and other pieces share the real window.
- Weekly is Pro-only until Anthropic publishes a weekly multiplier for Max.
- A run's cost includes the root's orchestration: projecting a chained piece uses its own
  cost only, which understates what running it alone would cost.

**Points cut in the rubric:** 5 (a new trail file, a section, a `doctor` check), rounded —
8/9 → 8.

**CI:** `validate · hooks-cross-platform › AuditRenderTest` — new cases: with
`plan-limits.json` set and a priced model, the report's table holds the expected runs for
Pro, Max 5x and Max 20x on 5 h, Pro on the week and `not published` for Max weekly; the time
cap binds when the budget alone would allow more runs than fit in 5 h; without the file the
cell names the field to fill; `summary` prints the per-piece table; `doctor` names a bad key.
Red once with the multiplier read from the wrong plan.

### Option 2 — Pro budget in `pricing.json` (score 4)

One file fewer, but `pricing.json` is in `export.copy`: `/arch-adopt` rewrites it on every
update and the budget is lost without a word. Cut: 2 (strains invariant 9's manifest — a
project value in an exported file), 5, 7.

### Option 3 — `/audit-usage` computes it (score 3)

The skill's contract is "never recompute a number the block printed": the arithmetic moves to
the model, which gets it wrong in silence. Cut: 1, 4, 5, 7.

### Option 4 — budgets in `extensions.json` (score 2)

Anthropic publishes no number (fact 4); one written here is a third-party estimate shipped as
data, wrong the day the limits move and as authoritative-looking as a right one. Cut: 2
(invariant 8's discipline → capped), 4, 7.

### Option 5 — create nothing (score 3)

`/usage` itself shows the account's position, not what one piece costs against it; the
question the person asked has no answer in the trail today.

## References

| Claim | Source |
|---|---|
| Plan usage reaches a program only through the status line | code.claude.com/docs/en/statusline § Rate limit usage; § hooks input fields |
| Max = 5x / 20x Pro per 5-hour session; weekly limits unpublished | claude.com/pricing |
| A trail file the project owns, never exported, validated by `doctor` | `@.claude/decisions/0111-audit-opt-out-owned-by-the-project.md`; `extensions.json` `audit.project_overrides` |
| `pricing.json` is rewritten by `export` | `extensions.json` `export.copy` |
| A number that changes outside this repository is never written from memory | `@CLAUDE.md` invariant 8; the `$comment` of `audit-pricing.json.example` |
| `/audit-usage` renders, never recomputes | `.claude/skills/audit-usage/SKILL.md` § What the block already did |
| Every list or number a mode reads lives in `extensions.json` | `@CLAUDE.md` invariant 10 |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` | `planWindows`, `planBudgets`, `planCell`, `planKeys`, `planWindowsSection` (the report's `## 🪟 Plan windows`, after the aggregate), `planWindowsSummary` (`audit summary`'s `### Plan windows`, before Health), `planLimitProblems` and the `Plan limits` line of `doctor` |
| `.claude/hooks/ArchHook.jar` | Rebuilt under JDK 21 |
| `.claude/schemas/extensions.json` | `audit.plans_source`, `plan_limits`, `budget_plan`, `windows`, `plans`, with `$comment_plans`; `$comment_project_overrides` no longer calls `audited.json` the only project-owned file |
| `.claude/skills/audit-usage/SKILL.md` | Vocabulary: **Plan windows**; § 2: `not configured` points at `plan-limits.json` and never suggests a budget |
| `.claude/.ci/AuditRenderTest.java` | The first report says there is no projection without the file; `plans()`: `doctor` on a bad and a good file, a USD 2.00 run projected per plan, the summary's mean with the time cap binding |
| `.github/workflows/validate.yml` | Comment on the `AuditRenderTest` step naming 0133 |
| `docs/pt-br/07-ci-validate.md` · `docs/en/07-ci-validate.md` | The `AuditRenderTest` row names the plan-window claims and 0133 |
| `docs/pt-br/08-audit-usage.md` · `docs/en/08-audit-usage.md` | New § Per project: `plan-limits.json`; `audited.json` is one of two project-owned files; the report's section list |

Not in `doctor.gate.labels`: a bad budget only blanks a projection, it breaks no guarantee.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › audit renders where the run spent, redacts tool errors, skips what class or project turns off` | Without `plan-limits.json` the report says there is no projection; `doctor` refuses another plan, a non-positive budget and an unknown field by name, and lists a valid file; a USD 2.00 run gives Pro 5 / 25, Max 5x 25, Max 20x 100 and `not published` for Max weekly; the summary's mean of USD 0.50 over one-minute runs gives Pro 20 / 100, Max 5x 100, and Max 20x capped by time at 300 | Green on the tree, 66/66. Red with every multiplier read from `pro`: 3 checks fail by name. Red with the time cap removed: the summary check fails by name. Source and jar restored; `build --verify` green |
| `validate › schema` (step `frontmatter schema`) | `extensions.json` parses with the new keys; the `audit-usage` body keeps its class sections | Green |

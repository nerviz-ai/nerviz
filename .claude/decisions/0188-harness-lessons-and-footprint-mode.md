# 0188 · A manual skill turns the `.claude/` footprint and the audit trail into a harness lessons-learned

- **Date:** 2026-10-28
- **Scenario:** "quero criar um componente para fazer analise do desempenho e funcionamento do
  nerviz … analisar funcionamento e comportamento do nerviz em relacao ao uso de skills, rules,
  hooks, mcps para avaliar desempenho e economia de tokens … analisar reports de audit-usage …
  deve ser aplicado no meta-repo e projetos gerados, pode ser chamado manualmente … antes de
  comecar a analise, busca documentacoes do claude code e anthropic para ter um guia base de boas
  praticas, pitfalls, mas praticas"
- **Decision:** Option 1 — Form 2 `.claude/skills/harness-lessons/SKILL.md` (class `report`, `opus`, travels) + Form 7c `ArchHook.java footprint` (read-only, no hook registration); `audit-usage` prints `/harness-lessons <piece>`, never invokes it; baseline revalidated past 90 days
- **State:** approved by the maintainer, on 2026-10-28

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | General suspicion: no measured run, large bodies (`new-feature` 944 lines) suspected | Forms 7 · 8 as a gate (mirror of invariant 6) |
| 2 — Trigger | Manual; chaining into the orchestrators to be evaluated | Form 1 |
| 3 — Frequency | Trade-offs requested; on demand + a release checklist recommended | Injection at every session |
| 5 — Nature | Sequence: measure, compare to a baseline, judge, write, hand off | Forms 4 · 5 |
| 6 — Isolation | Inline, `opus`; `footprint` already compresses what would be verbose | Form 3 |
| 8 — Destination | Both — meta-repo static only, generated project static + audit trail | — |
| 9 — Output | A `harness-NNN.md` and a handoff command — in a generated project under `docs/lessons-learned/` (gitignored there, read by `/report-issue`); here under `.claude/lessons-learned/`, beside the run reports, since `docs/` is the public documentation (amendment, same day) | `observer` class; the class default territory alone |
| Baseline | A curated reference with sources and a verified-on date; WebFetch only when stale | WebFetch on every run |
| Metrics | A new read-only `ArchHook.java` mode | The model counting bytes |
| Gate | Report only | Thresholds in `schema` / `doctor gate` |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `.claude/skills/harness-lessons/SKILL.md` + `ArchHook.java footprint` | 8 | **Approved** |
| 2 | Extend `audit-usage` | 4 | Rejected — `observer` class: sonnet, writes nothing, unaudited |
| 3 | Skill alone, the model measures | 5 | Rejected — reading every body to measure token economy spends the tokens it wants to save; numbers differ per run |
| 4 | create nothing | 3 | Rejected — nothing measures the static footprint nor compares it with a documented baseline |

### Option 1 — `harness-lessons` + `footprint` (score 8)

**Motivator:** axes 5, 2 and 9; reproducible metrics (the user's answer).

**Pros:** same shape as `sonar-lessons` — class `report`, `opus`, territory
`docs/lessons-learned/**` plus, through its own override, `.claude/lessons-learned/harness-*.md`
(§ Amendment), a handoff command at the end (`/report-issue` in a project,
`/claude-code-architect-designer` here). `footprint` prints per piece: `description` length
(always in context), body bytes, references, `` !` `` injections, rules without `paths`, root
`CLAUDE.md` size, hook registrations per event with and without `if`/`matcher`, MCP servers.
Its lists (globs, events) come from `extensions.json` (invariant 10). `references/best-practices.md`
carries each source URL and a `Verified on` date; past the window the run WebFetches those URLs for
that run only. Travels in `export.skills.include`.

**Cons:** a new Java mode; the token figure is an estimate (bytes ÷ 4, labelled as such — the
`count_tokens` API needs network and a key); the reference ages, and inside a generated project the
skill cannot refresh it (`export` overwrites `.claude/`), so a stale reference becomes a line of the
lessons file.

**Points cut in the rubric:** maintenance — one more mode and one more reference to keep current.

**CI:** `validate` › `schema` covers frontmatter, class, sections, export entry. New
`.claude/.ci/FootprintTest.java` (fixture tree → `ArchHook.jar footprint` → expected numbers), a
step of `validate.yml` › `hooks-cross-platform`.

### Option 2 — extend `audit-usage` (score 4)

Mixes rendering with diagnosis; the class that keeps it out of the trail forbids the write and the
model the judgment needs.

### Option 3 — skill alone (score 5)

Non-reproducible and self-defeating on cost.

### Chaining into the orchestrators

| Way | Verdict |
|---|---|
| `Skill` call at the end of `/new-feature` and `/new-feature-implement` | Rejected — an `opus` turn at a 250–370k context per feature, the cost `0182` removed from `git-publish` |
| Print `/harness-lessons` in every final report | Rejected — noise on every run |
| `audit-usage` § 2 names `/harness-lessons <piece>` when one piece dominates the spend, fails most, or peaks | **Approved** — no extra cost, appears only when the data justifies it |

## Amendment — the lessons file stays out of the public `docs/` here

Same day, before the first commit: the maintainer asked that the lessons file not be written in the
public documentation of this repository. `docs/lessons-learned/` is untracked here but sits in the
published `docs/` tree; `.claude/lessons-learned/` already holds the run reports (`lessons-learned-NNN.md`)
and is versioned history about this `.claude/`.

| Question | Answer |
|---|---|
| Generated project | Unchanged — `docs/lessons-learned/`, gitignored by `export.gitignore_lines`, read by `/report-issue`; `.claude/` is rewritten by `/arch-adopt` (`0100` axis 9) |
| File name here | `harness-NNN.md` — its own sequence, so § 4 still compares with `harness-<NNN-1>.md` |
| Territory | `skill_classes.classes.report.overrides.harness-lessons.write_allow`: `docs/lessons-learned/**`, `.claude/lessons-learned/harness-*.md` — the run reports beside it stay out of reach, and `sonar-lessons` and `report-issue` keep the class default |
| Existing `harness-001.md` | Moved from `docs/lessons-learned/` to `.claude/lessons-learned/` |

Rejected: the class default moved to `.claude/lessons-learned/**` (score 2 — breaks `/report-issue`
and `sonar-lessons` in generated projects), and a `.gitignore` line alone (score 3 — the file would
still sit in `docs/`). The strain: the class `$comment` meant a report skill to join "with no
override"; this is the first, and the comment now says why.

## References

| Claim | Source |
|---|---|
| `report` class: opus, `docs/lessons-learned/**` | `extensions.json` `skill_classes.report`; `sonar-lessons/SKILL.md` |
| A generated project's lessons stay outside `.claude/` | `0100` axis 9; `export.gitignore_lines` |
| Arithmetic belongs in Java, not in the model | `audit-usage/SKILL.md` § What the block already did |
| A chained piece at the end of a flow costs a 250–370k context | `0182` fact 2 |
| An agent only for one of three reasons | `decision-matrix.md` § 5; `@CLAUDE.md` invariant 5 |
| A mode reads its lists from `extensions.json` | `@CLAUDE.md` invariant 10 |
| A traveling skill is listed in `export` | `@CLAUDE.md` invariant 9 |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/harness-lessons/SKILL.md` | New skill |
| `.claude/skills/harness-lessons/references/best-practices.md` | Baseline BP-01..31, AP-01..09, sources, `Verified on: 2026-10-28` |
| `.claude/skills/harness-lessons/templates/lessons-learned.md.example` | Shape of `harness-NNN.md` |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `footprint` mode, rebuilt |
| `.claude/schemas/extensions.json` | `footprint` block; `skill_classes.report.skills` and `overrides.harness-lessons`; `export.skills.include` |
| `.claude/skills/audit-usage/SKILL.md` | § 2 item 5 — prints `/harness-lessons <piece>` |
| `CLAUDE.md` | `footprint` command row; routing row |
| `README.md` | Skill count 30 → 33 (it was already stale at 32) |
| `docs/{en,pt-br}/01-file-types` | `report` class row, with the override |
| `docs/{en,pt-br}/07-ci-validate.md` | Node H27, step row, local command; `SkillTerritoryTest` row 59 → 63 cases |
| `.github/workflows/validate.yml` | Step running `FootprintTest.java` |
| `.claude/.ci/FootprintTest.java` | New test |
| `.claude/.ci/SkillTerritoryTest.java` | Four cases: `/harness-lessons` opens the phase, `.claude/lessons-learned/harness-002.md` allowed, `lessons-learned-026.md` beside it blocked, the same `harness-002.md` blocked for `sonar-lessons` |
| `.claude/lessons-learned/harness-001.md` | Moved from `docs/lessons-learned/` |

Goes to the generated project: **yes** — the skill through `export.skills.include` (its `references/` and `templates/` are in `keep`), the mode inside the exported `ArchHook.java`/`.jar` and `extensions.json`. No hook registration, so neither settings file changes.

Known at the time of writing: the root `CLAUDE.md` was already 203 lines (BP-01, limit 200); the two rows this decision routes take it to 205 — the first finding `/harness-lessons` reports here.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › footprint counts what loads every session the way the runtime loads it` | `FootprintTest.java`: imports outside code only, rules with and without `paths`, manual skills out of the listing, body and contents limits, unnarrowed tool hooks, MCP servers, preloaded skills, no write | green on the tree · red with the manual-skill guard removed from the listing sum (`a manual skill stays out of the listing`), jar rebuilt each way |
| `validate › schema` (existing) | class, sections, frontmatter, injections, `$ARGUMENTS`, export entry of the skill | green · red with `harness-lessons` removed from `export.skills.include` (`is in neither export.skills.include nor .exclude`, exit 2) |
| `validate › build --verify` (existing) | the committed jar is the source compiled | green |
| `validate › SkillTerritoryTest` (existing, four cases added) | `harness-lessons` alone reaches `.claude/lessons-learned/harness-*.md`, never a run report beside it | green, 63 cases · red with the `.claude/lessons-learned/harness-*.md` glob dropped from the override (`.claude/lessons-learned/harness-*.md — allowed by the override — expected exit 0, got 2`) |

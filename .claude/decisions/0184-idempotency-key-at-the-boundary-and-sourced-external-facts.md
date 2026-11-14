# 0184 · The Idempotency-Key is stated at the boundary, and a spec's facts about another system carry a source

- **Date:** 2026-10-24
- **Scenario:** Issue #168, triaged at `debe4e6`: `use-case-design` never says that `Idempotency-Key` stays mandatory (nor its per-project cost) when the repetition fact is recorded, and no norm requires a source for spec constants that are facts about another system.
- **Decision:** Form 4 — `.claude/rules/spec-external-facts.md`, plus edits to `use-case-design`, `rest-api-architect`, `domain-modeling` and `new-feature` (option 1, B + X)
- **State:** approved by the maintainer, on 2026-10-24

## Reproduced on disk

Confirmed rows of the `issue-verifier` table, checked at `debe4e6` (static layer). Refuted and
unproven rows, and the issue's proposed fix, are not inputs.

| # | Claim | Evidence |
|---|---|---|
| 1 | A creating `POST` requires `Idempotency-Key` | `.claude/rules/api-rest.md:241-242` |
| 3 | `use-case-design` records "does repeating the request create a duplicate?" and delegates the mechanism | `use-case-design/SKILL.md:83`, `:142`; `templates/use-case-spec.md.example:19` |
| 4 | Nothing there says the key survives a "creates another" answer, or what it costs | three `idempot` hits in the skill, none states either; the key is decided by `rest-api-architect/SKILL.md:130`, an axis that ignores the repetition fact |
| 9 | Every "from memory" norm is about a version, coordinate, path or model name — none about a fact of another system | `mapping.md:88`, `messaging.md:165`, `ai.md:52-53`; the only partial source is the documentation link of the `External calls` row (`use-case-design/SKILL.md:126`) |
| 5 (refuted, kept as a finding) | "Repeating creates another" does not contradict the key — same key is a replay, a new key a new resource. The real defect is that one question blends retry safety with domain uniqueness | `use-case-design/SKILL.md:83`, template `:19` |

Commits since `debe4e6`: none.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Observed in a real `/new-feature` run (#168): the key's stack arrived with no word at the boundary, and external identifiers and a numeric property reached the spec from memory | create nothing |
| 5 — nature | Two facts: what the key is and costs (owned by `api-rest.md` and `rest-api-architect`); what a spec may state about another system (no owner yet) | 7, 8 — both are judgment over spec text; a hook cannot tell an external fact from an internal one |
| 4 — territory | `docs/use-cases/**`, where every spec lives | 5 — not every session |
| 8 — destination | Both: the skills touched and every rule travel | — |
| Gap 7 shape | B — state that the key stays mandatory and show the one-time cost when the key table is new; split the repetition fact into retry safety and domain uniqueness | C (negotiable key) |
| Gap 8 shape | X — a rule on `docs/use-cases/**`; an unsourced constant becomes a stop-and-ask before approval | Y (`unverified` mark, contradicts `0180`), Z (approval bullet only) |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | B + X — `rules/spec-external-facts.md` + edits to `use-case-design`, `rest-api-architect`, `new-feature`, `domain-modeling` | 9 | **Approved** |
| 2 | A + X — same, without splitting the repetition question | 7 | Rejected — leaves the question that blends retry safety with domain uniqueness |
| 3 | B + Z — no rule, one more bullet in the approval read | 5 | Rejected — the norm would live in a procedure, invisible to whoever writes the value |
| 4 | B + Y — rule, and an `unverified` mark allowed into an approved spec | 4 | Rejected — contradicts `0180` |
| 5 | C — `api-rest.md` lets a creating `POST` with a natural unique key skip the header | 3 | Rejected — changes a decided norm with no new fact |
| 6 | create nothing | 1 | Rejected — both gaps confirmed on disk |

### Option 1 — B + X (score 9)

**Motivator:** axes 1 and 5 — two observed gaps, each with a single owner to give it.

**Pros:** the key's mandatoriness keeps its owner (`api-rest.md`) and is cited, never restated,
at the boundary; its cost is shown by `rest-api-architect`, which owns the components, and only
when `idempotency_keys` is NEW — so it is true on the first case and silent after. The split
question removes the ambiguity the refuted claim 5 exposed. The new rule is a leaf with one
owner, loads by `paths` exactly when a spec is written or read, and its approval effect reuses
`0180`'s stop-and-ask instead of adding a second mechanism.

**Cons:** one more rule; the template's `Repetition` row grows a second fact.

**Points cut in the rubric:** 6 (precedent) — partial: `spec-diagrams.md` is the one norm on
`docs/use-cases/**`.

**CI:** `validate` › `frontmatter schema` (rule `paths`, every skill's sections); `design` ›
`rules is a leaf of the graph`, `every norm paths matches some blueprint`, `norm contains no
code boilerplate`; the rule's owning phrase added to `design` › `each norm has a single owner`.

### Option 2 — A + X (score 7)

Same rule; leaves one question blending retry safety with domain uniqueness — the defect behind
the refuted claim 5 stays, and a "creates another" answer still reads as if it argued against
the key.

### Option 3 — B + Z (score 5)

The norm would live in a procedure (`new-feature`'s approval read), invisible to whoever writes
the constant; a second skill needing it would restate it — invariant 2 by the next case.

### Option 4 — B + Y (score 4)

An `unverified` mark is text addressed to the approver frozen into an approved spec — exactly
what `0180` makes the approval read resolve. Two rules for one moment.

### Option 5 — C (score 3)

Changes the norm of `0006`/`0044` with no fact they did not weigh: a natural unique key arbitrates
domain duplicates, not a retried request whose first response was lost.

### Option 6 — create nothing (score 1)

Both gaps were observed in a real run and confirmed on disk.

## References

| Claim | Source |
|---|---|
| A rule may own a norm over spec text, loading on `docs/use-cases/**` | `.claude/rules/spec-diagrams.md` (precedent); `export.derived_paths.derived_paths_exempt_globs` already lists `docs/use-cases/**` |
| A rule never names a skill | `@CLAUDE.md` invariant 1 |
| One owner per norm, cited by path | `@CLAUDE.md` invariant 2 |
| Approver-addressed text is resolved or becomes a stop-and-ask before approval | `.claude/decisions/0180-module-slot-boundary-gradle-wrapper-and-approval-checks.md`; `new-feature/SKILL.md` § Approval |
| The aspect stack is a per-project cost, paid when the key table is NEW | `.claude/decisions/0044-idempotent-first-endpoint.md`; `rest-api-architect/SKILL.md` step 7 |
| Versions are never written from memory | `@CLAUDE.md` invariant 8 — not restated by the new rule |

## Propagation

| File | Change |
|---|---|
| `.claude/rules/spec-external-facts.md` | new rule, `paths: docs/use-cases/**` |
| `.claude/rules/00-index.md` | row in the written table; the documentation-territory paragraph names both rules |
| `.claude/skills/use-case-design/SKILL.md` | `Repetition` block asks two facts and states the key stays mandatory; `External calls` block asks an unsourced fact; Reads cites the rule |
| `.claude/skills/use-case-design/templates/use-case-spec.md.example` | `Repetition` row carries the two facts |
| `.claude/skills/rest-api-architect/SKILL.md` | step 7 shows the one-time cost when `idempotency_keys` is NEW |
| `.claude/skills/domain-modeling/SKILL.md` | Reads cites the rule |
| `.claude/skills/new-feature/SKILL.md` | Approval read gains a third defect: a fact about another system without a source |
| `.github/workflows/validate.yml` | owner phrase of the rule in `design` › `each norm has a single owner` |

Goes to the generated project: **yes** — `export.rules` takes the whole directory, the four skills are in `export.skills.include`, and `docs/use-cases/**` is already in `derived_paths_exempt_globs`, so no `derived_paths` entry.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › each norm has a single owner` | the phrase "A value with no source is not a fact" lives in one file of `rules/` | green on the tree · red with the phrase copied into `api-rest.md` |
| `validate · frontmatter schema` | the rule declares `paths`; every skill keeps its class sections | green (`ArchHook.java schema`, exit 0) |

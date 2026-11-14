# 0202 · Retry safety and the `Idempotency-Key` are opt-in: recorded only when the request states them

- **Date:** 2026-11-07
- **Scenario:** Issue #193, triaged at `2e50536`: `use-case-design` asks retry safety that `api-rest.md` § Idempotency always overrides, so the spec records two answers. Revised by the maintainer during the run: the default becomes no retry safety and no key; a use case adds them only when it states them.
- **Decision:** Form 4 edit — `.claude/rules/api-rest.md` § Idempotency — plus edits to `use-case-design` and `rest-api-architect` (option A3)
- **State:** approved by the maintainer, on 2026-11-07
- **Supersedes in part:** `0184` (the key mandatory on a creating `POST`, and its option 5 rejection) and the default of `0006`/`0044` (the key on every creating `POST`). `0044`'s mechanism (aspect, shared store, one transaction shape) is untouched.

## Reproduced on disk

Confirmed rows of the `issue-verifier` table, checked at `2e50536` (static layer). Refuted and
unproven rows, and the issue's proposed fix, are not inputs.

| # | Claim | Evidence |
|---|---|---|
| 1 | The `Repetition` block asks retry safety and domain uniqueness apart, and says the first is settled by `api-rest.md` | `use-case-design/SKILL.md:128` (at `2e50536`) |
| 2 | The same file forbids a question another skill overwrites | `use-case-design/SKILL.md:155` |
| 3 | A creating `POST` requires `Idempotency-Key` | `.claude/rules/api-rest.md:241-242` (at `2e50536`) |
| 4 | No step reads the retry-safety answer; the key is mandatory regardless | `rest-api-architect/SKILL.md:130`, `:207-209` (at `2e50536`); template `:19` |
| 8 | Nothing tells the interviewer the answer is non-binding | `use-case-design/SKILL.md`, by the text |

Commits since `2e50536`: none.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Confirmed in a real `/new-feature` run (#193 rows 1-4, 8) | create nothing |
| 5 — nature | A norm's trigger plus an interview block's wording | 5, 7, 8 — a hook cannot read an interview |
| 8 — destination | Both: the rule, `use-case-design` and `rest-api-architect` all travel | — |
| 9 — integration | Owners: `api-rest.md` (the trigger), `use-case-design` (the record), `rest-api-architect` (the reader). `persistence-architect` 4a is already conditional on `30-rest.md` | — |
| Shape | The maintainer chose the default: neither retry safety nor the key unless stated. Scope asked: only the HTTP `Idempotency-Key`; the idempotent consumer (`messaging.md`) and the at-least-once job (`scheduling.md`) stay, being delivery semantics, not an interview answer | A1, A2, B, C |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | A3 — retry safety `none` by default, recorded as `required` only when the request or an answer states it, never asked or inferred; `api-rest.md` requires the key only then; `rest-api-architect` reads the row | 8 | **Approved** |
| 2 | A1 — record retry safety from the norm of the trigger, key stays mandatory on a creating `POST` | 9 (before the maintainer's decision) | Superseded — drafted and applied first, then replaced |
| 3 | A2 — ask the residual HTTP action and extend the norm | 5 | Rejected — widens the mandatory key on no observed case |
| 4 | B — drop retry safety from the row, key stays mandatory | 6 | Rejected — the question disappears, the contradiction with the key stays |
| 5 | C — a unique business key lets the `POST` skip the header | 3 | Rejected — covered by A3 for any case that states nothing; not needed as a separate exemption |
| 6 | create nothing | 1 | Rejected — symptom confirmed on disk |

### Option 1 — A3 (score 8)

**Motivator:** axes 1 and 9 — the question had no reader because the norm decided before the
interview; making the interview decide removes the second answer at its root.

**Pros:** one source of truth for the key, the use case's own row; the issue's symptom cannot
recur, since nothing overwrites the answer; the `Audit` and `Admission` blocks already work this
way ("recorded only when the request states it"), so the pattern has precedent; no key table or
interceptor cost for a case that never asked for them.

**Cons:** strains `0184`'s reasoning: a `POST` that creates, with no key, duplicates the resource
when the client resends after a lost response. This is the maintainer's accepted trade, recorded
here so it is not read as drift. Templates and three examples (UC-105, UC-107, UC-109) keep the
key because they state it.

**Points cut in the rubric:** 2 (compliance) — none of the eleven invariants, but a decided
norm of `0006`/`0044`/`0184` is reversed on the maintainer's call, not on a new technical fact;
6 (precedent) — partial: `Audit` and `Admission` for the block, none for a norm becoming opt-in.

**CI:** `validate` › `frontmatter schema` (`ArchHook.java schema`); `validate` › `design › each
norm has a single owner`; `.claude/.ci/ApiVersionTemplateTest.java` and `AdmissionTemplateTest.java`
(the key exemplars still compile-check as templates). Nothing more testable: prose and a trigger
condition.

### Option 2 — A1 (score 9, superseded)

Recorded first and applied to `use-case-design/SKILL.md` and its template, then replaced: it kept
the key mandatory, which the maintainer chose to drop.

### Options 3-6

Kept so nobody re-proposes them without reading why.

## Design

| Piece | Change |
|---|---|
| `.claude/rules/api-rest.md` § Idempotency | "No endpoint carries an `Idempotency-Key` by default. A `POST` requires the header only when its use case's `Repetition` row states that a resend after a lost response must be safe…" |
| `.claude/skills/use-case-design/SKILL.md` | § Doesn't decide idempotency row; `Repetition` block; checklist row — retry safety recorded as `required` only when stated, else `none`; domain uniqueness still asked |
| `.claude/skills/use-case-design/templates/use-case-spec.md.example` | `Repetition` row: `none` unless stated, then `required` with who |
| `.claude/skills/rest-api-architect/SKILL.md` | Axis table row and step 7 cost paragraph read the `Repetition` row |
| `.claude/skills/rest-api-architect/templates/rest-spec.md.example` | The `POST /users` example states `retry safety: required` |
| `docs/pt-br/09-diferenciais.md`, `docs/en/09-differentiators.md`, `README.md` | "from the first endpoint" becomes "on the first endpoint whose use case asks for it" |

Not changed: `messaging.md` (idempotent consumer), `scheduling.md` (at-least-once),
`persistence-architect` step 4a, the key exemplars, UC-105/107/109.

## References

| Claim | Source |
|---|---|
| A question whose answer another skill overwrites is forbidden | `use-case-design/SKILL.md` § Checklist before every `AskUserQuestion` |
| "Recorded only when the request states it" precedent | `use-case-design/SKILL.md`, `Audit` and `Admission` blocks |
| The decision this supersedes in part | `.claude/decisions/0184-idempotency-key-at-the-boundary-and-sourced-external-facts.md`, `0044-idempotent-first-endpoint.md` |
| One owner per norm, cited by path | `@CLAUDE.md` invariant 2 |
| Delivery semantics are a separate matter | `.claude/rules/messaging.md` § Delivery semantics; `.claude/rules/scheduling.md` § Execution guarantees |

## Propagation

| File | Change |
|---|---|
| `.claude/rules/api-rest.md` | Idempotency bullet |
| `.claude/skills/use-case-design/SKILL.md` | three spots |
| `.claude/skills/use-case-design/templates/use-case-spec.md.example` | `Repetition` row |
| `.claude/skills/rest-api-architect/SKILL.md` | axis row, step 7 |
| `.claude/skills/rest-api-architect/templates/rest-spec.md.example` | two lines |
| `docs/pt-br/09-diferenciais.md`, `docs/en/09-differentiators.md`, `README.md` | one line each |
| `docs/diagrams/layers/*` | re-render, counts the records |

Goes to the generated project: **yes** — the rule and both skills travel.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · frontmatter schema` | the skills keep frontmatter and class sections | green (`ArchHook.java schema` exit 0; `claude plugin validate .claude/skills` passed) |
| `.ci/ApiVersionTemplateTest`, `.ci/AdmissionTemplateTest` | the key exemplars still hold their shape | green |

Nothing more testable: a trigger condition in prose.

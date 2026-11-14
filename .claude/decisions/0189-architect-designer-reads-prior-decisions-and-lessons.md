# 0189 · claude-code-architect-designer reads prior decisions and lessons before interviewing

- **Date:** 2026-10-28
- **Scenario:** "hoje le lessons-learned e decisions para ajudar na matriz de decisao ?" — it does not; close the gap.
- **Decision:** edit of a Form 1 skill — Phase 1 "Prior art" step and `## Contract` › **Reads** in `.claude/skills/claude-code-architect-designer/SKILL.md`
- **State:** approved by Lucas Fernandes, on 2026-10-28

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Anticipation: no observed case of a record contradicted or a lesson ignored | 7, 8 (invariant 6 mirror) |
| 2 — Trigger | Inside Phase 1 of the skill itself | new skill, agent, rule |
| 8 — Destination | This repo only (skill is in `export.skills.exclude`) | — |
| 9 — Sources | `.claude/decisions/` and `.claude/lessons-learned/` | — |
| 10 — Cost of error | Hours — human review catches it | 7c |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Phase 1 "Prior art" step in `claude-code-architect-designer/SKILL.md` | 7 | **Approved** |
| 2 | create nothing | 5 | Rejected — titles alone do not show rejected alternatives |
| 3 | `ArchHook.java decisions search` (Form 7c) | 2 | Rejected — hook against an unobserved failure, invariant 6 mirror |

### Option 1 — `SKILL.md` Phase 1 step (score 7)

**Motivator:** axes 1 and 10 — anticipation, cost in hours: persuasion, not guarantee.

**Pros:** no code, no process per event; Grep and Read already exist.

**Cons:** the model may skip the step; one grep plus the matched reads per scenario.

**Change:** Phase 1 greps the scenario's terms in `.claude/decisions/[0-9]*.md` and
`.claude/lessons-learned/*.md`, reads only the hits (`State`, rejected options, `Next`),
turns a prior rejection into an interview constraint, and cites the hits in the Phase 3
references table. `## Contract` › **Reads** names both folders.

**CI:** `validate › schema` covers frontmatter, class and sections; the step itself is prose, nothing testable.

### Option 2 — create nothing (score 5)

The injected `ls` already shows record titles; a title does not show rejected alternatives, so the gap stays.

### Option 3 — `decisions search` mode (score 2)

Deterministic and testable, but Java plus a test for a failure nobody observed, cost in hours.

## References

| Claim | Source |
|---|---|
| A hook against an unobserved failure is waste | `@CLAUDE.md` invariant 6 (mirror) |
| Reading records avoids repeating the interview | `claude-code-architect-designer/SKILL.md` Phase 3.5 |
| `harness-lessons` ends pointing at this skill | `@CLAUDE.md` § Routing, `0188` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/claude-code-architect-designer/SKILL.md` | Phase 1 prior-art paragraph; `## Contract` › **Reads** names both folders |

No routing row, class or export change: the skill already has all three.

Goes to the generated project: **no — the skill is a creation skill, excluded from export.**

## CI coverage

`validate › schema` covers the edited skill's frontmatter, class and sections; nothing added — the step is prose, nothing testable in CI.

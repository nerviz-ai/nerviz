# 0191 · claude-code-architect-designer delegates Phase 4 to a sonnet subagent by default

- **Date:** 2026-10-29
- **Scenario:** Today the skill interviews, drafts, gets approval and implements in the same session. Is splitting the implementation into a sonnet or haiku subagent worth it — tokens, quality?
- **Decision:** Option A — edit `.claude/skills/claude-code-architect-designer/SKILL.md` (Phase 4 delegation paragraph, step 11 wording, `## Contract` › Delegates). No new file, no new agent.
- **State:** approved by Lucas Fernandes, on 2026-10-29. Forms 7 and 8 delegate only with the exact JSON in the record and a diff review in the main thread. Phase 4 of this record itself delegated to a `sonnet` subagent, as in `0190`.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Phase 4 runs in the Opus main thread: every tool call resends the whole interview, the 40 KB `SKILL.md` and the references | create nothing, as is |
| 6 — isolation | Different model (sonnet) and a clean main context | — |
| 7 — mandatoriness | Persuasion; the skill's own `schema` check already gates its shape | Forms 7, 8 |
| 8 — destination | This repository only | `export` block |
| 9 — integration | Reuses the delegation `0190` did by exception and the memory `designer-implement-via-sonnet-subagent`; collides with the skill's "never delegated" sentences | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | Edit the skill: Phase 4 delegated to a `general-purpose` agent on `sonnet` by default, design text verbatim in the handoff | 8 | **Approved** |
| B | Dedicated `.claude/agents/designer-implementer.md` (`sonnet`, class `executor`, `write_allow` = meta) | 6 | Rejected — only reason 2 of invariant 5 applies, and weakly: `general-purpose` already writes under the open `meta` phase (`ArchHook.java` `guardCall`, `agentClassOf(...) != null` returns early only for classed agents) |
| C | Create nothing; keep the per-case exception from memory | 4 | Rejected — the skill still says "never", so a session without the memory behaves differently |

### Option A — SKILL.md edit (score 8)

**Motivator:** axis 1 and 6. Phase 4 is the longest run of tool calls; each one resends the interview context at Opus price. A clean handoff also forces the record to be complete.

**Pros:** cheaper per call; main window stays free for review; the subagent can only work from the record, so an incomplete record shows up at once; no new piece.

**Cons:** the handoff costs tokens, so a small Phase 4 (one rule line, one routing row) loses — the skill skips delegation there. A subagent has no conversation: anything design-critical not written in the record is lost. Strains the skill's own "never delegated" sentences, which this edit rewrites. No measured before/after (the audit trail is off here, as in `0190`).

**Points cut in the rubric:** no measurement (−1); small-Phase-4 case where it loses (−1).

**CI:** `schema` already validates the skill's body, class and `export` entry; the new text adds no section. Nothing else testable: whether the subagent received the design text verbatim is a property of one run.

### Option B — dedicated agent (score 6)

A classed `executor` would fix tools and territory in `agent_classes`. Rejected because `general-purpose` under the open `meta` phase already does the job (`0190`), and a file in `.claude/agents/` is one more piece loaded into every listing.

### Option C — create nothing (score 4)

Zero cost, but the rule and the practice disagree.

### Haiku — rejected

`0080` scored `haiku` 4 for procedural skills; `0010` rejected it for the executor. Phase 4 writes Java into `ArchHook.java`, loops on `schema` until green, and runs a test that must fail for the right reason.

## References

| Claim | Source |
|---|---|
| A whole Phase 4 was delegated to sonnet once, as an exception | `@.claude/decisions/0190-nerviz-diagrams-skill-derived-from-data.md` State |
| Design steps stay in the main thread because they decide firing and ownership | `SKILL.md` Phase 4 step 8 « Steps 1 through 7 … never get delegated » |
| A skill's subagent cost is its own requests | `@.claude/lessons-learned/harness-001.md` § 5, BP-16 |
| Phase 4 is 16.5 KB of the 40 KB body | `@.claude/lessons-learned/harness-001.md` § 7 |
| An unclassed agent's writes are judged by the open phase | `.claude/hooks/ArchHook.java` `guardCall` |
| Sonnet fits a spec-closed task, haiku does not | `@.claude/decisions/0010-pipeline-executor-and-orchestrator.md`, `@.claude/decisions/0080-procedural-skills-on-sonnet.md` |
| Guarantees (hooks, permissions) always carry a record | `@CLAUDE.md` invariant 6, `SKILL.md` Phase 3.5 |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/claude-code-architect-designer/SKILL.md` | Phase 4 step 8 delegation paragraph and the "never get delegated" sentences rewritten; step 9 and step 11 wording; `## Contract` › Delegates |

Goes to the generated project: **no** — the skill is a creation skill, in `export.skills.exclude`.

## CI coverage

The existing `validate` job's `schema` step already checks the skill's frontmatter, class, required sections and `export` entry; the edit adds no section and changes no field, so nothing new was added. Whether the handoff carries the design text verbatim is a property of one run and not testable in CI.

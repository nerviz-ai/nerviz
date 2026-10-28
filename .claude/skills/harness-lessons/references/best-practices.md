# Best practices — the baseline a harness lesson is measured against

**Verified on:** 2026-10-28

Read by `harness-lessons` before it judges anything. Each practice has an id the lessons file
cites (`BP-07`), the source it was read from, and what measures it: a `footprint` column, an
`audit summary` column, or judgment. Past the revalidation window the skill re-reads the
sources below for that run; a practice whose source no longer says it is reported, never
silently dropped.

## Contents

- Sources
- Context that loads every session — BP-01 to BP-06
- Skills — BP-07 to BP-14
- Agents — BP-15 to BP-19
- Hooks and permissions — BP-20 to BP-24
- MCP — BP-25 to BP-26
- Session economics — BP-27 to BP-31
- Anti-patterns — AP-01 to AP-09

## Sources

| Key | URL |
|---|---|
| costs | https://code.claude.com/docs/en/costs |
| best | https://code.claude.com/docs/en/best-practices |
| memory | https://code.claude.com/docs/en/memory |
| skills | https://code.claude.com/docs/en/skills |
| subagents | https://code.claude.com/docs/en/sub-agents |
| hooks | https://code.claude.com/docs/en/hooks |
| authoring | https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices |
| context | https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents |

## Context that loads every session

| Id | Practice | Source | Measured by |
|---|---|---|---|
| BP-01 | Keep each `CLAUDE.md` under 200 lines; it loads into every session and longer files reduce adherence | memory, costs | `footprint` › Always loaded › `CLAUDE.md` lines |
| BP-02 | An `@path` import does not reduce cost: the imported file loads with the `CLAUDE.md` that imports it (up to four hops). A path in backticks is not imported | memory | `footprint` › Always loaded › imports |
| BP-03 | A rule without `paths` loads at launch like `CLAUDE.md`; scope it with `paths` when it holds for part of the tree | memory | `footprint` › Rules › `paths` = none |
| BP-04 | `CLAUDE.md` holds only what Claude cannot work out from the code: commands, misleading places, decisions not in the repo, what was corrected twice. Architecture and file layout do not belong there | best | judgment |
| BP-05 | Workflow-specific instructions move from `CLAUDE.md` to skills, which load on demand | costs | judgment over `CLAUDE.md` sections |
| BP-06 | Every model-invocable skill's `description` (+ `when_to_use`) and every agent's `description` is in context every session; keep them short | skills, subagents | `footprint` › Listing chars |

## Skills

| Id | Practice | Source | Measured by |
|---|---|---|---|
| BP-07 | `SKILL.md` under 500 lines; move detail to files read on demand | skills, authoring | `footprint` › Skills › body lines |
| BP-08 | `description` + `when_to_use` are truncated at 1,536 characters in the listing: key use case first | skills | `footprint` › Skills › listing chars |
| BP-09 | A loaded skill's rendered body stays in context for the rest of the session; on compaction each re-attached skill keeps its first 5,000 tokens, 25,000 tokens shared — the procedure that matters goes first | skills | `footprint` › Skills › body tokens (est.) |
| BP-10 | Concise: only what Claude does not already know; every line is a recurring cost once loaded | authoring, skills | judgment |
| BP-11 | References one level deep from `SKILL.md`; a reference over 100 lines opens with a table of contents | authoring | judgment over `references/` |
| BP-12 | Deterministic work in a script whose output is all that enters context, never generated or re-derived by the model | authoring, costs | `footprint` › Skills › injections; judgment |
| BP-13 | `disable-model-invocation: true` keeps the description out of context entirely — for workflows with side effects | skills | `footprint` › Skills › manual |
| BP-14 | A skill's `model` holds for the rest of the turn; `effort` while the skill is active. Match the model to the job — Sonnet for most procedures, Opus for architecture | skills, costs | `footprint` › Skills › model, effort; `audit summary` › Model |

## Agents

| Id | Practice | Source | Measured by |
|---|---|---|---|
| BP-15 | A subagent pays off for parallel work, noisy output kept out of the main context, a fresh reviewer, or fewer tools. It does not give a better answer on the same task | best, subagents | judgment |
| BP-16 | Every subagent sends its own requests against the same usage; a fresh one first re-gathers context the conversation already had | best, costs | `audit summary` › own tokens per agent |
| BP-17 | Simple subagent work runs on a smaller model (`model: haiku` or `sonnet`) | costs, best | `footprint` › Agents › model |
| BP-18 | Ask for short summaries back: many subagents returning large reports fill the main context anyway | subagents | `audit summary` › Peak of the caller |
| BP-19 | Skills preloaded through an agent's `skills:` load whole at its start | subagents | `footprint` › Agents › preloaded bytes |

## Hooks and permissions

| Id | Practice | Source | Measured by |
|---|---|---|---|
| BP-20 | A rule that must always hold is a hook or a deny rule; a hook for a mere preference is deleted | best | judgment |
| BP-21 | Narrow every tool-event hook with `matcher` or `if`; with no match the process is never spawned | hooks | `footprint` › Hooks › unnarrowed |
| BP-22 | `SessionStart` runs every session: keep it fast | hooks | `footprint` › Hooks › per event |
| BP-23 | `additionalContext`, `systemMessage` and stdout are capped at 10,000 characters each; above it only a 2,000-character preview enters. Keep injected text short and factual — static conventions belong in `CLAUDE.md` | hooks | judgment over hook modes that inject |
| BP-24 | Preprocess in a hook so the model reads hundreds of tokens, not tens of thousands | costs | judgment |

## MCP

| Id | Practice | Source | Measured by |
|---|---|---|---|
| BP-25 | Tool definitions are deferred; names and server instructions still enter context | costs | `footprint` › MCP servers |
| BP-26 | Prefer a CLI (`gh`, `aws`, `gcloud`) over an MCP server when one exists; disable unused servers | costs | judgment |

## Session economics

| Id | Practice | Source | Measured by |
|---|---|---|---|
| BP-27 | Every request re-sends the whole conversation; `/clear` between unrelated tasks costs nothing | costs, best | `audit summary` › Peak |
| BP-28 | `/compact` reads the whole conversation it summarizes — a large request in itself | costs | `audit summary` › compaction incidents |
| BP-29 | The prompt cache lasts about an hour on a subscription, five minutes on an API key and for subagents; a miss re-processes the full context | best | judgment |
| BP-30 | Extended thinking is billed as output; lower `effort` where reasoning is shallow | costs | `footprint` › Skills › effort |
| BP-31 | Smallest set of high-signal tokens; just-in-time retrieval by path over loading up front | context | judgment |

## Anti-patterns

| Id | Anti-pattern | Source |
|---|---|---|
| AP-01 | Emphasis (`IMPORTANT`, capitals) piled on a line the model keeps missing — the file needs to be shorter, or the line is a hook | best |
| AP-02 | "Think harder", "be thorough", "don't be lazy" in a prompt — `effort` is the control; such lines cause over-work | best |
| AP-03 | Delegating to a subagent for a better answer on the same task | best |
| AP-04 | Fanning out subagents that return large reports | subagents |
| AP-05 | Describing architecture and file layout in `CLAUDE.md` — Claude reads the repo | best |
| AP-06 | Time-sensitive statements in a skill ("before August, use…") | authoring |
| AP-07 | Too many options offered where one default with an escape hatch would do | authoring |
| AP-08 | Overlapping tools or pieces a human could not choose between | context |
| AP-09 | Generating code or arithmetic in the model that a script could run deterministically | authoring |

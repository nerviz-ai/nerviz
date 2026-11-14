# Harness lessons 001 — first footprint of the Nerviz repository after 0188

Run date: 2026-10-28. Where: the Nerviz repository at `fa288e5` (worktree carries the
uncommitted 0188 change set). Focus: whole harness. Audit trail: off — by design in this
repository (no `.claude/audit-usage/`).

Token figures are estimates: bytes ÷ 4 (`footprint.bytes_per_token`). Section byte counts
below were measured with `awk` over the owning file to locate a finding; every total comes
from the `footprint` block.

---

## Snapshot

| Measure | Value |
|---|---|
| Every session, ~tokens | 13.3k (MCP not counted) |
| `CLAUDE.md` lines | 205 (38,280 bytes, 9.6k tokens) |
| Rules without `paths` | 0 |
| Skill listing, chars | 13,261 (18 model-invocable of 33) |
| Skills past the body limit | 5 |
| Hooks spawning on every tool call | 0 (`Stop` spawns 4 per turn, `UserPromptSubmit` 1) |
| Billable tokens, all runs | trail off |
| Worst failure ratio | trail off |

## Findings, by priority

| # | Lens | Owner | Practice | Saving | Status |
|---|---|---|---|---|---|
| 1 | every-session | `CLAUDE.md` § Routing | BP-01, BP-04, BP-05, AP-05, invariant 2 | ~3.2k tokens per session | new |
| 2 | every-session | `.claude/skills/*/SKILL.md` `description` (7 over 850 chars) | BP-06, BP-08 | ~1.3k tokens per session; three descriptions back in the listing | new |
| 3 | every-session | `CLAUDE.md` § Commands | BP-01, BP-04 | ~1.0k tokens per session | new |
| 4 | every-session (generated project) | `.claude/rules/error-handling.md`, `logging.md` | BP-03, BP-31 | ~0.8k tokens per generated-project session | new |
| 5 | invocation | `.claude/agents/java-spring-boot-developer.md` | BP-07, BP-10, BP-16 | up to ~8k tokens per agent start, 3 starts per implement run | new |
| 6 | invocation | `.claude/skills/new-feature/SKILL.md` | BP-07, BP-09 | the procedure survives compaction; ~3.6k tokens per run if § Contract leaves the body | new |
| 7 | invocation | `persistence-architect`, `messaging-architect`, `claude-code-architect-designer`, `project-bootstrap` `SKILL.md` | BP-07 | ~1–2k tokens per run each, estimate | new |
| 8 | invocation | 21 `references/*.md` over 100 lines | BP-11 | whole-file reads to find one section stop | new |
| 9 | guarantee | `.claude/settings.json` `Stop` | BP-20, invariant 6 (mirror) | 2 of 4 JVM spawns per turn stop | new |

---

## § 1 · `CLAUDE.md` § Routing — 52 rows, ~5.8k tokens every session

**Evidence:** `footprint` › Every session › `CLAUDE.md` — 205 lines, 38,280 bytes, 9.6k
tokens, "over 200 lines". § Routing — when X, read Y is 23,232 of those bytes in 52 rows;
the longest are 1,082 (platform-upgrade), 1,017 (Kafka), 893 (persistence), 742
(implement), 741 (TLS) and 740 (docker) bytes. The line count barely trips BP-01; the
bytes per line (≈187 on average) are the cost.

**Practice:** BP-01 — `CLAUDE.md` loads into every session, keep it short (memory, costs);
BP-05 — workflow detail moves to the skill, which loads on demand (costs); AP-05 (best).

**Change at the owner:** cut each row to its trigger words and its target — one skill, rule
or command, ≤ ~200 bytes. What each row adds past that (which partial, which step of
`/new-feature`, which mechanism, which decision record) is already owned elsewhere: the
skill's own `description` and body, the rule, the record. Several rows restate a norm, which
is itself an invariant-2 defect: the Kafka row spells out `@.claude/rules/messaging.md`
§ Retry and DLQ ("the container retries and commits, never the listener; one classification
by type per project"), the REST row spells out `rules/api-rest.md` § Versioning. Keep the
decision ids out of the rows; `decisions/README.md` is the index.

**Saving:** 52 rows × ~200 bytes ≈ 10.4k bytes → ~12.8k bytes cut, **~3.2k tokens per
session**, estimate.

**Decided on purpose?** No record sizes the routing table. 0004 already noted `CLAUDE.md`
"close to the ~200-line target"; every skill record since (0002, 0029, 0032, 0034, 0046…)
added or widened a row and none measured the bytes.

---

## § 2 · Pipeline skill descriptions — 13,261 listing chars, three dropped

**Evidence:** `footprint` › Every session › skill listing — 18 model-invocable, 13,261
chars, 3.3k tokens. Per skill: `graphql-api-architect` 1101, `jobs-architect` 1050,
`http-client-architect` 1042, `persistence-architect` 939, `ai-architect` 907,
`docker-architect` 893, `security-architect` 864. Observed in the session that produced this
file: the runtime listing showed `cache-architect`, `graphql-api-architect` and
`search-architect` by name only, with no description — the listing is shared with every
installed plugin's skills, and these three lost their trigger text.

**Practice:** BP-06 — every model-invocable description is in context every session, keep it
short (skills); BP-08 — key use case first (skills).

**Change at the owner:** in each description over ~450 chars, cut the enumeration of what
the partial holds (everything after "into the `NN-x.md` partial:") — the body already says
it — and keep three parts: one clause of what it designs, the "Use when …" keywords, the
"Piece of the `/new-feature` pipeline: requires …" precondition. Optional guarantee on top:
a `footprint.limits` entry for listing chars per skill, so `footprint` flags the next one.

**Saving:** capping the 16 skills above 450 chars at 450 ≈ 5,166 chars, **~1.3k tokens per
session**, estimate; and the three pipeline skills regain a description in a crowded
listing.

**Decided on purpose?** No.

---

## § 3 · `CLAUDE.md` § Commands — ~1.8k tokens every session

**Evidence:** § Commands is 7,110 bytes of `CLAUDE.md`. The longest rows: `upgrade-recipe`
933 bytes, `compose` 674, `config` 620, `test-diff` 593, `boundary-map` 461, `export` 430.

**Practice:** BP-01, BP-04 — `CLAUDE.md` keeps the commands, not their full semantics
(memory, best).

**Change at the owner:** keep the table, one clause per row (what it is for and the exit
code that matters). Move the full semantics — every exit-2 condition, which data block it
reads, who runs it — to a mode reference under `docs/en/` (and its `docs/pt-br/` twin),
cited by path in backticks, never with `@`.

**Saving:** ~4k bytes, **~1.0k tokens per session**, estimate.

**Decided on purpose?** No.

---

## § 4 · Rules on `**/src/**/*.java` — first Java read in a generated project

**Evidence:** `footprint` › Rules with `paths`: `code-quality.md` 1.7k, `error-handling.md`
2.3k, `logging.md` 2.6k, `lombok.md` 1.7k, `naming.md` 1.1k, `personal-data.md` 1.4k
(`**/src/**/*.java`) and `secrets.md` 904 (`**/src/**`) — all load on the first `.java` read,
which is nearly every session of a generated project. Two sections do not hold for every Java
file: `logging.md` § `logback.xml` (972 bytes, an XML file) and `error-handling.md`
§ Integration families (2,165 bytes, outbound adapters only).

**Practice:** BP-03 — scope a rule with `paths` when it holds for part of the tree (memory);
BP-31 — just-in-time over up front (context).

**Change at the owner:** move `logging.md` § `logback.xml` to a rule scoped
`**/src/main/resources/logback*.xml`; move `error-handling.md` § Integration families to a
rule scoped to the outbound adapter packages (`**/adapter/out/**`, `**/infrastructure/http/**`,
`**/infrastructure/messaging/**`), add both to `export` and `rules/00-index.md`, and update
the citations of `error-handling.md § Integration families` (`CLAUDE.md` routing,
`http-client-architect`).

**Saving:** ~3.1k bytes, **~0.8k tokens per generated-project session that edits Java**,
estimate.

**Decided on purpose?** 0082 scoped rules with `paths`; it did not size what a single Java
read loads.

---

## § 5 · `java-spring-boot-developer` — 22.2k tokens at each of three starts

**Evidence:** `footprint` › Agents — `java-spring-boot-developer` 1,415 lines, 22.2k tokens,
`sonnet`, `high`. Since 0130 it starts once per block group, three times per
`/new-feature-implement` run, and its body rides in every request of each start. The
conditional blocks — M (5,750 bytes), Form B (4,275), H (3,950), J (3,890), G (3,686),
S (3,585), A (3,540), C (3,333) — total 32,009 bytes, ~8k tokens, loaded whether or not the
spec has the block. Block 4: Tests (8,341 bytes) is loaded by groups 1 and 2, which never run
it. 0130 listed "the exact size of the exported agent body" as unproven; this is it.

**Practice:** BP-07 / BP-10 — detail in files read on demand, every loaded line is a
recurring cost (skills, authoring); BP-16 — each subagent pays its own requests (best).

**Change at the owner:** move each conditional block, and Block 4, to its own file under
`.claude/skills/new-feature-implement/references/blocks/` (the folder already travels).
The agent body keeps the always-run blocks of its group and one table: block → file → the
spec section that makes it apply; the agent reads a block file only when its group and the
spec ask for it.

**Saving:** up to **~8k tokens per agent start** when the spec has no conditional block,
~2k more for groups 1 and 2 (Block 4) — up to ~28k tokens of body per implement run,
before multiplying by the requests of each start; estimate.

**Decided on purpose?** 0130 split the run by block group and kept one body; it did not weigh
the body's size.

---

## § 6 · `new-feature` — 201 lines of contract before the procedure

**Evidence:** `footprint` › Skills — `new-feature` 937 lines, 16.6k tokens, "over 500
lines". § Contract runs from line 25 to 225 (14,452 bytes), then § Spec lifecycle and
§ Entry guardrail; § Procedure starts at line 349. 0135 split the implement flow out at 918
lines; the body is 19 lines longer now.

**Practice:** BP-09 — on compaction a re-attached skill keeps its first 5,000 tokens, so the
procedure that matters goes first (skills); BP-07 (skills, authoring).

**Change at the owner:** put § Procedure right after § Why; move § Contract to
`references/contract.md` with a ≤10-line summary (class, territory, owns, never writes)
left in the body; move § Template: UC-NNN-spec.md and § Entry into generated projects to
`references/` too.

**Saving:** after compaction the re-attached head is the procedure instead of the contract;
**~3.6k tokens per `/new-feature` run** if `references/contract.md` is opened only when a
guard refusal or an ownership question needs it; estimate.

**Decided on purpose?** 0135 — set the split, not the order; the order still costs.

---

## § 7 · Four more skills past 500 lines

**Evidence:** `footprint` › Past a limit — `persistence-architect` 575 lines (§ Procedure
28,775 bytes; step 4 "Design the schema" is lines 247–415 with the document family and audit
branches inline), `claude-code-architect-designer` 541 (§ Phase 4 · Write 16,531 bytes),
`project-bootstrap` 522 (§ Contract 5,211 + § Output contract 4,803), `messaging-architect`
509 (§ Procedure 26,799 bytes).

**Practice:** BP-07 (skills, authoring).

**Change at the owner:**
- `persistence-architect`: step 4's document-family and audit branches to
  `references/document-family.md` and `references/audit.md`, read when the `Storage` or
  `Audit` row asks.
- `claude-code-architect-designer`: § Phase 4's per-form write steps to
  `references/write-<form>.md`, one per form.
- `project-bootstrap`: merge § Contract and § Output contract into one; the per-file output
  list to `references/project-files.md`, which exists.
- `messaging-architect`: the retry-mode and Form B outbox branches of § Procedure to
  `references/`, read when the use case picks them.

**Saving:** ~1–2k tokens per run of each, estimate; each comes back under 500.

**Decided on purpose?** No record sets these sizes.

---

## § 8 · 21 references over 100 lines with no contents list

**Evidence:** `footprint` › Past a limit — 21 entries; `project-bootstrap` alone has 6
(`build-gradle.md`, `build-maven.md`, `dependency-catalog.md`, `project-files.md`,
`scaffold.md`, `verify-and-report.md`), `claude-code-architect-designer` 5.

**Practice:** BP-11 — a reference over 100 lines opens with a table of contents (authoring).

**Change at the owner:** a `## Contents` list of the `##` headings at the top of each of the
21 files.

**Saving:** what stops: reading a whole reference to find one section; the model can read
a range instead.

**Decided on purpose?** No.

---

## § 9 · `.claude/settings.json` `Stop` — two checks that cannot fire here

**Evidence:** `footprint` › Hooks — `Stop` 4 entries, every occurrence: `schema`,
`guard sweep`, `compose gate`, `tests`. This repository has no `docker-compose*` and no
`mvnw`: `compose gate` returns before checking anything (0064, "No compose file — exit 0,
silent") and `tests` exits 0 with no wrapper (`CLAUDE.md` Known pitfalls).

**Practice:** BP-20 — a hook guards a failure that can happen (best); `CLAUDE.md` invariant
6, mirror — "a hook against a failure nobody observed is a process per event confirming
what was already true".

**Change at the owner:** remove the `compose gate` and `tests` entries from
`.claude/settings.json`; keep both in
`.claude/skills/project-bootstrap/templates/settings.json.example`, where they guard real
failures.

**Saving:** two JVM spawns per turn stop; no tokens.

**Decided on purpose?** 0064 registered `compose gate` in both files as "the same entry",
with no reason given for this repository.

---

## Baseline

`references/best-practices.md` verified on 2026-10-28. Within the window — not re-read.

## What the trail would have added

Off here by design. In a generated project it would rank § 5 and § 6 by measured spend
(own tokens and peak per `new-feature` / `java-spring-boot-developer` run) and add the
behavior lens: failure ratio, compactions, repeated tool failures.

## Next

/claude-code-architect-designer Cut each `CLAUDE.md` routing row to its trigger and target (≤ ~200 bytes) and drop the norms they restate, saving ~3.2k tokens per session — .claude/lessons-learned/harness-001.md

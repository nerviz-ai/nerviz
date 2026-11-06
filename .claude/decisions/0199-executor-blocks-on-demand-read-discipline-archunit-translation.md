# 0199 · Executor blocks read on demand, one read rule for every file, and a named translation step in `archunit-installer`

- **Date:** 2026-11-06
- **Scenario:** Issue #199, triaged at 9e97f02: `java-spring-boot-developer` loads its whole 1417-line body (854 lines of Block sections) at every per-group start, and has no instruction against whole-file reads of exemplars, rules and `ArchitectureTest.java`; `archunit-installer` step 4 names no mechanism for translating packages.
- **Decision:** Option 1, A + B + C — twelve block files under `skills/new-feature-implement/references/blocks/`, the read rule and § Blocks table in `agents/java-spring-boot-developer.md`, step 4 of `agents/archunit-installer.md`, and `.claude/.ci/ExecutorBlocksTest.java`
- **State:** approved by Lucas Fernandes, on 2026-11-06

## Reproduced on disk

Confirmed rows of the `issue-verifier` table (triage of #199 at 9e97f02, layer static). No
commit since 9e97f02 touches a cited file. Refuted and unproven rows, and the issue's proposed
fixes, are not inputs (`0103`).

| # | Claim | Evidence |
|---|---|---|
| 2 | The body reads the spec and partials by section and forbids `cat` then `Read` | `.claude/agents/java-spring-boot-developer.md:320-322` |
| 3 (as rewritten by the verifier) | No line restricts whole reads of exemplars, rules or `ArchitectureTest.java`; the instruction is missing, not ignored | `:251` "Read the corresponding exemplar, if not read yet"; `:71`; `:325` |
| 5 | `guard bash` has no read check | `guard.bash_write_shapes` only; no `Read` matcher |
| 6 | Body 1417 lines, 22.2k tokens, `sonnet`, `high` | `ArchHook.jar footprint` |
| 7 | `### Block …` sections span 854 lines (`:437`–`:1290`) | `grep -n '^##'` |
| 9 | Every group start loads the whole body; each group runs only its blocks | `:411`, `:422` |
| 12 | Since `0130` the agent starts once per group; `0130` priced cold starts, not blocks loaded and never run | `:411-414`; `0130:112-113` |
| 13 | `archunit-installer` step 4 names no mechanism | `.claude/agents/archunit-installer.md:98-108` |

Measured independently before the issue: `.claude/lessons-learned/harness-001.md` § 5 —
conditional blocks 32,009 bytes (~8k tokens) plus Block 4 (8,341 bytes) loaded at every start,
three starts per `/new-feature-implement` run.

Unproven, not inputs: the whole-file read ranking (row 4, tracked in #195 item 8) and the
`python3 -` failure (row 14).

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Measured: harness-001 § 5 and triage rows 6–9, 12 | Create nothing |
| 5 — Nature | Procedure text inside two existing agents | A new skill, agent, rule |
| 7 — Mandatoriness | Cost discipline (`0117` axis 7 already classed it so) | Forms 7 and 8 — `guard bash` read limit |
| 8 — Destination | Both: both agents and `new-feature-implement` already travel | Nothing new in `export` |
| 9 — Integration | Block files owned by `new-feature-implement` (`references/blocks/`), not by each design skill | Option 3 |
| Scope | A, B and C, all three | Option 2 as the recommended one |
| C mechanism | Prose: a translation table, no script | A jar mode rendering `ArchitectureTest` |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | A + B + C, blocks under `new-feature-implement/references/blocks/` | 8 | **Approved** |
| 2 | B only; A left to #195, C left until the failure is shown | 7 | Viable — leaves two confirmed gaps open |
| 3 | B with each block owned by its design skill (`<skill>/references/executor-block.md`) | 4 | Rejected — ten owners for one procedure; reordering groups touches ten files |
| 4 | `guard bash` refuses reads over `max_lines` (issue fix A) | 2 | Rejected — contradicts `0117` axis 7; a 150-line cap refuses exemplars the body reads whole (`ApiExceptionHandler` 336, `ArchitectureTest` 292); invariant 6 mirror |
| 5 | Jar mode rendering `ArchitectureTest` from `packages.map` (issue fix C) | 3 | Rejected — renders package strings only; step 4 also needs the blueprint's naming-convention comment, which is prose; one unproven failure |
| 6 | Create nothing | 1 | Rejected — measured twice |

### Option 1 — A + B + C (score 8)

**Motivator:** axes 1 and 9; scope answer.

**B · Blocks out of the agent body.**

- Twelve new files under `.claude/skills/new-feature-implement/references/blocks/`, named by
  the `spec_checklist.blocks` ids of `extensions.json` plus the two always-run blocks:

  | File | Body lines moved (at 9e97f02) | Group |
  |---|---|---|
  | `domain.md` | `### Block 1: Domain` — `:437`–`:467` | domain |
  | `persistence.md` | `### Block 2: Persistence` — `:468`–`:554` | domain |
  | `http.md` | `### Block H` — `:555`–`:614` | adapters |
  | `ai.md` | `### Block A` — `:615`–`:670` | adapters |
  | `search.md` | `### Block I` — `:671`–`:709` | adapters |
  | `rest.md` | `### Block 3` — `:710`–`:777` | adapters |
  | `graphql.md` | `### Block G` — `:778`–`:832` | adapters |
  | `security.md` | `### Block S` — `:833`–`:892` | adapters |
  | `messaging.md` | `### Block M` incl. `#### Form B` — `:893`–`:1032` | adapters |
  | `jobs.md` | `### Block J` — `:1033`–`:1096` | adapters |
  | `cache.md` | `### Block C` — `:1097`–`:1151` | adapters |
  | `tests.md` | `### Block 4` — `:1152`–`:1290` | tests |

  Line numbers are from 9e97f02; locate each range by its heading, not by number.
- Each file's text is the block **verbatim**, its `###` heading promoted to `#`, its `####`
  to `##`. A file over 100 lines opens with `## Contents`, one line per `##` heading
  (`footprint.limits.reference_toc_lines`).
- The agent body replaces the twelve sections with one section, placed right after
  `### Groups — which blocks this run executes`:

  ```markdown
  ### Blocks — one file each, read when the block runs

  Each block's steps, exemplars, rules and line for the final report live in its own file
  under `.claude/skills/new-feature-implement/references/blocks/`. Read a block's file
  whole, once, when the block starts — never a file for a block your group does not run or
  the spec does not carry. Nothing about a block is in this body.

  | Block | File | Runs when |
  |---|---|---|
  | 1 · Domain | `domain.md` | always, group `domain` |
  | 2 · Persistence | `persistence.md` | spec `## 3.` is not `none`, group `domain` |
  | H · Outbound HTTP | `http.md` | spec carries `## 3.3`, group `adapters` |
  | A · AI | `ai.md` | spec carries `## 3.7`, group `adapters` |
  | I · Search | `search.md` | spec carries `## 3.8`, group `adapters` |
  | 3 · REST | `rest.md` | spec `## 4.` is not `none`, group `adapters` |
  | G · GraphQL | `graphql.md` | spec carries `## 4.1`, group `adapters` |
  | S · Security | `security.md` | spec carries `## 4.5`, group `adapters` |
  | M · Messaging | `messaging.md` | spec carries `## 3.5`, group `adapters` |
  | J · Jobs | `jobs.md` | spec carries `## 3.6`, group `adapters` |
  | C · Cache | `cache.md` | spec carries `## 3.4`, group `adapters` |
  | 4 · Tests | `tests.md` | always, group `tests` |
  ```

  The "Runs when" column is the same presence test `spec check` applies
  (`spec_checklist.blocks`); the order of execution stays in § Procedure's first paragraph.
- `**Reads (only):**` gains one bullet:
  `- .claude/skills/new-feature-implement/references/blocks/<block>.md — the block being run, per § Blocks`.
- Every other section of the body stays as it is. A cross-reference inside the body to a
  block ("see Block 4", "Block M's …") stays — the table resolves it.
- `.claude/.ci/AsyncApiTemplateTest.java`: `AGENT` points to
  `.claude/skills/new-feature-implement/references/blocks/tests.md` (where
  `AsyncApiContractIT` and `src/test/asyncapi/asyncapi.snapshot.json` now live); rename the
  constant `EXECUTOR_TESTS_BLOCK`. If either string lives in `messaging.md` instead, require it
  there.
- Body after the cut: 598 lines and 9.0k tokens by `footprint` (605 by `wc -l`), against 1417 lines and 22.2k tokens before. The ~33 lines above the 565 first estimated are § Blocks and the read rule of A.

**A · One read rule for every file the run opens.** In § What enters the context, the
paragraph starting `**Each file enters the context once.**` is replaced by:

```markdown
**Each file enters the context once per group, and only the part the step needs.**

- **The spec and a partial** — by section: `grep -n '^## ' <file>`, then `Read` with
  `offset`/`limit`.
- **A rule** — the section the block cites (`grep -n '^## ' <rule>`, then `Read` that
  range). Whole only when the block cites the rule with no section, and then once.
- **An exemplar** (`*.example`) — whole, once: it is the shape being copied. Do not read it
  again after the file written from it is on disk; read that file instead, by region.
- **A file already in the project** — a class of an earlier group, `ArchitectureTest.java`,
  `pom.xml`: `grep -n` for the member, rule or element you need, then `Read` with
  `offset`/`limit` around it. Never whole. On an `ArchitectureTest` failure, grep the rule
  name the failure prints.
- `cat`, `head` with no count, or `sed -n` with no range prints a whole file: the same as a
  whole `Read`, and never followed by a `Read` of the same file.

After an `Edit`, the tool result already confirms the change. Before the next `Edit` to the
same file, read only the region you will touch: the `format` hook may have rewritten the
file since, which is what leaves an `old_string` that is no longer there.
```

**C · `archunit-installer` step 4 names its mechanism.** Appended to step 4, after the
paragraph ending "… whose own comment says otherwise.":

```markdown
   **How to translate — a table first, then one `Write`.** Before writing the file, list
   every package string the exemplar names (`grep -n '"\.\.' <exemplar>`) and, next to each,
   the project's package from the active blueprint's `packages.map`, or `delete` when the
   blueprint has no such role; add one row per suffix or role rule the naming-convention
   comment changes. Then `Write` the file once, already translated, from the exemplar and
   that table. Never rewrite the exemplar with a script (`python3`, `perl`, `sed -i`): a
   string the map does not hold fails the script, not the translation, and the comment's
   rules are not in any map. Put the table in the report.
```

**Pros:** up to ~8k tokens per start off the body when the spec has no conditional block,
~2k more for groups `domain` and `adapters` (Block 4), riding every request of each of three
starts (harness-001 § 5). The read rule covers the files the body left uncovered (row 3). Step
4 stops leaving the mechanism to improvisation (row 13). Precedent: `0196` cut the
`new-feature` body the same way.

**Cons:** the executor reads files of the skill that invokes it — an agent → skill edge. The
executor already reads `.claude/skills/*/templates/*.example`, so the edge exists. A block
read is one more tool call per block. A and C are prose, still persuasion. `footprint`
applies its 500-line limit to skills only, so it will not flag the agent body growing back.

**Points cut in the rubric:** −1 the agent → skill read edge; −1 A and C are not
guaranteed.

**CI:** a new test, `.claude/.ci/ExecutorBlocksTest.java`, step `Executor block files` in
`.github/workflows/validate.yml` › `hooks-cross-platform` (after `SpecChecklistTest`).
Cases, each with the defect that makes it fail:

| Case | Fails when |
|---|---|
| every `spec_checklist.blocks[].id` of `extensions.json`, plus `domain` and `tests`, has `references/blocks/<id>.md` | a block id is added to `spec_checklist` with no file, or a file is deleted |
| every file under `references/blocks/` is named in the agent body's § Blocks table | a block file exists that the agent is never told to read |
| every file the table names exists | a renamed file leaves the table pointing at nothing |
| the agent body has no `### Block ` heading | a block is pasted back into the body |

`schema` already covers both agents' required sections and the export listing.
`AsyncApiTemplateTest` follows its strings into `tests.md`. A and C: nothing testable — the
read discipline is behaviour, observed through the audit trail's *What grew the context*.

### Option 2 — B only (score 7)

As option 1 without A and C. A stays with #195 item 8; C waits for the reporter's transcript.
Same CI.

### Option 3 — blocks owned by each design skill (score 4)

Each block file under the skill that designs its layer. Ten owners, the executor's order spread
across them; Block 4 goes to `test-architect`. Rejected in the interview.

## References

| Claim | Source |
|---|---|
| Executor starts once per group | `0130`; `java-spring-boot-developer.md:411` |
| Read cost is not a guarantee | `0117` axis 7 |
| Body cut into `references/` | `0196` option 1 A |
| Block sizes and saving | `.claude/lessons-learned/harness-001.md` § 5 |
| Executor class required sections | `extensions.json` `agent_classes.executor` (`:590`) |
| Block presence test | `extensions.json` `spec_checklist.blocks`; `0197` |
| A hook needs an observed failure | `@CLAUDE.md` invariant 6 |
| What travels | `@CLAUDE.md` invariant 9; `export.skills.include` (`new-feature-implement`), `export.agents.include` |
| `reference_toc_lines` | `extensions.json` `footprint.limits` |

## Handoff notes for Phase 4

Files to touch (the Propagation table below is filled from what was actually touched):

| File | Change |
|---|---|
| `.claude/skills/new-feature-implement/references/blocks/{domain,persistence,http,ai,search,rest,graphql,security,messaging,jobs,cache,tests}.md` | New — each block verbatim, per Option 1 B |
| `.claude/agents/java-spring-boot-developer.md` | Twelve block sections removed; § Blocks table added after § Groups; `**Reads (only):**` bullet; § What enters the context paragraph replaced (A) |
| `.claude/agents/archunit-installer.md` | Step 4 paragraph appended (C) |
| `.claude/skills/new-feature-implement/SKILL.md` | One line in its `## Contract` (or `## References`): it owns `references/blocks/`, the executor's per-block procedure, read by `java-spring-boot-developer` one block at a time (`0199`) |
| `.claude/.ci/ExecutorBlocksTest.java` | New — the four cases of Option 1's CI table; single-file Java, `java` only, reads `extensions.json` with the same minimal JSON approach its neighbours use |
| `.claude/.ci/AsyncApiTemplateTest.java` | `AGENT` → the block file holding the strings, renamed `EXECUTOR_TESTS_BLOCK` |
| `.github/workflows/validate.yml` | Step `Executor block files` › `java .claude/.ci/ExecutorBlocksTest.java`, job `hooks-cross-platform`, right after the `SpecChecklistTest` step, same shape |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | One row for the new step, next to `SpecChecklistTest`'s |
| `docs/pt-br/03-new-feature.md`, `docs/en/03-new-feature.md` | Only if they describe the blocks as living in the agent body: point to `references/blocks/` |
| `docs/diagrams/**` | Only if `Diagrams.java check` fails after the change: `java .claude/skills/nerviz-diagrams/scripts/Diagrams.java render` |

Rules for the move: text verbatim, no rewording; heading levels promoted by one (`###`→`#`,
`####`→`##`); a file over 100 lines opens with `## Contents`; no unquoted shell glob
(`ShellGlobSchemaTest`). No `ArchHook.java` change, so no jar rebuild. No `migrations` entry:
nothing in a project's code changes. No commit.

## Verification commands (the main thread runs them again)

```
java .claude/hooks/ArchHook.java schema
java .claude/.ci/ExecutorBlocksTest.java            # green
#   red 1: mv references/blocks/cache.md aside → fails naming `cache`; restore
#   red 2: append "### Block X: probe" to the agent body → fails naming the heading; restore
#   red 3: drop the `cache.md` row from the § Blocks table → fails naming `cache.md`; restore
java .claude/.ci/AsyncApiTemplateTest.java
java .claude/.ci/ShellGlobSchemaTest.java
java .claude/.ci/SpecChecklistTest.java
java .claude/.ci/AgentTerritoryTest.java
java .claude/skills/nerviz-diagrams/scripts/Diagrams.java check
java .claude/hooks/ArchHook.java footprint | grep -E 'java-spring-boot-developer|new-feature-implement'
java .claude/hooks/ArchHook.java export <scratch-dir> --blueprint hexagonal --dry-run | grep references/blocks
claude plugin validate .claude/skills
```

## Propagation

| File | Change |
|---|---|
| `.claude/skills/new-feature-implement/references/blocks/{domain,persistence,http,ai,search,rest,graphql,security,messaging,jobs,cache,tests}.md` | New — the twelve blocks verbatim from the agent body, headings promoted one level; `messaging.md` and `tests.md` open with `## Contents` |
| `.claude/agents/java-spring-boot-developer.md` | Twelve block sections removed (1424 → 605 lines); `### Blocks` table after `### Groups`; `**Reads (only):**` bullet; § What enters the context paragraph replaced (A) |
| `.claude/agents/archunit-installer.md` | Step 4 gains the "How to translate" paragraph (C) |
| `.claude/skills/new-feature-implement/SKILL.md` | `## References` line: owns `references/blocks/` |
| `.claude/.ci/ExecutorBlocksTest.java` | New — the four cases of the CI table |
| `.claude/.ci/AsyncApiTemplateTest.java` | `AGENT` → `EXECUTOR_TESTS_BLOCK`, pointing at `references/blocks/tests.md` |
| `.github/workflows/validate.yml` | Step `Executor block files` in `hooks-cross-platform`, after `SpecChecklistTest` |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | One row and one command line for the new step |
| `docs/diagrams/layers/*` | Re-rendered (`Diagrams.java render`) and PNG re-exported (`export.mjs layers`): the export manifest grew by the block files |

Goes to the generated project: **yes** — `new-feature-implement` (with `references/`),
`java-spring-boot-developer` and `archunit-installer` already travel; `/arch-adopt` pulls it.

## CI coverage

- **Job › step:** `hooks-cross-platform` › `Executor block files` (`java .claude/.ci/ExecutorBlocksTest.java`).
- **Proves:** each `spec_checklist.blocks` id plus `domain` and `tests` has a file; each file under
  `references/blocks/` is named in the agent's § Blocks table; each file the table names exists; the
  agent body has no `### Block ` heading.
- **Green run:** `OK 12 block ids, 12 files named, none in the agent body` (exit 0).
- **Red 1** (`cache.md` moved aside, exit 1):
  `FAIL block id `cache` has no file .claude/skills/new-feature-implement/references/blocks/cache.md` and
  `FAIL § Blocks names `cache.md` but .claude/skills/new-feature-implement/references/blocks/cache.md does not exist`.
- **Red 2** (`### Block X: probe` appended to the agent body, exit 1):
  `FAIL agent body line 607 has the heading `### Block X: probe`: blocks live in .claude/skills/new-feature-implement/references/blocks`.
- **Red 3** (`C · Cache` row dropped from the table, exit 1):
  `FAIL file `cache.md` exists but the agent's § Blocks table does not name it`.

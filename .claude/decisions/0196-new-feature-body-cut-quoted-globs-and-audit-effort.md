# 0196 · `/new-feature` body under 500 lines, quoted shell globs checked by `schema`, and the run's effort in the audit

- **Date:** 2026-10-31
- **Scenario:** Issue #188, triaged at f8e2c98: `/new-feature` context grows 80k→205k per run; `footprint` flags the `new-feature` body (950 lines) and `entry-guardrail.md` (no contents list); unquoted `--include=*.java` remains in `java-spring-boot-developer.md` and the rules.
- **Decision:** Option 1, package A–E — edits to `skills/new-feature/` (+ new references), `skills/use-case-design/SKILL.md`, 13 files of quoted globs, `shell_globs` + a `migrations` entry in `extensions.json` read by `schema`, and `effort` in `audit`
- **State:** approved by Lucas Fernandes, on 2026-10-31

## Reproduced on disk

Confirmed rows of the `issue-verifier` table (triage of #188 at f8e2c98, layers static plus
the reporter's audit trail). Refuted and unproven rows, and the issue's proposed fixes, are
not inputs (`0103`).

| # | Claim | Evidence |
|---|---|---|
| 1 | `new-feature/SKILL.md` body is past the 500-line limit | `footprint`: "`new-feature` \| 950 \| 16.3k … over 500 lines". Sections by line: Why 11, Contract 25 (11,934 bytes), Spec lifecycle 193, Entry guardrail 227, Procedure 320 (35,343 bytes; `### consolidation` 595–810, `### approval` 811, `### report` 865), End of flow 796, Template 888 |
| 1b | The body is loaded once and re-read for the rest of the run | audit report 2026-10-30T23-18-07: "`/new-feature` \| Skill \| — \| 9,228 \| 32 \| 295,296" |
| 2 | `use-case-design` listed every `.java` of `src/main` (140 paths) though its step 4 asks for targeted `Glob`/`Grep` | report:114; `use-case-design/SKILL.md:166-168` "Read the code before proposing. `Glob`/`Grep` to know what already exists: aggregate, ports, repository, controller." The listing is ~2.5k of the 9,377 tokens that call added (row 2a); the owner is step 4's wording, not an injection |
| 3 | Layer-skill bodies stay inline for the rest of the run | report:116–120 |
| 4 | `entry-guardrail.md` is 133 lines with no contents list, read whole on turn 1 | `footprint`: "skill `new-feature` — `references/entry-guardrail.md` over 100 lines without a contents list"; report:105 |
| 5c | `no matches found: --include=*.java` in `java-spring-boot-developer` | report 2026-10-07T23-33-45. Re-observed in the designer session of this record: `(eval):1: no matches found: --include=*.md` — the Bash tool runs the user's shell, zsh, which aborts on an unmatched glob before the command runs; bash passes it through, which is why CI never sees it |
| 7 | 214,337 billable, peak 80,413 → 205,170 | report:11, 68, 91–97; `history.jsonl` |

Found during the interview, not in the issue:

- **60 unquoted sites in 13 files**, not 3 rules: `agents/java-spring-boot-developer.md` (1),
  `rules/authorization.md` (8), `persistence.md` (8), `caching.md` (6), `http-client.md` (6),
  `messaging.md` (5), `inbound-admission.md` (4), `scheduling.md` (4), `personal-data.md` (3),
  `search.md` (3), `api-rest.md` (2), `secrets.md` (1), `testing.md` (1). `0037` item 7 quoted
  the 5 sites of its day; the defect came back with every rule written since — prose fixed it
  once and nothing kept it fixed.
- **The measured run ran at effort `low`.** Every assistant entry of transcript
  `0ce960f6-b5ba-4457-8710-d80182b62289` (bank-app, 2026-10-30T23-18) carries
  `"effort":"low"` — `domain-modeling` 8, `use-case-design` 18, `test-architect` 15 entries.
  The "domain-modeling asked 0 of 7" fact behind the issue's fork proposal is confounded by
  effort, and the audit trail does not record it: no report, `history.jsonl` or `nodes.jsonl`
  row says at which effort a run was made.
- `Diagrams.java` (`nerviz-diagrams`) parses every `### <id> · <Name>` heading under
  `## Procedure`, and every `## `/`### ` heading elsewhere followed by `**Runs:**`, with its
  field block (`Runs`, `Then runs`, `Stops when`, `Skips when`, `Hands over`). A step moved
  whole into `references/` drops out of the committed diagrams and `Diagrams.java check` fails.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Measured run (audit trail of bank-app) plus `footprint` flags at HEAD, plus a zsh `no matches found` reproduced in this session | create nothing |
| 2 — Trigger | `/new-feature`, manual; the glob is hit by whichever piece the model runs a rule's command from | a new skill or agent |
| 5 — Nature | Edits to existing procedure bodies; one guarantee (glob); one measurement (effort) | a new piece of any form for the body cut |
| 6 — Isolation | Fork of `domain-modeling` kept **out**: the 0/7 asks came, at least in the measured run, from effort `low`; other runs have a history of asking (user) | Form 3 / `context: fork` |
| 7 — Mandatoriness | The glob regressed after `0037` fixed it by hand; it must not come back | prose-only for the glob → `schema` |
| 8 — Destination | Both: `new-feature`, `use-case-design`, the rules, the agent, `ArchHook.java` and `extensions.json` travel | — |
| 9 — Integration | `Diagrams.java` owns the step grammar; `schema` owns `## Contract` + why section (`0058`); `audit` owns the trail's fields | moving step headings or `## Contract` out of the body |
| 16 — Existing mode | `schema` already scans markdown for `$ARGUMENTS` traps and injections; `audit` already reads `message.model` per turn | a new mode — both are extensions of existing ones |
| — Cut depth | Complete, headings stay (user, after the risk table) | the minimal cut |
| — Glob guarantee | Quotes + `schema` + a `migrations` entry (user) | quotes only |
| — Effort | Add the run's effort to the audit report header and ledger, for later analysis (user) | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Package A–E below | 8 | **Recommended** |
| 2 | A–C + E, glob quoted by hand only | 7 | Viable with caveat — already regressed once after `0037` |
| 3 | Minimal cut of the `new-feature` body (move consolidation/approval/report prose and the Template only) + B–E | 7 | Viable with caveat — body stays over 500 (~610); after compaction the re-attached head is still § Contract |
| 4 | `context: fork` for `domain-modeling` | 3 | Rejected now — axis 1 confounded by effort `low`; pending the effort column of E and runs at normal effort |
| 5 | Inject aggregate paths / `00-use-case.md` paths into `use-case-design` (issue fixes 2 and 5) | 3 | Rejected — a hard-coded `*/domain/model/*` breaks invariant 7; `00-use-case.md` paths print nothing in a project that did not run `english-partial-names` |
| 6 | Move § Why and § Contract wholly out of the body (issue fix 1 as proposed) | 2 | Rejected — `skill_classes.universal_sections` and `why_section` require both in the body (`0058`); `schema` fails |
| 7 | Split `entry-guardrail.md` by branch | 2 | Rejected — the file has no branches, only sequential §§ 1–4 every entry runs (`new-feature/SKILL.md:315`), and `new-feature-implement` shares it |
| 8 | Create nothing | 2 | Rejected — every symptom is measured |

### Option 1 — package A–E (score 8)

**Motivator:** axes 1, 7 and 9.

**A · `new-feature/SKILL.md` under 500 lines, step headings kept.**
- New order of the body: `## Why this is Form 2` → `## guardrail · Entry guardrail` (as
  today) → `## Procedure` → `## End of flow` → `## Spec lifecycle` → `## Contract`
  (summary) → `## References` → `## Entry into generated projects`.
- `## Contract` keeps, in ≤ 15 lines: the `**Class:**` line `schema` cross-checks, the
  territory in one sentence, what it owns, what it never writes, what it delegates, and
  "Full contract: `references/contract.md`". Everything else of today's § Contract moves to
  `references/contract.md`, verbatim.
- `### consolidation`, `### approval`, `### report` and every step under `## End of flow`
  keep their heading and their whole field block (`**Runs:**` … `**Hands over:**`) in the
  body. Their prose moves to `references/consolidation.md` and
  `references/approval-and-report.md`, and each step keeps one imperative line in its place:
  "Read `references/consolidation.md` now, whole, before writing `UC-NNN-spec.md`." (same
  shape for approval/report).
- `## Template: UC-NNN-spec.md` leaves the body: `templates/feature-spec.md.example`
  already holds the shape; the section becomes one line in `## References` pointing there,
  and whatever the section says that the template does not goes into the template.
- Each new reference over 100 lines opens with `## Contents`.

**B · `references/entry-guardrail.md`** opens with `## Contents`, one line per `##` heading
(§§ 1–4). No split.

**C · `use-case-design/SKILL.md` step 4** gains, after "aggregate, ports, repository,
controller.":
"Never list the source tree whole (`find src -name '*.java'`, `ls -R`, `tree`): one `Glob`
per component kind the request names, then `Grep` for its names. Inside `/new-feature`
whatever a step prints stays in context for every step after it."

**D · Quoted globs, guaranteed by `schema`.**
- Every one of the 60 sites becomes `--include='*.java'` (single quotes; the same for
  `--exclude=`, `--exclude-dir=`, and every other glob value).
- `extensions.json` gains a block `shell_globs` (sibling of `injections`), read by `schema`:
  `scope` — `.claude/**/*.md`, `.claude/**/*.example`, `CLAUDE.md`; `exclude` —
  `.claude/decisions/**`, `.claude/lessons-learned/**`; `patterns` —
  `(^|\s)--(include|exclude|exclude-dir)=[^\s'"]*[*?\[]`; `exempt_patterns` — empty;
  a `$comment` naming the zsh failure and this record.
- `schema` fails, per hit: `<file>:<line> — unquoted glob in a shell flag: zsh (the Bash
  tool runs the user's shell) aborts with "no matches found" before the command runs.
  Quote it: --include='*.java'`. No pattern in the Java (invariant 10).
- `migrations` entry `quoted-shell-globs`, every blueprint id:
  - `note`: "`doctor gate` now fails on a shell glob left unquoted in a flag
    (`--include=*.java`) in this project's `.claude/` files or root `CLAUDE.md`: zsh aborts on
    it before the command runs. The files `/arch-adopt` writes are already quoted; a file this
    project wrote itself is not touched."
  - `prompt`: "Quote every glob value of a `--include=`, `--exclude=` or `--exclude-dir=` flag
    in this project's own `.claude/` files and root `CLAUDE.md`, as `--include='*.java'`, then
    run `java .claude/hooks/ArchHook.java schema` until it reports none."

**E · Effort in the audit trail.**
- `audit` reads the top-level `effort` of every transcript entry it already reads for
  `message.model` (main and subagent transcripts), into `Turn`, aggregated per piece like the
  model: the distinct values in first-seen order, comma-joined.
- Report header: a row `| 🧠 Effort | <values or —> |` right after `🤖 Model`.
- `history.jsonl` run row and `nodes.jsonl` piece row: field `effort`, next to `model`.
- `audit summary` table: a `🧠 Effort` column after `🤖 Model`; `—` for rows written before.
- The field is observed in the transcript, not documented (every entry seen so far carries
  `effort` equal to `perTurnEffort`); absent → `—`. The Javadoc says so.
- `audit-usage/SKILL.md`'s list of header fields gains **Effort**, next to **Model**: "the
  effort the run's turns were made at — compare two runs of a piece only at the same effort".

**Pros:** the body falls under 500 and the procedure is the head a compaction re-attaches
(harness-001 § 6); the context before consolidation drops by the moved prose; the glob
regression is closed in CI and in every generated project's `doctor gate`; the next fork
decision has effort on every run row.

**Cons:** large diff over the most-used skill with no end-to-end test (one measured
`/new-feature` run in bank-app after `/arch-adopt` is the proof); consolidation now depends
on the model reading a reference (persuasion); billable barely moves — the re-reads it cuts
are cache reads.

**Points cut in the rubric:** 4 Enforcement — step 4's wording and the consolidation read are
persuasion (−1); 5 Maintenance — four new reference files and a 60-site edit (−1, rounded).

**CI:**
- A: `validate · design › frontmatter schema` (`## Contract`, why section, `**Class:**`,
  injection paths) and `validate · design › committed diagrams match the data they cite`
  (`Diagrams.java check` + `DiagramsTest` — the step headings stayed). `footprint` reports
  the 500-line limit and is never a gate (`0188`).
- B, C: nothing testable — B is reported by `footprint` (never a gate), C is model behavior,
  measured by the audit trail.
- D: new `.claude/.ci/ShellGlobSchemaTest.java`, step "schema blocks a shell glob zsh aborts
  on" in `validate · hooks-cross-platform`. Cases, each against `java -jar
  .claude/hooks/ArchHook.jar schema` over a temp copy of the tree:
  1. a rule line `grep -rn X --include=*.java src/main` → exit ≠ 0, stderr names the file and
     line. Fails when the pattern is dropped from `shell_globs.patterns`.
  2. `--include='*.java'` → exit 0. Fails when the pattern ignores quotes.
  3. `--include="*.java"` → exit 0. Same defect.
  4. `--exclude-dir=*` unquoted in an agent file → exit ≠ 0. Fails when only `include` is
     matched.
  5. the line of case 1 in `.claude/decisions/0001-*.md` → exit 0. Fails when `exclude` is
     ignored.
  6. `--include=src` (no glob character) → exit 0. Fails on an over-broad pattern.
  The `migrations` entry is covered by `MigrationsSchemaTest` and `schema` as they stand.
- E: a case in `.claude/.ci/AuditRenderTest.java`:
  1. fixture transcript whose assistant entries carry `"effort":"low"` → report header has
     `| 🧠 Effort | low |`, the `history.jsonl` row `"effort":"low"`, the root `nodes.jsonl`
     row `"effort":"low"`. Fails when `Turn` does not read `effort`.
  2. main transcript `low`, a subagent transcript `high` → header `low, high`, the subagent's
     node row `high`. Fails when effort is taken from the main transcript only.
  3. no `effort` field → header `| 🧠 Effort | — |`. Fails when the absent field prints
     `null`.

### Option 2 — quotes by hand only (score 7)

The 60 sites quoted, no `schema` check, no migration. Loses form fit (§ 2.2: the check reads
files → Form 7) and enforcement. `0037` is the precedent: same fix, same prose, back in 13
files.

### Option 3 — minimal cut (score 7)

Only the prose of consolidation/approval/report and the Template move. ~610 lines, still
`over`; § Contract stays first after § Why, so a compaction re-attaches the contract instead
of the procedure. Loses context cost and form fit for the limit `0188` set.

### Option 4 — `context: fork` for `domain-modeling` (score 3)

Pending, not rejected on merit. The run that showed 0/7 asks ran at effort `low`; the user
reports other runs with questions. Re-open with the effort column of E filled for runs at
the project's normal effort, and one measured forked run (its cold prefix was never
measured). Contradicts `0003`; a fork also runs in the background by default and may lack
`AskUserQuestion` (`claude-help.md` § Running the skill isolated).

## References

| Claim | Source |
|---|---|
| `## Contract` and the why section are required in every SKILL.md body | `extensions.json` `skill_classes.universal_sections`, `why_section`; `0058` |
| A body over 500 lines and a reference over 100 lines with no contents are flagged, never gated | `extensions.json` `footprint.limits`; `0188` |
| On compaction a re-attached skill keeps its first ~5k tokens | `.claude/lessons-learned/harness-001.md` § 6 (BP-09) |
| Body trimming was offered and not chosen, not rejected on data | `0117` § Interview, Options row "Trim the skill bodies" |
| Step headings and field blocks are parsed from SKILL.md | `.claude/skills/nerviz-diagrams/scripts/Diagrams.java:563-700`; `0190`, `0193` |
| The glob was quoted once by hand | `0037` item 7 |
| Layer skills stay inline because they ask | `0003`, `0005`, `0006` |
| Patterns a mode reads live in `extensions.json` | `CLAUDE.md` invariant 10 |
| `migrations` is for a convention change that leaves a project's files behind | `extensions.json` `migrations.$comment`; `0104` |
| The generated CI runs `doctor gate`, which includes `schema` | `project-bootstrap/templates/ci.yml.example:90-96` |
| The audit reads `message.model` per turn | `ArchHook.java` `Turn` (6717), scan (7498), ledger (7291) |

## Propagation

Files actually touched in Phase 4 (listed beyond the plan: the three references the body cut
needed, the layers diagram re-render, the `schema` hook change).

| File | Change |
|---|---|
| `.claude/skills/new-feature/SKILL.md` | A — 957 → 495 lines (body 488): new order, `## Contract` summary, consolidation/approval/report prose to references, input-table notes to a reference, § Template out, one-line reads |
| `.claude/skills/new-feature/references/contract.md` | A — new; former § Contract, verbatim; `## Contents` |
| `.claude/skills/new-feature/references/consolidation.md` | A — new; prose of `### consolidation`; `## Contents` |
| `.claude/skills/new-feature/references/approval-and-report.md` | A — new; prose of `### approval` and `### report`; `## Contents` |
| `.claude/skills/new-feature/references/input-table-notes.md` | A — new, **not in the plan**: rows 3 and 5 explanation and the error shapes, needed to reach the 500-line body |
| `.claude/skills/new-feature/templates/feature-spec.md.example` | A — unchanged: the removed § Template said nothing the template lacks |
| `.claude/skills/new-feature/references/entry-guardrail.md` | B — `## Contents` |
| `.claude/skills/use-case-design/SKILL.md` | C — step 4 sentence |
| `agents/java-spring-boot-developer.md` and 12 `rules/*.md` | D — 52 lines (53 flag occurrences) quoted |
| `.claude/schemas/extensions.json` | D — `shell_globs` block, `migrations` entry `quoted-shell-globs` |
| `.claude/hooks/ArchHook.java` + rebuilt `ArchHook.jar` | D — `checkShellGlobs`, `sweepShellGlobs`; E — `Turn.effort`, `Usage.efforts`, header row, run and node rows, `audit summary` column |
| `.claude/.ci/ShellGlobSchemaTest.java` | D — new test, 6 cases plus the tree as shipped |
| `.github/workflows/validate.yml` | D — step "schema blocks a shell glob zsh aborts on" in `hooks-cross-platform` |
| `.claude/.ci/AuditRenderTest.java` | E — 3 cases (10 checks) |
| `.claude/skills/audit-usage/SKILL.md` | E — **Effort** next to **Model** |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | D — `schema` blocks an unquoted glob and why (zsh); A — a step's prose may move, its heading and field block may not |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | D — diagram node, table row and command line for the new test |
| `docs/diagrams/layers/*` (svg, drawio, excalidraw, animated svg, `dist/layers.png` + hash) | re-rendered: the layers view cites the decisions count (181 with this record) |
| docs citing a section the new `SKILL.md` no longer has | none found (`grep -rn 'new-feature/SKILL.md\|§ Contract\|Template: UC' docs`: only whole-file citations) |

Goes to the generated project: **yes** — every edited skill, rule and agent is in `export`,
and `ArchHook.java`, `ArchHook.jar` and `extensions.json` travel whole.

## Handoff notes for Phase 4

- Order: D first (Java + `extensions.json` + the 60 sites + test), rebuild the jar, then E
  (Java + test), rebuild, then A–C (markdown only), then docs, then fill this record.
- `shell_globs` scope covers `.claude/**/*.md`, `.claude/**/*.example`, `CLAUDE.md` and
  excludes `.claude/decisions/**` and `.claude/lessons-learned/**`. Any new prose that
  shows an unquoted glob as an example (SKILL, references, pitfalls docs live outside the
  scope but the SKILL/reference files do not) must quote it or sit in a record.
- A already-correct reading of `Diagrams.java`: after A, run `java
  .claude/skills/nerviz-diagrams/scripts/Diagrams.java check`; if the committed diagrams
  differ only by line numbers they cite, regenerate with the skill's own command and commit
  the result; if a step disappeared, the cut is wrong — put the heading and field block back.
- A's body target: `wc -l` of the body (file minus the 7 frontmatter lines) ≤ 500, and
  `footprint` no longer prints `over 500 lines` for `new-feature`.
- Do not change `# Procedure` step wording, field blocks, gates or ids: only move prose.
- Never commit; never touch `.claude/settings.local.json`.

## Verification commands (the main thread runs them again)

```
java .claude/hooks/ArchHook.java build
java .claude/hooks/ArchHook.java build --verify
java .claude/hooks/ArchHook.java schema
java .claude/hooks/ArchHook.java doctor
java .claude/hooks/ArchHook.java footprint
java .claude/.ci/ShellGlobSchemaTest.java
java .claude/.ci/AuditRenderTest.java
java .claude/.ci/MigrationsSchemaTest.java
java .claude/.ci/DiagramsTest.java
java .claude/skills/nerviz-diagrams/scripts/Diagrams.java check
claude plugin validate .claude/skills
grep -rnE -e "(^|[[:space:]])--(include|exclude|exclude-dir)=[^[:space:]'\"]*[*?[]" .claude CLAUDE.md --include='*.md' --include='*.example' | grep -v '/decisions/\|/lessons-learned/'   # must print nothing
```

## CI coverage

Workflow `.github/workflows/validate.yml`.

- **D** — job `hooks-cross-platform` (ubuntu · macos · windows), step "schema blocks a shell glob
  zsh aborts on": `java .claude/.ci/ShellGlobSchemaTest.java`. Green run: 7 checks (the 6 cases
  and the tree as shipped). Red runs, each by editing `shell_globs` in `extensions.json`, rebuilding
  nothing (the data is read at run time):

  | Case | Defect injected | Red |
  |---|---|---|
  | 1 unquoted `--include` in a rule blocked | `patterns` emptied | "unquoted --include in a rule blocked — expected exit 2 naming `.claude/rules/naming.md:`, got 0" |
  | 2 single-quoted accepted | pattern made to ignore quotes (`=.*[*?\[]`) | "single-quoted --include accepted — expected exit 0, got 2" |
  | 3 double-quoted accepted | same defect | "double-quoted --include accepted — expected exit 0, got 2" |
  | 4 unquoted `--exclude-dir` in an agent blocked | `(include)` only | "unquoted --exclude-dir in an agent blocked — expected exit 2 naming …java-spring-boot-developer.md:, got 0" |
  | 5 decision record ignored | `.claude/decisions/` dropped from `exclude` | "unquoted glob in a decision record ignored — expected exit 0, got 2" |
  | 6 `--include=src` accepted | pattern without the glob-character class | "--include with no glob character accepted — expected exit 0, got 2" |

  The `migrations` entry is covered by `MigrationsSchemaTest` (green) and `schema` as they stand.
- **A** — job `design`, steps "frontmatter schema" (green: `## Contract`, why section, `**Class:**`
  and injection paths intact) and "committed diagrams match the data they cite" (green:
  `Diagrams.java check` and `DiagramsTest`; the new-feature diagram did not change, the layers
  diagram was re-rendered for the decisions count). `footprint` no longer flags `new-feature`
  (488 lines of body) or `entry-guardrail.md`, and is never a gate.
- **B, C** — nothing testable, as designed.
- **E** — job `hooks-cross-platform`, the existing step that runs `AuditRenderTest.java`. Green: 10
  new checks. Red runs, by editing `ArchHook.java` and rebuilding the jar:

  | Case | Defect injected | Red |
  |---|---|---|
  | 1 every entry `low` | `Turn` built with `null` instead of `get(e, "effort")` | header, run row and node row of case 1 (and case 2) missing |
  | 2 main `low`, subagent `high` | `usageOf` stops adding the subagent's effort | "effort: main low and subagent high -> both in the header — missing: \| 🧠 Effort \| high, low \|", the run row and the node row |
  | 3 no field | header written with `String.valueOf(all.effort())` | "no field -> header `—` — missing" and "the absent field never prints null — found: null" |

Deviations found in Phase 4, from the plan above:

- The unquoted sites are 52 lines (53 flag occurrences), not 60. The verification grep prints nothing.
- `nodes.jsonl` has no row for the root piece, only for nested ones: E case 1 asserts the nested
  agent's node row instead of a "root" row. In case 2 the header reads `high, low`, not
  `low, high`: the report adds the nested agents' usage before the main thread's, as it does for
  `model`.
- The body was 515 lines after the planned cuts; `references/input-table-notes.md` (prose of rows 3
  and 5 and the error shapes, no field block touched) brings it to 488.
- `shell_globs.exclude` also lists `.claude/skills/nerviz-diagrams/node_modules/`, so the export
  tooling's cache never lands in the scope.


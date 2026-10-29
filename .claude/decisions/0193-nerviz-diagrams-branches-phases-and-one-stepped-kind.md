# 0193 · nerviz-diagrams draws branches, phases and the reading order from the step contract, in one stepped kind

> Supersedes in part `0192-nerviz-diagrams-draws-the-orchestrators.md`: its rule
> `"emphasis": [<the step ids that carry a gate>]`, the `steps` list of a stepped view, the view
> kind `pipeline` and the view name `docs/diagrams/pipeline/` (also named in `0190`, line 168).

- **Date:** 2026-10-29
- **Scenario:** Fix the root cause behind the nerviz-diagrams findings: every branch, phase,
  conditional, soft dependency and flow point the approved prototypes draw becomes data in the
  SKILL.md of the skill a view draws and code in `Diagrams.java` — never text typed into a
  `view.json` or an SVG. Base: the uncommitted first pass on `feat/nerviz-diagrams-orchestrators`
  (root node, no `self` chips).
- **Decision:** Option A — a field block under each step heading (`Runs`, `Then runs`,
  `Stops when`, `Skips when`, `Hands over`; `Reads` and `Note` fields), steps allowed outside
  `## Procedure`, kind `pipeline` merged into `orchestrator`, view `pipeline` renamed `new-feature`
- **State:** approved by Lucas Fernandes, on 2026-10-29. Phase 4 delegated to a `sonnet`
  subagent per `0191`, reviewed in the main thread

## Reproduced on disk

| Claim | Evidence at `bab45c6` + working tree |
|---|---|
| A `stop`/`gate` step carries no condition nor exit text | `Runs` grammar of `Diagrams.java` `extractSteps`; `view.schema.json` `$defs.gate` |
| `new-feature-implement` step 2 draws five chains with no order | its `**Runs:**` line is one flat comma list; § 2 items 1–3 of its body give the order |
| `Reads` exists only in prose | `new-feature/SKILL.md` lines 95–125 ("reads 3 and 3b when present", …); `depthOf` reads `depends_on` only, so 3b (depth 3) is drawn below 3c (depth 2) |
| Entry guardrail, Short path, Approval, Final report are no steps | `## Entry guardrail` sits outside `## Procedure`; `### Short path — …` has no ` · `; `### Approval …` and `### Final report` sit under `## End of flow` |
| Step 3 and step 1 of new-feature lack `Conditional` | `### 3 · REST` body: "`inbound GraphQL adapter` alone → skip this step"; step 1 "(new case only)" |
| Two numberings in new-feature | "Integrates with" list: jobs `5`, persistence `6`, cache `6b`, tests `7`; headings `4b`, `5`, `5b`, `6`. `new-feature-implement` has a second numbered list (`1.`–`3.`) |
| "5b reads 3c, 3e and 5" is the list's numbering | the list's `5` is `jobs-architect`; `cache-architect/SKILL.md` lines 83, 99, 254 read `35-jobs.md`, `28-http-client.md`, `23-search.md` → in heading ids: 3c, 3e, **4b** |
| Arrows visible before their boxes | `scene()` sets `l.step = stepOf.getOrDefault(l.id, -1)` and derived edge ids are never in `steps` → `open()` writes no class |
| Half arrowheads in PNG | `svg()` marker `orient="auto-start-reverse"`; `docs/diagrams/init-project/dist/init-project.png` |
| `**` printed as a chip's territory | `chip()` → `territory(write_allow)`; `agent:project-initializer` and `skill:project-bootstrap` have `["**"]` |
| Root prints the whole territory | `platform-upgrade`'s effective `write_allow` has 14 globs |
| Edge 3c → 5 crosses 3b | straight `<line>` between non-adjacent rows in `scene()` |
| Emphasis restates the gate | `0192` "emphasis = steps that carry a gate"; nothing in the legend explains the stroke |

## Interview

The scenario answered every axis that eliminates forms; no question was asked.

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Prototypes approved; each finding is a diagram that lies or hides flow | create nothing |
| 2 — trigger | `/nerviz-diagrams`, unchanged | — |
| 5 — nature | Extension of an existing procedure, its contract and its script | new skill, rule |
| 7 — mandatoriness | Freshness and the contract stay gated by `Diagrams.java check` in CI (`0190`) | session hook |
| 8 — destination | Diagrams: this repository. The new field lines sit inside `new-feature`, `new-feature-implement`, `platform-upgrade` (travel) and `init-project` (does not) | — |
| 9 — integration | Same reads and writes as `0192`; the four SKILL.md are edited at design time | — |
| 17 — CI | `validate · design › committed diagrams match the data they cite` runs `check` + `DiagramsTest`; cases added below | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | Field-line block under each step (`Runs`, `Then runs`, `Stops when`, `Skips when`, `Hands over`), `Reads`/`Note` fields, steps allowed outside `## Procedure`, kind `pipeline` merged into `orchestrator`, view renamed `new-feature` | 8 | **Approved** |
| B | Same contract, but a separate `**Flow:**` marker for guardrail/short path/approval, and kind `pipeline` kept | 6 | Rejected at approval — two step declarations, two render paths |
| C | Branches, phases and flow points declared per view in `view.json` | 2 | Rejected — free text in a view, duplicated from the SKILL.md that owns it; what the scenario forbids |
| D | One `**Runs:**` line with operators (`;` sequence, `&` parallel, `?` conditional, `◆` verdict) | 4 | Rejected — unreadable in four travelling skills; a verdict needs two text fields a one-liner cannot hold |
| E | Create nothing — keep the prototypes as hand-made assets | 1 | Rejected — what `0190` exists to stop |

### Option A (score 8)

**Motivator:** axis 9 — every fact lives in the step that owns it (invariant 2); one renderer
path for the four views (invariant 7: a fourth orchestrator needs no Java).

#### 1 · The step contract v2 — owner `references/step-contract.md`

1. **Scope.** The whole file, outside fenced code blocks. Inside `## Procedure` (a heading that
   starts with `## Procedure`) every `### ` heading containing ` · ` is a step and must carry a
   field block (exit 2 otherwise, as today). Outside it, a `## ` or `### ` heading containing
   ` · ` is a step **only** when its next non-blank line starts with `**Runs:**`; otherwise it is
   prose (new-feature's `### 1 · Input table — closed` stays prose). Steps are ordered by their
   line in the file: **the written order is the execution order**.
2. **Step heading.** `#{2,3} <id> · <Name>`, id `[0-9]+[a-z]?` or `[a-z][a-z-]*`. Label: a
   numeric id → `<id> · <Name>`; a word id → `<Name>` (replaces the `consolidation` special case).
3. **Retired grammar.** `### Step <id>:` in scope → exit 2, unchanged.
4. **Field block.** The lines right after the heading that start with a field, blank lines
   between them allowed, ending at the first non-blank line that starts with none. In order:
   - `**Runs:** <phase>( · **Depends on:** <ids>)?( · **Reads:** <ids>)?( · **Conditional:** yes)?( · **Gate:** <gates>)?( · **Note:** <note>)?` — required, first.
   - `**Then runs:** <phase>( · **Note:** <note>)?` — zero or more; each is the next phase, run
     after the previous one ends.
   - `**Stops when:** <condition> · **Says:** <line>(; <line>)?` — a branch placed after the
     phase line it follows; yes ends (`stop`) or blocks (`gate`) the run.
   - `**Skips when:** <condition> · **To:** <id> · **Says:** <line>(; <line>)?` — a branch whose
     yes skips to a later step `<id>` of the same skill and rejoins the flow above it.
   - `**Hands over:** `<cmd>`(, `<cmd>`)*` — last, at most one per file, as today.
   A field line anywhere else in the file → exit 2 `<file>:<line> — **<Field>** must be in the
   field block right under a step heading (references/step-contract.md)`.
5. **Phase.** `<chain>(, <chain>)*`; chains separated by `, ` run in either order (in parallel
   when the step has two phases or more). A chain is `<ref>(><ref>)*` with an optional trailing
   `?`: that chain is conditional. `self` as before.
6. **Depends on / Reads.** Ids of steps of the same skill, each **written before** the step →
   else exit 2 `step <id> depends on|reads '<x>', which is written after it — the written order is the execution order`.
   `Reads` is a soft dependency: it orders the layout, it draws no edge.
7. **Gate.** Vocabulary in `view.schema.json` `$defs.gate.enum` (`ask confirm stop gate`), branch
   words in the new `$defs.branchGate.enum` (`stop gate`). The count of branch words in `Gate`
   equals the count of `**Stops when:**` lines; the i-th line takes the i-th branch word → else
   exit 2 `step <id> has <n> branch gate word(s) (<words>) and <m> **Stops when:** line(s)`.
   `**Skips when:**` needs no gate word.
8. **Text limits are data.** `condition` against `$defs.branchCondition`, each `Says` part
   against `$defs.branchLine`, a note against `$defs.phaseNote` — `Diagrams.java` validates the
   extracted string with its schema walker `sv()` and prints the def's `x-fix`, never a constant.
   `**Note:**` on a step with one phase → exit 2 `a note names a phase; step <id> has one`.
   `**To:**` naming no step written after this one → exit 2 `skips to '<x>', which is no later step`.

#### 2 · `view.schema.json`

- `kind.enum` → `["layers", "classes", "territory", "orchestrator"]` (`pipeline` removed; x-fix
  updated: "an orchestrator view draws a skill's steps; `derive` picks its layout").
- `edge.derive` stays `["depends_on", "sequence"]`; an orchestrator view accepts either.
- New defs, verbatim:
  ```json
  "branchGate": { "enum": ["stop", "gate"], "x-fix": "a gate word that branches (references/step-contract.md): its step carries one **Stops when:** line per word" },
  "branchCondition": { "type": "string", "pattern": "^.{1,33}\\?$", "x-fix": "a branch condition is a question of at most 34 characters, phrased so that yes is the exit" },
  "branchLine": { "type": "string", "pattern": "^.{1,34}$", "x-fix": "each part of **Says:** is at most 34 characters; two parts at most, separated by '; '" },
  "phaseNote": { "type": "string", "pattern": "^.{1,48}$", "x-fix": "a **Note:** is at most 48 characters" }
  ```
- `$comment` names this record next to `0190` and `0192`.

#### 3 · Facts

`step:<skill>:<id>` keeps every field of `0192` and gains: `reads` (step ids); `phases`: list of
`{"chains":[{"refs":[<fact ids>],"conditional":bool}], "note":"…"|""}`; `branches`: list of
`{"kind":"stop"|"gate"|"skip","after_phase":<int>,"condition":"…","says":["…"],"to":"step:<skill>:<id>"|""}`;
`runs` stays (every chain of every phase, flattened) for compatibility; `label` per rule 2.

#### 4 · Views

- **Rename** `docs/diagrams/pipeline/` → `docs/diagrams/new-feature/` with every file inside
  (`new-feature.{svg,animated.svg,drawio,excalidraw}`, `dist/new-feature.png` +
  `.source-sha256`): `git mv`, then render and export. `export.mjs` takes the folder name and
  needs no change. History: a note at the top of `0190` and `0192` naming this record (the
  decisions README rule — never rewrite, supersede and link).
- `new-feature/view.json`: `"kind": "orchestrator"`, `"derive": "depends_on"`, caption
  `"Each step runs in the written order, after what it depends on; a dashed step runs only when the use case needs it."`,
  group caption `/new-feature` kept (declared, never drawn), no `steps`, no `emphasis`.
- The three orchestrator views: drop `steps` and `emphasis`.
- `validate`: an orchestrator view with `steps` → `the animation follows the written order of <skill>; remove steps`;
  with `emphasis` → `an orchestrator view takes no emphasis — the badges and the diamonds say it; remove emphasis`.
  `emphasis` stays for `layers`/`classes`/`territory`. Revokes `0192`'s emphasis rule.

#### 5 · Render — kind `orchestrator`, both layouts

Common to both:
- **Root** above the first step, edge root → first step (enters with it). Head: the skill name;
  detail: `orchestrator.self_detail`, then the territory **collapsed**: each glob loses leading
  `**/`, is cut before its first segment with `*` (`src/**` → `src/`), an empty cut is
  `orchestrator.territory_any`; dedupe; directories first, then files, data order otherwise;
  wrapped to the box; at most `orchestrator.root.max_lines` lines, the last ending with
  `orchestrator.root.more` (`+{n} more`) when entries were cut. Empty territory → the facts'
  `writes nothing in the repository`.
- **Chips** keep the full territory, except `["**"]` → `orchestrator.territory_any` (finding f).
- **Diamond.** A step with no delegate chain (only `self`) and exactly one branch **is** the
  diamond: name (bold, wrapped like a step label) over the condition (detail). Otherwise its box
  stays and each branch draws a verdict diamond holding the condition: after the last phase →
  below the step's row in the step column; after an earlier phase → in the chip column between
  that phase and the next. Badges of non-branch gate words stay on the step (left of a diamond).
- **Terminal.** The yes exit leaves the diamond's right vertex, labelled `branch_labels.yes`, to a
  terminal box (`terminal.w`×`terminal.h`, `terminal.gap` to the right of the diamond): title
  `terminal.<kind>.title` (`{target}` = the skip target's name), then the `Says` lines. `stop`
  and `gate` terminals take the fill and stroke of `gates.<word>` and carry that badge; `skip`
  takes `terminal.skip`. The edge leaving a diamond downward is labelled `branch_labels.no`.
- **Skip rejoin.** An orthogonal path from the terminal's right edge to the right gutter, down to
  half `row_gap` above the target, left to its centre line, ending in an arrowhead on the target.
- **Legend.** Badges used, then `legend.decision` (a small diamond) when any diamond is drawn,
  then `legend.conditional` (a dashed box) when any dashed step or chip is drawn.
- **No heavier stroke** anywhere in this kind.
- **Arrow marker** `orient="auto"` (all kinds) — finding e.

Column layout (`derive: sequence`), the first pass's `orchLayout` extended:
- One row per step; flow arrows step → next step (or → its verdict → next step).
- **Phases** (a step with two or more phase lines): each phase is a block in the chip column
  with a caption `<n> · <note>` (`· ` + `phase.parallel_caption` appended when the phase holds two
  chains or more), `phase.caption_h` above its chips; a phase with ≥ 2 chains is wrapped by a
  dotted box (`phase.parallel`); phases are joined by vertical arrows `phase.gap` apart; one
  entry arrow from the step box's right edge to the first phase's first chip; the tool label of
  each chain's first chip sits left of the chip column, end-anchored. A one-phase step draws its
  chains as today (no caption, no box).
- A conditional chain (`?`) draws its chips dashed.

Rows layout (`derive: depends_on`), replacing `depthOf`:
- Walk the steps in written order; a step joins the current row unless it depends on or reads a
  step already in that row, carries a branch, or the current row holds a branch step — then it
  opens a new row. Rows centred on one column axis. No chips are drawn in this layout.
- Edges: `depends_on` minus those a longer path implies (as today); a step with no `Depends on`
  gets one edge from the step written before it. Edges between adjacent rows are straight;
  an edge that skips a row is an orthogonal path through the **left gutter**, one lane per edge
  (`routing.lane_gap` apart, sorted by source row then target row) — no edge crosses a box
  (finding g). The handoff box last, dashed, below the last step.

**Animation** (`animated.svg`; static files unchanged):
- Cursor `t = 0`; root always visible; legend always visible.
- Per step in written order: the step (or its diamond), its badges and every edge **into** it
  enter at `t` (finding b). Its items — phases and verdicts, in field order — enter at
  `t + i × phase_seconds`; a chain's later segment `k` at its phase time `+ k × delegate_seconds`
  (its arrow and tool label with it); a branch's yes edge, terminal and terminal badge at its
  diamond's time `+ branch_seconds`; a skip rejoin path with its target step.
- `t += step_seconds + (items − 1) × phase_seconds`. Handoff at the final `t`.
- In the rows layout the times follow the written order, never the row.

#### 6 · `visual-identity.md` JSON — added verbatim

```json
"animation": { "step_seconds": 0.7, "reveal_seconds": 0.5, "branch_seconds": 0.35, "phase_seconds": 0.5, "delegate_seconds": 0.25 },
```
and inside `orchestrator`:
```json
"decision": { "fill": "#F8DCE8", "stroke": "#B0366E", "stroke_width": 2, "h": 96, "h_tall": 112, "verdict_h": 80 },
"terminal": { "w": 248, "h": 64, "gap": 64,
  "stop": { "title": "Ends the run" },
  "gate": { "title": "Blocks the run" },
  "skip": { "title": "Skips to {target}", "fill": "#E4F1DE", "stroke": "#4C8C3A" } },
"branch_labels": { "yes": "yes", "no": "no" },
"phase": { "gap": 24, "caption_h": 16, "parallel_caption": "in parallel, either order",
  "parallel": { "stroke": "#7A7A7A", "dash": "2 4", "pad": 8, "radius": 10 } },
"root": { "max_lines": 2, "more": "+{n} more" },
"territory_any": "writes anywhere in the repository",
"legend": { "decision": "decision", "conditional": "conditional" },
"routing": { "lane_gap": 16 }
```
Prose above the block: the decision diamond's role (rose, used by no other role), terminals,
phases, the two legend entries; `emphasis` described as a `layers`/`classes`/`territory` marker
only. The paragraph on orchestrator views rewritten for both layouts.

#### 7 · SKILL.md edits — verbatim field lines

A blank line separates each field line from the next and from the body.

**`init-project`** step 1, after its `**Runs:**` line:
`**Stops when:** pom.xml / build.gradle present? · **Says:** reports what it found; suggests /new-feature there`

**`new-feature-implement`**
- Step 1: `**Stops when:** no approved case given? · **Says:** prints the error and usage; or lists the cases (empty)`
- Step 2, its `**Runs:**` line replaced by:
  ```
  **Runs:** agent:mapstruct-installer? · **Gate:** ask, stop · **Note:** pre-flight, first and alone when it installs

  **Then runs:** skill:test-architect>agent:archunit-installer?, agent:commons-logging-installer? · **Note:** pre-flight

  **Then runs:** agent:java-spring-boot-developer · **Note:** three groups, chained: domain, adapters, tests

  **Stops when:** a group fails? · **Says:** reports the failing group; the chain stops there

  **Then runs:** agent:compose-service-installer>skill:docker-architect? · **Note:** only with a materialize row
  ```
- The `**Integrates with:**` numbered list (lines 77–81) replaced by:
  `**Integrates with** the agents and skills step 2's field lines name, phase by phase; when each runs is step 2's body.`
  (the `git-publish` sentence after it stays).

**`platform-upgrade`**
- 1: `**Stops when:** dirty tree, no git or no wrapper? · **Says:** says what to run; never offers to commit` (the body also stops on "not a git repository")
- 2: `**Stops when:** ref predates this skill? · **Says:** says --ref must name a newer one`
- 4: `**Stops when:** no engine version resolves? · **Says:** the engine's public line moved; engine is the block to edit`
- 5: `**Stops when:** recipe refused (exit 2)? · **Says:** shows the exit as it is; or: nothing to upgrade`
- 9: Runs becomes `**Runs:** cmd:test-diff, cmd:doctor? · **Gate:** gate` (doctor runs only with a jar, body line 3), then
  `**Stops when:** test-diff exits 2? · **Says:** regressions are the report; nothing is committed`
- 5b unchanged (conditional, no diamond — as approved).

**`new-feature`**
- `## Entry guardrail` → `## guardrail · Entry guardrail`, then
  `**Runs:** self · **Gate:** stop` and
  `**Stops when:** input rejected? · **Says:** prints the error and usage; or lists the cases (empty)`.
- `### 1 · Use case (new case only)`: `**Runs:** skill:use-case-design · **Depends on:** guardrail · **Conditional:** yes`
- `### Short path — checked right after step 1` → `### short · Short path`; body's first sentence
  `Checked right after step 1.`; field lines
  `**Runs:** self · **Depends on:** 1` and
  `**Skips when:** all nine criteria met? · **To:** approval · **Says:** writes UC-NNN-spec.md; from the short template`
- `### 2 · Domain`: `**Depends on:** 1, short`
- `### 3 · REST`: add ` · **Conditional:** yes`
- `### 3g · GraphQL`: add ` · **Reads:** 3`
- `### 3c · Outbound HTTP`: add ` · **Reads:** 3, 3b`
- `### 3d · AI`: add ` · **Reads:** 3, 3b, 3c`
- `### 5b · Cache`: add ` · **Reads:** 3c, 3e, 4b`
- Under `## End of flow`: `### Approval — asked on every run that consolidated a spec` →
  `### approval · Approval`, body's first sentence `Asked on every run that consolidated a spec.`;
  field lines `**Runs:** self · **Depends on:** consolidation · **Gate:** ask, stop` and
  `**Stops when:** kept as draft? · **Says:** reports the spec path; resume: /new-feature UC-NNN-slug`
- `### Final report` → `### report · Final report`; field lines
  `**Runs:** self · **Depends on:** approval` and
  ``**Hands over:** `/clear`, `/git-publish docs(UC-NNN-<slug>): …`, `/new-feature-implement UC-NNN-<slug>` ``
- The `**Integrates with (sequence ordered by depends-on):**` list (lines 95–125) replaced by:
  `**Integrates with** the skill each step of § Procedure names on its `**Runs:**` line, in the written order of the headings; when a step runs, is skipped, or what it reads is that step's own body and field lines. The heading ids are the only step numbers.`
  — finding f solved by derivation: no second list to drift, nothing for `schema` to compare.

#### 8 · Not implemented from the prototypes, on purpose

| Prototype | Instead | Why |
|---|---|---|
| Skip terminal titled "Short spec" | "Skips to Approval" | title from identity + target name; no free title field |
| Final report sub-line "status: approved · every Infrastructure row" | no sub-line | a step has no detail field; adding one for one box is a field to maintain |
| new-feature's six-wide fan-out row | written-order rows (one row of three: 3d, 3e, 4) | finding c: reading order = execution order once `Reads` is data |
| Phase captions "1 · pre-flight · first and alone when it installs" etc. | same shape, from `**Note:**` + identity | wording now owned by the step |
| platform-upgrade 1 "dirty tree or no wrapper?" | "dirty tree, no git or no wrapper?" | the body also stops outside a git repository |
| Pipeline caption "… Execution follows the step numbers." | rewritten caption above | the written order, not the numbers, is the contract |

**Pros:** every diamond, phase and dashed chip cites a line of the SKILL.md that owns it; one
render path for four views; `Reads` and the written-order check make reading order = execution
order a validated fact; no list of numbers left to drift.

**Cons:** ~20 new field lines in four skills, three of which travel (~350 tokens when they run);
new-feature's guardrail and approval headings change shape; the rows layout for new-feature
becomes almost a column, with gutter lanes on the left; `Diagrams.java` grows by an estimated
400–600 lines.

**Points cut in the rubric:** 5 (maintenance: field lines beside prose); 7 (context in travelling
skills). ≈ 7.8 → 8.

**CI:** `validate · design › committed diagrams match the data they cite` (`check` + `DiagramsTest`);
the cases below.

### Option B (score 6)

Same contract and renderer, but the guardrail, short path, approval and final report are
declared by a `**Flow:** <id> · <Name>` marker line instead of a heading, and kind `pipeline`
stays with its own path. Right when a skill's flow points must not be headings (a body read by
other tooling by section name). Rejected for now: two step declarations to parse, and two render
paths drawing the same root, diamonds and handoff.

## Test cases — `.claude/.ci/DiagramsTest.java` (throwaway copy)

| Case | Defect injected | Must fail with (substring) |
|---|---|---|
| m | `init-project`: delete the `**Stops when:**` line of step 1 | `has 1 branch gate word(s) (stop) and 0 **Stops when:** line(s)` |
| n | `platform-upgrade` step 2 condition → 40 characters ending in `?` | `is a question of at most 34 characters` |
| o | `new-feature` 3c: `**Reads:** 3, 3b` → `**Reads:** 3, 3d` | `reads '3d', which is written after it` |
| p | `new-feature` short: `**To:** approval` → `**To:** nowhere` | `skips to 'nowhere', which is no later step` |
| q | `init-project/view.json`: add `"emphasis": ["step:init-project:1"]` | `takes no emphasis` |
| r | `init-project/view.json`: add `"steps": ["step:init-project:1"]` | `follows the written order` |
| s | `init-project/view.json`: `"kind": "pipeline"` | `'pipeline' is not one of` |
| t | `new-feature-implement`: move the `**Stops when:**` line of step 2 below the body's first paragraph | `must be in the field block` |
| u | `init-project` step 4: add ` · **Note:** relay` | `a note names a phase` |

Cases a–l stay; (e)–(l) re-checked against the new messages. Each run green on the untouched copy
and red with its defect.

## References

| Claim | Source |
|---|---|
| Diagrams derive from data; free text only in title, caption, group caption | `@.claude/decisions/0190-nerviz-diagrams-skill-derived-from-data.md` |
| The step contract, kind `orchestrator`, the emphasis rule revoked here | `@.claude/decisions/0192-nerviz-diagrams-draws-the-orchestrators.md` |
| Supersede, never rewrite, a record | `@.claude/decisions/README.md` § What this is not |
| One owner per norm; vocabularies are data | `@CLAUDE.md` invariants 2, 7, 10 |
| Phase 4 delegated to a sonnet subagent | `@.claude/decisions/0191-architect-designer-delegates-phase-4-to-sonnet.md` |
| Which skills travel | `export.skills.include` / `exclude` of `@.claude/schemas/extensions.json` |
| Required section `## Procedure` (prefix) for orchestrator and build classes | `skill_classes.classes.{orchestrator,build}.required_sections` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/nerviz-diagrams/references/step-contract.md` | Contract v2 (§ 1 above) |
| `.claude/skills/nerviz-diagrams/references/view.schema.json` | § 2 |
| `.claude/skills/nerviz-diagrams/references/visual-identity.md` | § 6 + prose |
| `.claude/skills/nerviz-diagrams/scripts/Diagrams.java` | Extractor v2, validate rules, both layouts, branches, phases, routing, animation, marker, territory wording; drawio (`rhombus`, edge waypoints) and excalidraw (`diamond`, multi-point arrows) for every new shape |
| `.claude/skills/nerviz-diagrams/SKILL.md` | Kinds list, `steps` derived for orchestrator views, failure modes for the new field lines |
| `.claude/skills/{init-project,new-feature-implement,platform-upgrade,new-feature}/SKILL.md` | § 7 |
| `docs/diagrams/pipeline/` → `docs/diagrams/new-feature/` | `git mv`, view per § 4 |
| `docs/diagrams/{init-project,new-feature-implement,platform-upgrade}/view.json` | drop `steps`, `emphasis` |
| `docs/diagrams/*/` | re-render; `export.mjs` for the four stepped views and `layers` |
| `.claude/decisions/0190-…md`, `0192-…md` | one-line supersede note at the top |
| `.claude/.ci/DiagramsTest.java` | cases m–u |
| `docs/{en,pt-br}/11-pitfalls.md`, `docs/{en,pt-br}/07-ci-validate.md`, `.github/workflows/validate.yml` | the new field lines; case list; comment above the `design` step |

Goes to the generated project: the field lines inside the three travelling skills only.

## CI coverage

| Job · step | `validate · design › committed diagrams match the data they cite` (`Diagrams.java check` + `DiagramsTest.java`) |
|---|---|
| Green | `✅ 20 rendered file(s) of 5 view(s) equal a fresh render; dist/ hashes match their SVG.` |

| Case | Red message, as observed |
|---|---|
| m | `step 1 has 1 branch gate word(s) (stop) and 0 **Stops when:** line(s)` |
| n | `step 2 **Stops when:** $: '…' does not match ^.{1,33}\?$. Fix: a branch condition is a question of at most 34 characters, phrased so that yes is the exit.` |
| o | `step 3c reads '3d', which is written after it — the written order is the execution order` |
| p | `step short skips to 'nowhere', which is no later step` |
| q | `$.emphasis: an orchestrator view takes no emphasis — the badges and the diamonds say it; remove emphasis.` |
| r | `$.steps: the animation follows the written order of init-project; remove steps.` |
| s | `$.kind: 'pipeline' is not one of [layers, classes, territory, orchestrator].` |
| t | `**Stops when:** must be in the field block right under a step heading (references/step-contract.md)` |
| u | `a note names a phase; step 4 has one` |

(e)–(l) stay green against the new messages.

Also touched, not listed above: `.claude/skills/nerviz-diagrams/references/step-contract.md` is rewritten
whole; `docs/diagrams/layers/` is re-rendered and re-exported (marker `orient="auto"`); the four
`docs/diagrams/*/dist/` PNGs and their `.source-sha256`.

# 0192 · nerviz-diagrams draws the three orchestrators from a step contract their SKILL.md carries

- **Date:** 2026-10-29
- **Scenario:** Estender a skill `nerviz-diagrams` para desenhar os três orquestradores —
  `/init-project`, `/new-feature-implement` e `/platform-upgrade` — uma view por orquestrador sob
  `docs/diagrams/<view>/`, derivada dos dados e dos arquivos em disco, nunca de memória (`0190`):
  passos em ordem, quem executa cada um (skill, agent ou modo do `ArchHook`), as fronteiras (o que
  cada executor escreve, o que roda em `context: fork`, o que é só delegação), os pontos de parada e
  de confirmação, e o handoff final impresso que nenhum orquestrador chama sozinho.
- **Decision:** Option A — a generic step extractor (`step:<skill>:<id>`), one heading grammar
  `### <id> · <Name>` for every diagrammed orchestrator (new-feature migrated), a `**Runs:**` line
  under each step heading, an optional `**Hands over:**` line, and a new view kind `orchestrator`
- **State:** approved by Lucas Fernandes, on 2026-10-29. Phase 4 delegated to a `sonnet`
  subagent per `0191`, reviewed in the main thread

## Reproduced on disk

| Claim | Evidence at `a8f9985` |
|---|---|
| Three heading formats | `new-feature`: `### Step 3b: Security (depends on 1, 2, 3, 3g, conditional)`; `new-feature-implement` and `platform-upgrade`: `### 2 · Implement`, `### 4b · The baseline …`; `init-project`: no step heading, a numbered list `1.`–`7.` under `## Procedure` |
| The step extractor reads one file | `Diagrams.java` `FEATURE` constant and regexes `STEP`, `STEP_FIRST`, `CONS`, `extractSteps` |
| Fact ids allow one colon | `view.schema.json` `$defs.factId.pattern` `^[a-z]+:[A-Za-z0-9._-]+$` |
| No skill declares `context: fork` | `grep -n '^context:' .claude/skills/*/SKILL.md` — empty. The fork boundary is derived, and renders empty today |
| `### N · X` is common outside the orchestrators | 15 skills use it (`arch-adopt`, `git-publish`, `project-bootstrap` …), so the contract binds only the skills a view names |
| Executor, gates and handoff exist only as prose | `init-project` steps 3, 5, 6; `new-feature-implement` § 2 items 1–3 and § 4's fenced block; `platform-upgrade` §§ 1, 4b, 5, 9, 10 |
| Effective territory needs overrides | `skill_classes.orchestrator.overrides` (`init-project`, `new-feature-implement`), `skill_classes.build.overrides` (`platform-upgrade` …), `agent_classes.installer.overrides` |
| `/clear` is a native command the data names | `types.skill.native_commands` of `extensions.json` |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Named motive: the site and video need the three orchestrators drawn, and a model drawing them from memory is wrong the first commit that rewords a step | create nothing |
| 2 — trigger | `/nerviz-diagrams`, unchanged | — |
| 5 — nature | Extension of an existing procedure and its script | new skill |
| 7 — mandatoriness | Freshness gated by `Diagrams.java check` in CI, as in `0190` | session hook |
| 8 — destination | The diagrams: this repository only. The `**Runs:**` lines: they sit inside `new-feature`, `new-feature-implement` and `platform-upgrade`, which travel (`export.skills.include`), so ~30 short lines of readable prose reach generated projects; `init-project` does not travel | — |
| 9 — integration | Reads the four SKILL.md, `extensions.json` (classes, overrides, `native_commands`), the frontmatter of every cited skill and agent, the dispatch of `ArchHook.java`. Writes `docs/diagrams/**` (skill) plus, at design time, the four SKILL.md headings (this record's Phase 4) | — |
| D1 — extractor | Generic, ids `step:<skill>:<id>`; new-feature migrates to `step:new-feature:3b` | one extractor per orchestrator |
| D2 — heading contract | **One grammar**, `### <id> · <Name>`, for every diagrammed skill including new-feature; its `depends on` moves into the `**Runs:**` line | two grammars |
| D3 — kind | New `orchestrator` | `pipeline` + skill parameter |
| D4 — executor source | A `**Runs:**` line under each step heading, ids validated against facts | body heuristic; a block in `extensions.json` |
| 17 — CI | `validate › design › committed diagrams match the data they cite` already runs `Diagrams.java check` and `DiagramsTest`; the test gains cases | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | Generic extractor + one heading grammar + `**Runs:**` line + kind `orchestrator` | 8 | Pending approval |
| B | Generic extractor + body heuristic (verbs near backticked names) | 4 | Rejected at the interview — noisy and silent drift |
| C | `orchestrators.<skill>.steps` block in `extensions.json` | 3 | Rejected — duplicates the headings; `0190` rejected a pipeline block for the same reason |
| D | One extractor per orchestrator, formats untouched | 5 | Rejected at the interview — three regexes, three failure modes, Java per new orchestrator |
| E | Create nothing — draw with the Excalidraw connector | 2 | Rejected — what `0190` exists to stop |

### Option A (score 8)

**Motivator:** D1–D4 of the interview; axis 9 (the facts exist only in the orchestrators' own
bodies, which are their single owner — invariant 2).

#### The step contract — owner: `.claude/skills/nerviz-diagrams/references/step-contract.md`

Binds every skill a view's `skill` field names, and nothing else.

1. **Scope.** Lines from `## Procedure` (a heading that starts with `## Procedure`) to the next
   `## ` heading, outside fenced code blocks.
2. **Step heading.** A `### ` heading in scope is a step **iff** it contains ` · `. Grammar:
   `^### ([0-9]+[a-z]?|[a-z][a-z-]*) · (.+?)\s*$` — group 1 the id, group 2 the name. A `### `
   heading without ` · ` (e.g. new-feature's `### Short path — checked right after step 1`) is
   prose, never a step.
3. **Old grammar.** A `### Step ` heading in scope → exit 2:
   `<file>:<line> — '### Step <id>: …' is the retired grammar; write '### <id> · <Name>' and move 'depends on' into the **Runs:** line (references/step-contract.md)`.
4. **Runs line.** The first non-blank line after a step heading must match, in this fixed order:
   `**Runs:** <ref>(, <ref>)*( · **Depends on:** <id>(, <id>)*)?( · **Conditional:** yes)?( · **Gate:** <gate>(, <gate>)*)?`
   - `<ref>` = `self` | `skill:<name>` | `agent:<name>` | `cmd:<mode>`, optionally chained with
     `>` to say who that executor delegates to (`skill:test-architect>agent:archunit-installer`).
     Each segment must resolve to a fact; `self` means the orchestrator itself, on its own thread.
   - `<gate>` ∈ the closed vocabulary `ask` (a question that collects input), `confirm` (a
     question that holds a side effect), `stop` (the step refuses and ends the run), `gate` (a
     command's exit 2 blocks the run). Repetition is meaningful: `confirm, confirm` is two gates.
     The vocabulary lives in `view.schema.json` `$defs.gate.enum`, and `Diagrams.java` reads it
     from there — never a Java constant.
   - Missing or malformed → exit 2: `<file>:<line> — step <id> has no **Runs:** line …` /
     `… **Runs:** does not match …`, both naming `references/step-contract.md`.
   - An unresolved segment → exit 2: `<file>:<line> — step <id> runs '<ref>', which is no fact (nearby: …)`.
   - A `Depends on` id that is no step of the same skill → exit 2, as today.
5. **Hands over.** At most one line per Procedure, `**Hands over:** `<cmd>`(, `<cmd>`)*`, the
   line directly after a step's `**Runs:**` line. Each item is a backticked literal kept verbatim;
   one that starts with `/` must name a `skill:<name>` fact or an entry of
   `types.skill.native_commands`, else exit 2 naming it. It is printed text: the diagram draws it
   as a dashed box, never as a call.
6. **Zero steps** in a named skill → exit 2, as today.

#### Facts — what `Diagrams.java facts` adds or changes

- `step:<skill>:<id>` (replaces `step:<id>`): `kind: step`, `skill`, `id`, `name`,
  `label` (`<id> · <name>`, unchanged shape), `order`, `depends_on` (`step:<skill>:<x>` ids),
  `conditional`, `runs` (list of chains; each chain a list of fact ids, `self` resolved to
  `skill:<skill>`), `gates` (list of gate words), `detail` (`conditional` or empty, unchanged).
- `handoff:<skill>`: `kind: handoff`, `label` `Hands over`, `commands` (verbatim list), `after`
  (the step id it follows).
- `cmd:<mode>`: `kind: cmd`, `label` `ArchHook <mode>`. Extracted from `.claude/hooks/ArchHook.java`:
  the first line whose trimmed text is exactly `switch (mode) {` **and** whose previous non-blank
  trimmed line is `try {`; every `case "<m>"` (and each name of a comma list) on lines at that
  switch's first-`case` indentation until the line closing the switch at its own indentation.
  None found → exit 2 naming `ArchHook.java` and this rule.
- `skill:*` gains `context` (the frontmatter `context:` value, `inline` when absent), `model`
  (frontmatter, empty when absent) and `write_allow` — the effective territory:
  `skill_classes.classes.<c>.overrides.<skill>.write_allow` when present, else the class's.
- `agent:*` gains `model`, `executor` (its class's) and the effective `write_allow`, same rule
  over `agent_classes`.
- Which skills are parsed: the union of every view's `skill` field. The facts no longer depend
  on a constant `FEATURE`.

#### Views and schema

- `view.schema.json`: `kind` enum adds `orchestrator` (x-fix updated); new optional property
  `skill` (`^[a-z][a-z0-9-]*$`); `$defs.factId.pattern` becomes
  `^[a-z]+:[A-Za-z0-9._-]+(:[A-Za-z0-9._-]+)?$`; `$defs.edge.derive` enum adds `sequence`; new
  `$defs.gate` `{"enum": ["ask", "confirm", "stop", "gate"]}`.
- `Diagrams.java validate` (Java, since the subset has no if/then): `pipeline` and `orchestrator`
  require `skill`, naming a `skill:*` fact with ≥ 1 step; their nodes resolve only to
  `step:<skill>:*` (`a <kind> view draws the steps of <skill> only; '<id>' is …`); `pipeline`
  admits `derive: depends_on` only, `orchestrator` `derive: sequence` only.
- `docs/diagrams/pipeline/view.json`: add `"skill": "new-feature"`, selector
  `step:new-feature:*`, every `steps`/`emphasis` id prefixed `step:new-feature:`.
- New `docs/diagrams/<s>/view.json` for `s` ∈ `init-project`, `new-feature-implement`,
  `platform-upgrade`, each:
  `{"kind":"orchestrator","skill":"<s>","title":"Nerviz — /<s>","caption":"<one sentence>","groups":[{"id":"steps","label":{"fact":"skill:<s>"},"nodes":[{"from":"step:<s>:*"}]}],"edges":[{"derive":"sequence"}],"steps":[<every step id in order>, "handoff:<s>" when present],"emphasis":[<the step ids that carry a gate>]}`.
  Captions: init-project — "Every question is asked before delegating; the agent asks nothing.";
  new-feature-implement — "Three executor groups, chained; git-publish is printed, never called.";
  platform-upgrade — "Nothing is committed; the tests recorded before the bump judge the result."

#### Render — kind `orchestrator`

Deterministic and byte-stable like the other kinds. One row per step, top to bottom in `order`:
the step box (dashed when `conditional`), `sequence` arrows between consecutive steps; to its
right, one chip per chain segment — `agent` chips in the agent style, `skill` chips in the skill
style, a `skill` whose `context` is `fork` with the fork marker, `cmd` chips in mono, `self` as
the orchestrator — and an arrow labelled by the tool: `Agent` (isolated), `Skill` (inline, or
`fork`), `Bash` (cmd); under each chip its effective `write_allow` (`writes nothing in the
repository` when empty), `self` showing the orchestrator's own. Gate badges on the step's right
edge, one per gate word. The `handoff:<s>` box last, dashed, captioned `printed — never called`.
Tokens (gate glyphs and colours, the fork marker, chip styles) go into the JSON block of
`references/visual-identity.md`, its single owner; never a Java constant. `.drawio`,
`.excalidraw`, `.svg` and `.animated.svg` as for every kind.

#### The SKILL.md edits — verbatim

Every heading below replaces the current one at the same place; the `**Runs:**` line goes on the
first line after it, followed by a blank line, then the existing body unchanged.

**`init-project/SKILL.md`** — the numbered list `1.`–`7.` of `## Procedure` becomes seven
headings; each item's text, de-indented by three spaces, is the body. The sub-list of step 2 stays
a numbered list. Cross-references inside ("of step 2", "Before step 6") stay valid.

| Heading | Runs line |
|---|---|
| `### 1 · Refuse an existing project` | `**Runs:** self · **Gate:** stop` |
| `### 2 · Interview` | `**Runs:** self · **Gate:** ask` |
| `### 3 · Delegate to project-initializer` | `**Runs:** agent:project-initializer>skill:project-bootstrap` |
| `### 4 · Relay the report` | `**Runs:** self` |
| `### 5 · Fill the genesis record` | `**Runs:** cmd:audit` |
| `### 6 · Chain git-publish` | `**Runs:** skill:git-publish · **Gate:** confirm, confirm` |
| `### 7 · Hand over to the new project` | `**Runs:** self` then `**Hands over:** `cd ../<artifactId> && claude`` |

**`new-feature-implement/SKILL.md`** — headings unchanged.

| Heading | Runs line |
|---|---|
| `### 1 · Entry guardrail` | `**Runs:** self · **Gate:** stop` |
| `### 2 · Implement` | `**Runs:** skill:test-architect>agent:archunit-installer, agent:commons-logging-installer, agent:mapstruct-installer, agent:java-spring-boot-developer, agent:compose-service-installer>skill:docker-architect · **Gate:** ask, stop` |
| `### 3 · Long-running background work` | `**Runs:** self · **Gate:** ask` |
| `### 4 · Final report` | `**Runs:** self` then `**Hands over:** `/clear`, `/git-publish`, `/new-feature`` |

**`platform-upgrade/SKILL.md`** — headings unchanged.

| Heading | Runs line |
|---|---|
| `### 1 · Refuse to write over uncommitted work` | `**Runs:** self · **Gate:** stop` |
| `### 2 · Fetch the source` | `**Runs:** self · **Gate:** stop` |
| `### 3 · Resolve the target version` | `**Runs:** self · **Gate:** ask` |
| `### 4 · Resolve the engine's version` | `**Runs:** self · **Gate:** stop` |
| `### 4b · The baseline — what was red before the upgrade` | `**Runs:** cmd:test-diff` |
| `### 5 · Pass 1 — the bump, the coordinates, the types` | `**Runs:** cmd:upgrade-recipe · **Gate:** stop` |
| `### 5b · The Gradle wrapper — before the new plugin configures anything` | `**Runs:** self · **Depends on:** 5 · **Conditional:** yes · **Gate:** ask` |
| `### 6 · The new classpath` | `**Runs:** self` |
| `### 7 · Pass 2 — the property renames` | `**Runs:** cmd:upgrade-recipe` |
| `### 8 · The guided residue` | `**Runs:** self` |
| `### 8b · The context loads` | `**Runs:** self` |
| `### 9 · Verify` | `**Runs:** cmd:test-diff, cmd:doctor · **Gate:** gate` |
| `### 10 · Hand the diff over` | `**Runs:** self` then `**Hands over:** `/arch-adopt`` |

**`new-feature/SKILL.md`** — the 15 step headings change grammar; prose references such as
"step 3b" elsewhere stay valid (no `#step-` anchor exists in the repository).

| Old heading | New heading | Runs line |
|---|---|---|
| `### Step 1: Use case (new case only)` | `### 1 · Use case (new case only)` | `**Runs:** skill:use-case-design` |
| `### Step 2: Domain (depends on 1)` | `### 2 · Domain` | `**Runs:** skill:domain-modeling · **Depends on:** 1` |
| `### Step 3: REST (depends on 1,2)` | `### 3 · REST` | `**Runs:** skill:rest-api-architect · **Depends on:** 1, 2` |
| `### Step 3g: GraphQL (depends on 1, 2, conditional)` | `### 3g · GraphQL` | `**Runs:** skill:graphql-api-architect · **Depends on:** 1, 2 · **Conditional:** yes` |
| `### Step 3b: Security (depends on 1, 2, 3, 3g, conditional)` | `### 3b · Security` | `**Runs:** skill:security-architect · **Depends on:** 1, 2, 3, 3g · **Conditional:** yes` |
| `### Step 3c: Outbound HTTP (depends on 1, 2, conditional)` | `### 3c · Outbound HTTP` | `**Runs:** skill:http-client-architect · **Depends on:** 1, 2 · **Conditional:** yes` |
| `### Step 3d: AI (depends on 1, 2, conditional)` | `### 3d · AI` | `**Runs:** skill:ai-architect · **Depends on:** 1, 2 · **Conditional:** yes` |
| `### Step 3e: Search (depends on 1, 2, conditional)` | `### 3e · Search` | `**Runs:** skill:search-architect · **Depends on:** 1, 2 · **Conditional:** yes` |
| `### Step 4: Messaging (depends on 2, conditional)` | `### 4 · Messaging` | `**Runs:** skill:messaging-architect · **Depends on:** 2 · **Conditional:** yes` |
| `### Step 4b: Jobs (depends on 1, 3d, 3e, 4, conditional)` | `### 4b · Jobs` | `**Runs:** skill:jobs-architect · **Depends on:** 1, 3d, 3e, 4 · **Conditional:** yes` |
| `### Step 5: Persistence (depends on 1,2,3,3g,3b,3c,3d,3e,4,4b)` | `### 5 · Persistence` | `**Runs:** skill:persistence-architect · **Depends on:** 1, 2, 3, 3g, 3b, 3c, 3d, 3e, 4, 4b` |
| `### Step 5b: Cache (depends on 1, 2, 5, conditional)` | `### 5b · Cache` | `**Runs:** skill:cache-architect · **Depends on:** 1, 2, 5 · **Conditional:** yes` |
| `### Step 6: Tests (depends on 1,2,3,3g,3b,3c,3d,3e,4,5,5b)` | `### 6 · Tests` | `**Runs:** skill:test-architect · **Depends on:** 1, 2, 3, 3g, 3b, 3c, 3d, 3e, 4, 5, 5b` |
| `### Consolidation (after 1,2,5,6 ✅, and 3 or 3g — steps 3, 3g, 3b, 3c, 3d, 3e, 4, 4b and 5b conditional)` | `### consolidation · Consolidation` | `**Runs:** self · **Depends on:** 1, 2, 5, 6, 3, 3g` — then, as its body's first sentence, keep the old heading's information: `Runs after 1, 2, 5 and 6 ✅, and 3 or 3g; steps 3, 3g, 3b, 3c, 3d, 3e, 4, 4b and 5b are conditional.` |

new-feature's printed handoff lives in `## End of flow`, outside `## Procedure`, so it carries no
`**Hands over:**` line and the pipeline view draws none — unchanged from today. The old `Step 3`
had no `conditional`; keep it without one. Verify the depends-on lists against the current
headings at edit time and copy them, not this table, if they differ.

**Pros:** every node and chip cites a fact that resolves; executor, gate and handoff have one
owner, the step itself, next to its prose; one extractor and one grammar for every orchestrator,
a fourth one needs no Java; `check` keeps the gate.

**Cons:** rewrites the 15 headings of `new-feature` (944 lines, travels) for a cosmetic gain the
person chose over two grammars; ~30 `**Runs:**` lines enter three travelling skills (~400 tokens
loaded when they run); a `**Runs:**` line can diverge from its prose — the id fails when it
vanishes, not when the prose changes; `cmd:*` is parsed from the Java dispatch's layout;
`>` chains are written by hand (resolved, but not derived from the callee's own body); the fork
lane renders empty until a skill declares `context: fork`.

**Points cut in the rubric:** 5 (maintenance: a line per step to keep beside the prose); 7
(context: tokens in travelling skills). ≈ 7.6 → 8.

**CI:** existing `validate › design › committed diagrams match the data they cite` runs
`Diagrams.java check` then `DiagramsTest.java`. `DiagramsTest` changes case (e) and gains cases —
see § CI coverage below.

### Options B, C, D, E

As in the table: each rejected at the interview with the reason given there.

## Test cases — `.claude/.ci/DiagramsTest.java` (on its throwaway copy)

| Case | Defect injected | Must fail with (substring) |
|---|---|---|
| e (changed) | `new-feature/SKILL.md`: `### 3b · Security` back to `### Step 3b: Security (depends on 1, 2, 3, 3g, conditional)` | `is the retired grammar` |
| f | `new-feature-implement/SKILL.md`: delete the `**Runs:**` line under `### 2 · Implement` | `step 2 has no **Runs:** line` |
| g | `init-project/SKILL.md`: `agent:project-initializer` → `agent:project-initialiser` | `runs 'agent:project-initialiser', which is no fact` |
| h | `platform-upgrade/SKILL.md`: `**Gate:** stop` → `**Gate:** halt` on step 1 | `**Runs:** does not match` |
| i | `new-feature-implement/SKILL.md`: `` `/git-publish` `` → `` `/git-publsh` `` in `**Hands over:**` | `'/git-publsh'` and `no skill or native command` |
| j | `docs/diagrams/init-project/view.json`: node `{"fact":"step:platform-upgrade:1"}` | `draws the steps of init-project only` |
| k | `docs/diagrams/init-project/view.json`: drop `skill` | `requires skill` |
| l | `.claude/hooks/ArchHook.java` copy: rename `case "test-diff"` to `case "test-dif"` | `runs 'cmd:test-diff', which is no fact` |

The six existing cases (a–d, the `.svg` byte, the `.source-sha256`) stay; (a) and (b) keep their
assertions. Every case is run once green on the untouched copy and once red with its defect.

## References

| Claim | Source |
|---|---|
| Diagrams derive from data, a node without evidence is refused | `@.claude/decisions/0190-nerviz-diagrams-skill-derived-from-data.md` |
| A pipeline block in `extensions.json` was rejected | `0190` Interview, "pipeline source" |
| Phase 4 delegated to a sonnet subagent | `@.claude/decisions/0191-architect-designer-delegates-phase-4-to-sonnet.md` |
| A norm has one owner; others cite it | `@CLAUDE.md` invariant 2 |
| `context: fork` and `agent:` are skill frontmatter fields | `types.skill.allowed` of `@.claude/schemas/extensions.json` · `references/frontmatter-fields.md` |
| Native commands are data | `types.skill.native_commands` of `@.claude/schemas/extensions.json` |
| Territory overrides per piece | `skill_classes` / `agent_classes` `overrides` · `0059` · `0188` |
| `new-feature`, `new-feature-implement`, `platform-upgrade` travel; `init-project` does not | `export.skills.include` / `exclude` |
| A committed diagram stale against its data fails CI | `.github/workflows/validate.yml` `design` job · `DiagramsTest.java` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/nerviz-diagrams/scripts/Diagrams.java` | Generic extractor, `cmd:*`, `handoff:*`, skill/agent fields, validate rules, `orchestrator` render |
| `.claude/skills/nerviz-diagrams/references/view.schema.json` | `orchestrator`, `skill`, factId pattern, `sequence`, `$defs.gate` |
| `.claude/skills/nerviz-diagrams/references/step-contract.md` | New — the step contract, single owner |
| `.claude/skills/nerviz-diagrams/references/visual-identity.md` | Orchestrator tokens in its JSON block |
| `.claude/skills/nerviz-diagrams/SKILL.md` | description, Procedure step 1 and 2 (facts, kinds), Failure modes (heading → step contract), Contract `Reads` |
| `.claude/skills/{init-project,new-feature-implement,platform-upgrade,new-feature}/SKILL.md` | Headings and `**Runs:**` / `**Hands over:**` lines, verbatim above |
| `docs/diagrams/pipeline/view.json` | `skill`, prefixed ids |
| `docs/diagrams/{init-project,new-feature-implement,platform-upgrade}/` | `view.json` + four rendered files + `dist/<view>.png` + `.source-sha256` |
| `docs/diagrams/{layers,pipeline}/` | Re-render and re-export: decisions count and step ids changed |
| `.claude/.ci/DiagramsTest.java` | Case (e) changed, cases f–l |
| `CLAUDE.md` | Routing row of `nerviz-diagrams`: "layers, classes and territories, the `/new-feature` pipeline and the three orchestrators" + this record |
| `docs/{en,pt-br}/11-pitfalls.md` | Part 2 entry: `### Step` → the step contract and its `**Runs:**` line |
| `docs/{en,pt-br}/07-ci-validate.md` | `DiagramsTest` row: the new red cases |
| `.github/workflows/validate.yml` | Comment above the `design` step: lists the step-contract breaks |

Goes to the generated project: the `**Runs:**` lines inside the three travelling skills only;
the diagrams, the contract and the skill do not (`export.skills.exclude`).

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › committed diagrams match the data they cite` | `Diagrams.java check` (5 views valid, 20 rendered files equal a fresh render, every `dist/` hash equals its SVG's) then `DiagramsTest.java` on a temp copy that now also carries `ArchHook.java` | green on the tree: `20 rendered file(s) of 5 view(s) equal a fresh render; dist/ hashes match their SVG.` |
| same, case (e) changed | a step heading back in `### Step 3b: …` | red: `is the retired grammar` |
| same, case (f) | `### 2 · Implement` of `new-feature-implement` without its `**Runs:**` line | red: `step 2 has no **Runs:** line` |
| same, case (g) | `agent:project-initialiser` in `init-project` | red: `runs 'agent:project-initialiser', which is no fact` |
| same, case (h) | `**Gate:** halt` on `platform-upgrade` step 1 | red: `**Runs:** does not match` |
| same, case (i) | `` `/git-publsh` `` in `**Hands over:**` | red: `'/git-publsh'` and `no skill or native command` |
| same, case (j) | `init-project` view node `step:platform-upgrade:1` | red: `draws the steps of init-project only` |
| same, case (k) | `init-project` view without `skill` | red: `requires skill` |
| same, case (l) | `case "test-diff"` renamed in the `ArchHook.java` copy | red: `runs 'cmd:test-diff', which is no fact` |

Deviation from the table above, for Phase 4: case (a) renames `skill:sonar-lessons` instead of
`skill:git-publish` (and the throwaway classes view cites it), since `git-publish` is now also
named by `init-project`'s `**Runs:**` line and its rename fails earlier, in the step extractor,
with a different message; the assertion text is unchanged in shape. A view of kind `orchestrator`
accepts `handoff:<skill>` in `steps` without it being a node, since the record's view carries it
there and the schema's `groups` select `step:<s>:*` only.

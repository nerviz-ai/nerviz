# 0211 · Diagrams of a generated project, drawn by a travelling skill that shares the nerviz-diagrams engine

- **Date:** 2026-11-16
- **Scenario:** "quero criar uma nova skill que é levado para projetos gerados que usa o mesmo
  motor de criacao de diagramas para criar diagramas tecnicos, arquiteturais e de fluxo de Use
  case nos projetos gerados"
- **Decision:** Option A — `.claude/skills/project-diagrams/`, Form 2, class `media`, travels;
  owns `scripts/Diagrams.java` with `facts --source project|harness`; `nerviz-diagrams` calls it.
- **State:** approved by the maintainer on 2026-11-17, after prototypes on `bank-app @ c7500c5`;
  the containers kind renamed `blueprint`, default style `brand`.
- Reopens the `export.skills.exclude` half of `0190` with a fact that record did not have: the
  generated project wants the same derived diagrams of itself. Option C of `0190` (an
  `ArchHook.java` mode) stays rejected.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Named motive, no observed failure: living documentation that goes stale visibly, and onboarding/presentation material | create nothing |
| 2 — trigger | Manual `/project-diagrams`, plus the command printed at the end of `/new-feature-implement` (`0182`), never invoked by it | Form 1 |
| 5 — nature | Procedure: extract facts, compose a view, validate, render, export | Forms 4, 5 |
| 6 — isolation | None | Form 3 |
| 7 — mandatoriness | Persuasion; freshness is a **warning** line in `doctor`, never in `doctor.gate.labels`, no CI step in the generated project | Form 8, a gating hook, a `ci.yml.example` step |
| 8 — destination | Both: the skill travels; the engine is shared with `nerviz-diagrams`, which stays meta-only | — |
| 9 — integration | Reads `src/` through the active blueprint's `packages.map`, `docs/use-cases/*/` (the Mermaid blocks `spec-diagrams.md` already constrains, plus the ports table of `10-domain.md`), `docker-compose.yml`. Writes `docs/diagrams/**` of the project only. Never writes under `docs/use-cases/` (`0143`) | — |
| — kinds | Architecture (layers/modules and dependency rules), blueprint (compose services and outbound adapters), one flow view per use case, a map of all use cases | — |
| — fact source | Code + specs + compose | specs-only, code-only |
| — engine | One file, two extractors: `Diagrams.java facts --source project\|harness`; owner moves to the travelling skill, `nerviz-diagrams` calls it | a copy of the engine |
| — formats | `.drawio`, `.excalidraw`, SVG, animated SVG (Java); PNG through the optional Node export, which travels with its lockfile | SVG only |
| 17 — CI | Meta-repo `validate`; nothing in the generated project's CI | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | `.claude/skills/project-diagrams/` (Form 2, class `media`) owning `scripts/Diagrams.java` with `--source project\|harness`; `nerviz-diagrams` calls it; `doctor` line `Diagrams` (warn) over an inputs manifest `render` writes; command printed by `/new-feature-implement` | 8 | **approved** |
| C | `nerviz-diagrams` itself travels with two sources | 6 | rejected — a meta-only procedure (harness views, site export) would travel with it |
| B | Copy of the engine into a new skill + a parity test in the meta-repo | 5 | rejected — two copies of one renderer, invariant 2 held only by a test |
| D | create nothing — Mermaid inside specs | 2 | rejected — no architecture, blueprint or map view |
| — | `ArchHook.java diagram` mode | — | Rejected before scoring — `0190` option C: dead renderer in every jar, jar rebuild per layout change |
| — | `.drawio`/`.excalidraw`/`.svg` next to a spec | — | Rejected before scoring — `0143`, `rules/spec-diagrams.md` § Format |

### Option A — `project-diagrams`, shared engine (score 8)

**Motivator:** axes 1, 2, 8 and the engine answer.

**Pros:** one renderer and one visual identity; facts from what is implemented (packages,
ports, adapters, aggregates), what was designed (spec Mermaid, already restricted to the
text's names, so the view adds no fact) and what runs (compose); class `media` already
describes the territory (`docs/diagrams/**`, `sonnet`, `blocked_during_design`); the
`doctor` line rehashes the inputs listed in `docs/diagrams/.inputs.sha256` and holds no
diagram logic; the inputs' globs live in `extensions.json` (invariant 10).

**Cons:** `Diagrams.java` changes owner and `nerviz-diagrams` cites a script of another
skill; the harness extractor travels as small dead code; Node becomes an *optional*
dependency of the generated project (PNG only); refactor of a 2448-line file; `media`'s
`$comment` and `0190`'s exclusion are rewritten.

**Shape.**

```
project-diagrams/ (travels, media)            nerviz-diagrams/ (meta, media)
  SKILL.md                                      SKILL.md ──calls──┐
  scripts/Diagrams.java ◄────────────────────────────────────────┘ --source harness
     facts --source project|harness
     validate · render · check ──writes──► docs/diagrams/.inputs.sha256
  scripts/export.mjs + package.json + package-lock.json (PNG, optional Node)
  references/view.schema.json · visual-identity.md · step-contract.md · project-facts.md
ArchHook doctor ──reads .inputs.sha256──► line "Diagrams" (warn, never gated)
/new-feature-implement final report ──prints──► /project-diagrams UC-NNN-<slug>
```

Views in a generated project: `docs/diagrams/architecture/`, `docs/diagrams/blueprint/`,
`docs/diagrams/use-cases/map/`, `docs/diagrams/use-cases/UC-NNN-<slug>/`.

**CI:** `validate · design › committed diagrams match the data they cite` keeps running
`Diagrams.java check` (new path) and `DiagramsTest.java`, which gains `--source project`
cases over a temp fixture project — a renamed package (`fact … not found`), a spec whose
flow block is not an allowed Mermaid type (named by file and line), a compose service
removed (`differs from a fresh render`). `schema` covers class, sections, `export` entry. A
`doctor` case (existing doctor test or a new `.claude/.ci/DoctorDiagramsTest.java`): an input
changed after render → `Diagrams` warns; `doctor gate` still exits 0 on it. Generated
project: no CI step, by the axis 7 answer.

### Option C — `nerviz-diagrams` travels (score 6)

One skill, no owner change. But its body would carry both the harness and the project
procedure, loaded on every invocation; its name and description become ambiguous inside a
project; the routing row and `0190` are rewritten anyway. CI: same as A.

### Option B — engine copied, parity test (score 5)

Leaves the meta-repo untouched, but two ~2.5k-line files diverge; a parity test over
selected sections is fragile; a norm (the renderer) in two files — invariant 2 in spirit.
CI: new `DiagramsEngineParityTest`.

### Option D — create nothing (score 2)

Specs already carry Mermaid. No architecture, blueprint or map view; the named motive stays
unserved.

## View kinds — the selection rule the SKILL.md carries

Prototyped on `bank-app @ c7500c5` (https://claude.ai/artifact/4xNFgGrsM7RPQH7X4zphAD). One
kind answers one question with one diagram type; the skill never picks a type by taste.

| Kind · folder | Diagram type | Question | Facts | Leaves out | Generated when |
|---|---|---|---|---|---|
| `architecture` · `docs/diagrams/architecture/` | Components by layer: inbound adapters → use cases → domain; ports implemented by outbound adapters (dashed, hollow head) | Where does each type live, and which way do dependencies point? | active blueprint `packages.map`, types under `src/main/java`, `implements` read from source, `.claude/forbidden-imports.txt`, specs for drift | methods, fields, DTOs, config, tests | no argument, or `architecture` |
| `blueprint` · `docs/diagrams/blueprint/` | Containers (C4 level 2): app, compose services, external systems named by a spec; protocol and payload on every edge; each piece drawn as its product logo (style `brand`, the default) | What runs together, and how does the app talk to each piece? | `docker-compose.yml`, `application*.yml` keys (topics, issuer, OTLP), `CREATE TABLE` in migrations, `Trigger`/`External calls` rows | internal ports, replicas, secrets, variable values | no argument, or `blueprint` |
| `use-cases/map` · `docs/diagrams/use-cases/map/` | Map: trigger → use case → what it touches (aggregate, table, topic), backlog faded below | Which cases exist, what starts them, what do they share? | `Trigger` and `Side effects` rows of every `00-*.md`, `BACKLOG.md` active table, `*UseCase` types | each case's internal flow | no argument, `map`, or with a UC |
| `use-cases/UC-NNN-<slug>` · `docs/diagrams/use-cases/UC-NNN-<slug>/` | Two panels: sequence (happy path) + decision flowchart, each participant checked against code (✓/✗) | How does this case run, and where does it stop? | the spec's `mermaid` blocks (`sequenceDiagram`, `flowchart TD`), already bound to the text's names by `spec-diagrams.md`; with none, the header chain Trigger → adapter → use case → Side effects, captioned `spec has no flowchart` | path, verb, status, exceptions, retries; `classDiagram` and `stateDiagram-v2` stay in the spec | `/project-diagrams UC-NNN-<slug>`, printed by `/new-feature-implement` |

Blueprint style, in `view.json`: `"style": "brand" | "icons" | "boxes"`, default `brand`. `brand`
draws each compose service as its product logo from `simple-icons@13.21.0`, copied into
`project-diagrams/references/icons/<slug>.svg` with a `NOTICE` naming source and license per
icon (Kafka Apache-2.0 under the ASF marks policy, Keycloak under the Linux Foundation
trademark policy, OpenTelemetry CC-BY-4.0 with attribution, PostgreSQL, SonarQube and Spring
Boot under each project's trademark policy); the `image → slug` map is data in
`extensions.json` (`postgres*` → `postgresql`). A service with no mapped logo, the client and
an external system fall back to the generic role icon of `icons` (cylinder, partition bands,
shield, funnel, cloud); a black logo takes the text colour in the dark theme. `icons` and
`boxes` stay for a project that wants no brand mark. The kind is named `blueprint` by the
user's choice; in this repository the word also names the architecture blueprint
(`.claude/blueprints/`), so the `SKILL.md` and `visual-identity.md` call it "the blueprint
view" and never bare "blueprint".

Two choices Phase 4 made outside this record: the active blueprint is read from the YAML
file of `.claude/blueprints/<id>/<id>.yaml` (the blueprints are not JSON); the label wraps to
`label_lines` = 3, so a 70-character type name fits.

Drawing rules the prototype fixed: a spec-only element (no type in `src/`) is rose dashed and
labelled `spec only`; a dev-tool compose service (list in `extensions.json`) is grey dashed;
an `implements` edge exists only when read in source; support types (mapper, config,
properties) are a caption, not a box; an edge verb comes from the `Side effects` row or is
left unlabelled. Fact id prefixes: `pkg:`, `type:`, `impl:`, `service:`, `topic:`, `table:`,
`external:`, `uc:`, `bl:`, `mermaid:`, `drift:`. Observed: 5 of `bank-app`'s 7 specs predate
`0143` and carry no `mermaid` block — the fallback is the common case.

## Legibility — no text over text, shape or line

Observed on the first prototype: 42 collisions across the six diagrams, found by measuring the
rendered page (`overlap.mjs`: system Chrome through the cached Playwright, `getBBox` of every
`<text>`, every filled shape, and a point sampled every 2 px along every edge). Kinds: text ×
text, text past its box, text crossed by an edge, text outside the `viewBox`. The redrawn
prototype measures zero. A diagram whose labels collide is unreadable however right its facts
are, so the engine owes the same guarantee, deterministically and in Java.

1. **Measure, then lay out.** Every label's width comes from a committed advance-width table
   per font and size — a JSON block in `references/visual-identity.md`, beside the palette —
   never from AWT, whose metrics differ by OS and would break the byte-identical `check` the
   Windows runner also runs. A box is as wide as its longest label plus padding; a column gap
   or a sequence lifeline gap is at least the widest edge label it carries.
2. **Wrap before shrinking.** A label past `max_label_width` breaks at a word boundary (a
   camel-case boundary for a type name, `KycVerificationCompleted` / `Listener`) into at most
   `max_label_lines` lines, line height ≥ 1.4 × font size. Font size never shrinks per label.
3. **Edges route around text.** An edge label sits on a straight segment of its own edge,
   beside it, never on a segment another edge or a lifeline crosses. A long payload name moves
   into the target box as a line of its own (the topic names inside the `kafka` box), and the
   edge keeps a short verb.
4. **Check after render, exit 2.** `validate` and `check` compute the box of every text,
   shape and edge polyline the render produced and fail naming both parties and the view —
   `TEXT×TEXT`, `TEXT×SHAPE`, `TEXT×LINE`, `OUT OF VIEWBOX` and `LINE×LINE` (two edges
   collinear and overlapping by more than 4 px) — the detector's five kinds. Two exceptions are
   declared, in the render model and never by coordinates: a badge (the ✓ on a participant),
   and edges that leave one source port, which carry the same `data-fan` and may share their
   first segment. Every edge otherwise gets a channel of its own: a lane per vertical run,
   ports on multiples of 8 on the left box and 8 plus 4 on the right.
5. **Both sources.** The check runs on `--source harness` views too: the committed views under
   this repository's `docs/diagrams/` are rendered and checked again in Phase 4, and any
   collision there is fixed in the same change.

CI: `DiagramsTest` gains, per kind, a red case for each defect — a 70-character type name that
must wrap (red if it is drawn on one line past its box), two edge labels forced onto one
segment (red: `TEXT×LINE`), a lifeline gap below its widest message label (red: `TEXT×LINE`),
a label past the `viewBox` (red: `OUT OF VIEWBOX`) — each proven green on the fixed engine and
red, by name, with the defect injected.

## References

| Claim | Source |
|---|---|
| No diagram file next to a spec; spec Mermaid restricted to the text's names | `@.claude/decisions/0143-mermaid-diagrams-in-use-case-and-domain-specs.md` · `@.claude/rules/spec-diagrams.md` |
| Derived-from-data engine, class `media`, Node only for export, `ArchHook` mode rejected | `@.claude/decisions/0190-nerviz-diagrams-skill-derived-from-data.md` |
| Orchestrator steps contract the engine parses | `@.claude/decisions/0192-nerviz-diagrams-draws-the-orchestrators.md` · `0193` |
| Feature flows print the next command, never invoke it | `@.claude/decisions/0182-git-publish-printed-not-chained-by-the-feature-flows.md` |
| A travelling skill is listed in `export.skills.include` | `@CLAUDE.md` invariant 9 |
| A `doctor` line outside `doctor.gate.labels` warns and never gates | `extensions.json` `doctor.gate.labels` · precedent `Skill shadowing`, `0138` |
| Lists a mode reads live in `extensions.json` | `@CLAUDE.md` invariant 10 |

## Phase 4 handoff — what the implementation writes

**`.claude/skills/project-diagrams/SKILL.md` frontmatter:**

```yaml
name: project-diagrams
description: Renders diagrams of a generated project from its code, its use case specs and its docker-compose.yml — the architecture view (components by layer), the blueprint view (containers, each service as its product logo), the use case map, and one flow view per use case — as .drawio, .excalidraw and SVG under docs/diagrams/, plus PNG when Node is present. Derived from facts the engine extracts; a model never draws a fact from memory. Never writes next to a spec, whose diagrams stay Mermaid.
argument-hint: "[empty for architecture, blueprint and map | architecture | blueprint | map | UC-NNN-<slug>]"
disable-model-invocation: true
allowed-tools: Read, Write, Edit, Glob, Bash(java:*), Bash(node:*), Bash(ls:*)
model: sonnet
```

**Body sections, in order:** `# Project Diagrams` · `## Target` (`$ARGUMENTS`, the argument
table) · `**Class:** media` · `## Why this is a manual skill` · `## View kinds` (the table of
§ View kinds above, verbatim, plus the blueprint style paragraph) · `## Procedure` (facts →
compose `view.json` citing fact ids → `validate` → `render` → `check`; legibility exit 2 is fixed
by relayout, never by deleting a fact) · `## Failure modes` (`fact … not found`, a non-allowed
Mermaid type by file and line, the four legibility kinds, Node absent → PNG skipped with one
line, no `docs/use-cases/` → map and flow kinds skipped with one line) · `## Contract`
(writes `docs/diagrams/**` only, the class territory; reads `src/main/java/**`,
`docs/use-cases/**`, `docker-compose.yml`, `src/main/resources/application*.yml`,
`src/main/resources/db/migration/**`, `.claude/blueprints/<active>.json`,
`.claude/forbidden-imports.txt`; called by `nerviz-diagrams` with `--source harness`; printed,
never invoked, by `/new-feature-implement`).

**`## Why this is a manual skill`, verbatim:** "Form 2: rendering writes files the person
decides to commit, run after an implementation or a release, never every session, so the
trigger is manual (`disable-model-invocation`) and the case flow is printed by
`/new-feature-implement` rather than chained (`0182`). Axis 8 is both: the generated project
wants the same derived diagrams of itself that `nerviz-diagrams` draws of the harness, so the
engine moves here and travels, and `nerviz-diagrams` becomes its meta-only caller. The closest
rejected form was an `ArchHook.java` mode, which would put a renderer in every hook jar
(`0190` option C); record: `.claude/decisions/0211-project-diagrams-skill-sharing-the-diagram-engine.md`."

**Files.**

| Change | File |
|---|---|
| Move `scripts/Diagrams.java`, `scripts/export.mjs`, `package.json`, `package-lock.json`, `references/view.schema.json`, `visual-identity.md`, `step-contract.md` from `nerviz-diagrams` (git mv); add `--source project\|harness` (default `harness` when `.claude/schemas/extensions.json` has `export`, i.e. this repo; `project` otherwise), the project extractor, the four kinds, the legibility layer and check of § Legibility, the `.inputs.sha256` writer | `.claude/skills/project-diagrams/**` |
| New: `references/project-facts.md` (fact id prefixes and their source files), `references/icons/<slug>.svg` from `simple-icons@13.21.0` (postgresql, apachekafka, keycloak, opentelemetry, sonarqube, springboot) and `references/icons/NOTICE` with source and license per icon | same |
| Calls `../project-diagrams/scripts/Diagrams.java … --source harness`; body and `## Contract` updated | `.claude/skills/nerviz-diagrams/SKILL.md` |
| `media`: add `project-diagrams` to `skills`, rewrite `$comment` (meta-only no longer true for the class; cite 0211); `export.skills`: `project-diagrams` into `include`; `export.exclude` path `.claude/skills/nerviz-diagrams/node_modules/` gains `.claude/skills/project-diagrams/node_modules/`; new block `diagrams` with `inputs` globs, `image_icons` map (`postgres*`→`postgresql`, `*kafka*`→`apachekafka`, `*keycloak*`→`keycloak`, `*otel*`/`*opentelemetry*`→`opentelemetry`, `*sonarqube*`→`sonarqube`, app → `springboot`), `dev_tool_services` (`sonarqube`) | `.claude/schemas/extensions.json` |
| `doctor` line `Diagrams`: no `docs/diagrams/.inputs.sha256` → ⚪ optional; a listed input whose hash differs → warn naming the input and `/project-diagrams`; never in `doctor.gate.labels`; globs read from `extensions.json` | `.claude/hooks/ArchHook.java` + rebuilt `ArchHook.jar` |
| § 4 final report prints `/project-diagrams UC-NNN-<slug>` next to the `/git-publish` line; never a `Skill` call | `.claude/skills/new-feature-implement/SKILL.md` |
| CI step path `nerviz-diagrams/scripts/Diagrams.java` → `project-diagrams/scripts/Diagrams.java` | `.github/workflows/validate.yml`, `.claude/.ci/DiagramsTest.java`, `@CLAUDE.md` § Commands |
| Re-render the committed harness views with the new engine; fix any legibility collision there | `docs/diagrams/**` |

**Documentation.**

| File | What |
|---|---|
| `CLAUDE.md` | Routing row: "A diagram of a generated project — architecture, blueprint (containers), use case map, one use case's flow" → skill `project-diagrams`, manual, travels, Mermaid in the spec unchanged, cite 0211; the `nerviz-diagrams` row says it calls `project-diagrams`'s engine; § Commands `Diagrams.java check` path |
| `docs/pt-br/17-project-diagrams.md`, `docs/en/17-project-diagrams.md` (new) | What `/project-diagrams` draws, the four kinds table, blueprint styles with the logo licenses, where facts come from, the legibility check, the `doctor` line, PNG with optional Node; pt-BR and English, same content |
| `docs/pt-br/README.md`, `docs/en/README.md`, `README.md` § Documentation | One row each for page 17 |
| `README.md` § What is in the box, `docs/pt-br/01-tipos-de-arquivo.md`, `docs/en/01-file-types.md` | `project-diagrams` in the `media` class row and wherever travelling skills are listed |
| `docs/pt-br/03-new-feature.md`, `docs/en/03-new-feature.md` | `/new-feature-implement`'s report now prints `/project-diagrams UC-NNN-<slug>` |
| `docs/pt-br/04-arch-doctor.md`, `docs/en/04-arch-doctor.md` | The `Diagrams` line, warn only |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | New `DiagramsTest` cases |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | `/project-diagrams` wherever the template lists the project's manual commands |

**Verification commands (main thread reruns them):** `java .claude/hooks/ArchHook.java schema`
· `java .claude/hooks/ArchHook.java build --verify` · `java .claude/hooks/ArchHook.java doctor`
· `java .claude/skills/project-diagrams/scripts/Diagrams.java check` · `java .claude/.ci/DiagramsTest.java`
· `java .claude/skills/project-diagrams/scripts/Diagrams.java render --source project` against
`~/Documents/GitHub/bank-app` into a temp copy, then `check` — zero legibility findings.

## Propagation

Written by Phase 4, from the tables above.

| File | What changed |
|---|---|
| `.claude/skills/project-diagrams/scripts/Diagrams.java` (`git mv` from `nerviz-diagrams`) | `--source project\|harness` (default `harness` while `extensions.json` has `export`); the project extractor; the four kinds; the three blueprint styles; `.inputs.sha256`; the advance-width `Metrics`, wrap, edge lanes and edge-label placement; the post-render legibility check on every render (both sources) and `legible <file.svg>`; icons in SVG, drawio and Excalidraw. Two harness layout fixes the check found: a tool label crossed by a sibling arrow, a gate badge crossed by the arrow of its own step |
| `scripts/export.mjs`, `package.json`, `package-lock.json` (`git mv`) | paths; `export.mjs` takes a nested view (`use-cases/map`) |
| `references/view.schema.json`, `visual-identity.md`, `step-contract.md` (`git mv`) | schema: four project kinds, `facts`, `style`, `uc`, `groups` no longer required by the schema (the harness kinds still require it in `resolve`); identity: `text` advance table, `brand` colours, `project` layout, the `drift` palette entry and project roles |
| `references/project-facts.md`, `references/icons/*.svg`, `references/icons/NOTICE` | new |
| `.claude/skills/project-diagrams/SKILL.md` | new, the frontmatter and the `## Why this is a manual skill` of the record |
| `.claude/skills/nerviz-diagrams/SKILL.md` | calls the moved engine with `--source harness`; paths; the legibility failure mode |
| `.claude/schemas/extensions.json` | `diagrams` block (`inputs`, `inputs_manifest`, `walk_exclude_dirs`, `mermaid_types`, `dev_tool_services`, `image_icons`, `protocols`, `trigger_kinds`, `verbs`, `read_words`, `architecture`, `blueprint`, `map`, `flow`); `media` gains `project-diagrams` and a rewritten `$comment`; `export.skills.include` gains `project-diagrams`; `export.exclude` gains the second `node_modules/` |
| `.claude/hooks/ArchHook.java`, `ArchHook.jar` | `doctor` line `Diagrams` (`doctorDiagrams`), never in `doctor.gate.labels`; jar rebuilt |
| `.claude/skills/new-feature-implement/SKILL.md` | § 4 prints `/project-diagrams UC-NNN-<slug>`, `**Hands over:**` lists it; never a `Skill` call |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | a row for `/project-diagrams` |
| `.claude/.ci/DiagramsTest.java` | path; `--source project` cases over a fixture; the detector's red cases; the `doctor` cases |
| `.github/workflows/validate.yml`, `.gitignore`, `CLAUDE.md` | script path; the second `node_modules/`; routing row, `nerviz-diagrams` row, Commands, Node sentence |
| `docs/diagrams/**` | the five harness views re-rendered (two layout fixes, advance-width wrapping of the territory chips, the new tree counts) and their PNGs re-exported |
| `README.md`, `docs/en/01-file-types.md`, `docs/pt-br/01-tipos-de-arquivo.md`, `docs/en/03-new-feature.md`, `docs/pt-br/03-new-feature.md`, `docs/en/04-arch-doctor.md`, `docs/pt-br/04-arch-doctor.md`, `docs/en/07-ci-validate.md`, `docs/pt-br/07-ci-validate.md`, `docs/en/README.md`, `docs/pt-br/README.md` | the documentation table above |
| `docs/en/17-project-diagrams.md`, `docs/pt-br/17-project-diagrams.md` | new |

Choices the record left open: the blueprint of a project is the first `*.yaml` under `.claude/blueprints/<id>/`
(the record wrote `.json`; the blueprints are YAML); `render --source project` writes a default `view.json`
(`kind`, `title`, `facts`) for a kind that has none; an external system is a spec's `External calls` row that is
not `none` (none in `bank-app`, so its KYC cloud does not appear); a use case is `spec only` when its spec cites
`*UseCase.java` files and `src/` has none of them; the topics of the broker are listed with their partitions,
without a direction.

## CI coverage

| Job · step | Cases | Defect each catches | Proven |
|---|---|---|---|
| `validate · design › committed diagrams match the data they cite` | `Diagrams.java check` on the tree, now with the legibility check | a stale committed diagram, a label that collides in a harness view | green on the tree; red by name on a renamed cited skill, a changed `.svg` byte, a `.source-sha256` mismatch (existing cases a–u) |
| same, `DiagramsTest.java`, `--source project` over a fixture | a fresh render of the kinds is green; (a) a renamed package → `fact 'type:OrderController' not found`; (b) a Mermaid block of type `graph LR` → `00-use-case.md:` and `opens with 'graph LR'`; (c) a compose service removed → `differs from a fresh render`; a 70-character type name is wrapped inside its box | the specs, the code and the compose file drifting from the committed diagrams | green on the fixed engine; (a)–(c) red by name in the run |
| same, the detector | `legible <svg>`: a clean SVG green; red `TEXT×TEXT`, `TEXT×SHAPE`, `TEXT×LINE`, `OUT OF VIEWBOX`, `LINE×LINE` (horizontal and vertical); green for a badge on a box corner, for edges sharing a declared `data-fan` port and for edges 4 px apart | each of the detector's four findings, and the one declared exception | red by name, each case, in the run |
| same, `doctor` | `Diagrams` green with the inputs unchanged; an input changed after render → `Diagrams` names it and `/project-diagrams`; `doctor gate` has the same exit and no failing `Diagrams` line | a stale diagram going unnoticed; the warning leaking into the gate | green in the run |
| engine rules, injected once by hand (not a standing case) | no wrap of a long name → the wrap case red; the gutter ignoring its widest edge label → `TEXT×TEXT "customers" × "INSERT"`; the lifeline gap ignoring its widest message → `TEXT×LINE … crossed by 'edge-lifeline-…'`; a canvas 220 px too narrow → `OUT OF VIEWBOX "KafkaOutboxEventSender"`; every port on one y (two labels on one segment) → `TEXT×TEXT "INSERT" × "UPDATE"`; every lane at 0 → `LINE×LINE 'edge-type_KycVerificationCompletedListener_…' and 'edge-type_CustomerController_…' run together` | the five layout guarantees of § Legibility | each red with the defect injected, green again once reverted (the engine file is byte-identical to the fixed one) |

`schema` covers the `media` class, the required sections and the `export` entry (green). The generated
project has no CI step, by the axis 7 answer.

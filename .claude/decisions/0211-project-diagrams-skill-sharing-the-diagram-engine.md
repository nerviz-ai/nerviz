# 0211 · Diagrams of a generated project, drawn by a travelling skill that shares the nerviz-diagrams engine

- **Date:** 2026-11-16
- **Scenario:** "quero criar uma nova skill que é levado para projetos gerados que usa o mesmo
  motor de criacao de diagramas para criar diagramas tecnicos, arquiteturais e de fluxo de Use
  case nos projetos gerados"
- **Decision:** pending
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
| — kinds | Architecture (layers/modules and dependency rules), technical (compose services and outbound adapters), one flow view per use case, a map of all use cases | — |
| — fact source | Code + specs + compose | specs-only, code-only |
| — engine | One file, two extractors: `Diagrams.java facts --source project\|harness`; owner moves to the travelling skill, `nerviz-diagrams` calls it | a copy of the engine |
| — formats | `.drawio`, `.excalidraw`, SVG, animated SVG (Java); PNG through the optional Node export, which travels with its lockfile | SVG only |
| 17 — CI | Meta-repo `validate`; nothing in the generated project's CI | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | `.claude/skills/project-diagrams/` (Form 2, class `media`) owning `scripts/Diagrams.java` with `--source project\|harness`; `nerviz-diagrams` calls it; `doctor` line `Diagrams` (warn) over an inputs manifest `render` writes; command printed by `/new-feature-implement` | 8 | pending |
| C | `nerviz-diagrams` itself travels with two sources | 6 | pending |
| B | Copy of the engine into a new skill + a parity test in the meta-repo | 5 | pending |
| D | create nothing — Mermaid inside specs | 2 | pending |
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

Views in a generated project: `docs/diagrams/architecture/`, `docs/diagrams/technical/`,
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

Specs already carry Mermaid. No architecture, technical or map view; the named motive stays
unserved.

## View kinds — the selection rule the SKILL.md carries

Prototyped on `bank-app @ c7500c5` (https://claude.ai/artifact/4xNFgGrsM7RPQH7X4zphAD). One
kind answers one question with one diagram type; the skill never picks a type by taste.

| Kind · folder | Diagram type | Question | Facts | Leaves out | Generated when |
|---|---|---|---|---|---|
| `architecture` · `docs/diagrams/architecture/` | Components by layer: inbound adapters → use cases → domain; ports implemented by outbound adapters (dashed, hollow head) | Where does each type live, and which way do dependencies point? | active blueprint `packages.map`, types under `src/main/java`, `implements` read from source, `.claude/forbidden-imports.txt`, specs for drift | methods, fields, DTOs, config, tests | no argument, or `architecture` |
| `technical` · `docs/diagrams/technical/` | Containers (C4 level 2): app, compose services, external systems named by a spec; protocol and payload on every edge | What runs together, and how does the app talk to each piece? | `docker-compose.yml`, `application*.yml` keys (topics, issuer, OTLP), `CREATE TABLE` in migrations, `Trigger`/`External calls` rows | internal ports, replicas, secrets, variable values | no argument, or `technical` |
| `use-cases/map` · `docs/diagrams/use-cases/map/` | Map: trigger → use case → what it touches (aggregate, table, topic), backlog faded below | Which cases exist, what starts them, what do they share? | `Trigger` and `Side effects` rows of every `00-*.md`, `BACKLOG.md` active table, `*UseCase` types | each case's internal flow | no argument, `map`, or with a UC |
| `use-cases/UC-NNN-<slug>` · `docs/diagrams/use-cases/UC-NNN-<slug>/` | Two panels: sequence (happy path) + decision flowchart, each participant checked against code (✓/✗) | How does this case run, and where does it stop? | the spec's `mermaid` blocks (`sequenceDiagram`, `flowchart TD`), already bound to the text's names by `spec-diagrams.md`; with none, the header chain Trigger → adapter → use case → Side effects, captioned `spec has no flowchart` | path, verb, status, exceptions, retries; `classDiagram` and `stateDiagram-v2` stay in the spec | `/project-diagrams UC-NNN-<slug>`, printed by `/new-feature-implement` |

Drawing rules the prototype fixed: a spec-only element (no type in `src/`) is rose dashed and
labelled `spec only`; a dev-tool compose service (list in `extensions.json`) is grey dashed;
an `implements` edge exists only when read in source; support types (mapper, config,
properties) are a caption, not a box; an edge verb comes from the `Side effects` row or is
left unlabelled. Fact id prefixes: `pkg:`, `type:`, `impl:`, `service:`, `topic:`, `table:`,
`external:`, `uc:`, `bl:`, `mermaid:`, `drift:`. Observed: 5 of `bank-app`'s 7 specs predate
`0143` and carry no `mermaid` block — the fallback is the common case.

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

## Propagation

Pending approval.

## CI coverage

Pending Phase 4 step 9.

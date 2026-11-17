---
name: project-diagrams
description: Renders diagrams of a generated project from its code, its use case specs and its docker-compose.yml — the architecture view (components by layer), the blueprint view (containers, each service as its product logo), the use case map, and one flow view per use case — as .drawio, .excalidraw and SVG under docs/diagrams/, plus PNG when Node is present. Derived from facts the engine extracts; a model never draws a fact from memory. Never writes next to a spec, whose diagrams stay Mermaid.
argument-hint: "[empty for architecture, blueprint and map | architecture | blueprint | map | UC-NNN-<slug>]"
disable-model-invocation: true
allowed-tools: Read, Write, Edit, Glob, Bash(java:*), Bash(node:*), Bash(ls:*)
model: sonnet
---

# Project Diagrams

Draws the project from what it already says about itself — the types under `src/`, the specs
under `docs/use-cases/`, `docker-compose.yml` — so a diagram cannot name a class that was
renamed or a service that was removed. Code extracts the facts, you compose a view that cites
them, code validates, renders and checks that no label collides with another. A diagram a model
drew from memory is wrong the first commit the code changes.

## Target

$ARGUMENTS

Empty: the architecture view, the blueprint view and the use case map. A kind or a use case
folder name: only that view, created when absent (the table below says which).

**Class:** media

## Why this is a manual skill

Form 2: rendering writes files the person decides to commit, run after an implementation or a
release, never every session, so the trigger is manual (`disable-model-invocation`) and the case flow is printed by
`/new-feature-implement` rather than chained (`0182`). Axis 8 is both: the generated project
wants the same derived diagrams of itself that `nerviz-diagrams` draws of the harness, so the
engine moves here and travels, and `nerviz-diagrams` becomes its meta-only caller. The closest
rejected form was an `ArchHook.java` mode, which would put a renderer in every hook jar
(`0190` option C); record: `.claude/decisions/0211-project-diagrams-skill-sharing-the-diagram-engine.md`.

## View kinds

One kind answers one question with one diagram type; the skill never picks a type by taste.

| Kind · folder | Diagram type | Question | Facts | Leaves out | Generated when |
|---|---|---|---|---|---|
| `architecture` · `docs/diagrams/architecture/` | Components by layer: inbound adapters → use cases → domain; ports implemented by outbound adapters (dashed, hollow head) | Where does each type live, and which way do dependencies point? | active blueprint `packages.map`, types under `src/main/java`, `implements` read from source, `.claude/forbidden-imports.txt`, specs for drift | methods, fields, DTOs, config, tests | no argument, or `architecture` |
| `blueprint` · `docs/diagrams/blueprint/` | Containers (C4 level 2): app, compose services, external systems named by a spec; protocol and payload on every edge; each piece drawn as its product logo (style `brand`, the default) | What runs together, and how does the app talk to each piece? | `docker-compose.yml`, `application*.yml` keys (topics, issuer, OTLP), `CREATE TABLE` in migrations, `Trigger`/`External calls` rows | internal ports, replicas, secrets, variable values | no argument, or `blueprint` |
| `use-cases/map` · `docs/diagrams/use-cases/map/` | Map: trigger → use case → what it touches (aggregate, table, topic), backlog faded below | Which cases exist, what starts them, what do they share? | `Trigger` and `Side effects` rows of every `00-*.md`, `BACKLOG.md` active table, `*UseCase` types | each case's internal flow | no argument, `map`, or with a UC |
| `use-cases/UC-NNN-<slug>` · `docs/diagrams/use-cases/UC-NNN-<slug>/` | Two panels: sequence (happy path) + decision flowchart, each participant checked against code (✓/✗) | How does this case run, and where does it stop? | the spec's `mermaid` blocks (`sequenceDiagram`, `flowchart TD`), already bound to the text's names by `spec-diagrams.md`; with none, the header chain Trigger → adapter → use case → Side effects, captioned `spec has no flowchart` | path, verb, status, exceptions, retries; `classDiagram` and `stateDiagram-v2` stay in the spec | `/project-diagrams UC-NNN-<slug>`, printed by `/new-feature-implement` |

Blueprint style, in `view.json`: `"style": "brand" | "icons" | "boxes"`, default `brand`. `brand`
draws each compose service as its product logo from `simple-icons@13.21.0`, copied into
`references/icons/<slug>.svg` with a `NOTICE` naming source and license per icon (Kafka
Apache-2.0 under the ASF marks policy, Keycloak under the Linux Foundation trademark policy,
OpenTelemetry CC-BY-4.0 with attribution, PostgreSQL, SonarQube and Spring Boot under each
project's trademark policy); the `image → slug` map is data in `extensions.json`
(`diagrams.image_icons`, `postgres*` → `postgresql`). A service with no mapped logo, the client
and an external system fall back to the generic role icon of `icons` (cylinder, partition bands,
shield, funnel, cloud); a black logo takes the text colour. `icons` and `boxes` stay for a
project that wants no brand mark. In this repository the word "blueprint" also names the
architecture blueprint (`.claude/blueprints/`), so this skill calls the kind "the blueprint view"
and never bare "blueprint".

Drawing rules: a spec-only element (no type in `src/`) is rose dashed and labelled `spec only`; a
dev-tool compose service (`diagrams.dev_tool_services`) is grey dashed; an `implements` edge exists
only when read in source; support types (mapper, config, properties) are a caption, not a box; an
edge verb comes from the `Side effects` row or is left unlabelled. Fact id prefixes and the file
each comes from: `references/project-facts.md`.

## Procedure

Run every command from the project root. No network at run time; `npm ci` in step 6 is the only
download, and nothing is ever `npx`-ed from an unpinned package.

1. **Facts.** `java .claude/skills/project-diagrams/scripts/Diagrams.java facts --source project`
   writes `docs/diagrams/facts.json`: `pkg:*`, `type:*`, `impl:*`, `service:*`, `topic:*`,
   `table:*`, `external:*`, `uc:*`, `bl:*`, `mermaid:*`, `drift:*`, each with a stable id. Read
   it to learn what you may cite.
2. **Compose.** With no `view.json` for the kind asked, `render` writes the default one
   (`kind`, `title`, `facts`: the ids or selectors it draws, `style` for the blueprint view). Adjust
   it per `references/view.schema.json`; free text lives only in `title` and `caption`. A node you
   want that no fact backs does not go in: the spec or the code is what is missing.
3. **Validate.** `java .claude/skills/project-diagrams/scripts/Diagrams.java validate --source project`.
   Every fact id and selector must resolve against facts extracted now, and every label must be legible.
4. **Render.** `java .claude/skills/project-diagrams/scripts/Diagrams.java render --source project [architecture|blueprint|map|UC-NNN-<slug>]`
   writes `<view>.drawio`, `<view>.excalidraw`, `<view>.svg` and `<view>.animated.svg` beside the
   view, and `docs/diagrams/.inputs.sha256`, the hash of every file the views were drawn from.
   A legibility exit 2 is fixed by relayout (a wider column, a wrapped label), never by deleting a fact.
5. **Check.** `java .claude/skills/project-diagrams/scripts/Diagrams.java check --source project` —
   every committed file equals a fresh render, and no text lies over text, a shape's outline or a line.
6. **Export (optional).** Once per machine, copy the skill's `package.json` and `package-lock.json`
   into `"${XDG_CACHE_HOME:-$HOME/.cache}/nerviz-diagrams"` and run
   `npm ci --prefix "${XDG_CACHE_HOME:-$HOME/.cache}/nerviz-diagrams" --no-update-notifier --no-audit --no-fund`.
   Then `node .claude/skills/project-diagrams/scripts/export.mjs <view> [--video]` writes
   `docs/diagrams/<view>/dist/<view>.png`.

Never hand-edit a rendered file: edit `view.json`, or fix the spec or the code the fact comes from,
and render again. `doctor`'s `Diagrams` line warns when an input changed after the last render.

## Failure modes

**`fact … not found`.** A type, service or case was renamed or removed: `validate` names the view,
the JSON path and nearby ids. Update the view, or re-render when it cites by selector.

**A Mermaid block of a spec that is not an allowed type.** `facts` exits 2 naming the file and the
line; a spec draws `sequenceDiagram`, `flowchart TD`, `classDiagram` or `stateDiagram-v2` only
(`@.claude/rules/spec-diagrams.md`). Fix the spec, never this skill.

**Legibility.** `validate`, `render` and `check` exit 2 naming the view and both parties —
`TEXT×TEXT`, `TEXT×SHAPE`, `TEXT×LINE`, `OUT OF VIEWBOX`, `LINE×LINE`. Widen the column or the lifeline gap in
`references/visual-identity.md`, or shorten what the spec names; never delete a fact to silence it.

**Node absent.** Steps 1-5 are Java and still work; only step 6 needs Node, and the PNG is skipped
with one line.

**No `docs/use-cases/`.** The map and the flow views are skipped with one line; the architecture and
the blueprint views still render.

**Stale render.** `check` lists each file that differs from a fresh render. Run `render`.

## Contract

**Class:** media — the territory is `skill_classes.media` in `@.claude/schemas/extensions.json`:
`docs/diagrams/**`, nothing else. `ArchHook.java guard` enforces it.

**Reads** `src/main/java/**`, `docs/use-cases/**`, `docker-compose.yml`,
`src/main/resources/application*.yml`, `src/main/resources/db/migration/**`,
`.claude/blueprints/<active>/<active>.yaml`, `.claude/forbidden-imports.txt`, and this skill's `references/`.

**Writes** `docs/diagrams/**` only — never under `docs/use-cases/` (`0143`).

**Called by** `nerviz-diagrams`, with `--source harness`; **printed, never invoked,** by
`/new-feature-implement`'s final report.

**Never writes** any other path, and declares no `.mcp.json` server.

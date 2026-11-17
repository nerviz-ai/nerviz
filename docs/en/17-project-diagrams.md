# Project diagrams — what `/project-diagrams` draws of a generated project

A generated project already says everything a diagram needs: the types under `src/`, the specs
under `docs/use-cases/`, `docker-compose.yml`. `/project-diagrams` draws those facts, so a diagram
cannot name a class that was renamed or a service that was removed. It is a skill that travels
into the project, class `media`: it writes `docs/diagrams/**` and nothing else, and it never
writes next to a spec, whose own diagrams stay Mermaid.

Design: [`0211`](../../.claude/decisions/0211-project-diagrams-skill-sharing-the-diagram-engine.md).
The engine is the one that draws this repository's own harness (`/nerviz-diagrams`,
[`0190`](../../.claude/decisions/0190-nerviz-diagrams-skill-derived-from-data.md)): one file,
`.claude/skills/project-diagrams/scripts/Diagrams.java`, with `--source project` or
`--source harness`. `nerviz-diagrams` is its meta-only caller.

## What it draws

One kind answers one question with one diagram type. The skill never picks a type by taste.

| Kind · folder | Diagram type | Question | Facts | Leaves out |
|---|---|---|---|---|
| `architecture` · `docs/diagrams/architecture/` | Components by layer: inbound adapters → use cases → domain; ports implemented by outbound adapters (dashed, hollow head) | Where does each type live, and which way do dependencies point? | the active blueprint's `packages.map`, the types under `src/main/java`, `implements` read from the source, the specs (for drift) | methods, fields, DTOs, config, tests |
| `blueprint` · `docs/diagrams/blueprint/` | Containers (C4 level 2): the app, the compose services, external systems a spec names; protocol on every edge | What runs together, and how does the app talk to each piece? | `docker-compose.yml`, `application*.yml` (topics), `CREATE TABLE` in the migrations, the `External calls` rows | internal ports, replicas, secrets, variable values |
| `use-cases/map` · `docs/diagrams/use-cases/map/` | Map: trigger → use case → what it touches (table, topic), the backlog faded below | Which cases exist, what starts them, what do they share? | the `Trigger` and `Side effects` rows of every `00-*.md`, the active table of `BACKLOG.md` | each case's internal flow |
| `use-cases/UC-NNN-<slug>` · `docs/diagrams/use-cases/UC-NNN-<slug>/` | Two panels: a sequence (the path that succeeds) and a decision flowchart, each participant checked against the code (✓ or ✗) | How does this case run, and where does it stop? | the spec's own `mermaid` blocks; with none, the header chain Trigger → adapter → use case → Side effects, captioned `spec has no flowchart` | path, verb, status, exceptions, retries; `classDiagram` and `stateDiagram-v2` stay in the spec |

`/project-diagrams` with no argument draws the first three; `architecture`, `blueprint`, `map` or
`UC-NNN-<slug>` draws one. `/new-feature-implement` prints `/project-diagrams UC-NNN-<slug>` in its
final report, next to the `/git-publish` line, and never runs it.

Drawing rules: a type a spec names that `src/` does not have is rose and dashed, labelled `spec only`;
a dev-tool compose service is grey and dashed; an `implements` edge exists only when it is read in the
source; support types (mappers, configuration, properties) are a caption, not a box; the verb on an edge
comes from the `Side effects` row or the edge has no label.

## The blueprint view's styles

`"style"` in the view's `view.json`: `brand` (the default), `icons` or `boxes`.

| Style | A compose service is drawn as |
|---|---|
| `brand` | its product logo, in the brand colour; a black logo takes the text colour |
| `icons` | a generic icon of its role — cylinder, partition bands, shield, funnel, cloud — in the palette colour |
| `boxes` | a rectangle with text |

The `image → logo` map is data, `diagrams.image_icons` of `extensions.json` (`postgres*` →
`postgresql`). A service with no mapped logo, the client and an external system fall back to the
generic icon. The logos come from `simple-icons@13.21.0` and sit in
`.claude/skills/project-diagrams/references/icons/`, with a `NOTICE` that names source and license per
icon:

| Logo | License and policy |
|---|---|
| Apache Kafka | Apache-2.0; the mark is the ASF's (marks policy) |
| Keycloak | the Linux Foundation trademark policy |
| OpenTelemetry | CC-BY-4.0, attribution required |
| PostgreSQL, SonarQube, Spring Boot | each project's trademark policy |

A project that wants no brand mark sets `icons` or `boxes`.

## Where the facts come from

`references/project-facts.md` is the single owner: each fact id prefix (`pkg:`, `type:`, `impl:`,
`service:`, `topic:`, `table:`, `external:`, `uc:`, `bl:`, `mermaid:`, `drift:`) and the file it is read
from. Every list the extractor uses — the globs of the inputs, the package roles, the compose images and
their logos, the trigger words — is the `diagrams` block of `.claude/schemas/extensions.json`, never a
constant in the Java. A Mermaid block of a spec whose first line is not one of the four allowed types
stops the extraction and names the file and the line.

## The legibility check

A diagram whose labels collide is unreadable however right its facts are, so the engine owes a
guarantee, in Java, byte for byte the same on every OS:

1. **Measure, then lay out.** A label's width comes from an advance-width table committed in
   `references/visual-identity.md`, never from AWT. A box is as wide as its longest label plus padding;
   a column gap or a lifeline gap is at least the widest edge label it carries.
2. **Wrap before shrinking.** A label past `max_label_width` breaks at a word boundary, or at a
   camel-case boundary for a type name, into at most `label_lines` lines. The font never shrinks.
3. **Edges route around text.** An edge runs in the gutter between two columns, in a lane of its own, and
   its label sits on the last straight segment, beside its own edge.
4. **Check after render, exit 2.** `validate`, `render` and `check` read the SVG they produced and name
   both parties and the view: `TEXT×TEXT`, `TEXT×SHAPE`, `TEXT×LINE`, `OUT OF VIEWBOX`, `LINE×LINE`. A badge (the ✓ on a
   participant) is the one declared exception. Nothing is written on a finding: it is fixed by relayout,
   never by deleting a fact.
5. **Both sources.** The harness views of this repository go through the same check.

`Diagrams.java legible <file.svg>` runs just the check on one file.

## Using it

```bash
java .claude/skills/project-diagrams/scripts/Diagrams.java facts    --source project
java .claude/skills/project-diagrams/scripts/Diagrams.java render   --source project [architecture|blueprint|map|UC-NNN-<slug>]
java .claude/skills/project-diagrams/scripts/Diagrams.java check    --source project
```

`render` writes, beside each view, `.drawio`, `.excalidraw`, `.svg` and `.animated.svg`, and the project's
`docs/diagrams/.inputs.sha256`: the hash of every file the views were drawn from. With no `view.json` for
the kind asked, it writes the default one (`kind`, `title`, `facts`, and `style` for the blueprint view).
With no `docs/use-cases/` the map and the flow views are skipped with one line.

**PNG** needs Node, optional and only for that: once per machine, `npm ci` from the skill's
`package-lock.json` into a per-user cache, then
`node .claude/skills/project-diagrams/scripts/export.mjs <view>`. Without Node the PNG is skipped, and
everything else still runs in Java.

## The `doctor` line

`Diagrams` rehashes the files listed in `docs/diagrams/.inputs.sha256` and the files the globs match now.
⚪ when no diagram was rendered yet; ⚠️ naming the first input that changed, was removed or appeared since,
with the command to run. It is a warning, never in `doctor.gate.labels`, and the generated project's CI
has no diagram step: a diagram going stale is visible, not dangerous.

## What CI proves

Only in this repository: `DiagramsTest.java` keeps the harness views equal to a fresh render, and runs
`--source project` over a fixture project it writes — a renamed package (`fact … not found`), a Mermaid
block that is not an allowed type (named by file and line), a compose service removed (`differs from a
fresh render`), a 70-character type name that must wrap — plus one red case per legibility finding and
the `doctor` line (an input changed after render warns, `doctor gate` unmoved).

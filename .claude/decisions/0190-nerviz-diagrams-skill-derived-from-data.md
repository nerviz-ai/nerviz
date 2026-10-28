# 0190 · Diagrams of this `.claude/` derived from its data, by a meta-repo skill

- **Date:** 2026-10-28
- **Scenario:** Criar uma skill própria de diagramas do Nerviz, manual e só para este
  repositório (fora do export): lê `.claude/schemas/extensions.json` (skill_classes,
  agent_classes, pipeline do /new-feature) e as camadas do `.claude/` e gera um modelo de
  diagrama validado por schema, emitido como `.drawio` e/ou `.excalidraw` em `docs/diagrams/`,
  com export SVG/PNG para o site e animação para vídeo. Aceita Node. Input: a trade-off note
  the user wrote outside the repository (GitHub skills, draw.io/Excalidraw MCP, own skill,
  blend — recommending the blend with the own skill at its core).
- **Decision:** Option A — Form 2, `.claude/skills/nerviz-diagrams/SKILL.md`, class `media`,
  `scripts/Diagrams.java`, `.claude/.ci/DiagramsTest.java`
- **State:** approved by Lucas Fernandes, on 2026-10-28. Phase 4 delegated whole to a
  `sonnet` subagent at the approver's request — a deliberate exception to the skill's
  "steps 1-7 and 9 never delegated", bounded by this record and reviewed in the main thread

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Named motive, not a failure: diagrams of the harness for the site and for promotional video, which a model drawing from memory gets wrong the first commit the pipeline changes | create nothing |
| 2 — trigger | `/nerviz-diagrams`, by hand | Form 1 (auto-invocable — would also compete with the Mermaid of `use-case-design`/`domain-modeling`) |
| 3 — frequency | Rare: per release, or when a piece is added | Form 5 (every session) |
| 5 — nature | Procedure: extract, compose, validate, render, export | Forms 4, 5 |
| 6 — isolation | None — output is files, the interview-free composition fits the main thread | Form 3 |
| 7 — mandatoriness | Persuasion for the composition; **the freshness is gated**: CI fails when a committed diagram no longer matches the data | — (gate is a CI test, not a session hook) |
| 8 — destination | This repository only | `export.skills.include` |
| 9 — integration | Reads `extensions.json`, `.claude/**` on disk, the `### Step N: X (depends on …)` headings of `new-feature/SKILL.md`. Writes `docs/diagrams/**` only. Collides with nothing: `0143` keeps `.drawio`/`.excalidraw` out of `docs/use-cases/`, which this skill never touches | — |
| — model | Code extracts `facts.json`; the skill composes a **view** whose every entity node cites a fact id or a selector over facts; a validator refuses a node without evidence | model-authored diagram (option 1/2 of the note) |
| — language | Java single-file under the skill's `scripts/` for extract, validate, emit `.drawio`, `.excalidraw`, static and animated SVG; Node only for PNG (`@resvg/resvg-js`) and optional MP4/WebM (Playwright) | all-Node; a new `ArchHook.java` mode |
| — render | Own SVG from the model (one visual identity); no editor CLI | draw.io desktop CLI, Excalidraw through a headless browser |
| — pipeline source | Parsed from the step headings of `new-feature/SKILL.md`, its single owner today | a `pipeline` block in `extensions.json` (refactors the orchestrator) |
| — formats | Both `.drawio` and `.excalidraw`, from the same view | — |
| — Node install | `package.json` + lockfile in the skill, exact versions; `npm ci --prefix` into a per-user cache outside the repository, with `--no-update-notifier --no-audit --no-fund` | `node_modules/` inside `.claude/` |
| 11–13 — MCP | None declared. The Excalidraw connector of the user's claude.ai account already answers for ad-hoc refinement; a team server with no observed use is anti-pattern 13 | Form 6a |
| 17 — CI | `validate` on every PR, Java only | path-filtered workflow; no gate |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | `.claude/skills/nerviz-diagrams/` (Form 2) + `scripts/Diagrams.java` + Node package for PNG/video + new class `media` + `DiagramsTest` | 8 | Approved |
| B | Same skill and scripts, joined to class `report` with a `write_allow` override | 6 | Rejected — the `report` class describes an analyzer that publishes through report-issue; an override would name a territory the class does not describe |
| C | Generator as an `ArchHook.java` mode `diagram` | 4 | Rejected — `export` would ship a meta-repo-only renderer as dead code into every generated project and force a jar rebuild for a layout change |
| D | Create nothing — draw ad hoc with the Excalidraw connector | 2 | Rejected — the diagram is what the model remembers, stale on the next pipeline change, unversioned |

### Option A — `nerviz-diagrams` in a class of its own (score 8)

**Motivator:** axes 1, 2 and 9 — a named motive, a manual trigger, and a write territory
(`docs/diagrams/**`) no class has today.

**Shape.**

```
.claude/skills/nerviz-diagrams/
  SKILL.md                         Form 2, model: sonnet, **Class:** media
  references/view.schema.json      the view contract (JSON Schema 2020-12 subset)
  references/visual-identity.md    palette, type, stroke, spacing — one owner
  scripts/Diagrams.java            facts | validate | render | check   (java only)
  scripts/export.mjs               PNG via @resvg/resvg-js, MP4/WebM via Playwright (opt-in)
  package.json · package-lock.json exact versions, nothing else
docs/diagrams/
  facts.json                       NOT committed — regenerated, gitignored
  <view>/view.json                 composed by the skill, committed
  <view>/<view>.drawio · .excalidraw · .svg · .animated.svg   rendered, committed
  <view>/dist/<view>.png · .mp4    Node output + <file>.source-sha256, committed
```

- **Facts** — `Diagrams.java facts` reads `skill_classes`, `agent_classes`, `export`,
  `mods`, the step headings of `new-feature/SKILL.md`, and counts on disk under
  `.claude/{mods,hooks,skills,agents,rules,blueprints}`. Every fact has a stable id
  (`skill:use-case-design`, `class:design`, `step:3b`, `layer:rules`). No list is a Java
  constant: what to read is the view's selectors plus `extensions.json` itself.
- **View** — what the skill composes: kind (`layers`, `classes`, `pipeline`, `territory`),
  groups, emphasis, captions, and `steps` (animation order). An entity node is either
  `{"fact": "<id>"}` or a selector `{"from": "skill_classes.design.skills"}` that expands
  at render time — so a skill added to a class enters the diagram on the next render
  with no recomposition. Free text is allowed only in captions and titles, never as a
  node.
- **Layout** — computed by the renderer per kind (layered rows, lanes, grid), deterministic
  and byte-stable: sorted keys, fixed number format, `\n` line endings — the `hooks`
  matrix runs on Windows too.
- **Animation** — `.animated.svg`: CSS keyframes revealing nodes and edges in `steps`
  order. Java writes it; Node only records it to MP4/WebM when asked.
- **Network** — none at run time. `npm ci` is the one download, at install; no update
  check anywhere, no `npx` of an unpinned package.
- **Mermaid** — untouched. The skill writes no file under `docs/use-cases/`; its
  description names the harness of this repository only, and `disable-model-invocation`
  keeps it out of the trigger race.

**Pros:** fidelity by construction (evidence per node, selectors expand from the data);
regeneration is one Java command, not a redesign; CI needs no Node; one visual identity
for site and video; nothing travels.

**Cons:** new precedent — the first skill with a `scripts/` folder and the first Node
dependency outside the `mods` CI job (`@CLAUDE.md` § Dependencies changes). The
pipeline is parsed from prose headings: a reworded heading turns CI red. Every PR that
adds a skill, agent or class must re-render (`Diagrams.java render`), or CI fails — the
friction the freshness gate buys. `@CLAUDE.md` is already at 205 lines, over the ~200
target; the Dependencies line and one routing row add two more. PNG/MP4 are only
checked by the hash of the SVG they came from, not re-rendered in CI.

**Points cut in the rubric:** 5 (maintenance: a second language at the edge, a renderer
of our own); 6 (precedent: `scripts/` in a skill, a new class for one skill). ≈ 7.8 → 8.

**CI:** `validate` › `design` › `frontmatter schema` already covers frontmatter, class,
sections and the `export.skills.exclude` entry. New: `.claude/.ci/DiagramsTest.java`,
step `committed diagrams match the data they cite` in `validate` › `design` — runs
`Diagrams.java check`: every view validates, every fact id and selector resolves against
facts extracted now, every rendered file equals a fresh render byte for byte, every
`dist/` file's `.source-sha256` equals its SVG's hash. Plus one case in
`SkillTerritoryTest` for class `media` (writes `docs/diagrams/x.svg`, refused
`docs/use-cases/UC-001-x/a.drawio`).

### Option B — class `report` with an override (score 6)

Same files. Saves one class block, as `harness-lessons` did in `0188`. But `report`'s
`$comment` defines it as an analyzer writing a findings file and publishing through
`report-issue`; a diagram renderer is neither, and the class name would lie about the
territory it overrides. Cut: 1 (form fit of the class), 5.

### Option C — `ArchHook.java diagram` (score 4)

Reuses the JSON parser and the jar. But `export` copies `ArchHook.java` whole: a
meta-repo-only renderer ships as dead code into every generated project, grows the jar
every hook launches, and forces a `build` and `build --verify` cycle for a change to a
diagram's layout. Cut: 3, 5, 8, 9.

### Option D — create nothing (score 2)

Draw with the Excalidraw connector when needed. Fast, but the diagram is what the model
remembers, stale on the next pipeline change, not versioned as a file, and the site
export stays manual — the problem the note set out to solve.

## References

| Claim | Source |
|---|---|
| `.drawio`/`.excalidraw` are refused next to a spec, not elsewhere | `@.claude/decisions/0143-mermaid-diagrams-in-use-case-and-domain-specs.md` · `@.claude/rules/spec-diagrams.md` |
| Writes outside the open phase's territory are swept at Stop, including a script's | `@CLAUDE.md` § Commands, `guard sweep` · `0114` |
| Every skill needs a class; a class may pin models | `skill_classes` in `@.claude/schemas/extensions.json` · `0058` · `0081` |
| A creation/meta-only skill goes in `export.skills.exclude` | `@CLAUDE.md` invariant 9 · `export.skills.exclude` |
| Node entered this repo only for the `mods` CI job | `@.claude/decisions/0131-mods-in-architect-designer.md` · `@CLAUDE.md` § Dependencies |
| The pipeline order exists only as step headings | `.claude/skills/new-feature/SKILL.md` § Procedure |
| A server with no observed use costs context every session | decision matrix § 7, anti-pattern 13 |
| The class-override precedent for one skill | `@.claude/decisions/0188-harness-lessons-and-footprint-mode.md` |
| CI tests are single-file Java programs, `java`/`git`/`curl` only | `@CLAUDE.md` § Dependencies · `.claude/.ci/*Test.java` |
| `validate`'s `hooks` job runs on Windows | `.github/workflows/validate.yml` matrix |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/nerviz-diagrams/SKILL.md` | New skill, class `media`, manual |
| `.claude/skills/nerviz-diagrams/references/view.schema.json` | View contract (JSON Schema 2020-12 subset) |
| `.claude/skills/nerviz-diagrams/references/visual-identity.md` | Palette, type, stroke, spacing; the JSON block `Diagrams.java` reads |
| `.claude/skills/nerviz-diagrams/scripts/Diagrams.java` | `facts`, `validate`, `render`, `check` |
| `.claude/skills/nerviz-diagrams/scripts/export.mjs` · `package.json` · `package-lock.json` | PNG (`@resvg/resvg-js` 2.6.2) and opt-in WebM (`playwright` 1.64.0), exact versions |
| `.claude/schemas/extensions.json` | `skill_classes.classes.media`; `nerviz-diagrams` in `export.skills.exclude` |
| `.gitignore` | `docs/diagrams/facts.json` |
| `CLAUDE.md` | Dependencies sentence; Commands row (`Diagrams.java check`); routing row |
| `README.md` | Skill count 33 → 34 |
| `docs/{en,pt-br}/07-ci-validate.md` | Node D29, step row, local commands; `SkillTerritoryTest` 63 → 69 cases (it was already stale) |
| `docs/{en,pt-br}/11-pitfalls.md` | Part 2 entry: a hand-edited file under `docs/diagrams/` |
| `.github/workflows/validate.yml` | `design` › `committed diagrams match the data they cite` |
| `.claude/.ci/DiagramsTest.java` | New test, six red cases |
| `.claude/.ci/SkillTerritoryTest.java` | Three cases for class `media` |
| `docs/diagrams/layers/` · `docs/diagrams/pipeline/` | `view.json`, four rendered files and `dist/*.png` + `.source-sha256` each |

Goes to the generated project: **no** — `export.skills.exclude`; `docs/diagrams/` is this
repository's.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › committed diagrams match the data they cite` | `Diagrams.java check` (views valid, every fact and selector resolves, every rendered file equals a fresh render, every `dist/` hash equals its SVG's) then `DiagramsTest.java` on a temp copy | green on the tree · red by name for: a cited skill renamed (`fact 'skill:git-publish' not found`), a skill added to a class (`classes.svg — differs from a fresh render`), one `.svg` byte, a node with no `fact`/`from` (`matches none of the allowed shapes`), a step heading without `(depends on …)` (`step heading does not match`), a `.source-sha256` mismatch |
| `validate · design › frontmatter schema` (existing) | class `media`, sections, `export.skills.exclude` entry | green |
| `validate › SkillTerritoryTest` (existing, three cases added) | `docs/diagrams/layers/layers.svg` admitted, `docs/use-cases/UC-001-x/a.drawio` refused for `media` | green, 69 cases |
| `validate · design › no dependencies outside the Java ecosystem` (existing) | the skill's JSON carries no `node `/`npm install` text | green |

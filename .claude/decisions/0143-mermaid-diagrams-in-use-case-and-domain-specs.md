# 0143 · Mermaid diagrams in the use case and domain specs, owned by one norm

- **Date:** 2026-10-12
- **Scenario:** Add Mermaid diagrams to the spec templates of the design skills: a
  `sequenceDiagram` in `00-caso-de-uso.md` (trigger, use case, ports, side effects), a
  `classDiagram` of the aggregate and its value objects in `10-dominio.md`, plus an optional
  `stateDiagram-v2` when the aggregate has a lifecycle. Mermaid only, inside the markdown — no
  `.drawio` or `.excalidraw` output, no new dependency. The diagram uses only the canonical
  names the spec already defines, so it cannot contradict the text.
- **Decision:** Option A — Form 4, `.claude/rules/spec-diagrams.md`, plus the diagrams in
  `use-case-design/templates/use-case-spec.md.example` and
  `domain-modeling/templates/domain-spec.md.example`, a citation in each skill's generation
  step, and `.claude/.ci/SpecDiagramTemplateTest.java`
- **State:** approved by Lucas Fernandes, on 2026-10-12

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | No run failed for lack of a diagram. Motive named by the user: a reviewer reads the spec in the PR, where GitHub renders Mermaid, and the generated project shows its design at a glance | create nothing (a named motive, not anticipation) |
| 5 — nature | A section shape (template) plus a declarative fact about every spec file (names, text wins) | new skill, agent |
| 4 — territory | `docs/use-cases/**` — the folder the design skills write and the executor reads | Form 5 (`CLAUDE.md`, every session) |
| 7 — mandatoriness | Persuasion inside a generated project; a CI test keeps the exemplars honest | Form 7a/7c (`doctor` line or `guard` check on generated specs), Form 8 |
| 8 — destination | Both — `use-case-design` and `domain-modeling` are in `export.skills.include`, rules travel whole | — |
| 9 — integration | Templates owned by the two skills (`0115`: the template is the only source of the file's shape); the executor reads the specs; consolidation is by reference | — |
| — norm | One owner: a new rule with `paths: docs/use-cases/**`, loaded for whoever writes and whoever reads a spec | the norm in two template comments |
| — placement | In `00-caso-de-uso.md`, under `## Flow`, below the numbered list, `autonumber` matching it | replacing the list; a new section |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | `rules/spec-diagrams.md` + diagrams in both templates + a citation in each skill's generation step + `SpecDiagramTemplateTest` | 8 | **Approved** |
| B | Diagrams in both templates, norm in each template's HOW TO USE comment, same CI test | 5 | Rejected — one norm in two files (invariant 2), and the reader of a finished spec never sees it |
| C | A + a `Spec diagrams` line in `doctor` warning on a generated spec whose diagram names something its text does not | 5 | Rejected — no observed failure (anti-pattern 16); a regex over free-form Mermaid would misfire on a legitimate alias |
| D | create nothing | 3 | Rejected — the motive the user named stays unserved |
| — | `.drawio` / `.excalidraw` files next to the spec | 1 | Rejected before scoring — a second file the executor cannot read, a diff no reviewer reads, and a viewer the project does not have; both tools import Mermaid, so the spec's block already is the source of any such view |

### Option A — `.claude/rules/spec-diagrams.md` (score 8)

**Motivator:** axes 1 and 4 — a named motive, and a territory (`docs/use-cases/**`) that both
the writer and the reader of a spec touch.

**Shape.**

- `00-caso-de-uso.md` · `## Flow`: the numbered list stays the source; a `sequenceDiagram`
  with `autonumber` follows it, one message per step — the trigger's caller, the inbound
  adapter, the inbound port, the aggregate, each outbound port, and each side effect from the
  header's `Side effects` row as a note. No path, verb or status: those are
  `30-rest.md`'s.
- `10-dominio.md` · § 1: a `classDiagram` after the value object table — the root, its fields
  from the field table, each value object, and a reference to another aggregate drawn as an
  id, never as composition. A `stateDiagram-v2` after the *State reachability* table, written
  exactly when that table is not `none`: one state per value, one transition per *Reached by*
  method, and a value no method reaches yet carries a note with the named case.
- The rule says: Mermaid inside the markdown and nothing else; every name in a diagram is one
  the same file's text already defines; a diagram adds no fact the text lacks; where they
  disagree the text wins and the diagram is the defect. It mentions no skill or agent
  (invariant 1).

**Pros:** one owner for the norm (invariant 2). Loads by `paths` for the design skill writing a
partial and for whoever implements from the spec, so the executor learns that the text wins
without an edit to its body or to `new-feature-implement`. Consolidation stays by reference —
`UC-NNN-spec.md` carries no diagram, the partials do. No dependency: GitHub renders the block,
and a project without a renderer reads it as text.

**Cons:** persuasion in a generated project — a model can still draw a name the text lacks.
Accepted by axis 7. A rule on a non-package glob is new: `docs/use-cases/**` must enter
`export.derived_paths.derived_paths_exempt_globs`, or `validate` › `design` › `every norm
paths matches some blueprint` and `schema` fail on it. Fixing the exemplars exposes a
pre-existing divergence the diagram would otherwise draw: `00`'s `UserStatus` row lists
`ACTIVE`, `BLOCKED` while `10`'s *State reachability* has `PENDING_EMAIL_CONFIRMATION`,
`ACTIVE`, `DEACTIVATED`, and `10`'s field table has no `status` row — both exemplars are
aligned in the same change.

**Points cut in the rubric:** 4 (enforcement) — persuasion in the generated project; 6
(precedent) — the first rule whose territory is a documentation folder.

**CI:** `validate` › `design` › `frontmatter schema` already covers the rule's frontmatter
and the exempt glob. New: `.claude/.ci/SpecDiagramTemplateTest.java`, step `spec template
diagrams name only what their text defines` in `validate` › `design` — every identifier of
every `mermaid` block in the two templates (participants and aliases, classes, states, and
each `CamelCase`, `UPPER_SNAKE` or `method(` token) appears in the same file outside the
blocks, and every block opens with one of the three allowed diagram types. Proves the
exemplar, never a generated spec: nothing under `.claude/.ci/` travels.

### Option B — the norm in both templates (score 5)

Same diagrams and test; the norm is written in each template's HOW TO USE comment. Costs zero
context, but the same norm lives in two files (strains invariant 2), and the executor, which
never reads a template comment, is not told that the text wins.

### Option C — A plus a `doctor` line (score 5)

A `Spec diagrams` line (warning, never gating) scanning every `docs/use-cases/**` file for a
diagram name its text lacks. No failure observed — anti-pattern 16 — and a new `ArchHook.java`
mode path to maintain for it. Reopen when a lessons-learned shows a spec diagram that misled an
implement run.

### Option D — create nothing (score 3)

Specs keep text only. The motive the user named (PR review, a design readable at a glance)
stays unserved, and the item stays open on the roadmap.

## Not needed — decided with A

| Question | Answer | Why |
|---|---|---|
| Does `java-spring-boot-developer` or `new-feature-implement` change? | No | The rule loads by `paths` when the spec is read; the spec's text stays the source of truth (agent body: "where it and a partial disagree, the spec wins") |
| Does consolidation copy the diagrams into `UC-NNN-spec.md`? | No | `UC-NNN-spec.md` is consolidated by reference; a copied diagram would be a second place for the same fact |
| A `migrations` entry for projects already generated? | No | Nothing existing is left wrong: an `approved` or `implemented` folder is frozen (`0066`) and stays valid without a diagram; the next spec takes the shape from the template, its only source (`0115`). `/arch-adopt` carries the rule and both templates |

## References

| Claim | Source |
|---|---|
| The template is the only source of the spec's shape | `@.claude/decisions/0115-use-case-design-template-sole-shape-source.md` |
| Approved and implemented spec folders are frozen | `@.claude/decisions/0066-spec-state-machine.md` |
| A rule loads by `paths`; without it, at launch | `@.claude/rules/00-index.md` § How a rule enters context · `0082` |
| A non-package glob must be in `derived_paths_exempt_globs` | `.claude/schemas/extensions.json` `export.derived_paths` · `validate.yml` › `every norm paths matches some blueprint` |
| `rules/` mentions no skill or agent | `@CLAUDE.md` invariant 1 · `validate.yml` › `rules is a leaf of the graph` |
| The executor reads the spec by section and the spec wins over a partial | `.claude/agents/java-spring-boot-developer.md` § Inputs |
| Consolidation is by reference | `.claude/agents/java-spring-boot-developer.md` § Inputs · `.claude/skills/new-feature/SKILL.md` § Consolidation |
| A cross-skill norm owned once and cited | `.claude/skills/docker-architect/references/compose-service-decision.md` |
| Mermaid already renders in this repo's docs | `docs/pt-br/03-new-feature.md` § Diagrama de sequência |

## Propagation

| File | Change |
|---|---|
| `.claude/rules/spec-diagrams.md` | New norm, `paths: docs/use-cases/**` |
| `.claude/rules/00-index.md` | Row in *Rules written*, and a paragraph on the one documentation territory |
| `.claude/schemas/extensions.json` | `docs/use-cases/**` in `export.derived_paths.derived_paths_exempt_globs` |
| `.claude/skills/use-case-design/templates/use-case-spec.md.example` | `sequenceDiagram` under `## Flow`; HOW TO USE line; `UserStatus` values aligned with `10-dominio.md`'s (`PENDING_EMAIL_CONFIRMATION`, `ACTIVE`, `DEACTIVATED`) |
| `.claude/skills/domain-modeling/templates/domain-spec.md.example` | `status` row in the field table; `classDiagram` after the value objects; `stateDiagram-v2` after *State reachability*; `UserStatus.java` in § 5; HOW TO USE line |
| `.claude/skills/use-case-design/SKILL.md` | Step 8 draws the diagram last, from the list; the rule in **Reads** |
| `.claude/skills/domain-modeling/SKILL.md` | Step 6 draws block 1's diagrams last, from the tables; the *What the partial contains* row; the rule in **Reads** |
| `.claude/.ci/SpecDiagramTemplateTest.java` | New test |
| `.github/workflows/validate.yml` | Step `spec template diagrams name only what their text defines` in `design` |
| `docs/pt-br/07-ci-validate.md` · `docs/en/07-ci-validate.md` | Job diagram node, table row, local run line |
| `docs/pt-br/03-new-feature.md` · `docs/en/03-new-feature.md` | *Diagrams in the spec* paragraph |
| `README.md` | Norm count 18 → 19 |
| `roadmap.md` | The norm, and the pipeline item |

Not touched, on purpose: `java-spring-boot-developer.md`, `new-feature-implement`, the
consolidation of `new-feature` (see *Not needed*), no `migrations` entry, and the thirteen
fixtures in `use-case-design/examples/` (`0027`) — meta-repo only, read as `/new-feature`
input, and a spec with no diagram stays valid.

Goes to the generated project: **yes, through `export`** — the rule with `export.rules` (its
glob exempt, so it travels unchanged), the two templates and `SKILL.md` files with
`export.skills.include`. The CI test does not: nothing under `.claude/.ci/` travels.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate` · `design` › `frontmatter schema` | The rule's frontmatter, its exempt glob, the two skills' class and sections | green on the tree |
| `validate` · `design` › `every norm paths matches some blueprint` | `docs/use-cases/**` is exempt, read from `extensions.json` | green on the tree |
| `validate` · `design` › `rules is a leaf of the graph` | The rule names no skill or agent | green on the tree |
| `validate` · `design` › `spec template diagrams name only what their text defines` | Every Mermaid block of a spec template opens with an allowed type and names only what its text defines; a sequence diagram has one message per flow step; each template has its required diagrams | green on the tree · red, by name, with each of: a participant renamed (`UserGateway`), a sixth message, a transition labelled `suspend`, a class field renamed (`nickname`), a `flowchart TD` block |

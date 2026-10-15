# 0165 · Hexagonal and onion get single-module siblings, with the multi-module package map

- **Date:** 2026-10-15
- **Scenario:** "hoje nos blueprint temos apenas o clean-arch como single module e multi module. Hexagonal e onion apenas tem multi. Vamos criar uma versao single-module para onion e hexagonal"
- **Decision:** Option 1 — `.claude/blueprints/hexagonal-single-module/hexagonal-single-module.yaml` and `.claude/blueprints/onion-single-module/onion-single-module.yaml` (data, not a form of extension)
- **State:** approved by Lucas Fernandes, on 2026-10-15

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 5 — Nature | A declarative fact: an architecture's packages and boundaries | Forms 1-3, 7-9 — an architecture is data (invariant 7), a new `<id>.yaml` |
| 8 — Destination | Both: the active blueprint travels into the generated project (`export.blueprint_copy`) | — |
| Ids (user) | Keep `hexagonal` and `onion`; add `hexagonal-single-module` and `onion-single-module` | Renaming the multi-module ids |
| Packages (user) | Same `packages.map` as the multi-module sibling, as `clean-architecture-single-module` does | A single-module-only package vocabulary (onion's `app.*` renamed) |
| Boundary (user) | Parity with `clean-architecture-single-module`: the `project-files.md` § 5 derivation plus ArchUnit | Extending `_schema.md` with per-package `forbidden_imports` in this change |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `.claude/blueprints/hexagonal-single-module/hexagonal-single-module.yaml` + `.claude/blueprints/onion-single-module/onion-single-module.yaml` | 9 | **Approved** |
| 2 | create nothing — use `clean-architecture-single-module` | 3 | Rejected — the request is the port/adapter and Application Core/Gateway vocabulary in one module |
| 3 | rename `hexagonal`/`onion` to `-multi-module` and add the single siblings | 4 | Rejected by the user — breaks every existing project's provenance id, ~20 `migrations[].blueprints`, three CI tests and the docs |
| 4 | option 1 plus per-package `forbidden_imports` in `_schema.md` and `project-files.md` § 5, applied to all three single-module blueprints | 6 | Deferred — a separate decision: changes the generation rule, touches clean-single and needs a migration |

### Option 1 — two new blueprint YAMLs (score 9)

**Motivator:** axis 5 — an architecture is data; invariant 7.

**Pros:** no skill, agent or hook changes — `/init-project` lists every `*.yaml` under
`.claude/blueprints/` at run time (`init-project/SKILL.md`, its `find` injection), and the
pipeline discovers single- vs multi-module from the POMs. The package map equals the
multi-module sibling's, so code moves between the two variants without renaming a package,
and every norm's `paths` glob already matches.

**Cons:** inherits clean-single's boundary gap: `.claude/forbidden-imports.txt` gives `domain`
only the four framework roots of `project-files.md` § 5 and gives `application` no framework
ban at all; the other prefixes the multi-module `forbidden_imports` carry (GraphQL, MongoDB,
Hibernate, logging, MapStruct, Spring AI, Spring Web) are left to ArchUnit. Hexagonal's
`"adapters/*" → "adapters/*"` has no package form — `adapter → adapter` would forbid a
package its own imports — so each adapter package is listed against the others. Onion's
`app.*` packages keep the name of a module the single-module layout does not have.

**Points cut in the rubric:** the inherited boundary gap (option 4 closes it).

**CI:** existing steps of `validate.yml` cover a new YAML with no change —
`export twice per blueprint, require identical trees`, `blueprint-declared templates exist on
disk`, `every norm paths matches some blueprint`.

### Option 4 — per-package `forbidden_imports` (score 6)

Would make the edit-time `check` and CI's `doctor gate` refuse the same framework imports in
a single-module project as in its multi-module sibling. Deferred, not rejected: it changes the
contract of `_schema.md` rule 5 and the derivation every single-module blueprint runs through,
so it is its own decision, with a migration for projects already generated.

## References

| Claim | Source |
|---|---|
| An architecture is data; adding one edits no skill | `@CLAUDE.md` invariant 7; `validate.yml` › `new blueprint doesn't touch prompts` |
| The single-module boundary map is derived from `dependency_rules` | `.claude/skills/project-bootstrap/references/project-files.md` § 5 |
| A single-module sibling keeps the multi-module package map | `.claude/blueprints/clean-architecture-single-module/clean-architecture-single-module.yaml` |
| The interview lists every blueprint YAML at run time | `.claude/skills/init-project/SKILL.md` § Available blueprints |
| Only the active blueprint travels | `@CLAUDE.md` invariant 9; `export.blueprint_copy` in `.claude/schemas/extensions.json` |

## Propagation

| File | Change |
|---|---|
| `.claude/blueprints/hexagonal-single-module/hexagonal-single-module.yaml` | New. `layout: single-module`, the `hexagonal` package map, one `dependency_rules` entry per adapter package against every other one |
| `.claude/blueprints/onion-single-module/onion-single-module.yaml` | New. `layout: single-module`, the `onion` package map, `persistence` and `presentation` also forbidden from `app` |
| `README.md` | "Seven" → "Nine" blueprints (two places), two rows in the Blueprints table |
| `.claude/blueprints/README.md`, `README.pt-br.md` | Two rows in the quick table, a variant paragraph under Hexagonal and under Onion |
| `docs/en/05-blueprints.md`, `docs/pt-br/05-blueprints.md` | "all seven" → "all nine", two rows |
| `docs/en/09-differentiators.md`, `docs/pt-br/09-diferenciais.md` | The list of blueprints and its count |
| `docs/en/10-arch-adopt.md`, `docs/pt-br/10-arch-adopt.md` | "7 official blueprints" → "9" |

Not touched, on purpose: no skill, agent, rule or hook (invariant 7). `export` needs no entry — a
blueprint is copied only as the active one (`export.blueprint_copy`), and the catalog does not
travel. `project-bootstrap/references/blueprint-selection.md` is not edited either: it is a
hand-written tie-breaker table that already omits `clean-architecture-single-module`.

Goes to the generated project: **yes, only the YAML of the blueprint the project chose**, through
`export.blueprint_copy`; nothing else of this change travels.

The `export` comment-block above `dependency_rules` is the vocabulary the exported `naming.md`
carries (`vocabulary_anchor_key`), so the explanation of the per-package adapter list sits inside
the `dependency_rules` block, not above it.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · export twice per blueprint, require identical trees` | Both new ids export, twice, to identical trees with no surviving dead citation | green on the tree, all nine ids, 2026-10-15 |
| `validate · blueprint-declared templates exist on disk` | Every `templates.<role>` of the two files resolves to a real file | green on the tree · red with `templates/pom.parent.xml.example` changed to `templates/nope.xml.example` in `onion-single-module.yaml` (failed by name: `❌ … onion-single-module.yaml -> templates/nope.xml.example`) |
| `validate · every norm paths matches some blueprint` | Unchanged: the new files only repeat packages the multi-module siblings already declare | not re-run, nothing in it can change |
| `java .claude/hooks/ArchHook.java schema` | Nothing in `.claude/` the new files touch is malformed | green |

Not proven, and said so: a real `/init-project --blueprint hexagonal-single-module` (and
`onion-single-module`) generation, which needs the Initializr and a full run. The
`dependency_rules` → `.claude/forbidden-imports.txt` derivation of `project-files.md` § 5 is
prose run by an agent, so no CI step exercises it for any single-module blueprint.

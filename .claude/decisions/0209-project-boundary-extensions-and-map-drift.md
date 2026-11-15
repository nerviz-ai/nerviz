# 0209 · A generated project extends its boundary map through `project_config`, and `doctor` gates drift of the delivered lines

- **Date:** 2026-11-14
- **Scenario:** Issue #210, triaged at 689e2c8: the single-module boundary map has no library confinement per package role and a project-local package role has no owner; regenerating the boundary map silently drops hand-added lines, and doctor `Boundaries` stays green
- **Decision:** Form 7c + data — `project_config.files.boundary` (`.claude/boundary-extensions.json`), merged read in `check`/`doctor`, gated `Boundary map` drift line (option 1)
- **State:** approved by Lucas Fernandes, on 2026-11-15

## Reproduced on disk

Confirmed rows of the `issue-verifier` table, checked at `689e2c8` (source project at `a5638d1` = v0.51.4):

| # | Fact | Where |
|---|---|---|
| 1 | Port kinds are a closed list (persistence · messaging · external HTTP · LLM · vector store · search index); a layer skill stops on a missing role; `packages.map` is never mentioned by `new-feature` or `new-feature-implement` | `domain-modeling/SKILL.md:309`, `ai-architect/SKILL.md:124-126` |
| 3 | `guard write` as `java-spring-boot-developer` exits 2 on the blueprint copy and on `.claude/forbidden-imports.txt` | executor territory, `extensions.json:615` |
| 4 | `boundary_map.framework_roots` holds only `domain` | `extensions.json:1502-1508`, `ArchHook.java:4355` |
| 5 | The single-module branch derives package→package pairs plus `framework_roots[from]`, nothing per library | `ArchHook.java:4337-4362` |
| 6 | `boundary-map` overwrites the whole file; only `project-bootstrap` § 5 runs it | `ArchHook.java:4377-4380`, `project-files.md:11` |
| 8 | doctor `Boundaries` passes on `rules > 0`; nothing compares the file with what the blueprint derives | `ArchHook.java:649-661` |
| 9 | Clean single-module: a Kafka import outside the messaging package passes — the "messaging package" wording is the root `CLAUDE.md` template's, not a rule | `clean-architecture-single-module.yaml:115-123`, `root.CLAUDE.md.example:159-161` |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Hand-added lines lost on regeneration; doctor green over a map that no longer holds what the blueprint delivered | — |
| 4/9 — Scope of confinement | "the project should have more freedom, especially in infrastructure"; library confinement per role "not necessary" | any `confined_roots` / per-role library data in blueprints or `boundary_map` |
| 7 — Mandatoriness | "the forbidden imports the meta-repo delivers must be respected. But each generated project must have autonomy to extend it" | prose-only (Form 4/5) for the delivered lines |
| — Upstream roots (messaging/http/cache/ai) | No | `framework_roots` beyond `domain` |
| 8 — Destination | Both: the mode and the data travel through `export` | — |
| 16 — Existing mode | `check`, `doctor`, `boundary-map`, `config` exist; none reads a second boundary source, none compares the generated file to its derivation | 7a alone → 7c needed |
| 17 — CI | `validate · hooks-cross-platform` already runs `BoundaryMapTest`, `BoundaryTest`, `BoundarySweepTest`, `DoctorGateTest` | — |

Unanswered on purpose: an owner for a project-local package role. With the answer "freedom", a project's
own role needs no owner in this repository — the project writes its rule as an extension line.

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `project_config` entry `boundary` (`.claude/boundary-extensions.json`) + merged read in `check`/`doctor` + gated `Boundary map` drift line | 8 | pending |
| 2 | Free-form `.claude/forbidden-imports.local.txt`, same line format, read beside the generated file | 6 | pending |
| 3 | `boundary-map` keeps every line below a `# project` marker on regeneration | 4 | pending |
| 4 | create nothing — document that the file is regenerated | 2 | Rejected — the user asked for extensions that survive |

### Option 1 — `project_config.files.boundary` (score 8)

**Motivator:** axes 7 and 8 — delivered lines hold, the project extends, both travel; 0187 says a new
project setting is one entry in `project_config`, never a file in its own block.

**Shape:**

```
.claude/schemas/extensions.json
  project_config.files.boundary   path .claude/boundary-extensions.json, base root,
                                  validator boundary_extensions, doctor_label "Boundary extensions",
                                  key "{module_path}"  type prefix_list
  doctor.gate.labels              + "Boundary map"
  migrations                      + boundary-extensions: move hand-added lines out of forbidden-imports.txt
.claude/hooks/ArchHook.java
  boundaryLines()                 generated lines + extension lines → the one list check, doctor
                                  Boundaries and doctorBoundarySweep read (forbiddenImports unchanged)
  boundaryMap()                   header also records `package: <pkg>`
  doctor "Boundary map"           re-derives from the active blueprint copy (export.blueprint_copy)
                                  and the header's package; a missing or extra line → red, gated;
                                  a header with no package → warning only (files written before 0209)
  config set|unset boundary …     the only writer; validator refuses a non-package prefix or a path
                                  outside boundary_map.source_root
```

`config set boundary src/main/java/com/acme/app/domain org.apache.pdfbox.` adds a line; `/project-config`
and the `nerviz-config` mod show it with no new code (they render `config list`).

```
check / doctor ──► boundaryLines() ──► forbidden-imports.txt   (boundary-map, never by hand)
                                  └──► boundary-extensions.json (config set|unset, never by hand)
doctor gate ──► "Boundary map": forbidden-imports.txt == boundary-map(blueprint copy, package)
```

**Pros:** delivered lines become verifiable (a deletion or edit is red in CI); extensions survive every
regeneration and every `/arch-adopt`, since `export` never names a `project_config` file; single writer
with a validator; existing UI picks it up; no per-library data in blueprints (answer to axis 4).

**Cons:** a new value type (`prefix_list`) in `config`; a 7c change to three modes; a header format change,
with old files only warned. An executor still cannot add a line itself — a person types `config set`
(the guard already refuses both files to it; kept on purpose: a model that relaxes or extends its own
boundary mid-run is the failure `guard` exists for).

**Points cut in the rubric:** cost (three modes touched) → −1; header migration warning-only → −1.

**CI:** `validate · hooks-cross-platform` — new `.claude/.ci/BoundaryExtensionTest.java` (set/unset, merged
`check` exit 2 on an extension line, survives a rerun of `boundary-map`, validator refusals); `DoctorGateTest`
gains `broken("Boundary map", delete one delivered line)`; `BoundaryMapTest` asserts the `package:` header.

### Option 2 — `.claude/forbidden-imports.local.txt` (score 6)

Same merged read and drift line, but a plain text file the person edits. Cheaper (no new config type), same
line format as the generated file. Strains 0187 (a project file outside the `project_config` registry: no
validator, invisible to `/project-config`), and a malformed line fails silently as "no rule". CI: same tests
minus the config cases.

### Option 3 — marker-preserving regeneration (score 4)

`boundary-map` rewrites only the part above `# project lines`. One file, but generated and hand-written lines
share it, a person can still edit a delivered line above the marker, and the drift check must parse the marker.
`boundary-map` only runs at bootstrap (0180 rejected rerunning it in the pre-flight), so the loss it guards
against is mostly a manual rerun. CI: `BoundaryMapTest` rerun case.

### Option 4 — create nothing (score 2)

Leaves row 8: the gate stays green over a map missing delivered lines.

## References

| Claim | Source |
|---|---|
| A project setting is one `project_config` entry, written only by `config set\|unset` | `0187-project-config-registry-skill-and-mod.md`; `extensions.json` `project_config` |
| `export` never names a `project_config` file, so `/arch-adopt` keeps it | `extensions.json:694` `$comment` |
| The active blueprint travels, so `doctor` can re-derive in the project | `export.blueprint_copy`, `CLAUDE.md` invariant 9 |
| Rerunning `boundary-map` in the pre-flight was rejected | `0180` option 2 |
| `boundary-map` exists to keep the derivation in one place | `0166`; `ArchHook.java:4297-4301` |
| A gated doctor line fails the generated `architectural boundaries` job | `0128`, `doctor.gate.labels` |
| `forbiddenImports` is the single owner of the match | `ArchHook.java:365-381`, `0140` |
| Mode reads lists from `extensions.json`, never a constant | `CLAUDE.md` invariant 10 |
| A guarantee is a hook, with a record | `CLAUDE.md` invariant 6 |

## Propagation

Option 1 implemented. `ArchHook.java`/`.jar`: `boundary-map` writes `# package: <pkg>` on line 2 and shares
`deriveBoundaryLines` with `doctor`; `check` and `doctor` read delivered plus extension lines; `config unset` takes
an optional prefix. `extensions.json`: `Boundary map` in `doctor.gate.labels`, `project_config.files.boundary`,
`migrations` entry `boundary-extensions` for every blueprint. Docs: `project-bootstrap` SKILL and `project-files.md`,
both `11-pitfalls.md`, `CLAUDE.md` `config` row.

Deviations and choices: `Boundary extensions` is reported but not gated. A map with no `# package:` header (older
than 0209) only warns. A module path is valid when it is a plain directory or under `src/main/java` (other `src/`
paths and `..` are refused). `export` drops the `export` block from the project's `extensions.json`, so `doctor`
defaults the blueprint copy to `.claude/blueprints/{id}/{id}.yaml`.

Goes to the generated project: **yes** — `ArchHook.java`/`.jar` and `extensions.json` travel through `export`;
the extension file is the project's own.

## CI coverage

`.claude/.ci/BoundaryExtensionTest.java` (new, registered in `validate.yml`): set/unset/idempotence, merged `check`,
delivered rule still blocks, `doctor` lines, survival of a `boundary-map` rerun, three validator refusals.
`BoundaryMapTest` asserts the `# package:` header. `DoctorGateTest` builds the real map and blueprint copy, points
`source_marker` at a missing file (a `.claude/blueprints` dir would make it a source repo), and breaks `Boundary map`
by deleting one delivered line.

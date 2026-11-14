# 0208 · arch-adopt's territory covers every destination its export writes, and `schema` fails when it does not

- **Date:** 2026-11-14
- **Scenario:** Issue #212, triaged at 0c21797: arch-adopt's `write_allow` leaves out `.github/hooks/archhook.json`, which its own export writes — the `Stop` sweep blocks every update that creates or changes that file
- **State:** approved by Lucas, on 2026-11-14 — option 1
- **Decision:** `export.writer_skill` names the skill whose `write_allow` must cover every destination of the `export` block; `schema` fails by name on a miss

## Reproduced on disk

From the `issue-verifier` table of `/triage-issue 212`, static layer, at `0c21797`
(comment: https://github.com/nerviz-ai/nerviz/issues/212#issuecomment-6885454824).

| Claim | Result |
|---|---|
| `skill_classes.build.overrides.arch-adopt.write_allow` is `.claude/**`, `.gitignore`, `CLAUDE.md` (`extensions.json:518`; `:516` at v0.52.0) | true |
| `export.overwrite` writes `.github/hooks/archhook.json` verbatim from `project-bootstrap/templates/copilot-hooks.json.example` (`extensions.json:1236`) | true |
| `guard write .github/hooks/archhook.json` with an `/arch-adopt` phase open exits 2, "outside its territory" | true |
| `guard bash` admits `java -jar .claude/hooks/ArchHook.jar export …` without seeing what it writes; the `Stop` sweep (`ArchHook.java:10654-10660`, `guardViolations(…, sweep=true)`) blocks the turn; `sweep_exempt` holds only `.claude/audit-usage/**` | true |
| `arch-adopt/SKILL.md:337-340` ("`.claude/` tree, `.gitignore`, root `CLAUDE.md` … nothing else") contradicts `:350-351` ("everything else `export` writes … the `export` block") | true |
| Fails every run where the file is new or changed (porcelain-based sweep); a byte-identical update passes | true |
| Reverting the file to pass the gate drops `guard read` for the Copilot CLI (`extensions.json:846`) | true |
| Present since v0.52.0 (238ddf6), unchanged at HEAD | true |
| An earlier update left the file missing | needs-evidence — not an input |

Decision `0173` chose to export the file and says `arch-adopt` writes it through the same
`export`; it tested an export into an empty directory and never weighed arch-adopt's
territory. Not by-design. The same drift sits latent in `optional_copy` (`.mcp.json`,
`MCP-SETUP.md`): no template exists yet, so nothing writes them today.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Stop sweep blocks `/arch-adopt` on every update that creates or changes `.github/hooks/archhook.json` | — (a case, not anticipation) |
| 4 — territory | Every `to` of the `export` block, not only the one that bit | a fix scoped to one path |
| 7 — mandatoriness | Must always hold: the drift reappears the day a new export destination is added | 1 · 2 · 4 · 5 as the guarantee |
| 8 — destination | Both: `extensions.json` and `arch-adopt` travel | — |
| 16 — existing mode | `schema` already cross-checks the `export` block (`checkSkillClasses`, export manifest); no new mode, a new check inside it | a new 7c mode |
| 17 — CI | `SkillTerritoryTest` (validate › hooks-cross-platform) proves territory; nothing proves export ⊆ territory | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | data + `schema` cross-check (`export.writer_skill`) | 9 | Approved |
| 2 | data only — widen `write_allow` | 6 | Rejected |
| 3 | `guard` derives the export's destinations at runtime | 4 | Rejected |
| 4 | `export` stops writing outside `.claude/` | 2 | Rejected |
| 5 | create nothing | 0 | Rejected |

### Option 1 — `extensions.json` + `ArchHook.java schema` (score 9)

**Motivator:** axes 7 and 4 — the territory and the export manifest are two lists of the
same fact in one file, and nothing compares them.

**Change:**

1. `export.writer_skill: "arch-adopt"`, with a `$comment`: the skill whose territory must
   cover every destination of this block. The name is data (invariant 10), never a
   constant in the Java.
2. `skill_classes.build.overrides.arch-adopt.write_allow` gains `.github/hooks/archhook.json`,
   `.mcp.json`, `MCP-SETUP.md` — every destination today, `optional_copy` included.
3. New check in `schema`, beside `checkSkillClasses`: for every `to` of `copy`,
   `overwrite`, `optional_copy`, `binary_copy`, and `blueprint_copy` with `{id}` replaced
   by a placeholder segment, `matchesAny(writeAllowOf(sch, writer), to)`; a miss fails by
   name:
   ```
   ❌ export writes <to> (export.<group>), outside the territory of <writer> — add it to skill_classes.<class>.overrides.<writer>.write_allow, or the Stop sweep blocks every /<writer> that changes it
   ```
   A `writer_skill` that names no skill in any class fails too. Runs here and in a generated
   project, since both carry the block.
4. `arch-adopt/SKILL.md` § Contract stops restating the list: the territory is the override
   in `extensions.json`, which covers every destination of the `export` block plus the root
   `CLAUDE.md` — invariant 2.

**Pros:** fixes #212 and the latent `.mcp.json`/`MCP-SETUP.md` case; the next export
destination cannot ship without its territory; zero runtime cost (schema, not a hook).

**Cons:** one more data key; arch-adopt's territory grows by three root paths — still
named files, no glob.

**Points cut in the rubric:** −1 one new key in a block already large.

**CI:** `SkillTerritoryTest` gains `guard write .github/hooks/archhook.json` under an
`/arch-adopt` phase → 0 (and `.mcp.json` → 0, `pom.xml` still → 2). New
`.claude/.ci/ExportTerritorySchemaTest.java` (validate › hooks-cross-platform), on a temp
copy of the real tree like `ExportCitationSchemaTest`: tree as shipped → 0; an `overwrite`
`to` of `docs/x.json` added → 2 naming the path and `arch-adopt`; `writer_skill` naming an
unknown skill → 2; arch-adopt's new entry removed → 2 naming `.github/hooks/archhook.json`.
Each proven green on the tree and red with the defect injected.

### Option 2 — widen `write_allow` only (score 6)

Fixes #212 today. Leaves the two lists unchecked: the next destination added to `export`
reproduces the bug, silently, in every generated project — the shape of anti-pattern 21.

### Option 3 — `guard` reads the `export` block and admits its destinations under arch-adopt (score 4)

Special case in the runtime path of every `Stop`; the territory would no longer be what
`write_allow` says, and `doctor`/`guard status` would show a territory narrower than the
one enforced. Rejected.

### Option 4 — `export` writes only under `.claude/` (score 2)

Contradicts `0173`: the Copilot runtimes read `.github/hooks/` and nowhere else. Rejected.

### Option 5 — create nothing (score 0)

The symptom is confirmed and blocks every affected update. Rejected.

## References

| Claim | Source |
|---|---|
| Territory = override, else class default | `ArchHook.java:5321` `writeAllowOf` |
| Glob match used by guard | `ArchHook.java:6981` `matchesAny` |
| schema already validates classes and the export block | `ArchHook.java:5016`, `:5457` `checkSkillClasses` |
| A rule that must always hold is a check, not prose | `@CLAUDE.md` invariant 6 |
| Names in lists are data read from `extensions.json` | `@CLAUDE.md` invariant 10 |
| One owner per fact; SKILL.md cites, never restates | `@CLAUDE.md` invariant 2 |
| Why the file is exported at all | `.claude/decisions/0173-secret-read-guard-for-copilot.md:165-168` |
| Same-class chain joins territories | `.claude/decisions/0092-guard-same-class-chain-sums-territories.md` · `ArchHook.java:9935-9956` |
| Precedent for a schema test over a temp copy of the tree | `.claude/.ci/ExportCitationSchemaTest.java` (0204) |

## Propagation

| Piece | Change |
|---|---|
| `.claude/schemas/extensions.json` | `export.writer_skill: "arch-adopt"`; arch-adopt's `write_allow` gains `.github/hooks/archhook.json`, `.mcp.json`, `MCP-SETUP.md` |
| `.claude/hooks/ArchHook.java` + `.jar` | `checkExportTerritory`, called from `schemaSweep` after `checkExportManifest`; the jar rebuilt |
| `.claude/skills/arch-adopt/SKILL.md` | § Contract cites the override instead of listing the territory |
| `.claude/.ci/SkillTerritoryTest.java` | four cases (86 → 90) |
| `.claude/.ci/ExportTerritorySchemaTest.java` | new |
| `.github/workflows/validate.yml`, `docs/{en,pt-br}/07-ci-validate.md` | step, node D31, table row, case count |

Goes to the generated project: yes — `extensions.json` and `arch-adopt` travel, so the check
runs there too. No new hook registration, so no restart needed.

## CI coverage

| Claim | Job | Proof |
|---|---|---|
| a root file `export` writes is admitted for `/arch-adopt`, `pom.xml` and a workflow still refused | validate › hooks-cross-platform › `SkillTerritoryTest` | four new cases |
| an export destination outside the writer's territory, or an unknown `writer_skill`, fails `schema` by name | validate › hooks-cross-platform › `ExportTerritorySchemaTest` | green on the tree, red with each defect injected |
| the jar equals the source | `build --verify` | existing step |

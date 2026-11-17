# 0212 · project-diagrams' engine travels as verbatim `export.copy` entries, and `schema` refuses an exported file citing a skill path the export does not write

- **Date:** 2026-11-17
- **Scenario:** Issue #218, triaged at f93ce3d: export copies only export.skills.keep (SKILL.md, templates, references), so project-diagrams reaches a generated project without scripts/Diagrams.java, scripts/export.mjs, package.json and package-lock.json, which every step of its procedure invokes
- **Decision:** data in `export.copy` and `export.body_transforms.residue_exempt` of `.claude/schemas/extensions.json`, plus Form 7c — a new check `checkExportSkillPaths` in `.claude/hooks/ArchHook.java`, run by `schema` next to `checkExportCitations` (0204)
- **State:** approved by Lucas Fernandes, on 2026-11-17 (option A and the check, chosen in the interview)

## Reproduced on disk

From the `issue-verifier` table of `/triage-issue 218`, checked at `f93ce3d` (= v1.1.0), layers static. Confirmed rows only:

| # | Claim | Evidence |
|---|---|---|
| 1 | `project-diagrams/SKILL.md` says the engine travels | `SKILL.md:31-33` |
| 2 | Steps 1, 3, 4, 5 run `scripts/Diagrams.java`; step 6 copies `package.json`/`package-lock.json` and runs `scripts/export.mjs` | `SKILL.md:72,80,82,86,88-91` |
| 3 | `project-diagrams` is in `export.skills.include` | `extensions.json:1447` |
| 4 | `export.skills.keep` is `["SKILL.md","templates","references"]`, applied as a keep-list by `exportTree` | `extensions.json:1426`; `ArchHook.java` `exportTree` |
| 5 | The four files exist here | `.claude/skills/project-diagrams/{scripts/Diagrams.java,scripts/export.mjs,package.json,package-lock.json}` |
| 6 | An export writes only `SKILL.md` and `references/**` of the skill | `ArchHook.jar export <scratch> --blueprint clean-architecture-single-module --dry-run` |
| 7 | No `copy`/`overwrite`/`optional_copy`/`binary_copy` entry delivers them | `extensions.json:1322-1360` |
| 9 | Nothing checks that an exported file keeps the skill paths it cites | `checkExportCitations` checks only citations to excluded skills |

Root cause: `0211` moved the engine into `project-diagrams` and listed the skill in `export.skills.include`, but never added the engine to what the export writes. An implementation gap of `0211`, not a decision.

## Interview

Answered from the triage table and the code; the two open choices asked.

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Every step of `/project-diagrams` fails in a generated project: the file it runs is not there | create nothing |
| 7 — Mandatoriness | A file an exported skill invokes must exist in the project; the gap reached a release silently, the same class `0204` closed for excluded skills | prose (a note in `0211`, a review checklist) |
| 8 — Destination | Both: the fix is data read by `export` here; the check runs in this repo only (the exported `extensions.json` has no `export` block) | — |
| 9 — Integration | `export.skills.keep` is a keep-list on purpose (`$comment`); `export.copy` already carries per-file `verbatim` (`0075`); `checkExportCitations` (`0204`) already scans the exported files | a global `keep` change |
| 16 — Existing mode | `schema` runs `checkExportCitations`; a second check over the same file set is a sibling method, not a mode | a new mode |
| 17 — CI | `validate` › `ExportCitationSchemaTest` already spawns the jar over a copy of the tree; new cases go there | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | Four `export.copy` entries with `verbatim: true`, the two code files in `residue_exempt`, plus `checkExportSkillPaths` in `schema` | 8 | **Approved** |
| B | A per-skill `export.skills.keep_extra` map, copied verbatim | 6 | Rejected — Java change in `exportTree`, the citation check and a new schema shape for one skill |
| C | `scripts`, `package.json`, `package-lock.json` added to the global `keep` | 3 | Rejected — undoes the keep-list's intent (any future `scripts/` of any skill leaks) and sends Java/JSON through `transform` |
| D | Option A's data without the check | 5 | Rejected — the `0211` gap class recurs silently with the next skill that adds a non-kept path |

### Option A (score 8)

**Motivator:** axes 7 and 9.

**Pros:** the fix is data only (invariant 10); the payload is copied byte for byte, the `0075` precedent for `ArchHook.java`; `copy` always writes, so `/arch-adopt` refreshes the engine on every update; the check would have failed `0211`'s PR by name (`SKILL.md:72`).

**Cons:** file-level entries — a new file under `scripts/` needs its own entry. The check names the citing line when one is missing, so the gap is no longer silent.

**Points cut in the rubric:** maintenance (four entries to keep in step with the directory).

**CI:** `validate` › `ExportCitationSchemaTest` gains five cases (below).

### Option B (score 6)

Directory-level, so a new engine file travels unlisted. Costs a new key in the `export` block, a branch in `exportTree`, the same branch in the citation scan, and a schema shape for a single skill. Re-open when a second skill needs a non-kept directory.

### Option C (score 3)

`transform` would cut the `// Design: .claude/decisions/…` comment lines of `Diagrams.java:16-17` and `export.mjs:13`, and the next code payload with such a citation in a string literal would ship broken.

## Design (for Phase 4, verbatim)

### 1 · `export.copy` — four entries, appended after the `NOTICE` entry

```json
{ "$comment": "project-diagrams' engine travels with the skill (0211): every step of its procedure runs it from the project. Outside `export.skills.keep` on purpose — the keep-list stays three entries — and verbatim because it is code: `transform` would cut its decision citations. Design: .claude/decisions/0212-project-diagrams-engine-travels-verbatim-and-exported-skill-paths-checked.md", "from": ".claude/skills/project-diagrams/scripts/Diagrams.java", "to": ".claude/skills/project-diagrams/scripts/Diagrams.java", "verbatim": true },
{ "from": ".claude/skills/project-diagrams/scripts/export.mjs", "to": ".claude/skills/project-diagrams/scripts/export.mjs", "verbatim": true },
{ "from": ".claude/skills/project-diagrams/package.json", "to": ".claude/skills/project-diagrams/package.json", "verbatim": true },
{ "from": ".claude/skills/project-diagrams/package-lock.json", "to": ".claude/skills/project-diagrams/package-lock.json", "verbatim": true }
```

### 2 · `export.body_transforms.residue_exempt` — two paths appended

```json
".claude/skills/project-diagrams/scripts/Diagrams.java", ".claude/skills/project-diagrams/scripts/export.mjs"
```

Both carry `.claude/decisions/` in comments, a residue marker; copied verbatim, the export would report them on every run. Same reason `.claude/hooks/ArchHook.java` is there.

### 3 · `export.skills.$comment` — one sentence appended

> A file of an included skill that must travel and is none of these goes in `copy`, verbatim when it is code — `schema` fails when an exported file cites a skill path neither writes.

### 4 · `checkExportSkillPaths` in `ArchHook.java`

Called by `schema` right after `checkExportCitations(exp, errors)`, same `errors` list. Reads only the `export` block (invariant 10). Returns at once when `export.skills` is absent — the exported `extensions.json` drops the `export` block, so the check never runs inside a generated project.

- **File set:** identical to `checkExportCitations` — the `keep` entries of each `export.skills.include` id, each `export.agents.include` agent, each rule not in `export.rules.exclude`, each `blueprint_copy` file — minus `export.body_transforms.residue_exempt`. Extract the file-set collection into a shared helper used by both methods; do not duplicate it.
- **Pattern, per included id:** `(?<![A-Za-z0-9_-])\.claude/skills/<id>/([A-Za-z0-9_.*{}<>/-]+)`. The id is `Pattern.quote`d. A placeholder such as `<name>` is not an included id and never matches.
- **Normalise the captured rest:** strip trailing `.` `,` `:` `;` and a trailing `/`. Empty rest → pass.
- **Passes when any holds:**
  1. the first segment of the rest (up to the first `/`, or the whole rest) is in `export.skills.keep`;
  2. `.claude/skills/<id>/<rest>` equals the `to` of an entry of `copy`, `overwrite`, `optional_copy` or `binary_copy`;
  3. `.claude/skills/<id>/<rest>/` is a prefix of such a `to` (a directory citation).
- **Error line, one per hit:** `"  " + rel + ":" + line + " cites `" + match + "` — the export writes no such path into a generated project: `" + firstSegment + "` is outside export.skills.keep and no export.copy entry writes it. Add a `copy` entry (verbatim for code), or cite a file that travels."`
- **Header:** `schema`'s existing failure header for export errors; when the two checks share one errors block, keep a single header.
- **Javadoc, the three sentences:** "Form 7c of `claude-code-architect-designer`, run by `schema` at sweep time (Stop, or a manual run) — motivated by axis 7: `project-diagrams` reached generated projects without the engine every step of its procedure runs (issue #218), because `export.skills.keep` writes three entries of a skill and nothing compared what an exported file cites against them. The closest rejected form was adding the engine's directory to the global `keep`, which would undo the keep-list and send code through `transform`. Design: .claude/decisions/0212-project-diagrams-engine-travels-verbatim-and-exported-skill-paths-checked.md"

Rebuild the jar: `java .claude/hooks/ArchHook.java build`.

### 5 · Test cases — `.claude/.ci/ExportCitationSchemaTest.java`

Five cases added through the existing `expect(...)` helper, all appended to `RULE` (`.claude/rules/naming.md`). The header comment gains one sentence naming issue #218 and this record.

| Case | Appended text | Exit | Output must contain | The defect that makes it fail |
|---|---|---|---|---|
| a skill path the export drops blocked | ``\n`.claude/skills/project-diagrams/scripts/Missing.java`\n`` | 2 | `naming.md:` | the check is absent, or reads `keep` only |
| a non-kept directory blocked | ``\n`.claude/skills/use-case-design/examples/x.md`\n`` | 2 | `use-case-design/examples` | the check ignores directories other than `scripts` |
| a `copy` destination accepted | ``\n`.claude/skills/project-diagrams/scripts/Diagrams.java`\n`` | 0 | — | the check ignores `copy` `to`s |
| a kept path accepted | ``\n`.claude/skills/test-architect/templates/Foo.java.example`\n`` | 0 | — | the check ignores `keep` |
| a placeholder accepted | ``\n`.claude/skills/<name>/scripts/x.java`\n`` | 0 | — | the pattern matches any directory name instead of included ids |

The success message gains: "a skill path no export entry writes is blocked".

## References

| Claim | Source |
|---|---|
| `keep` is a keep-list on purpose | `extensions.json` `export.skills.$comment` |
| `verbatim` skips `transform`; precedent for code | `extensions.json` `export.copy[0]`; `@.claude/decisions/0075-precompiled-hook-jar.md` |
| `copy` writes on every export, so updates refresh | `ArchHook.java` `exportFiles` |
| The residue scan skips `residue_exempt` paths | `ArchHook.java` export residue loop |
| Sibling check over the same file set | `@.claude/decisions/0204-archunit-installer-on-gradle-and-dead-citations-to-excluded-skills.md` |
| The engine travels by decision | `@.claude/decisions/0211-project-diagrams-skill-sharing-the-diagram-engine.md` |
| A list read by a mode lives in `extensions.json` | `@CLAUDE.md` invariant 10 |
| A generated project is self-contained | `@CLAUDE.md` invariant 9 |

## Propagation

| File | Change |
|---|---|
| `.claude/schemas/extensions.json` | four `export.copy` entries; two `residue_exempt` paths; one sentence in `export.skills.$comment` |
| `.claude/hooks/ArchHook.java` | `checkExportSkillPaths`, a shared file-set helper, the call in `schema` |
| `.claude/hooks/ArchHook.jar` | rebuilt |
| `.claude/.ci/ExportCitationSchemaTest.java` | five cases, header sentence, success message |
| `CLAUDE.md` | § Commands, the `schema` row: "…and the `export` manifest against what is on disk, and that every skill path an exported file cites is one the export writes" |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | the `ExportCitationSchemaTest` row, if it lists what the test proves |

Goes to the generated project: **yes — the four engine files, through `export.copy`. The check does not: the exported `extensions.json` has no `export` block.**

## CI coverage

| Step | What it proves | Green | Red |
|---|---|---|---|
| `validate · hooks · <os> › schema blocks an exported file citing a skill that never travels` (`java .claude/.ci/ExportCitationSchemaTest.java`) | `schema` exits 2 naming file and line on an exported file citing a path of an included skill that is outside `export.skills.keep` and in no `copy`/`overwrite`/`optional_copy`/`binary_copy` destination; a `copy` destination, a kept path and a placeholder pass | all 12 cases green on the shipped tree | check neutered and jar rebuilt: `a skill path the export drops blocked` and `a non-kept directory blocked` fail (got exit 0). With the four `export.copy` entries removed, `schema` exits 2 on `.claude/skills/project-diagrams/SKILL.md:72` (and 80, 82, 86, 91) |
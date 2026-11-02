# 0197 · The spec checklist follows the blocks the case carries; persistence and REST become conditional; `spec check` and the approval guard make the count a fact

- **Date:** 2026-11-02
- **Scenario:** Issue #191, triaged at a90628e (consolidation no longer names `templates/feature-spec.md.example` or the checklist), widened by the maintainer: only `use-case-design`, `domain-modeling` and `test-architect` are mandatory; REST, persistence and every other layer depend on what the case imposes, the checklist must say so, and the count must not depend on the model remembering or computing it.
- **Decision:** Option 1 — edits to `skills/new-feature/` (template, short template, `consolidation.md`, `approval-and-report.md`, `SKILL.md`), `skills/new-feature-implement/SKILL.md`, `agents/java-spring-boot-developer.md`; a new `spec` mode and a `guardViolations` rule in `hooks/ArchHook.java` (Form 7c); a `spec_checklist` block in `schemas/extensions.json`; `.ci/SpecChecklistTest.java`
- **State:** approved by Lucas Fernandes, on 2026-11-02

## Reproduced on disk

Confirmed rows of the `issue-verifier` table (triage of #191 at a90628e, layer static). No
commit since a90628e touches a cited file. Refuted and unproven rows, and the issue's proposed
fix, are not inputs (`0103`). Rows 10-15 were read by the designer in this session, on disk.

| # | Claim | Evidence |
|---|---|---|
| 1 | The template fixes `## Implementation order (checklist)` at 19 items, `n/a` never deleted | `new-feature/templates/feature-spec.md.example:226-229`; byte-identical to v0.47.0 |
| 2 | Executor precondition 5 needs the 19-item checklist, or aborts | `agents/java-spring-boot-developer.md:209`, `:53` |
| 3 | `SKILL.md` § consolidation is one line pointing to `references/consolidation.md` | `new-feature/SKILL.md:370` |
| 4 | No `references/*.md` names `feature-spec` | grep: no match |
| 5 | The only ordering line is "Order: implementation order (depends-on)"; "None touches the fixed 19-item checklist" never says to write it | `consolidation.md:170`, `:190-191` |
| 6 | v0.47.0's `## Template` section was the procedure's only pointer; 0196 removed it | v0.47.0 `SKILL.md` l.888-921; `0196` l.90, l.226 |
| 7 | `new-feature-implement` does not stop on a spec with no checklist | `new-feature-implement/SKILL.md:199` |
| 10 | Persistence is not conditional: step 5 has no `Conditional`, a missing `Storage` row stops `persistence-architect`, and `Storage: none` — already recorded by the use-case template and examples UC-102, UC-112 — is handled nowhere downstream | `new-feature/SKILL.md:306-308`; `persistence-architect/SKILL.md` § Family rule; `use-case-design/templates/use-case-spec.md.example:13` |
| 11 | REST is skipped only for a `Trigger` that is GraphQL alone | `new-feature/SKILL.md:155-157` |
| 12 | Consolidation demands `20-persistence.md` § 6 and `## Compose services` "always" | `consolidation.md:121`, `:134` |
| 13 | Blocks H, A, I, G, S, M, J, C have no checklist line; the group `adapters` is judged by steps 13-15 alone. GraphQL-only works because the executor ticks 13-15 `n/a` itself at the group's end | `java-spring-boot-developer.md:392`, `:416-420`, `:710-711`; `new-feature-implement/SKILL.md:~190-199`; `0130` l.92 |
| 14 | A checklist toggle is any same-length `[ ]`↔`[x]` edit of a spec, so a line with a letter id is admitted as the numbered ones are | `ArchHook.java:9570-9580`; `guard.checklist_toggle_statuses` |
| 15 | `draft` → `approved` is an ordinary `Edit`: `guardViolations` returns before judging any spec whose status is not in `frozen_statuses`, and `status_transitions` has no `draft` key on purpose. Nothing inspects a spec at approval | `ArchHook.java:9068-9074`; `extensions.json` `$comment_status` |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Confirmed on disk (above) | — |
| 5 — nature | Steps of existing procedures, and a fact checkable by a program | 3, 4, 5, 6, 9 |
| 7 — mandatoriness | Must hold: the count of a spec about to be approved. Not the model's arithmetic (maintainer) | prose alone |
| 14 — lifecycle event | `PreToolUse` on `Edit`/`Write` of a `UC-*-spec.md` that sets `status: approved` — already registered as `guard write` | no new registration |
| 15 — reaction | Block (exit 2) with the missing and extra ids and the way out | 7a observe-only |
| 16 — existing mode | None checks a spec's checklist: a new mode `spec`, one rule shared by the guard and the command | 7a alone |
| 8 — destination | Both: the mode travels inside `ArchHook.java`, the data inside `extensions.json` | — |
| 9 — integration | The template owns the lines, `extensions.json` the block→ids map, `ArchHook` the check; prose cites, never restates a number | — |
| — | `tests` stays mandatory; persistence is **derived**; one line per lettered block, numbered steps kept for persistence and REST (maintainer) | — |
| 17 — CI | New `.ci` test, registered in `validate`; see below | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Checklist per block + conditional persistence/REST + #191 pointer + `spec check` mode + guard on `draft`→`approved` + implement and executor run the mode | 8 | **Approved** |
| 2 | Same, mode only — no guard; a procedure step must run it | 6 | Rejected — the call stays the model's, the shape of #191 |
| 3 | Same, markdown only — count by `grep` and a formula in prose | 5 | Rejected — the count stays the model's |
| 4 | Option 1 + a `doctor` line sweeping every spec | 6 | Rejected for now — specs approved before this change would turn the line red; a later record |
| 5 | create nothing | 1 | Rejected — the failure is reproduced |

### Option 1 — mode plus guard (score 8)

**Motivator:** axes 7 and 15. The failure is a spec that reaches the executor with the wrong
lines; `approved` is the one moment after which the guard freezes the folder (`0066`), and the
only moment nothing looks. A refusal at the write makes a wrong spec unapprovable, instead of
asking the model to count.

**Pros:** the formula leaves the model: prose cites `spec check`, never a number; one
function (`specChecklistProblems`) serves the guard, the command, the implement pre-flight and
the executor's precondition 5 (invariant 2); the data (`spec_checklist`) is the single owner
and `schema` cross-checks it against the template so it cannot drift (invariant 10); the
checklist mirrors what the case implements, a case with a block always has an open line for it
(row 13), and GraphQL-only needs no special tick; a 19-item spec of today passes unchanged.

**Cons:** nine prose files and three program files change and all travel; the guard runs on
every spec edit, so a false positive blocks an approval — the message names the ids and the
way out; `guard bash` cannot judge content, so `sed -i 's/draft/approved/'` is the one bypass,
caught only later by the implement pre-flight and the executor; ids have gaps (1-7, 16-19)
because renumbering would break approved specs and `0130`; the short path stays
persistence- and REST-shaped (19), so a trivial case without them takes the full path;
`new-feature/SKILL.md` is 495 of 500 lines, so its edits are line-neutral.

**Points cut in the rubric:** blast radius (-1): the guard sits on a hot path; bypass via
shell (-1), accepted.

**CI:** `validate · hooks-cross-platform` gets a step `spec check and the approval guard`
running `.claude/.ci/SpecChecklistTest.java` (below). Existing, still run:
`design › frontmatter schema`, `export-determinism › the exported tree passes its own schema`,
`DiagramsTest`.

### Option 2 — mode only (score 6)

Moves the arithmetic to a program but leaves the call to the model on the approval turn: the
failure of #191 was an instruction the model did not have, and a step it may skip is the same
shape. Kept as the fallback if the guard proves noisy.

### Option 3 — markdown only (score 5)

What `0197` first proposed. The count stays the model's.

### Option 4 — plus a `doctor` line (score 6)

Useful for specs edited by hand, but specs approved before this change would have to pass the
new rule or the line goes red. A later record, once the rule has run.

### Option 5 — create nothing (score 1)

The failure is reproduced; it ships with every generated project.

## Verbatim texts (for Phase 4)

### T1 · `new-feature/templates/feature-spec.md.example` — replace the whole `## Implementation order (checklist)` section (line 226 up to the `---` before `## Resolved divergences`)

```
## Implementation order (checklist)

The executor's checkpoints. **Core** — steps 1-7 and 16-19 — is in every spec. Every other line
exists only when its block does: a block this case does not carry adds no line, and no line is
marked `n/a` for an absent block. Inside a block that is present, a step the case doesn't need
is marked `n/a` with the reason — never deleted. The ids are stable names, not a sequence: 8-12
are Persistence (`## 3`), 13-15 REST (`## 4`), and each letter is the executor's block. Which
lines a spec must carry is `spec_checklist` in `.claude/schemas/extensions.json`;
`java -jar .claude/hooks/ArchHook.jar spec check <spec>` says what is missing or extra, and the
guard refuses `status: approved` until it passes.

### Domain — core

- [ ] 1. Domain exceptions (`10-domain.md` § 2)
- [ ] 2. Value objects + constructor validation
- [ ] 3. Aggregate + invariants
- [ ] 4. Events — n/a, none
- [ ] 5. Use case command
- [ ] 6. Use case (port and implementation per the blueprint vocabulary)
- [ ] 7. Output ports

### Persistence — only when `## 3` is not `none`

- [ ] 8. Migration — materialize from `20-persistence.md` § 4
- [ ] 9. Persistence entity
- [ ] 10. Spring Data interface
- [ ] 11. Repository adapter
- [ ] 12. Persistence properties

### Adapters — only the blocks the spec carries

- [ ] 13. DTOs + manual mapper — `## 4` not `none`
- [ ] 14. Controller + contract interface + `ApiExceptionHandler` + API versioning config (first REST case: NEW)
- [ ] 15. Idempotency interceptor + `IdempotentExecution` wiring (first time: store + component) + REST properties
- [ ] H. Outbound HTTP (H1-H3) — `## 3.3`
- [ ] A. AI (A1-A3) — `## 3.7`
- [ ] I. Search (I1-I3) — `## 3.8`
- [ ] G. GraphQL (G1-G3) — `## 4.1`
- [ ] S. Security (S1-S3) — `## 4.5`
- [ ] M. Messaging (M1-M4) — `## 3.5`
- [ ] J. Jobs (J1-J3) — `## 3.6`
- [ ] C. Cache (C1-C3) — `## 3.4`

### Tests — core

- [ ] 16. Test fixtures
- [ ] 17. Unit tests
- [ ] 18. Integration + contract tests
- [ ] 19. `./mvnw verify` (Gradle: `./gradlew build`) — green build, coverage gate
```

`## 3. Persistence` gains after its heading line: `When the case needs no persistence (the Storage row is none and no partial asked for a schema), this block's first line is "none — <reason>" and nothing else follows; the checklist then has no steps 8-12.` `## 4. REST API` gains: `When the Trigger names no HTTP adapter, this block's first line is "none — <reason>"; the checklist then has no steps 13-15. When it names GraphQL alone: "none — GraphQL only".` Every conditional block already reads `none` when absent; its first body line starting with `none` is what `spec check` reads. The HOW TO USE comment gains: `Checklist: copy the lines of the blocks this spec carries and drop the others; run spec check.`

### T2 · `new-feature/templates/feature-spec-short.md.example`

The 19 lines stay. Under the checklist heading add: `Short path: Persistence and REST are present, no lettered block — spec check expects exactly 1-19.`

### T3 · `new-feature/references/consolidation.md`

(a) Step 2, replace `   - Order: implementation order (depends-on)` with:

```
   - **Shape: `templates/feature-spec.md.example`, read now** (`feature-spec-short.md.example` on the short
     path). Its headings and block order are the spec's. Its `## Implementation order (checklist)` is copied
     for the blocks this spec carries and for no other, never `n/a` for an absent block, never prose, never
     renumbered. Before moving on, run `java -jar .claude/hooks/ArchHook.jar spec check <spec>` and fix what
     it names — the same check the guard applies when the spec is approved (issue #191)
   - **A layer the case does not need is `none` with its reason, not an empty partial.** `## 3` reads
     `none — <reason>` only when `Storage` is `none` **and** no earlier partial listed a schema
     requirement (`30-rest.md` block 4, `31-graphql.md` § 9, `32-security.md` § 7,
     `28-http-client.md` § 10, `29-ai.md` § 9, `23-search.md` § 8, `25-messaging.md` § 6,
     `35-jobs.md` § 3 and § 6); `## 4` reads `none — <reason>` when the `Trigger` names no HTTP adapter. Order
     inside a block is depends-on
```

(b) Lines 121 and 134: `` `20-persistence.md` § 6 always; `` → `` `20-persistence.md` § 6 whenever step 5 ran; `` and `` `20-persistence.md` always; `` → `` `20-persistence.md` whenever step 5 ran; ``.

(c) Lines 190-191: `None touches the fixed\n     19-item checklist` → `Each has its own line in the checklist, and the\n     checklist has no line for a block the spec does not carry`.

(d) The "Runs after" line: `Runs after 1, 2, 5 and 6 ✅, and 3 or 3g;` → `Runs after 1, 2 and 6 ✅, 5 when it ran, and 3 or 3g when either ran;`.

### T4 · `new-feature/SKILL.md` — line-neutral (`wc -l` ≤ 495 after)

Step 3 (`### 3 · REST`): replace `Read the closing phrase of `00-use-case.md`'s `Trigger` row. `inbound GraphQL adapter` alone →\nskip this step. Any other phrase, or none (a case written before the phrase existed) → if\n`30-rest.md` is missing: **invoke** `/rest-api-architect UC-NNN`.` with `Read the closing phrase of `00-use-case.md`'s `Trigger` row. No HTTP adapter named\n(`inbound GraphQL adapter` alone, a schedule, a listener, a model call with no endpoint) → skip\nthis step. An HTTP adapter, or no phrase (a case written before it existed) → if `30-rest.md`\nis missing: **invoke** `/rest-api-architect UC-NNN`.`; the Output line gains `"— skipped (no HTTP adapter)"`.
Step 5: the header gains ` · **Conditional:** yes`; `If `20-persistence.md` is missing: **invoke** `/persistence-architect UC-NNN`. It reads` becomes `Skip when `Storage` is `none` and none of the partials above lists a schema requirement (the list below). Otherwise, if `20-persistence.md` is missing: **invoke** `/persistence-architect UC-NNN`. It reads`; the Output line gains `"— skipped (no persistence)"`. If the body would pass 495 lines, delete in the same step the clause `and consolidation checks it again, for the paths that never take this step`.

### T5 · `new-feature/references/approval-and-report.md`, § approval

`Three defects real runs approved\n(issues #167 and #168), fixed here, never after:` → `Four defects real runs approved\n(issues #167, #168 and #191), fixed here, never after:`, plus the last bullet:

```
- an `## Implementation order (checklist)` that is not the template's, or that does not match the
  blocks the spec carries: run `java -jar .claude/hooks/ArchHook.jar spec check <spec>` while the spec is
  still `draft` and fix what it names. The guard runs the same check on the `status: approved` write and
  refuses it, so the approval cannot be skipped past a wrong checklist
```

### T6 · `agents/java-spring-boot-developer.md`

- `:53` — `checklist 19/19 (or aborts) — no optional block changes the 19` → `` `spec check` exits 0 on the checklist (or aborts) ``.
- `:209-212` precondition 5 — `**Checklist** — `java -jar .claude/hooks/ArchHook.jar spec check <spec>` exits 0: the checklist carries the lines of the blocks the spec carries, each open or marked `n/a` with a reason (or aborts, it's not an executable spec). An `n/a` step is skipped, not improvised.` (keep the `path: short` sentence).
- `:384` `## Procedure — 4 fixed blocks + 8 conditional` → `## Procedure — 2 fixed blocks + 10 conditional`; replace the sentence `Blocks H, A, I, G, S, M, J and C don't renumber the fixed checklist — it stays 19 items whether or not they run; a spec without any of them skips straight from Block 3 to Block 4, same as it always did.` with `Blocks 2 (Persistence), 3 (REST) and the lettered blocks run only when the spec carries them, each ticking its own checklist lines; a spec with none of them skips from Block 1 to Block 4.`
- Block 2 (`:467`): heading `### Block 2: Persistence (steps 8-12, conditional)`; first line `§ 3 of spec.md reads `none — <reason>` → skip Block 2; the checklist has no steps 8-12.`
- Block 3 (`:708-711`): heading `### Block 3: REST (steps 13-15, conditional)`; first sentence `§ 4 of spec.md reads `none` (GraphQL only, or no HTTP adapter) → skip Block 3; the checklist has no steps 13-15.` (the tick `n/a — GraphQL only` is removed).
- Groups table and bullets (`:416-420`): `domain` ticks 1-7 and, when present, 8-12; `adapters` ticks 13-15 when present and the lettered line of every block it ran; `tests` unchanged. `Tick at the end of the group, green only` gains: `A lettered line is ticked when its block's last compile passes.`
- `:1280` — `✅ Checklist: 19/19 complete` → `✅ Checklist: <n>/<n> complete`.

### T7 · `new-feature-implement/SKILL.md`

Groups table (~`:188-192`):

```
   | Group | Blocks | Checklist lines it ticks |
   |---|---|---|
   | `domain` | 1, 2 | 1-7, and 8-12 when the spec carries them |
   | `adapters` | H, A, I, 3, G, S, M, J, C — those the spec carries | 13-15 when present, and each lettered line |
   | `tests` | 4 | 16-19, then the `status:` line |
```

Insert, as the bullet right before `**Where the chain starts.**`:

```
   - **No valid checklist, no chain.** Before the first delegation run
     `java -jar .claude/hooks/ArchHook.jar spec check <spec>`. Exit 2 → stop, nothing delegated:
     ❌ `<UC-NNN-slug> has no valid implementation checklist` — followed by what the command printed and
     `reopen it as draft and run /new-feature <UC-NNN-slug>`. The executor's precondition 5 would abort
     the first group after the context was spent (issue #191)
```

`Where the chain starts` is unchanged: a block the spec carries always has an open line.

### T8 · `.claude/hooks/ArchHook.java` — Form 7c, two callers of one function

1. Dispatch in `main`, next to `config`: `case "spec" -> spec(args);`.
2. `static void spec(String[] args)`: `spec check <path>` — `<path>` is a `UC-*-spec.md` or its folder (the folder's `UC-*-spec.md`). Missing file → `err` and exit 1. `List<String> problems = specChecklistProblems(content, cfg)`; empty → print `✅ <file>: checklist matches the blocks it carries (<n> lines)` to stdout, exit 0; otherwise `err` each line and exit 2. Any other first argument prints usage, exit 1. Javadoc carries the three "why this is a mode" sentences, names `guard write` as its second caller, and cites this record.
3. `static List<String> specChecklistProblems(String content, Map<String,Object> sch)` — the single owner of the rule, reading `spec_checklist` from `extensions.json` (no list in Java): (a) `path: short` in the frontmatter → expected ids = `short_items`; otherwise expected = `core` ∪ the `items` of every block in `blocks` that is **present** — its `heading` regex matches a line of the spec and the first non-blank line after it does not match `absent_marker`; (b) found ids = the group-1 captures of `item_pattern` over the lines between the `checklist_heading` line and the next `## ` heading; (c) problems: `no checklist section`, one line `missing: <ids>`, one line `extra: <ids> (<block> is absent or none)`, one line `duplicate: <ids>`, each followed by the single way-out line `Rewrite the checklist from .claude/skills/new-feature/templates/feature-spec.md.example: the lines of the blocks this spec carries, none for an absent block, then run: java -jar .claude/hooks/ArchHook.jar spec check <spec>`.
4. `guardViolations`, between the `Matcher m` / `folder` lines and the `status == null || !frozen…` return (`:9068-9074`), when `!sweep` and `rel` matches `UC-[^/]*-spec.md`: take the on-disk status (null/`draft` only — a frozen spec is judged by the existing rules); compute the content after the write — `Write`: `tool_input.content`; `Edit`/`MultiEdit`: the file with each `old_string` replaced once by its `new_string`; if that content has `status: approved` in its frontmatter and the on-disk status is not `approved`, run `specChecklistProblems`; non-empty → `out.add("❌ " + rel + " cannot be approved — its checklist does not match the blocks it carries.")`, then the problems, and return. Exit 2 comes from the existing `guardPath`.
5. `schema`: a new check `spec_checklist` — the ids of `core` ∪ every `blocks[].items` equal the ids of `item_pattern` over the template `.claude/skills/new-feature/templates/feature-spec.md.example` (and `short_items` equal those of `feature-spec-short.md.example`); each regex compiles; a mismatch fails by name `spec_checklist: <ids> only in template|data`. Skipped silently where the template does not exist.
6. Rebuild: `java .claude/hooks/ArchHook.java build`, jar committed with the source.

### T9 · `.claude/schemas/extensions.json` — new top-level block `spec_checklist`

```json
"spec_checklist": {
  "$comment": "Read by ArchHook.java `spec check`, by `guard write` when a spec is approved, and cross-checked against the template by `schema`. The lines a UC-NNN-spec.md must carry in `## Implementation order (checklist)`: the core always, plus the ids of every block the spec carries. A block is present when its heading matches a line of the spec and the first non-blank line after it does not match `absent_marker` (every conditional block reads `none` when absent). Ids are stable names, not a sequence. Design: .claude/decisions/0197-spec-checklist-follows-the-blocks-the-case-carries.md",
  "checklist_heading": "^## Implementation order \\(checklist\\)",
  "item_pattern": "^- \\[[ x]\\] ([0-9]+|[A-Z])\\. ",
  "absent_marker": "^none\\b",
  "core": ["1","2","3","4","5","6","7","16","17","18","19"],
  "short_items": ["1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19"],
  "blocks": [
    { "id": "persistence", "heading": "^## 3\\. ",   "items": ["8","9","10","11","12"] },
    { "id": "rest",        "heading": "^## 4\\. ",   "items": ["13","14","15"] },
    { "id": "http",        "heading": "^## 3\\.3 ",  "items": ["H"] },
    { "id": "cache",       "heading": "^## 3\\.4 ",  "items": ["C"] },
    { "id": "messaging",   "heading": "^## 3\\.5 ",  "items": ["M"] },
    { "id": "jobs",        "heading": "^## 3\\.6 ",  "items": ["J"] },
    { "id": "ai",          "heading": "^## 3\\.7 ",  "items": ["A"] },
    { "id": "search",      "heading": "^## 3\\.8 ",  "items": ["I"] },
    { "id": "graphql",     "heading": "^## 4\\.1 ",  "items": ["G"] },
    { "id": "security",    "heading": "^## 4\\.5 ",  "items": ["S"] }
  ]
}
```

### T10 · `.claude/.ci/SpecChecklistTest.java` — single-file Java, `java` only, spawns `java -jar .claude/hooks/ArchHook.jar`

Each case builds a spec in a temp `docs/use-cases/UC-901-x/` (with `CLAUDE_PROJECT_DIR` pointing at a temp tree that holds a copy of `.claude/schemas/extensions.json`), and names the defect that must make it fail:

| Case | Expected | Defect that makes it fail |
|---|---|---|
| full spec: persistence and REST present, no lettered block, 19 lines | `spec check` exit 0 | — |
| same, line `9.` removed | exit 2, stderr `missing: 9` | a present block's step dropped |
| jobs-only: `## 3` and `## 4` read `none`, `## 3.6` present with `J.`, core lines | exit 0 | — |
| jobs-only with lines 13-15 left in | exit 2, `extra: 13, 14, 15` | pre-marked lines of an absent block |
| jobs-only without the `J.` line | exit 2, `missing: J` | the hole of row 13 |
| a duplicated `5.` | exit 2, `duplicate: 5` | copy error |
| `path: short`, 19 lines / 18 lines | exit 0 / exit 2 | short count |
| `guard write`, `Edit` `status: draft` → `status: approved` on the bad jobs-only spec | exit 2, message starts `❌ … cannot be approved` | approval past a wrong checklist |
| same Edit on the good spec | exit 0 | — |
| `guard write`, `Edit` `[ ]` → `[x]` on an `approved` good spec; `Edit` of a `draft` spec that does not touch `status:` | exit 0 | regression of the toggle and of drafts |
| `schema` on a copy whose template lost one checklist line | exit non-zero, stderr names `spec_checklist` | drift between template and data |

Register it as a step `spec check and the approval guard` of `validate · hooks-cross-platform` in `.github/workflows/validate.yml`, right after `schema blocks a shell glob zsh aborts on`. Prove it: green on the tree, then once per row with the defect injected, red by name (`0099`).

### T11 · Propagation prose

`@CLAUDE.md` § Commands gains a row: `Check a spec's implementation checklist against the blocks it carries — what is missing or extra by id; the guard applies the same check when a spec is approved | java .claude/hooks/ArchHook.java spec check <spec-or-folder>`. `docs/pt-br/11-pitfalls.md` and `docs/en/11-pitfalls.md` part 2 gain one row: a refused `status: approved` on a spec — what `spec check` prints and how to rewrite the checklist. `docs/*/07-ci-validate.md` gains the new step's row.

## References

| Claim | Source |
|---|---|
| Approval freezes the folder; only status moves and toggles after | `@.claude/decisions/0066-spec-state-machine.md`; `ArchHook.java:9068-9110` |
| A mode is a `case` plus a method, lists in `extensions.json` | `@CLAUDE.md` invariant 10; `claude-code-architect-designer/templates/hook-mode.java.example` |
| A refusal states the rule, the file and the way out | `claude-code-architect-designer` Phase 4 step 6 |
| Groups and the "no step of their own" note | `@.claude/decisions/0130-executor-split-by-block-group-implement-in-clean-session.md` l.91-93 |
| One owner per norm; boilerplate cited | `@CLAUDE.md` invariants 2 and 3 |
| Body limit | `0196`; `wc -l new-feature/SKILL.md` = 495 |
| CI proves the jar, not the source | `@.claude/decisions/0084-*` and `0099` § Verified before committing |
| Triaged rows only | `@.claude/decisions/0103-issue-filing-and-skeptical-triage.md` |

## Propagation

Written in Phase 4 (T1-T11): `skills/new-feature/` (`templates/feature-spec.md.example`,
`templates/feature-spec-short.md.example`, `references/consolidation.md`,
`references/approval-and-report.md`, `SKILL.md` at 495 lines), `skills/new-feature-implement/SKILL.md`,
`agents/java-spring-boot-developer.md`, `hooks/ArchHook.java` (`spec` mode, `specChecklistProblems`,
`contentAfterWrite`, the `guardViolations` rule 2a, `checkSpecChecklist` in `schema`, and `spec` fails
closed in the top-level catch) with the rebuilt `hooks/ArchHook.jar`, `schemas/extensions.json`
(`spec_checklist`), `.ci/SpecChecklistTest.java`, `.github/workflows/validate.yml`, `CLAUDE.md`
§ Commands, `docs/{pt-br,en}/11-pitfalls.md`, `docs/{pt-br,en}/07-ci-validate.md`, and — after the main thread's
review — `docs/diagrams/{layers,new-feature}/` re-rendered (`Diagrams.java render`, then `export.mjs layers`
and `new-feature` for `dist/`): the step 5 `Conditional` and the data block changed what the views cite.

Goes to the generated project: **yes** — `new-feature`, `new-feature-implement`, `java-spring-boot-developer`, the `spec` mode and the `spec_checklist` block travel (`ArchHook.java` and `extensions.json` are copied whole). `.claude/.ci/` does not.

## CI coverage

Step `spec check and the approval guard` of `validate · hooks-cross-platform`, right after
`schema blocks a shell glob zsh aborts on`, runs `.claude/.ci/SpecChecklistTest.java` (17 cases, one
per row of the table plus `Write`, `MultiEdit`, a missing section and a schema control on the
shipped tree). Green on the tree on 2026-11-02. Red proof: the guard rule disabled in the source
(`if (false && …)`), jar rebuilt: 3 cases red by name (`Edit`/`Write`/`MultiEdit` draft to approved on
the bad spec, expected exit 2, got 0); restored, rebuilt, `build --verify` green, test green again. The
defect rows of `spec check` and of `schema` are the test's own negative cases, asserted by exit code
and by the ids in the output. Also green: `schema`, `SweepTest`, `BashGuardTest`,
`AgentTerritoryTest`, `SkillTerritoryTest`, `ShellGlobSchemaTest`.

Main-thread verification (2026-11-02): `build --verify`, `schema`, `SpecChecklistTest`, `DiagramsTest`,
`Diagrams.java check` and `claude plugin validate .claude/skills` green. `doctor gate` is red on the
tree and on a clean `HEAD` worktree alike (no wrapper, 0 boundary rules: this is not a generated
project), so it is not this change.

## Known limit

A spec **approved before this change** that carries a lettered block (`## 3.5 Messaging`, `## 3.6 Jobs`,
`## 4.5 Security`, …) has 19 lines and no letter line, so `spec check` reports `missing: M` (and so
on) at `/new-feature-implement`'s pre-flight and at the executor's precondition 5. An approved spec is
frozen and `draft` is reachable only by hand, so resuming such a case needs `status: draft` set by hand
and the lines added. Specs with no lettered block, and every spec already `implemented`, are not
affected. The entry `spec-checklist-per-block` in `migrations` of `extensions.json` (all blueprints)
makes `/arch-adopt` print the note and a prompt on update: it lists the approved specs that fail
`spec check` and tells the person the hand steps (`status: draft`, add the named lettered lines,
re-approve so the guard re-runs the check) — it edits no approved spec, since the guard refuses that.
Verified: `schema` and `MigrationsSchemaTest` green with the entry.

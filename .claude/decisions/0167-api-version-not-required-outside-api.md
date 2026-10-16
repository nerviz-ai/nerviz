# 0167 · The API version flag stays off: the `/api/v{version}` path already requires it, and the flag reaches every controller

- **Date:** 2026-10-16
- **Scenario:** Issue #153, triaged at e5ed817: the ApiVersioningConfig template sets `setVersionRequired(true)` on the whole `RequestMappingHandlerMapping` while the resolver only reads `/api/`, so library controllers outside `/api/` (Springwolf's `/springwolf/docs`) throw `MissingApiVersionException` and answer 500
- **Decision:** option 1 — edit of existing pieces — `rest-api-architect/templates/ApiVersioningConfig.java.example`, `rules/api-rest.md` § Versioning, `test-architect/templates/AsyncApiContractIT.java.example`, a new `migrations` entry; amends row 1 of `0150`
- **State:** approved by the maintainer, on 2026-10-16

## Reproduced on disk

From the `issue-verifier` table of the triage (static layer, `e5ed817` = `v0.40.0`; the only later commit, `862a21f`, touches `README.md` and `docs/assets/`). The issue's proposed fix was not an input; the rows below are what was checked.

| # | Claim | Evidence |
|---|---|---|
| 4 | The template pairs `usePathSegment(…startsWith("/api/"))` with `setVersionRequired(true)` | `ApiVersioningConfig.java.example:63-65`; pinned by `.claude/.ci/ApiVersionTemplateTest.java:153` |
| 5 | The required flag applies to every handler of the mapping, not only `/api/` | Framework 7.0.9: `PathApiVersionResolver` returns `null` off `/api/`; `DefaultApiVersionStrategy.validateVersion`:176 throws; `AbstractHandlerMapping.getHandler`:552-553 rethrows |
| 6 | `/springwolf/docs` is a library `@RestController` outside `/api/` | `springwolf-core-2.8.0` `AsyncApiController`; `addPathPrefix` applies only to the project's base package |
| 7 | The response is 500 | No `MissingApiVersionException` handler; `ApiExceptionHandler.java.example:272-274` (`handleUnexpected`) |
| 8-9 | `0150` did not check a non-springdoc library controller; `0162` checked Springwolf without versioning | springdoc passes through its own `SpringDocApiVersionStrategy`; `0162` § Verified has no versioning row |
| 10 | No norm, reference or migration mentions the interaction | 0 hits |
| 12 | `AsyncApiContractIT` asserts no status, only `channels.size() > 0` | `AsyncApiContractIT.java.example:72-79` |

Claim 14 (reproduced on a generated v0.40.0 project) was not run; the source chain above explains the reported stack line by line.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | 500 on `/springwolf/docs` in every project with a REST case and a Kafka contract | create nothing |
| 7 — mandatoriness | A template defect, not a rule the model breaks: the executor copies the exemplar as written | 7, 8 |
| 8 — destination | Both — the templates, the norm and the migration travel | — |
| 9 — integration | `api-rest.md` owns versioning; `messaging.md` owns the AsyncAPI exposure and may only cite | a norm written in both files |
| Mechanism (asked) | `setVersionRequired(false)` | a fixed-version resolver outside `/api/` |
| Verification (asked) | Static only; no Initializr run | — |
| Existing projects (asked) | A new migration | no migration |
| Contract IT (asked) | Assert status 200 before parsing | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `setVersionRequired(false)` + norm wording + IT status + migration `api-version-not-required` | 9 | **Approved** |
| 2 | Keep `required(true)`, add a resolver returning a fixed version outside `/api/` | 5 | Rejected — a default version by another route |
| 3 | Keep `required(true)`, handle `MissingApiVersionException` | 3 | Rejected — 400 instead of 500, document still unreachable |
| 4 | State the Springwolf exemption in `api-rest.md` and `messaging.md` | 3 | Rejected — invariant 2 |
| 5 | Create nothing | 2 | Rejected — the 500 keeps shipping |

### Option 1 — flag off (score 9)

**Motivator:** axis 1, confirmed symptom; axis 8, it ships into every generated project.

**Pros:** one flag. Under `/api/` nothing changes: the prefix makes `v{version}` part of every project mapping, so a request either carries a segment the resolver reads (unsupported → 400, `InvalidApiVersionException`) or matches no mapping (404, `0150` § Verified row 8). The flag only ever acted outside `/api/`, where it broke every library controller with no version strategy of its own — Springwolf now, any other later.

**Cons:** amends row 1 of `0150`; existing projects need a migration, since an entry is never edited after it ships (`extensions.json` `migrations.$comment`). Not proven against a running project.

**Score:** −1 criterion 5 (an amendment, a migration and two test edits for one flag).

**CI:** `validate` › `ApiVersionTemplateTest.config()` requires `setVersionRequired(false)` and fails on `true` naming the library-controller 500; `AsyncApiTemplateTest.contractIt()` requires the status assertion; `schema` checks the migration's fields and blueprint ids.

### Option 2 — fixed-version resolver outside `/api/` (score 5)

**Pros:** `required(true)` stays literal.

**Cons:** a custom `ApiVersionResolver` in every project, and a version assigned to requests that never named one is a default version by another route — `api-rest.md` § Versioning forbids it. −1 criteria 1, 3, 5, 6, 8.

**CI:** `ApiVersionTemplateTest` would need to prove the resolver returns the constant only off `/api/` — not provable by text.

### Option 3 — handle `MissingApiVersionException` (score 3)

**Cons:** turns the 500 into a 400; `/springwolf/docs` stays unreachable. Treats the log line, not the defect.

**CI:** a handler check in `ApiVersionTemplateTest` — proving the wrong thing.

### Option 4 — the exemption in both norms (score 3)

**Cons:** invariant 2 — a norm in two files has diverged; capped at ≤ 4. `messaging.md` cites `api-rest.md` instead.

**CI:** nothing to test — prose.

### Option 5 — create nothing (score 2)

**Cons:** the 500 ships into every project that has a REST case and a Kafka contract; the contract IT fails with "no channel", pointing away from the cause.

**CI:** none.

## References

| Decision | Source |
|---|---|
| A path with no version segment answers 404; `MissingApiVersionException` never surfaces under `/api/` | `0150` § Verified row 8; `ApiExceptionHandler.java.example` Javadoc of `handleUnsupportedApiVersion` |
| The flag acts on every handler of the mapping | Triage claim 5 (Framework 7.0.9 source) |
| A shipped migration is never edited; a correction is a new id | `extensions.json` `migrations.$comment` |
| An amended record keeps its text and gets a pointer | `.claude/decisions/README.md`; precedent `0006` row f |
| Single owner of the norm | `CLAUDE.md` invariant 2 |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/rest-api-architect/templates/ApiVersioningConfig.java.example` | `setVersionRequired(false)` and the Javadoc paragraph that says why |
| `.claude/rules/api-rest.md` § Versioning | The "version is required" bullet: required under `/api/`, the framework flag off, the reason |
| `.claude/rules/messaging.md` § Contract documentation | A citation of `api-rest.md` § Versioning on the exposure bullet; no norm restated |
| `.claude/skills/test-architect/templates/AsyncApiContractIT.java.example` | Asserts status 200 before reading the body |
| `.claude/schemas/extensions.json` `migrations` | `api-version-not-required`, the seven blueprints of `api-version-path-prefix`; the two single-module blueprints added by `0165` generate from the fixed template |
| `.claude/decisions/0150-…md` | Row 1 marked superseded by this record |

The `00-index.md` row of `api-rest.md` names no "required" and needs no change. No skill, agent, rule or MCP server was added, so no routing, class or `export` entry.

## CI coverage

| Job and step | Proves | Green | Red |
|---|---|---|---|
| `validate` › `ApiVersionTemplateTest` (`.claude/.ci/ApiVersionTemplateTest.java`, `config()`) | The config sets `setVersionRequired(false)` and never `true` | Green on the tree | `setVersionRequired(true)` injected: fails twice, naming the Springwolf 500 and the missing `false` |
| `validate` › `AsyncApiTemplateTest` (`contractIt()`) | The contract IT reads `getStatus()` and asserts `.isEqualTo(200)` | Green on the tree | `.isEqualTo(200)` swapped for `.isPositive()`: fails by name |
| `validate` › `schema` | The new `migrations` entry: required fields, id pattern, blueprint ids | Exit 0 | — (no new failure injected; the entry is data under the existing check) |

Not proven against a running project, by the maintainer's choice: claim 14 of the triage stays unrun and the fix rests on the Framework 7.0.9 source chain above.

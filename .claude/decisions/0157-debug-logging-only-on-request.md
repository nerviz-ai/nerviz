# 0157 · DEBUG and TRACE only when a request asks for them

- **Date:** 2026-10-14
- **Scenario:** "Classe: ApiExceptionHandler ou qualquer outra. Deve trocar os log.debug para
  log.warn ou error (investigar caso a caso). debug é so para quando for explicitamente pedido.
  Cheque os templates"
- **Decision:** Form 4 — `.claude/rules/logging.md` § Format and level (`DEBUG`/`TRACE` only on request;
  a refused request at `WARN` without the exception), `scheduling.md`'s empty pass, the REST and
  security templates, the `debug-logging-only-on-request` migration and `TemplateLogLevelTest`
- **State:** approved by Lucas Fernandes, on 2026-10-14 ("aprovo 1"); implemented on the branch
  `code-quality-improvement`

## What the code showed

Every `.debug(`/`.trace(` call under `.claude/` outside `ArchHook.java`, and `bank-app` at `45fc2bb`:

| Site | Event | Today | Case-by-case verdict |
|---|---|---|---|
| `rest-api-architect/templates/ApiExceptionHandler.java.example:168` | 429, the caller's quota spent | `DEBUG` | `WARN` — an expected rejection (`logging.md` § Format and level) |
| `…:204` | 400, missing or malformed `Idempotency-Key` | `DEBUG` | `WARN` |
| `…:220` | 400, unsupported API version | `DEBUG` | `WARN` |
| `…:235` | 400, bean validation (`violations` are field + constraint message, no rejected value) | `DEBUG` | `WARN` |
| `…:248` | 400, path or query parameter type mismatch | `DEBUG` | `WARN` |
| `…:259` | 400, unreadable body, **with the exception** | `DEBUG` | `WARN` **without** the exception: Jackson's message can echo a fragment of the payload, harmless while the line was off in production and a leak once it is `WARN`. The path and the cause's type only |
| `security-architect/templates/SecurityExceptionHandler.java.example:65` | 401, authentication refused | `DEBUG` | `WARN`, by the user's choice — see Option 1's cons |
| `…:76` | 403, access denied | `DEBUG` | `WARN`, same |
| `rules/scheduling.md:93` | a job pass that found nothing to do | "`DEBUG` or not at all" | not logged — the per-run timer and the last-success gauge already show the run |
| `bank-app` `ApiExceptionHandler.java:71,83,92,98,104` | the five 400s above | `DEBUG` | as the template |
| `bank-app` `OutboxPruneJob:48`, `PruneExpiredIdempotencyKeysJob:60`, `OutboxRelayJob:72` | empty pass | `DEBUG` | removed |

Every `ERROR` in the templates is already an unmapped failure that needs human action; none moves
up from `DEBUG`. `application-persistence.yml.example` and `persistence-architect/references/sql-tuning.md`
name `DEBUG` for Hibernate's own SQL logger as a development switch — a logger level a person turns
on, not a line the code writes; they stay.

`logging.md` § Format and level defines `ERROR`, `WARN` and `INFO` and says nothing about `DEBUG`
or `TRACE`; `scheduling.md` is the one rule that names `DEBUG`, as an allowed level.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | The `DEBUG` lines in `ApiExceptionHandler`, "or any other class"; checked templates | create nothing |
| 5 — Nature | A level norm: `DEBUG`/`TRACE` only when a request or spec asks for it; each existing line moved case by case | — |
| 7 — Mandatoriness | Norm in `logging.md` only — the explicit-request exception would need a suppression per file in any mechanical check | Forms 7, 8; a Checkstyle or ArchUnit check |
| 8 — Destination | New projects, and existing ones through a `migrations` entry | — (both) |
| Case — 401/403 | `WARN` in `SecurityExceptionHandler` too, not removed | — |
| Case — empty job pass | not logged | — |
| Case — 429 | `WARN` | — |
| Case — unreadable body | `WARN` without the exception | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `logging.md` § Format and level (the `DEBUG`/`TRACE` bullet, a structural rejection at `WARN` with no exception text) + `scheduling.md` line + the two templates + `migrations` entry | 8 | **Approved** |
| 2 | Option 1 + a Checkstyle `RegexpSinglelineJava` on `\.(debug\|trace)\(` in `src/main` | 5 | Rejected at the interview (axis 7) |
| 3 | Fix the two templates only | 4 | — |
| 4 | create nothing | 1 | — |

### Option 1 — `rules/logging.md` § Format and level (score 8)

**Motivator:** axes 1, 5 and 8 — the level semantics already have one owner, `logging.md`; the gap
is that `DEBUG` has no meaning there, so a template author and `scheduling.md` each gave it one.

**Pros:** one owner (invariant 2): `logging.md` gains the bullet — `DEBUG` and `TRACE` are written
only when the use case or the request asks for them, naming what and why, and never stand in for a
level the event already has; `scheduling.md` stops naming `DEBUG` and cites it. A second bullet
fixes what the unreadable-body case showed: a request rejected before the use case (400, 429) logs
at `WARN` with method/path and the reason's category, never the exception object or its message —
the message can echo the payload. The templates follow; `authorization.md` already says a denial
is `WARN`, so the security handler matches the norm it cites. The migration lists every
`.debug(`/`.trace(` in `src/main` and the verdict per line before changing any.

**Cons:** with `WARN` in `SecurityExceptionHandler` (the user's choice) a refusal writes two
`WARN` lines — the handler's (method, path, reason category) and `SecurityAuditListener`'s
(principal, from the framework event). The two templates' headers, which promised one line,
change to say which line carries what. A 429 flood writes one `WARN` per refused request; the
volume is still read from the status metric (`inbound-admission.md`). Nothing mechanical holds
the norm (axis 7, by choice).

**CI:** `validate` › `schema` (the rules' frontmatter and export; the migration's shape);
`MigrationsSchemaTest`; `AdmissionTemplateTest` and `ApiVersionTemplateTest` still hold the
handler's 429 and version shape. New: `.claude/.ci/TemplateLogLevelTest.java`, a step of
`validate`, failing by file and line on `.debug(`, `.trace(`, `isDebugEnabled` or
`isTraceEnabled` in any `.java.example` — a template is never a case that asked for one — and on
a structural-rejection handler of `ApiExceptionHandler` passing the exception to the log. It holds
the templates, the source of every line `bank-app` showed; the generated project gets no check
(axis 7). `ApiExceptionHandler` and the security templates are outside `JavaTemplatesTest`; the
change is a method name on the same SLF4J signature, compiled by hand against stubs.

### Option 2 — Option 1 + a Checkstyle check (score 5)

`RegexpSinglelineJava` in `checkstyle.xml.example` failing on `.debug(`/`.trace(` under
`src/main`, with a `SuppressionXpathSingleFilter` per file for an asked-for line. Holds the norm in
the build; but the exception is the point of the norm, and every honored request edits
`suppressions.xml`. Rejected by axis 7. **CI:** Option 1 plus `CheckstyleConfigTest`.

### Option 3 — templates only (score 4)

The eight lines move; `logging.md` keeps no meaning for `DEBUG` and `scheduling.md` keeps allowing
it, so the next template or executor writes it again. **CI:** `JavaTemplatesTest`.

### Option 4 — create nothing (score 1)

`bank-app` shows the gap: eight `DEBUG` lines, all from a template or a rule. **CI:** none.

## References

| Claim | Source |
|---|---|
| Level semantics have one owner | `.claude/rules/logging.md` § Format and level; `.claude/rules/error-handling.md` § Logging and verification defers to it |
| An expected rejection is `WARN` | `.claude/rules/logging.md` § Format and level |
| A denial is logged at `WARN` | `.claude/rules/authorization.md` § Credentials and logs |
| Rejections are read from the status metric | `.claude/rules/inbound-admission.md` |
| The only rule naming `DEBUG` | `.claude/rules/scheduling.md` § Observability, the logs bullet |
| Zero raw sensitive data in a log line | `.claude/rules/logging.md` § Format and level |
| A convention change reaches existing projects through `migrations` | `@.claude/decisions/0104-use-case-subpackage-per-aggregate.md` |

## Propagation

| File | Change |
|---|---|
| `.claude/rules/logging.md` | § Format and level: the `DEBUG`/`TRACE` bullet, the refused-request bullet, and the stack-trace bullet's exception for it |
| `.claude/rules/scheduling.md` | § Observability: an empty pass is not logged, citing `logging.md` |
| `.claude/rules/00-index.md` | `logging.md`'s row |
| `.claude/skills/rest-api-architect/templates/ApiExceptionHandler.java.example` | six rejections at `WARN` with method and path; the unreadable body logs the cause's type, never `e`, and its Javadoc says why |
| `.claude/skills/security-architect/templates/SecurityExceptionHandler.java.example` | 401 and 403 at `WARN` with method, path and the exception's type; the header names the two lines per refusal |
| `.claude/skills/security-architect/templates/SecurityAuditListener.java.example` | header: no longer "the only place a refusal is logged above DEBUG" |
| `.claude/schemas/extensions.json` | migration `debug-logging-only-on-request` |
| `.claude/.ci/TemplateLogLevelTest.java`, `.github/workflows/validate.yml` | new test and its step |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | the step's row |
| `docs/pt-br/14-rules.md`, `docs/en/14-rules.md` | `logging.md`'s role and verification |

Goes to the generated project: **yes** — the rules and both skills through `export`; an existing
project through the migration on its next `/arch-adopt` update. The test stays here: nothing under
`.claude/.ci/` travels, and the project gets no mechanical check (axis 7).

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate` › `exemplars log no DEBUG/TRACE and rejections at WARN without the exception` | no exemplar calls `.debug(`/`.trace(`/`is*Enabled` outside a comment; every rejection handler (8 today) has a `log.warn` and passes neither `e` nor its message | green · red with `log.debug` back on the quota line (`:168` and `:166` named), with `, e` back on the unreadable body (`:261`), with `log.info` on access denied (`SecurityExceptionHandler.java.example:72`); a `log.debug` inside a `//` comment stays green |
| `validate` › `schema` | the rules' frontmatter and export; the migration's shape | green |
| `validate` › `MigrationsSchemaTest`, `TemplateCommentsTest`, `AdmissionTemplateTest`, `ApiVersionTemplateTest` | the migrations block; Javadoc only; the 429 and version handlers keep their shape | green |

`ApiExceptionHandler` and the security templates are outside `JavaTemplatesTest`. The one new
call, `e.getMostSpecificCause()` on `HttpMessageNotReadableException`, was compiled by hand with
`javac` against spring-web and spring-core 7.0.9 and slf4j-api.

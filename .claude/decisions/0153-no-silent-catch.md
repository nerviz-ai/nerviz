# 0153 · A caught exception is never silent: an empty `catch` fails the build, and a `catch` that recovers logs at the level `error-handling.md` already assigns

- **Date:** 2026-10-14
- **Scenario:** "analisando o codigo gerado pelo projeto bank-app, vi que há varias blocos de codigo
  com try catch, aonde o catch nao faz nada além de escrever um comentario. Quero aplicar uma regra
  aonde evite o máximo possivel esse tipo de codigo gerado para ao menos ser gerado um log.warn ou
  log.error (dependendo do cenario)"
- **Decision:** Option 1 — `.claude/rules/error-handling.md` § A caught exception is never silent
  (Form 4, existing rule), the empty-body exception of `.claude/rules/code-quality.md` § Comments
  narrowed, Checkstyle `EmptyCatchBlock` in `project-bootstrap/templates/checkstyle.xml.example`,
  a `WARN` in `ai-architect/templates/StructuredOutputAdapter.java.example`, and the migration
  `no-silent-catch`. No hook: the `Stop` gate already runs `verify`.
- **State:** approved by Lucas Fernandes, on 2026-10-14 — option 1; implemented on the branch
  `code-quality-improvement`

## What the context showed

`bank-app` at `45fc2bb` (`.claude` at nerviz v0.33.0), every `catch` in `src/main`:

| File | Catch | Shape |
|---|---|---|
| `infrastructure/rest/CustomerController.java:88` | `CustomerNotFoundException` → body is `// answered as success by decision of UC-006 30-rest.md` | **empty, comment only** |
| `infrastructure/messaging/listener/DeadLetterMonitoringRecoverer.java:61` | `JacksonException` → `return UNKNOWN` | **swallowed** — a placeholder, no log |
| `infrastructure/rest/idempotent/IdempotencyKeyInterceptor.java:42` | `IllegalArgumentException` from `UUID.fromString` → `return false` | probe — the boolean is the method's answer |
| `persistence/customer/CustomerRepositoryJpaAdapter.java:57,59`, `persistence/idempotency/IdempotencyKeyStore.java:40,87`, `rest/idempotent/IdempotencyRequestHasher.java:26` | translated and rethrown with the cause | fine |
| `messaging/kycverificationcompleted/KycVerificationCompletedListener.java:54,71` | retry / `interrupt()` then rethrow | fine |
| `messaging/outbox/KafkaOutboxEventSender.java:47` | `log.warn` with `eventId`, `topic`, `reason` | fine |

The empty one was not the model straying from a norm. It followed one:

- `.claude/rules/code-quality.md` § Comments allows "**An otherwise empty body** — one `//` line
  saying why it is empty". A `catch` is a body; nothing excludes it.
- `project-bootstrap/templates/checkstyle.xml.example` carries `EmptyBlock` with `option=text` —
  a block with a comment passes — and the `LineComment` `RegexpMultiline` exempts the one `//`
  between `{` and `}` on purpose. `EmptyBlock`'s default tokens do not include `LITERAL_CATCH`
  anyway; nothing in the build looks at a `catch` body.
- `.claude/rules/error-handling.md` § Logging and verification already fixes the level of each
  family once it reaches the log — typed domain or integration family `WARN`, no stack, `errorCode`
  as a field; unmapped `ERROR` with the stack — and `.claude/rules/logging.md` § Format and level
  says `WARN` is "recovered degradation or an expected business rejection". What no norm says is
  that a `catch` must reach the log, or rethrow, at all.

Templates with a `catch` that returns instead of rethrowing, checked against the rule below:
`http-client-architect`'s `FeignClientAdapter` and `HttpExchangeAdapter` (`NotFound` →
`Optional.empty()`), `HttpFailureTranslator` (unreadable `Retry-After` → `null`), and
`graphql-api-architect`'s `GraphQlController` (domain family → payload `userErrors`) are answers the
return value carries. `ai-architect`'s `StructuredOutputAdapter` (`JacksonException` →
`Undecided("unreadable answer")`) is a recovered degradation of the provider — it gets the `WARN`.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | One comment-only `catch` and one swallowed `catch` in `bank-app`'s `src/main`, both produced under the current norms | create nothing |
| 4 — territory | `src/main/**/*.java` only — test code keeps its light Checkstyle set; `KycVerdictKafkaIT`'s `ConditionTimeoutException → Optional.empty()` is legitimate there | — |
| 5 — nature | Declarative fact about Java code | 1, 2, 3 |
| 7 — mandatoriness | Must hold in the generated build, not as prose alone — chose norm + Checkstyle + migration | 4 alone |
| 8 — destination | Generated project (rules travel by default; the Checkstyle config is a bootstrap template) | — |
| 9 — integration | Owner of what a `catch` does: `error-handling.md` (`code-quality.md` delegates exceptions to it). `code-quality.md` § Comments must stop licensing an empty `catch`. Level table: unchanged, `error-handling.md` § Logging | — |
| scope | Forbid the empty `catch` (comment or not) **and** the swallowed one; allow rethrow/translate, restored interrupt, and an exception that is the call's own answer | — |
| level | The current table — no new level, no change to `logging.md`. UC-006's not-found answered with 204 is a typed family recovered → `WARN`, `errorCode`, no stack | — |
| 16 — existing mode | No `ArchHook` mode needed: the `Stop` gate already runs `verify` (`tests.stop_maven_args`), so Checkstyle at `validate` blocks the executor at the end of its turn | 7c |
| 17 — CI | `templates` › `checkstyle-configs` › `CheckstyleConfigTest` already proves each module of the two configs against fixtures; it takes the new cases. `MigrationsSchemaTest` covers the entry's shape | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `rules/error-handling.md` § A caught exception is never silent + `code-quality.md` exception narrowed + `EmptyCatchBlock` in `checkstyle.xml.example` + `StructuredOutputAdapter` `WARN` + migration `no-silent-catch` | 9 | **Approved** |
| 2 | Option 1 without the migration | 7 | Rejected — leaves `bank-app` and every earlier project on the old config |
| 3 | Norms only, no Checkstyle | 6 | Rejected — the norm text alone produced the defect |
| 4 | New `ArchHook.java` mode scanning `catch` bodies on `PostToolUse` | 3 | Rejected — duplicates Checkstyle with regexes and a JVM per edit |
| 5 | create nothing | 1 | Rejected — symptom observed, cause in this repository |

### Option 1 — norms + Checkstyle + migration (score 9)

**Motivator:** axes 1 and 7 — the defect was produced by the norm, and the user wants it held by
the build.

**Pieces:**

1. `error-handling.md`, new section **A caught exception is never silent**. Every `catch` in
   production code does one of four things:
   1. **rethrows** — the same exception, or the family it translates into, with the cause;
   2. **restores the interrupt** — `InterruptedException` sets the flag back, then rethrows or
      translates;
   3. **returns the exception as the call's answer** — only when the exception *is* an expected
      answer of the operation (a lookup's not-found, malformed input the method exists to check, a
      domain family a protocol payload carries) **and** the return value has a distinct shape for
      it that the caller can tell apart from success (`Optional.empty()`, `false`, a payload error);
   4. **logs** — everything else: a placeholder or default value, carrying on, a response
      identical to success. Level per § Logging and verification: typed family, integration
      family or recovered degradation → `WARN`, `errorCode` (and `dependency`) as fields, no stack;
      unmapped → `ERROR` with the stack. Masking per `logging.md`.

   Never an empty body — a comment does not make it non-empty. Verification: Checkstyle
   `EmptyCatchBlock` for the empty one; review for the swallowed one, which no structural check
   tells apart from case 3.
2. `code-quality.md` § Comments, first exception: "An otherwise empty body … — never a `catch`:
   `error-handling.md` § A caught exception is never silent". § How to verify lists the new module.
3. `checkstyle.xml.example`: `EmptyCatchBlock` with `commentFormat` that matches nothing, so a
   comment no longer exempts the block, and a message naming the section. `checkstyle-test.xml` is
   not touched (axis 4).
4. `ai-architect/templates/StructuredOutputAdapter.java.example`: the `JacksonException` branch logs
   `WARN` with the dependency and no answer content (`ai.md` keeps content out of logs).
5. `extensions.json` `migrations`: entry `no-silent-catch`, every blueprint. `/arch-adopt` writes
   no Checkstyle config, so `bank-app` and every earlier project gets the module and a list of its
   catches to fix, behind a go-ahead, from the prompt.

**Pros:** fixes the cause, not the instance; one owner for what a `catch` does; the guarantee is the
build the executor already runs at `Stop`; same shape as `javadoc-only-comments`,
`MultipleStringLiterals` and `OneCallInThrowLambda`.

**Cons:** the swallowed `catch` stays review-held — no AST check separates a placeholder from an
answer. A migration entry is permanent: never edited after it ships.

**Points cut in the rubric:** criterion 4 → the swallowed half is persuasion; no cheaper guarantee
exists for it.

**CI:** `templates` › `checkstyle-configs` › `checkstyle.xml and checkstyle-test.xml against
fixtures` (`.claude/.ci/CheckstyleConfigTest.java`) gains three fixtures on `checkstyle.xml`: a
comment-only `catch` flagged under `EmptyCatchBlock`, a truly empty one flagged, a logging/rethrowing
one clean — and the comment-only one clean on `checkstyle-test.xml`. `validate` › `design` ›
`frontmatter schema` and `MigrationsSchemaTest` cover the migration entry. `AiTemplateTest` covers
the template edit if it compiles the adapter.

### Option 2 — without the migration (score 7)

New projects are covered; `bank-app` and every project generated before it keep building with the
old config and never learn the rule changed — `/arch-adopt` does not touch `config/checkstyle/`.
Cut: criterion 7, propagation stops at new projects.

### Option 3 — norms only (score 6)

The norm text alone is exactly what produced the comment-only `catch`. Cut: criteria 1 and 4 (the
user asked for a guarantee and one is cheap), 6 (every earlier code-quality limit went to
Checkstyle).

### Option 4 — `ArchHook.java` mode on `PostToolUse` (score 3)

A second enforcement of what Checkstyle already holds at `Stop` and in CI, parsing Java with
regexes, spawning a JVM per edit. Cut: criteria 5, 6 and 9, and the mirror of invariant 6.

### Option 5 — create nothing (score 1)

The symptom is observed and its cause is a norm in this repository.

## References

| Claim | Source |
|---|---|
| A comment-only body is licensed today | `.claude/rules/code-quality.md` § Comments, first exception |
| Checkstyle passes a commented block and ignores `catch` | `project-bootstrap/templates/checkstyle.xml.example` — `EmptyBlock option=text`, `LineComment` look-around |
| Level per family already decided | `.claude/rules/error-handling.md` § Logging and verification; `.claude/rules/logging.md` § Format and level |
| Exceptions are `error-handling.md`'s | `.claude/rules/code-quality.md` header — single owner, `@CLAUDE.md` invariant 2 |
| The `Stop` gate runs `verify`, Checkstyle included | `.claude/decisions/0142-stop-gate-runs-verify-without-its.md`; `docs/pt-br/11-pitfalls.md` |
| `/arch-adopt` writes no Checkstyle config; a migration is the channel | `extensions.json` `migrations.entries` `javadoc-only-comments`, `one-call-throw-lambda`, `multiple-string-literals`; `.claude/decisions/0104-use-case-subpackage-per-aggregate.md` |
| No content of an LLM answer in a log | `.claude/rules/ai.md` § content logging |

## Propagation

| File | Change |
|---|---|
| `.claude/rules/error-handling.md` | New § A caught exception is never silent — the four handlers, never an empty body, WRONG/RIGHT example; § Logging and verification names the Checkstyle check and the review-held half |
| `.claude/rules/code-quality.md` | § Comments: the empty-body exception is never a `catch`; § How to verify lists the empty `catch` |
| `.claude/rules/00-index.md` | `error-handling.md` row: covers what a `catch` may do; verified by Checkstyle `EmptyCatchBlock` too |
| `.claude/skills/project-bootstrap/templates/checkstyle.xml.example` | `EmptyCatchBlock`, `commentFormat` `(?!)`, message naming the section — main config only |
| `.claude/skills/ai-architect/templates/StructuredOutputAdapter.java.example` | `JacksonException` branch logs `WARN` with `dependency` and the exception type, never its message (it can quote the answer) |
| `.claude/schemas/extensions.json` | `migrations.entries` `no-silent-catch`, every blueprint |
| `.claude/.ci/CheckstyleConfigTest.java` | Fixtures `CommentedCatch`, `BareCatch`, `HandledCatch` |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | `checkstyle-configs` row: the three fixtures and this decision |

Goes to the generated project: **yes** — the two rules travel by default, `checkstyle.xml` is
written by `project-bootstrap`, the migration reaches projects through `/arch-adopt`.

Found while verifying: a Checkstyle `message` value goes through `MessageFormat`, where a single
quote is an escape — "the call's answer" printed as "the calls answer". The message avoids the
apostrophe; any later custom message must too.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `templates · checkstyle-configs › checkstyle.xml and checkstyle-test.xml against fixtures` | `checkstyle.xml` rejects a comment-only and a bare empty `catch` with `EmptyCatchBlock`, passes `Optional.empty()` / log / restored interrupt; `checkstyle-test.xml` passes the commented one | green on Checkstyle 14.3.0 · red with `commentFormat` removed: `✗ checkstyle.xml should reject CommentedCatch.java with EmptyCatchBlock — exit 0` |
| `validate · design › MigrationsSchemaTest` | the `no-silent-catch` entry's shape, id and blueprints | green |
| `validate · design › frontmatter schema` (`ArchHook.java schema`) | `extensions.json` still valid with the new entry | green |
| `validate · TemplateCommentsTest`, `AiTemplateTest`, `exemplar-imports` | the edited AI exemplar carries no forbidden comment, keeps its AI boundary; `org.slf4j` already resolves | green (`exemplar-imports` runs in CI, network) |

The swallowed `catch` — a placeholder with no log — has no check: no structural rule tells it
apart from case 3 of the section. Review holds it, and the migration prompt lists it by hand.

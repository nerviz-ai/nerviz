---
paths:
  - "**/src/**/*.java"
status: active
---

# Error handling — exception taxonomy

Transport-agnostic. No framework exception crosses the domain boundary. Mapping to a
protocol is the adapter's decision — HTTP in `@.claude/rules/api-rest.md`.

Two roots, and nothing else extends `RuntimeException`: `DomainException` for what is wrong
with the input or the state (four families), `IntegrationException` for a system this service
calls that did not answer usefully (three families). Seven families in all, never an eighth
invented ad hoc.

## Taxonomy

Typed family, not a flat `DomainException` with a status field:

| Type | When |
|---|---|
| `NotFoundException` | Referenced entity doesn't exist |
| `ValidationException` | Invariant violated while constructing the aggregate/VO |
| `BusinessRuleViolationException` | Well-formed input, violates a business rule |
| `ConflictException` | Duplicate or concurrent modification |

Domain `ValidationException` ≠ bean validation (`jakarta.validation` on the DTO): the
DTO blocks structural shape before the use case; the exception blocks the aggregate's
invariant. Two layers of defense.

## Shape of the base classes

`DomainException` is abstract and is one of the **only two** classes allowed to extend
`RuntimeException` — the other is `IntegrationException`, below. The four typed ones extend
`DomainException` and add no state of their own. `errorCode` (`UPPER_SNAKE_CASE`, e.g.
`ORDER_OUT_OF_STOCK`) is `private final` on the base, exposed via `errorCode()`, and required in
every constructor. The ones that wrap a cause (`BusinessRuleViolationException`,
`ConflictException`) carry a constructor with `Throwable cause`. Source code lives as a generated
exemplar in bootstrap, not in this rule.

The four are **not** `final`: they stay the mandatory family vocabulary — exactly four
domain families, never a fifth invented ad hoc — but each family may be narrowed by a named
subclass that adds no state, only a fixed `errorCode` given a real type instead of a
string every caller has to spell correctly (e.g. `OrderAlreadyPaidException extends
ConflictException`). Entry bar, so this doesn't quietly become "any exception name
goes": a named subclass only when the `errorCode` already repeats across two or more
call sites, or a caller needs to `catch` it specifically rather than branch on
`errorCode()`. A first occurrence stays the plain typed family with a string
`errorCode`; `domain-modeling`'s own step decides when a repeat earns the subclass.

## Integration families

A failure of another system is not a domain error: the input and the state may be perfectly valid,
and the dependency still did not answer. It gets its own abstract root, `IntegrationException`,
sibling of `DomainException` and in the same package, so an outbound port can declare it and a use
case can catch it without importing anything from an adapter. Three families, decided by one
question — was the request sent, and was it processed?

| Type | When | Retry-safe |
|---|---|---|
| `DependencyUnavailableException` | The request never left, or the dependency says it did not process it: refused connection, unknown host, connect timeout, no pooled connection in time, 429, 503, an open circuit, a full bulkhead | Yes |
| `OutcomeUnknownException` | The request was sent and no answer is known: read timeout, connection lost after sending, a gateway's 502 or 504 | Only for an idempotent call |
| `DependencyResponseException` | The dependency answered and the answer cannot be used: an unexpected 4xx, another 5xx, an unreadable body, a TLS failure | No |

Shape: `errorCode` (`UPPER_SNAKE_CASE`, stem of the dependency, e.g. `PAYMENT_GATEWAY_UNAVAILABLE`)
and `dependency` (the remote system's name) are `private final` on the root and required in every
constructor, always with the `cause`. The families add no state, with one exception:
`DependencyUnavailableException` carries the wait the dependency or the open circuit announced
(`Retry-After`), empty when none was — it is the one fact the response to our own caller needs.

A business answer of the other system — a not-found, a duplicate, a refusal on business grounds —
is **not** an integration family: the adapter maps it to the domain family the operation defines.
Named subclasses follow the same entry bar as the domain families.

A request refused at the entry boundary — a caller's quota spent, a concurrency cap full — is
neither a domain family nor an integration family: nothing failed in the domain and nothing was
called. It belongs to the inbound adapter and never crosses into the application
(`@.claude/rules/inbound-admission.md` § Rejection).

## Prohibition — never generic

Forbidden to throw `RuntimeException`, `Exception`, `IllegalArgumentException`, or any
generic JDK exception, in any layer. If none of the seven fits, a new subclass is
missing. A client or transport exception (an HTTP status exception, an I/O failure) never
crosses an adapter's boundary: it is the `cause` of the family it was translated into.

Wrapping is allowed: catch a library exception and rethrow it as the `cause` of the
typed one, preserving the stack trace.

```java
// WRONG
throw new IllegalArgumentException("id must not be null");

// RIGHT
throw new ValidationException("ORDER_ID_REQUIRED", "order id is required");
catch (DataIntegrityViolationException e) { throw new ConflictException("ORDER_ALREADY_EXISTS", "Order already exists", e); }
```

## A caught exception is never silent

Every `catch` in production code does one of four things, and nothing else:

1. **Rethrows** — the same exception, or the family it translates into, with the `cause`.
2. **Restores the interrupt** — an `InterruptedException` sets the flag back with
   `Thread.currentThread().interrupt()`, then rethrows or translates.
3. **Returns the exception as the answer of the call** — only when the exception *is* an
   expected answer of the operation (a lookup's not-found, malformed input the method exists to
   check, a domain family a protocol payload carries) **and** the return value has a shape of
   its own for it, one the caller tells apart from success: `Optional.empty()`, `false`, a
   payload error.
4. **Logs** — everything else: a placeholder or default value standing in for data that should
   exist, carrying on as if the call had worked, a response identical to success. The level is
   § Logging and verification's: a typed family, an integration family or a recovered
   degradation at `WARN`, with `errorCode` (and `dependency`) as fields and no stack trace; an
   unmapped exception at `ERROR`, with the stack trace. Masking per `@.claude/rules/logging.md`.
   Never in the domain, which does not log (`@.claude/rules/logging.md` § Format and level): a
   `catch` there rethrows, restores the interrupt, or answers.

Never an empty body. A comment does not make it non-empty: the reason it states belongs in
the log line, where whoever reads the log after the fact can see it.

```java
// WRONG — the not-found vanishes, and the 204 looks like any other success
catch (CustomerNotFoundException e) {
    // answered as success by decision of the spec
}

// RIGHT — same 204, and the log says which branch ran
catch (CustomerNotFoundException e) {
    log.warn("Anonymize answered 204 for an unknown customer: customerId={} errorCode={}", customerId, e.errorCode());
}
```

Test code is out of this section's scope: a test may catch and ignore on purpose.

## Logging and verification

Level semantics (what `ERROR`, `WARN`, and `INFO` mean) are
`@.claude/rules/logging.md`'s call, not this file's — this section only fixes what's
specific to an exception once it reaches the log:

- Typed domain family: logged at the level `logging.md` assigns an expected business flow
  (`WARN`). No stack trace, `errorCode` as its own field.
- Integration family: `WARN` too — an expected operational condition, not our bug. `errorCode`
  and `dependency` as their own fields, the cause's message, no stack trace.
- Unmapped: logged at the level `logging.md` assigns a bug (`ERROR`), with the full
  stack trace.

Never the real message or stack trace in the response to the caller — only in the log.

Verification: a contract test that triggers the case and asserts the thrown type +
`errorCode`. An empty `catch`, with or without a comment, fails the build: Checkstyle's
`EmptyCatchBlock` in `config/checkstyle/checkstyle.xml`, production code only. The `catch` that
swallows behind a placeholder is review-held — no structural check tells it apart from case 3
of § A caught exception is never silent.

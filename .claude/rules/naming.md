---
paths:
  - "**/src/**/*.java"
status: active
---

# Naming — packages, classes, methods, tests

Single principle: the name states the **role**, never the design pattern. No `Impl`
suffix. No generic `execute()`.

## Packages

Lowercase, no underscore, logical role as suffix. The concrete map comes from the
active blueprint's `packages.map` (e.g. hexagonal: `domain.model`, `domain.event`,
`application.port.in`, `application.service`, `adapter.in.rest`,
`adapter.out.persistence`). A blueprint may redefine the map; the principle doesn't
change.

## Classes

| Role | Convention | Example |
|---|---|---|
| Entity, aggregate, value object | Noun | `Order`, `Money` |
| REST controller | `<Resource>Controller` | `OrderController` |
| Inbound REST DTO | `<Verb><Resource>Request` | `ConfirmOrderRequest` |
| Outbound REST DTO | `<Resource>Response` | `OrderResponse` |
| Mapper (DTO or JPA entity ↔ domain) — mechanism per `@.claude/rules/mapping.md` | `<Resource>Mapper` | `OrderMapper` |
| JPA entity — name distinct from the aggregate | `<Resource>Entity` | `OrderEntity` |
| Outbound port implementation | `<Resource>RepositoryAdapter` | `OrderRepositoryAdapter` |
| Spring Data interface | `<Resource>JpaRepository` | `OrderJpaRepository` |
| Messaging consumer / producer | `<Event>Listener` / `<Event>Publisher` | `OrderConfirmedListener` |
| Global exception handler | `<Area>ExceptionHandler` | `ApiExceptionHandler` |
| Spring configuration | `<Area>Config` | `SecurityConfig` |

## Architecture vocabulary

The names of the use case, its ports, and their implementations change with the
architecture — a concrete `<Verb><Noun>UseCase` in one, a `<Verb><Noun>UseCase` port
implemented by `<Verb><Noun>Service` in another, a `<Verb><Noun>Handler` in a third. They
belong to the active blueprint's naming convention, not to this file. Where that
convention and the table above disagree, the convention wins.

<!-- Written at generation time: the active blueprint's naming convention, verbatim.
     Empty in any copy that isn't a generated project. -->

## Use case identifier

`UC-NNN-<slug-kebab>`, where `NNN` is sequential across the whole project and never
reused — a number that existed in the history belongs to the case that had it, even after
that case's folder is deleted.

The slug names the **trigger's verb and object**: `UC-001-register-customer` for a case
triggered by "register a customer". Never a generic verb — no `process`, `handle`,
`execute`, `manage`.

**The case that creates no use-case class.** A case can be entirely an extension of
already-approved cases: it changes their behavior, adds fields, ports or events, and
introduces no new class of its own. It still gets its own sequential `UC-NNN` — the change
has a spec, a review and a decision date, and those need an identifier. Its slug is the
exception to the paragraph above: **it names the capability being added**, not the trigger,
because the trigger already belongs to the case being changed and two cases cannot carry
the same slug. `initiate-kyc-verification` over a case whose trigger is still "register
customer" is right; `register-customer-v2` and `change-register-customer` are not.

Each altered case records the change in a `CHANGELOG.md` in **its own** folder, one line
per change: date, the `UC-NNN` that made it, and what changed. The approved spec itself is
immutable and is never edited to record it — that is why the log is a separate file.
Without it, immutability protects the text and loses the history: whoever reads
`UC-001-register-customer/` has no way to learn its behavior changed elsewhere.

## Methods

- Use case: a specific verb — `ConfirmOrderUseCase.confirm(...)`.
- Boolean predicate: prefix `is`, `has`, or `can` — `isPaid()`.
- Value object or aggregate with invariants: static factory `of`, `from`, or `create`,
  never a public constructor.

## Restricted identifiers

`record`, `yield`, `var`, `permits`, `sealed` and `when` are never the name of a variable,
parameter, field, method, or type — in `src/main` and in `src/test` alike. They compile, and
they read as the keyword they shadow. A variable holding a `ProducerRecord`,
`ConsumerRecord` or any other "record of something" is named for what it holds —
`sent`, `received`, `pending` — never `record`.

## Tests

Class, method, and test data factory names are the rule of
`@.claude/rules/testing.md` § Names and shape and § Test data. This rule doesn't repeat
them: the convention used to be written in both files and diverged — English with a
`should` prefix here, descriptive Portuguese there.

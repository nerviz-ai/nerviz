---
paths:
  - "docs/use-cases/**"
status: active
---

# Facts about another system in a spec — sourced, or open

A use case spec fixes what the code will do, and some of what it fixes is not this service's to
decide: an identifier another system assigned, a path or header it expects, a limit it enforces, a
code it answers with. A value of that kind written from memory reads exactly like a decision, is
approved with the rest, and is implemented verbatim. The error surfaces at the first call to the
real system, three groups of work later.

## Why this is a rule and not a `CLAUDE.md` section

It is a declarative fact with a territory: it holds for the files under `docs/use-cases/`, when a
spec is written and when one is read to implement it, and nowhere else (axis 4 of the interview).
The nearest rejected form was one more line in the approval read of the design flow, which would
leave the norm out of sight of whoever writes the value. Full record in
`@.claude/decisions/0184-idempotency-key-at-the-boundary-and-sourced-external-facts.md`.

## What counts

A fact about another system is any value the spec states that the other system defines and this
service cannot change:

- an identifier, name or key the other system assigns (a product, plan, queue, model or scope)
- a path, a header, a field name or a payload shape it expects or returns
- a numeric property it enforces — a rate limit, a size ceiling, a timeout, a validity period
- a status or error code and what it means there

A value this service defines (its own field, its own limit, its own name for a thing) is a
decision, and needs no source. A version of Java, Spring Boot or a library is resolved at runtime
and never written from memory — that norm has its own owner and is not restated here.

## Format

- **Each such fact carries its source in the same row or sentence:** a link to the system's
  documentation and the date it was read, or a person and the date they stated it. The source
  names where the value is, so whoever implements can check it without asking
- **A value with no source is not a fact — it is an open question.** It stays out of the table
  and out of the example payloads until a source is found or someone states it. A placeholder that
  looks like a real value (`prod_123`, `100/min`) is the same defect
- **A recorded absence is a source.** "The provider does not publish its limit" is a fact with a
  source; the limit then comes from what the business tolerates, as a decision of this service

## Approval

A spec is approved with every fact about another system sourced. A fact still without one is
resolved before approval — a source found, or the person who owns the integration asked — and if
that is not possible in the same run, the spec stays a draft. An unsourced value is never carried
into an approved spec behind a mark or a note: the executor reads it as a decision.

## How to verify

```
grep -rnE "unverified|to be confirmed|confirm with|TBD" docs/use-cases/UC-*/   # no hit in an approved spec
```

Review of the sourced facts is by reading: each one names a place, and the place says it.

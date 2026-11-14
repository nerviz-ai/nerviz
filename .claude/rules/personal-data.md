---
paths:
  - "**/src/**/*.java"
  - "**/db/migration/**"
status: active
---

# Personal data — at rest and in transit

Single principle: **personal data leaves the process only where something outside it
genuinely needs that value, and every place it lands is a place it can be found.** A field
that is masked in the logs and stored in clear in a table, or published in clear on a topic,
was never protected — it was protected in the one place someone happened to look.

`paths` is every Java source and every migration, not one boundary package. Where a payload
is written differs by architecture, but the fields it carries are often decided further in —
a domain event's fields are what crossed the broker in the observed case. Wherever a payload,
a column, or a message body is *designed*, before any code exists, cite it by path
(`@.claude/rules/personal-data.md`).

## Scope

**Personal data at rest and in transit, and nothing else.** Authentication and authorization
are `@.claude/rules/authorization.md`; secrets are a separate topic, planned in
`@.claude/rules/00-index.md`. What is observed is a national identifier serialized in clear
into a `jsonb` column and published in clear on a broker topic, in a project whose logs
masked the same field correctly.

Masking in **logs** is not here either — it has a single owner already,
`@.claude/rules/logging.md` § Masking candidates. This file starts where that one stops: the
same field, written anywhere that is not a log.

## What counts as personal data

Anything that identifies a natural person on its own, or identifies them when joined with
what the same store already holds. The catalog is
`@.claude/rules/value-objects.md` § Catalog — its types exist precisely because these fields
have a formation rule: national identifier (CPF/CNPJ), document number, full name, date of
birth, e-mail, phone, address, and any identifier issued by a government.

Two traps, both observed:

- **The field name does not have to say so.** `securityNumber` held a CPF. A list of names is
  a starting point, never the test — the test is what the value *is*.
- **A value object does not make a field safe.** `Cpf` validates and formats; it serializes
  exactly like the `String` it wraps.

## At rest

- A column that stores personal data is **named in the migration's own comment and in the
  partial that designed it**. A reader auditing where a person's data lives has one place to
  look, not every table.
- **Payload columns are the hard case.** A `jsonb`/`text` column holding a serialized
  document hides its fields from every schema-level review: the table says `payload`, and what
  is inside it is whatever the producer wrote. A payload column that can carry personal data
  says so where it is designed, and says for how long it keeps it.
- **Retention is part of the design, not an afterthought.** Personal data kept "until someone
  prunes it" is kept forever. A store whose retention window is a property nobody reads has no
  retention.
- Deriving instead of storing is the cheaper answer whenever it works: a last-four-digits
  column, a hash used only for lookup, a stable pseudonymous id.

## In transit

- **The question is asked out loud, and the answer is recorded, before the payload is
  serialized:** does this message carry personal data, and if so, what is the minimum the
  receiver actually needs? Three answers are legitimate — the full value (the receiver's whole
  purpose needs it), a reduced form (an id, a hash, the last digits), or a reference the
  receiver resolves under its own authorization. What is never legitimate is the question
  going unasked, which is how a full national identifier crosses a team boundary because it
  was in the domain event.
- **"The receiver needs it" is an answer, not an excuse — and it is written down.** When the
  value has to cross in clear, the decision is recorded with who receives it and why, in the
  design that publishes it. An unrecorded decision cannot be reviewed when the receiver
  changes.
- **A message body is retained by whoever consumes it.** A broker's retention, a consumer's
  dead-letter topic, and a replay tool are all copies nobody designed. A field that must not
  be kept must not be sent.
- **A receiver outside the project is a boundary, not a detail.** Where the consumer belongs
  to another team or another company, the payload is the contract: nothing travels in it that
  the contract does not name.

## How to verify

Grep finds the shape, not the meaning — the field that leaked was named `securityNumber`.
These are the three questions worth automating; the fourth is human.

```bash
# 1. Every payload record and DTO whose field names match the catalog's types: each one is
#    either masked for logs, reduced, or has a recorded decision. Zero hits is not the goal —
#    a hit list to check against the design is.
grep -rniE "cpf|cnpj|document|securityNumber|birth|phone|address" \
  --include='*.java' src/main | grep -iE "record|class|String|private"

# 2. Serialized payload columns, which schema review cannot see into.
grep -rniE "jsonb|@JdbcTypeCode\(SqlTypes.JSON\)" --include='*.java' --include='*.sql' src

# 3. A retention window that no code reads is not a retention window.
grep -rn "prune\|retention\|ttl" --include='*.java' --include='*.yml' src/main
```

The human question, and it is the one that caught nothing automatically: for each hit of 1
and 2, is the value in the design that put it there, with the reason it has to be that value?

## Admitted exception

A value crossing in clear because the receiver's purpose *is* that value — an identity
verification service receiving the identifier it verifies — is correct, and it is recorded
with the receiver's name and the reason. The exception is the record, not the absence of one.

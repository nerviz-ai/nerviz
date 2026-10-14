---
paths:
  - "**/src/**/*.java"
status: active
---

# Logging — format, level, and per-class-type content

Single principle: **a log line is the only witness left once the debugger is gone.**
Whoever reads it later — an on-call engineer, an audit, a postmortem — reconstructs what
happened from the line alone, not from re-running the request.

## Why this is a rule and not part of `observability.md`

Both used to live in `observability.md`. Splitting them fixes an ownership overlap: this
file owns what gets written and how; `@.claude/rules/observability.md` owns which vendor
mechanism supplies the correlation identifier's value and how metrics/tracing
integration is annotated. A rule about log content that also decided the tracing
vendor's wiring was two topics wearing one name.

## `logback.xml`

Every project ships a `logback-spring.xml`, present from the first commit, in
`src/main/resources` of the module that gets packaged — **not** at the project root:
logback only resolves its config from the classpath root, and a root-level file is
never on it. One file, no per-module copy. `logback-spring.xml`, not plain
`logback.xml`, so Spring Boot's own profile-aware initialization is what loads it.
Default pattern, console appender:

```
%d{ISO8601} %-5level [traceId=%X{traceId}] %logger{36} - %msg%n
```

`traceId` is an MDC key, populated per `@.claude/rules/observability.md`'s decision on
where the value comes from — this file doesn't decide that, only that the field exists
and where it sits in the line.

No JSON encoder by default. Until the project declares a structured-logging dependency,
the pattern above is the contract; adding one means updating the dependency and this
line in the same change, not silently diverging from it.

## Format and level

- One event per line, structured, with the correlation identifier as its own field —
  never interpolated into the middle of the message.
- Level, by business meaning: `ERROR` only for what requires human action; `WARN` for
  recovered degradation or an expected business rejection; `INFO` for a business state
  transition. An expected failure translated into the client's response is `WARN` or
  `INFO`, never `ERROR`.
- `DEBUG` and `TRACE` are written only when the use case or the request asks for them, and
  the line says what it shows and why. They never stand in for the level the event already
  has: a rejection logged at `DEBUG` is a rejection nobody sees in production. An event with
  no level above — a pass that found nothing to do — is not logged.
- A request refused before the use case ran — malformed, invalid, over its quota, not
  authenticated, not allowed — is logged at `WARN` with method, path and the reason: its
  category, or the fields that failed. Never the exception object or its message: a parser's
  message can echo the payload it failed on.
- The exception enters the log as an exception, with the stack, not as concatenated
  text — except where `@.claude/rules/error-handling.md` says otherwise for a typed
  domain or integration family (no stack; `errorCode`, and an integration family's
  `dependency`, as their own fields), and for a refused request (above).
- **Zero *raw* sensitive data in the log**: credentials, tokens, cards, personal
  documents. Two ways to comply, not one — pick per field: log the technical key instead
  of the value (an id, not the document it identifies), or mask the value before it's
  interpolated into the line, never the value itself unmasked. A masked field still
  counts as compliant; an unmasked one never does, regardless of how it got there.
- The domain does not log. It throws and returns; the adapter or application service is
  the one that logs, because it's the one that knows what the event means to the outside.
  No logging API is imported in the domain — SLF4J, Logback, Log4j, Commons Logging, JUL,
  or a Lombok logger annotation.

## Masking mechanism

The concrete tool for the "mask it" half of the rule above, same discipline
`@.claude/rules/lombok.md` already uses for naming a concrete annotation instead of
staying abstract: a field-level `@MaskSensitiveData` annotation (predefined patterns —
email, document, name, date, address, zip code, number, telephone — or a custom regex,
declared once per field) plus a small interface a record or DTO implements to get a
masked `toString()` for free. Lives in the project's cross-cutting `commons` package
(named per this project's own `CLAUDE.md`), alongside two logging annotations —
`@LogExecution` for a method, `@HttpMethodLogExecution` for a REST endpoint — and a
global aspect that logs every REST endpoint's parameters and response **by default**,
opt-out via configuration, not opt-in.

That default matters here specifically: a response DTO reaching a controller without
implementing the masking interface gets logged raw the moment the endpoint runs, whether
or not anyone remembered to think about it. A DTO with a PII field masks it before it
ever reaches a controller — this is the point in the pipeline where the "zero raw
sensitive data" line above stops being a promise and starts being what actually happens.

## Masking candidates — derived, never remembered

Which fields the mechanism above applies to is **not** a judgment call made once per
field. It is derived, and this section is the single owner of the derivation.

A field is a masking candidate when **either** holds:

1. Its type is one of `@.claude/rules/value-objects.md`'s catalog entries for personal
   data — `Email`, `PhoneNumber`, `Cpf`, `Cnpj`, `Document` — or the field is the
   primitive that stands in for one of them.
2. Its name matches the list below, case-insensitive, on the whole name or as a part of
   it.

| Kind | Names |
|---|---|
| National id | `cpf`, `cnpj`, `rg`, `ssn`, `taxId`, `nationalId`, `securityNumber`, `socialSecurity` |
| Generic document | `document`, `documento`, `documentNumber`, `passport`, `driverLicense` |
| Contact | `email`, `phone`, `telefone`, `celular`, `mobile`, `whatsapp` |
| Address | `address`, `endereco`, `street`, `logradouro`, `zipCode`, `cep`, `postalCode` |
| Birth | `birthDate`, `dataNascimento`, `dateOfBirth` |
| Payment | `card`, `cardNumber`, `cvv`, `cvc`, `iban`, `pix`, `accountNumber`, `agency` |
| Credential | `password`, `senha`, `secret`, `token`, `apiKey`, `accessKey`, `privateKey` |

The name list is what the type test cannot reach: a `String securityNumber` carrying a
CPF is a leak no type-based check sees, and it is exactly the field that crossed design,
implementation, review and a green build in a real project. The list trails reality by
one field, always — a field whose name is not here and whose content is personal data is
still a candidate, and whoever notices adds the name.

**The burden is inverted.** A field that matches is masked unless someone writes why it
is not. Not masking is the decision that needs a reason; masking is the default. A name
on the list that is genuinely not personal data (`accountNumber` of an internal ledger,
say) is declared as such where the field is designed, in one line, not left implicit.

Counter-list, so the derivation does not turn into masking everything: `name`,
`description`, `comments`, `title`, `status`, `type`, `id` of an aggregate, `createdAt`,
`version`, `amount`, `price`. A value that is not personal data and not a credential is
not a candidate, however long it is.

## Per class type

What each layer logs, so "log something" doesn't turn into copy-pasted judgment calls
per class. Names per `@.claude/rules/naming.md` and the active blueprint's
`packages.map`.

| Class type | What it logs | Level |
|---|---|---|
| `<Resource>Controller` (inbound REST) | method + path, response status, duration | `INFO` on success; `WARN`/`ERROR` per `error-handling.md`'s exception-family mapping on failure |
| `<Verb><Noun>UseCase` / `<Verb><Noun>Service` (inbound port + its implementation) | one line per business state transition (`from → to`, not the full payload) | `INFO` |
| `<Resource>RepositoryAdapter` (outbound persistence) | query identifier, duration | `WARN` when the duration crosses `@.claude/rules/persistence.md`'s slow-query threshold, otherwise not logged |
| domain (aggregate, value object, domain service) | nothing | — forbidden, see § Format and level |
| outbound integration adapter (external HTTP/gateway client) | outcome (success/failure) and latency, never the raw request/response payload | `INFO` on success, `WARN` on a failure the caller recovers from |
| inbound event/message listener | event or message type, correlation identifier, outcome | `INFO` |

The inbound port (`UseCase`) is an interface — the log line physically lives in the
implementing `Service`, since interfaces have no method body. Listed together because
they're one decision (what this use case logs at its own boundary), not two.

## Admitted exception

A service without an inbound adapter — message processor, scheduled task — has no HTTP
response to attach the identifier to, but the general log rule above still applies:
one line per business transition, correlation identifier as its own field, using the
message's or the execution's identifier in place of the response's. Where that
identifier comes from is `observability.md`'s decision, not this file's.

## How to verify

§ Masking candidates is checked by an architecture test: a DTO in the inbound REST
adapter's DTO package with a field matching the derivation implements the masking
interface and marks the field. The test is guarded by the presence of that interface —
where the `commons` package was never installed there is nothing to implement, and the
rule evaluates vacuously instead of failing.

The domain's silence is checked twice. Where the blueprint gives the domain its own module,
the logging prefixes are among that module's forbidden imports, refused at the import. In
every layout, the architecture test fails on a domain class that depends on a logging API —
read from the bytecode, so a Lombok logger annotation counts by the field it generates.
JUL is refused in every package by the same test.

The rest of this file has no mechanical check: the format, the levels and the per-class
content are `Review`-verified, same class as `naming.md` and `value-objects.md`.

---
paths:
  - ".claude/rules/**"
# Loads when a rule is read or edited; everyone else cites it. Without `paths` it
# would load at launch, in every session.
status: active
---

# Rule index

Each topic has **one** owning file. Whoever needs it cites the path
(`@.claude/rules/<file>.md`) and never reproduces the content. A rule written in two
places diverges — treat that as a bug.

This file contains no rules. It contains the map of who covers what, the loading
mechanism, and the list of what is still missing. Each rule's globs live in that file's
own `paths`; they are not repeated here.

## How a rule enters context

Two paths, and both apply to every rule:

1. **Auto-loading via `paths`** — the frontmatter declares globs and the rule enters
   context on its own when matching files are touched. It doesn't depend on anyone
   remembering to read it.
2. **Explicit citation** — whoever designs something the rule governs, before the file
   exists, cites the path.

**Every rule declares `paths`.** A rule without it is not "citation only": the runtime
loads it at launch, in every session, whatever the session touches. A cross-cutting rule
still gets the narrowest glob that holds its territory — this index loads on
`.claude/rules/**`.

Java globs are `**/src/**/*.java`, never `**/*.java`: the second matches
`.claude/hooks/ArchHook.java`, which is no application code and would pull every Java norm
into context on each read of the hook.

## Rules written

| File | Covers | Verified by |
|---|---|---|
| `architecture-ddd.md` | Universal DDD + layers, dependency direction, boundaries | `hooks/ArchHook.java check` |
| `naming.md` | Packages, classes, methods, tests, files | Review |
| `code-quality.md` | OCP, LSP, ISP + Clean Code limits (size, complexity, `null`, comments) | Checkstyle (`validate`) + ArchUnit in the generated project |
| `error-handling.md` | Domain exception taxonomy (transport-agnostic) | Contract tests |
| `api-rest.md` | Resources and versioning, verb semantics, success status, 4xx/5xx errors (Problem Details), pagination, idempotency, OpenAPI | OpenAPI + contract tests |
| `lombok.md` | Which Lombok annotations may be generated: `@Data` and `@Setter` forbidden, `@Getter` only with an immutable return, `@FieldDefaults` mandatory | `lombok.config` (`flagUsage = ERROR`, at compile time) + review for the `@Getter` condition |
| `value-objects.md` | When a domain field becomes a value object and when it stays a primitive: criteria, catalog (email, phone, CPF/CNPJ, document, money, percentage, id) and counter-catalog (`name`, `description`, `comments`) | Review |
| `persistence.md` | Adapter boundary common to both store families (one store per aggregate and per transaction, one repository and subpackage per aggregate, exception translation, paginated and ordered queries); relational: JPA mapping, identity and keys, versioned and immutable migrations, N+1, datasource configuration; document: MongoDB mapping, embedded vs referenced, `@Version` on every document, indexes ensured at startup and changed by expand/contract, `schemaVersion` upcast, keyset pagination, transactions on a replica set retried whole, `spring.mongodb.*` | `grep` from § How to verify + integration tests |
| `testing.md` | Pyramid and levels, slices and context, doubles, names and shape, test data, database engine in integration tests, coverage gate (80% lines / 70% branches), architecture tests | `./mvnw verify` (failsafe + JaCoCo) + ArchUnit |
| `observability.md` | Vendor integration for tracing (correlation identifier origin) and metrics (cardinality, health endpoint, vendor annotations) | Contract tests + context startup |
| `logging.md` | `logback.xml` default pattern, log format and level semantics, sensitive data masked or kept out of logs, per-class-type log content | Review |
| `messaging.md` | Kafka producer/consumer boundary, delivery semantics (at-least-once, idempotent consumer), topic naming and serialization, retry/DLQ, consumer configuration | `grep` from § How to verify + integration tests |
| `scheduling.md` | Jobs on their own clock: trigger as a driving adapter, fixed delay vs rate, cron zone, on/off property off in tests, pool size, coordination across instances and lock bounds, missed runs, bounded passes, last-success metric, retention enforced by a job | `grep` from § How to verify + unit tests through the inbound port |
| `personal-data.md` | Personal data at rest and in transit: what counts as personal data, payload columns and retention, the minimum a receiver needs, the recorded decision when a value must cross in clear | `grep` from § How to verify + the design record it requires |
| `authorization.md` | Authentication and authorization at the entry boundary, servlet stack: where each check lives (roles at the boundary, ownership in the use case), deny by default and the public exceptions, one mechanism per kind of caller and what each must validate, stateless/CSRF/CORS/headers, 401 and 403 bodies, credentials kept out of files and logs, the three-way endpoint proof | `grep` from § How to verify + contract tests through the real filter chain |
| `http-client.md` | Calling another service over HTTP: outbound adapter boundary and tolerant reader, client choice, engine and pool pinned, explicit timeouts, one retrying layer and the retry allowlist, failures mapped by "was the request sent?", resilience, outbound credentials, TLS and SSRF, bounded consumption, URI templates, tests against a real socket | `grep` from § How to verify + integration tests against a stub server |
| `transport-security.md` | Inbound transport: where TLS terminates (edge, direct, mutual TLS, mesh), forwarded headers and the trusted proxy list, the SSL bundle and its reload, profile-gated TLS, TLS 1.3/1.2 and JDK ciphers, HTTP/2 and its abuse limits, HSTS with exactly one writer, automatic renewal and the `ssl` health indicator kept out of readiness, tests against a real server | `grep` from § How to verify + integration tests against a real server |
| `secrets.md` | Where a secret may live: never in a versioned file, placeholders without defaults, nothing baked into an image, key material mounted at run time and generated by tests, configuration types that never print a secret, rotation before history cleanup | `grep` from § How to verify + review |
| `spec-diagrams.md` | Diagrams in use case specs: Mermaid blocks inside the markdown and no diagram file beside them, only names the same file's text defines, no fact the text lacks and the text wins on disagreement, what the sequence, flowchart, class and state diagrams each draw | `grep` from § How to verify + review; CI checks the spec templates' own diagrams against their text |
| `caching.md` | Reads served from a copy: the cache as a decorator of an outbound port in the cache package, a cache only with a stated freshness tolerance, public annotated methods only, keys naming every input that changes the result (the caller included) and the cache below the authorization check, immutable record values and a chosen serializer with a format version, expiry and size per cache, eviction by every writer and after the commit, a miss computed once, a cache failure never failing a read, hit and miss metrics, tests against the real provider | `grep` from § How to verify + integration tests that prove the hit, the eviction and the key per caller |
| `api-graphql.md` | Inbound GraphQL: choosing the protocol per use case, resolvers calling input ports only, adapter records named as schema types, schema first with an inspection that fails the startup, nullability, batched child fields with a bounded batch, Relay connections with a page ceiling and a keyset cursor, errors as data on mutations and a classified top-level error otherwise, method security per operation and per sensitive field, depth/complexity/time limits, introspection and GraphiQL off outside development, deprecation before removal checked against a schema snapshot, outcome metrics | `grep` from § How to verify + a GraphQL slice test, an HTTP test through the real filter chain and a schema compatibility test |
| `ai.md` | Outbound calls to a language model and a vector store: the AI library only in the AI package, the model suggests and the domain decides, a chat client from the auto-configured builder, model and options named per port, prompts as versioned resources with input only in named delimited variables, a typed answer with closed fields and every unusable answer as an outcome, each field sent decided, content logging off, searches with a named threshold returning ids, the store's schema from a migration, one retrying layer and the provider's failure families, token metrics, tests without a real model | `grep` from § How to verify + a contract test with a stub model, an IT against a stub provider server and an IT against the real store engine |
| `search.md` | A search engine index (Elasticsearch, OpenSearch) as a rebuildable projection of a source of truth: the client only in the search package, a port returning ids or a read projection, the index written by an indexer from an event and never by the request, a rebuild from the source from the first index, versioned mapping files with index creation off and strict mapping, versioned indexes behind an alias swapped atomically, typed fields (`text`/`keyword`, `nested`), writes carrying the source version as an external version with a conflict acknowledged, no refresh on write, `search_after` over a point in time, no leading wildcard, an unreachable engine as unavailable, tests against the real engine | `grep` from § How to verify + integration tests against the real engine, out-of-order events and an alias swap |

`personal-data.md` loads on every Java source and every migration, not on one boundary
package: the field that leaked was decided in a domain event, and its name did not say what
it held. It is also cited by path wherever a payload is designed. Secrets are a separate
topic, `secrets.md`, which loads on every source and configuration file for the same reason:
the norm that a credential stays out of a versioned file used to be restated in four boundary
norms, and now each of them cites it.

`spec-diagrams.md` is the one norm whose territory is documentation, not code: it loads on
`docs/use-cases/**`, when a spec is written and when one is read to implement it. That glob
names no package, so it is listed in `export.derived_paths.derived_paths_exempt_globs` and
travels unchanged.

`architecture-ddd.md`'s `paths` here is an example: its globs come from the active
blueprint's `architecture_paths` and are written into the file that bootstrap copies into
the generated project. See `@.claude/blueprints/_schema.md`.

The rules whose territory is a package — `api-rest.md`, `persistence.md`,
`value-objects.md`, `observability.md`, `messaging.md`, `scheduling.md`, `authorization.md`, `http-client.md`, `caching.md`, `ai.md`, `api-graphql.md`, `search.md` — have a `paths` here that serves as an example
and is **rewritten at generation time** from the active blueprint's `packages.map`. The
name of the entry-layer package changes from architecture to architecture
(`adapter/in/rest` in one, `infrastructure/rest` in another), and a glob copied verbatim
leaves the rule unloaded, silently. See `@.claude/blueprints/_schema.md`.

## Planned rules (file does not exist yet)

Do not cite these by path — there is nothing to load. If you need one, write it before
using it; don't improvise it inside another file.

| File | Will cover | Expected `paths` |
|---|---|---|
| `git-workflow.md` | Branches, commit messages, PRs | narrowest glob that holds it — without one it loads at launch |

## States

`status: active` applies now · `status: draft` is a proposal, do not apply ·
`status: deprecated` is kept for reading old code, do not use in new code.

The runtime does not read `status`. A `draft` rule under `rules/` still loads whenever its
`paths` match and reads as a norm like any other, so a proposal stays out of this folder
until it is `active`.

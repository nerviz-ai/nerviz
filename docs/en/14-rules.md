# Rules — the role of each norm, and where its responsibility ends

Versão em português: [`docs/pt-br/14-rules.md`](../pt-br/14-rules.md).

This page answers one question: **which rule decides what, and where it hands over to another.**
It does not say *what* each norm requires. For that, open the rule: it is the single source
(`CLAUDE.md`, invariant 2), and this page only points at it. When this page and a rule disagree,
the rule wins. The short map, one line per rule, is
[`.claude/rules/00-index.md`](../../.claude/rules/00-index.md). The loading mechanism (`paths`,
citation) is in [01-file-types.md § Rule](01-file-types.md#rule).

Each rule below has four entries:

- **Role** — the responsibility only it has.
- **Loads when** — the territory of its `paths`, in words.
- **Not its call** — the boundary, and who owns the other side.
- **Verified by** — what catches a violation. "Review" means nothing mechanical catches it.

Rules marked ⟳ carry example `paths` in this repository. `export` rewrites those `paths` from
the active blueprint's `packages.map`, because the package name changes from architecture to
architecture (`adapter/in/rest` in one, `infrastructure/rest` in another).

---

## How they group

| Group | Rules | What they share |
|---|---|---|
| Foundation | `architecture-ddd`, `naming`, `code-quality`, `error-handling`, `logging`, `lombok`, `value-objects` | They hold for all Java code, whatever the layer |
| Data that leaks | `personal-data`, `secrets` | They cut across every layer, because a leak is decided far from where it happens |
| Inbound | `api-rest`, `api-graphql`, `authorization`, `inbound-admission`, `transport-security`, `observability` | The path of a request to the use case |
| Outbound | `persistence`, `messaging`, `http-client`, `ai`, `caching`, `search` | Each owns one kind of outbound adapter |
| Clock | `scheduling` | Code nothing upstream waits for |
| Runtime | `virtual-threads` | Which threads the code runs on, decided by configuration, not by a layer |
| Tests and specs | `testing`, `spec-diagrams` | Their territory is not `src/main` |

---

## Foundation

### `architecture-ddd.md` — dependency direction

- **Role:** decides who may import whom. Dependencies point inward, and the domain knows no
  framework. The domain is the same in every blueprint. The other layers fix only the principle,
  and the name of each abstraction (port, use case, service) is the blueprint's.
- **Loads when:** a file of the layers is touched. The globs come from the active blueprint's
  `architecture_paths`, not from a fixed list.
- **Not its call:** class names (`naming.md`), what a persistence adapter may expose
  (`persistence.md` § Boundary), and the shape of exceptions (`error-handling.md`).
- **Verified by:** `ArchHook.java check` at edit time and `doctor gate` in CI, both reading the
  blueprint's `forbidden_imports`. In the build, the inner module compiled in isolation. A
  single-module layout has no module to isolate, and the ArchUnit test is the only check outside
  the editor.

### `naming.md` — the name states the role

- **Role:** decides the names of packages, classes, methods and tests. The name states the role,
  never the design pattern. Includes the use case identifier (`UC-NNN-slug`) and the restricted
  identifiers.
- **Loads when:** any Java under `src/`.
- **Not its call:** the vocabulary of the use case and its ports (`UseCase`, `Service`,
  `Handler`). That vocabulary is the blueprint's convention, which wins when it disagrees with the
  rule's table. The shape of a test is `testing.md`'s.
- **Verified by:** review. Checkstyle catches the format (package, type, method, restricted
  identifier) and ArchUnit catches the `Impl` suffix.

### `code-quality.md` — SOLID and Clean Code, only what's left

- **Role:** owns the size and complexity limits, `null` and mutability, the `switch` over a sealed
  type, and the comment policy. It also decides where a branch over an enum or sealed type
  lives: in the type itself, in a `switch` expression in the layer, or in a Strategy. And it
  requires a `switch` that computes a value to be an expression. It covers on purpose only
  what no other rule covers.
- **Loads when:** any Java under `src/`.
- **Not its call:** layer SRP and DIP (`architecture-ddd.md`), names (`naming.md`) and exceptions
  (`error-handling.md`). The comment policy's "empty body with one `//`" exception does **not**
  hold for a `catch`: an empty `catch` is `error-handling.md`'s subject.
- **Verified by:** Checkstyle at the `validate` phase. Each module of `checkstyle.xml` matches one
  limit of the rule, and changing one side without the other is a bug. Spotless handles
  formatting. ArchUnit covers ISP, `private final` fields in the domain and a generic `throw`.
  OCP, LSP, the level of abstraction and where a branch over a closed set lives stay with
  review.

### `error-handling.md` — what an error is, and what a `catch` does with it

- **Role:** owns the exception taxonomy, transport-agnostic. Two roots and seven families: four
  domain families, for wrong input or state, and three integration families, for a called system
  that did not answer usefully. It also decides what a `catch` may do and what is specific to an
  exception once it reaches the log (stack trace or not, `errorCode`, `dependency`).
- **Loads when:** any Java under `src/`.
- **Not its call:** the HTTP status of each family (`api-rest.md`) and the meaning of `ERROR`,
  `WARN` and `INFO` (`logging.md`). A refusal by quota or concurrency is none of the seven
  families: it is `inbound-admission.md`'s.
- **Verified by:** a contract test (thrown type + `errorCode`). In Checkstyle, `IllegalCatch`,
  `IllegalThrows` and `EmptyCatchBlock` (`src/main` only). In ArchUnit, the generic `throw`. A
  `catch` that swallows behind a placeholder value stays with review.

### `logging.md` — the log line as the only witness

- **Role:** decides what is written to the log and how. It covers `logback-spring.xml` and its
  pattern, the meaning of each level (`DEBUG`/`TRACE` only when asked for; a refused request at
  `WARN`, without the exception), what each class type logs, and what stays out of the log.
  It owns the **derivation** of which fields are masking candidates, by type or by name.
- **Loads when:** any Java under `src/`.
- **Not its call:** where the `traceId` value comes from (`observability.md`) and an exception's
  stack trace and fields (`error-handling.md`). The same personal data outside the log, in a
  column or a topic, is `personal-data.md`'s.
- **Verified by:** ArchUnit catches a REST DTO with an unmasked sensitive field and a domain that
  depends on a logging API. The domain module's `forbidden_imports` refuse the import. Format,
  levels and per-class content stay with review; in this repository's templates,
  `TemplateLogLevelTest` refuses `DEBUG`/`TRACE` and a rejection logged below `WARN`.

### `lombok.md` — generate only what cannot break an invariant

- **Role:** decides which Lombok annotations may be generated. A read accessor cannot break an
  invariant; a mutator always can. It also decides what Lombok writes by default: `@Slf4j`
  instead of a hand-written logger, `@RequiredArgsConstructor` instead of a constructor that only
  assigns `final` fields (outside the domain and the exceptions it lists) and `@FieldDefaults`
  when every instance field is private.
- **Loads when:** any Java under `src/`.
- **Not its call:** the aggregate's immutability (`architecture-ddd.md`) and the value object
  itself (`value-objects.md`). Lombok does **not** count as a framework for domain purity, because
  nothing of it survives in the bytecode. The exception is Lombok's loggers, which `logging.md`
  forbids in the domain.
- **Verified by:** the compiler, through `lombok.config` with `flagUsage = ERROR`. The `@Getter`
  condition (an immutable return) and what Lombok writes by default stay with review; in this
  repository's templates, `TemplateLombokTest` refuses a hand-written logger, constructor or
  modifier.

### `value-objects.md` — when a field stops being `String`

- **Role:** owns the criterion that turns a field into a value object (an input exists that can be
  rejected), the catalog, the counter-catalog, and the type of each number.
- **Loads when:** domain Java. ⟳
- **Not its call:** the exception the invariant throws (`error-handling.md`) and domain purity
  (`architecture-ddd.md`). Adapters receive outside text on purpose, and the criterion does not
  hold there. Which of these types are personal data is used by `logging.md` and
  `personal-data.md`.
- **Verified by:** review.

---

## Data that leaks

### `personal-data.md` — personal data at rest and in transit

- **Role:** decides where personal data may stay and where it may leave: payload columns,
  retention, the minimum a receiver needs, and the recorded decision when a value must cross in
  clear.
- **Loads when:** every Java source and every migration. It is wide on purpose: in the observed
  case, the field that leaked was decided in a domain event, far from the adapter that published
  it.
- **Not its call:** masking in the **log** (`logging.md` § Masking candidates; this rule starts
  where that one stops), credentials (`secrets.md`) and authentication (`authorization.md`).
- **Verified by:** the `grep` of § How to verify and the design record it requires.

### `secrets.md` — where a credential may live

- **Role:** decides where a secret may exist: never in a versioned file, placeholders without
  defaults, nothing baked into an image, key material mounted at run time and generated by tests.
  It also decides what to do when a secret leaks.
- **Loads when:** every source and configuration file (`.yml`, `.properties`, `Dockerfile`). This
  norm used to be restated in four boundary rules. Now each of them cites this one.
- **Not its call:** what may appear in the log (`logging.md`) and personal data
  (`personal-data.md`).
- **Verified by:** `grep` and review.

---

## Inbound

### `api-rest.md` — the shape of the HTTP contract

- **Role:** decides only the shape of the exposed contract: resources and URIs, verbs, success and
  error statuses, Problem Details, versioning, pagination, idempotency and OpenAPI. The client
  tells what happened by the status.
- **Loads when:** the inbound REST adapter is touched. ⟳
- **Not its call:** business rules (`architecture-ddd.md`), the error taxonomy
  (`error-handling.md`, which this rule only maps to statuses) and class names (`naming.md`). The
  401 and 403 bodies follow the shape defined here, but *who* may call is `authorization.md`'s.
  The admission 429 and 503 are `inbound-admission.md`'s.
- **Verified by:** `grep`, OpenAPI and contract tests.

### `api-graphql.md` — what replaces the protections HTTP gave for free

- **Role:** decides the schema, resolvers, batching, connections, errors, depth and complexity
  limits, introspection and schema evolution. It also owns the **criterion for choosing** REST,
  GraphQL or both for a use case.
- **Loads when:** the GraphQL adapter or the `.graphqls` files are touched. ⟳
- **Not its call:** the HTTP semantics shared with REST (statuses, Problem Details,
  `Idempotency-Key`). They are `api-rest.md`'s and do not apply here unless this rule says so.
  Neither is where each access check lives (`authorization.md`; this rule only says how to apply
  it on a single endpoint, per operation and per field), nor the quota (`inbound-admission.md`).
- **Verified by:** `grep`, a GraphQL slice test, an HTTP test through the real filter chain and a
  schema compatibility test.

### `authorization.md` — who may call

- **Role:** decides where each check lives (roles at the boundary, ownership in the use case),
  deny by default, one mechanism per kind of caller, stateless, CSRF, CORS and headers, and what a
  refusal never reveals to the caller. Servlet stack only.
- **Loads when:** the REST and GraphQL adapters or the configuration are touched. ⟳
- **Not its call:** the status table and the Problem Details shape of the body (`api-rest.md` §
  Errors and § Error body), masking a credential in the log (`logging.md`) and where the
  credential lives (`secrets.md`).
- **Verified by:** `grep` and contract tests through the real filter chain.

### `inbound-admission.md` — refusing before the use case

- **Role:** decides the per-caller quota and the concurrency cap at the entry, REST and GraphQL. A
  limit exists only when the use case states one.
- **Loads when:** the REST and GraphQL adapters are touched. ⟳
- **Not its call:** resilience of calls going **out** (`http-client.md`). The refusal is neither a
  domain nor an integration family (`error-handling.md`). The shape of the 429 and the 503 follows
  `api-rest.md`.
- **Verified by:** `grep` and integration tests against a real server, with the full context.

### `transport-security.md` — how the request arrives

- **Role:** decides where TLS terminates (edge, direct, mutual TLS, mesh), forwarded headers and
  trusted proxies, the SSL bundle, protocol versions, HTTP/2, HSTS and certificate renewal.
  Decided once per project, before the first endpoint.
- **Loads when:** `application*.yml`, a `Dockerfile` or `docker-compose*.yml` is touched.
- **Not its call:** TLS on calls going **out** (`http-client.md` § Security) and where the key and
  keystore live (`secrets.md`).
- **Verified by:** `grep` and integration tests against a real server.

### `observability.md` — the vendor's wiring

- **Role:** decides where the correlation identifier's value comes from and how tracing and
  metrics integrate with the vendor (cardinality, health endpoint, annotations).
- **Loads when:** the inbound REST and GraphQL adapters are touched. ⟳
- **Not its call:** what is logged, at which level and in which shape (`logging.md`). This rule
  decides only that the value exists and where it comes from. `logging.md` decides where it sits
  in the line.
- **Verified by:** contract tests and context startup.

---

## Outbound

### `persistence.md` — the store as an adapter detail

- **Role:** decides the boundary common to both store families (one store per aggregate, one
  repository per aggregate, exception translation, paginated queries), relational mapping (JPA,
  migrations, N+1) and document mapping (MongoDB, `@Version`, indexes, `schemaVersion`), and
  auditing (stamps, an Envers history).
- **Loads when:** the persistence adapter or the migrations are touched. ⟳
- **Not its call:** **choosing** relational or document, which is a fact of the use case recorded
  before. Retention of personal data is `personal-data.md`'s; the job that enforces it is
  `scheduling.md`'s. The second-level cache is kept off by `caching.md`.
- **Verified by:** `grep` and integration tests.

### `messaging.md` — the event after it leaves the domain

- **Role:** decides the adapter that publishes an event to a broker and the one that reads it
  back: delivery semantics (at-least-once, idempotent consumer), publication timing (direct, or
  outbox + relay), topics and serialization, the AsyncAPI contract generated from the code and
  compared with a committed snapshot (both sides annotated; spec-first recorded with when to use it
  and when not), retry and DLQ. Kafka only today; a second broker adds a section, not a file.
- **Loads when:** the inbound or outbound messaging adapters are touched. ⟳
- **Not its call:** the domain event itself, a past-tense fact with no framework
  (`architecture-ddd.md` § Domain). The trigger that runs the relay on a clock is
  `scheduling.md`'s.
- **Verified by:** `grep` and integration tests.

### `http-client.md` — calling another service

- **Role:** decides every default of an HTTP library nobody chose: client, engine and pool,
  timeouts, **one** retrying layer, failures mapped by "was the request sent?", resilience,
  outbound authentication, TLS and SSRF, bounded consumption and URI templates.
- **Loads when:** the outbound HTTP adapter is touched. ⟳
- **Not its call:** the three integration families a failure becomes (`error-handling.md` §
  Integration families) and the outbound credential (`secrets.md`). A refusal at the **entry** is
  `inbound-admission.md`'s.
- **Verified by:** `grep` and integration tests against a stub server.

### `ai.md` — the model suggests, the domain decides

- **Role:** decides calls to a language model (structured answer) and to a vector store (search by
  meaning): model and options per port, versioned prompts, a typed answer with closed fields, what
  goes to the provider, a named threshold, one retrying layer and token metrics.
- **Loads when:** the AI adapter or the prompts are touched. ⟳
- **Not its call:** search by **words** and facets (`search.md`) and generic HTTP transport
  (`http-client.md`). Tool calling, chat memory and generation over retrieved passages are out of
  scope, and using one needs a decision recorded first.
- **Verified by:** `grep`, a contract test with a stub model, an integration test with a stub
  provider and an integration test with the store's real engine.

### `caching.md` — the copy that must stay honest

- **Role:** decides the application cache as a decorator of an outbound port. It exists only with
  a freshness tolerance stated by the business. It covers a key per caller, expiry and size,
  eviction after the commit, stampede, and a cache failure that never fails the read.
- **Loads when:** the cache package is touched. ⟳
- **Not its call:** HTTP response caching (the transport's) and Hibernate's second-level cache,
  which this rule keeps off: three caches over one read are three invalidation paths.
- **Verified by:** `grep` and integration tests that prove the hit, the eviction and the key per
  caller.

### `search.md` — the index as a rebuildable projection

- **Role:** decides an Elasticsearch or OpenSearch index fed from a source of truth: written by an
  indexer from an event, rebuildable from the first index, versioned mapping, alias swapped
  atomically, and an external version on every write.
- **Loads when:** the search adapter or the mappings are touched. ⟳
- **Not its call:** search by meaning (`dense_vector`, kNN), which is a vector store and belongs
  to `ai.md` even on the same engine. Full-text inside the aggregate's own database (`tsvector`, a
  MongoDB text index) is that database's index, hence `persistence.md`'s.
- **Verified by:** `grep` and integration tests against the real engine, with out-of-order events
  and an alias swap.

---

## Clock

### `scheduling.md` — code nobody waits for

- **Role:** decides jobs that run on their own clock. The trigger is an inbound adapter, like a
  controller. It covers fixed delay or rate, the cron zone, an on/off property, coordination
  across instances, missed runs, bounded passes and a last-success metric. It also owns the
  sentence "a retention window exists only while a job enforces it".
- **Loads when:** the scheduling package is touched. ⟳
- **Not its call:** what the job does, which is the use case called through the inbound port. The
  outbox relay is `messaging.md`'s; this rule covers only its clock.
- **Verified by:** `grep` and unit tests through the inbound port.

---

## Runtime

### `virtual-threads.md` — which threads the code runs on

- **Role:** decides that a project whose build targets JDK 21 or later turns on virtual threads
  and the keep-alive beside them, and that no profile turns them off without saying why. The
  version is the build's, not the machine's. It also says what the request pool stops bounding.
- **Loads when:** an `application*.yml`, `.yaml` or `.properties` under `src/main/resources` is
  touched.
- **Not its call:** the size of the inbound concurrency cap (`inbound-admission.md`) and the
  scheduler pool below JDK 21 (`scheduling.md`). Both cite this rule for the on case.
- **Verified by:** `grep` and `doctor`'s `Virtual threads` line, which warns and never gates.

---

## Tests and specs

### `testing.md` — a test exists to fail

- **Role:** decides the pyramid and levels, slices and context, doubles, name and shape,
  assertions, asynchronous effects, test data, the database in integration tests, the coverage
  gate and architecture tests.
- **Loads when:** any file under `src/test/`.
- **Not its call:** what each boundary test must prove. That lives in each adapter rule's § Tests
  (`transport-security.md`, for instance, needs a real socket). Names and code limits are not its
  call either (`naming.md`, `code-quality.md`).
- **Verified by:** `./mvnw verify` (failsafe + JaCoCo) and ArchUnit.

### `spec-diagrams.md` — the diagram is a view of the text

- **Role:** decides the diagrams of use case specs: Mermaid inside the markdown, only names the
  text defines, no fact the text lacks (and the text wins when they disagree), and what each kind
  of diagram draws.
- **Loads when:** `docs/use-cases/**`, both when a spec is written and when it is read to
  implement. It is the only rule whose territory is documentation, which is why its glob travels
  without a rewrite.
- **Not its call:** the content of the spec.
- **Verified by:** `grep` and review. CI checks the spec templates' own diagrams against their
  text.

---

## Boundary questions — which rule answers

| Question | Owner | Not |
|---|---|---|
| Which HTTP status does a `NotFoundException` become? | `api-rest.md` | `error-handling.md`, which only defines the family |
| Do I log this exception with its stack trace? | `error-handling.md` § Logging and verification | `logging.md`, which fixes only the level's meaning |
| May this `catch` stay empty, or hold only a comment? | `error-handling.md` § A caught exception is never silent | `code-quality.md` § Comments, which excludes `catch` from the "empty body" exception |
| Where does the `traceId` come from? Where does it sit in the line? | `observability.md` / `logging.md` | — |
| May this CPF appear in the log? In a `jsonb` column or on a topic? | `logging.md` / `personal-data.md` | — |
| May this password sit in `application.yml`? | `secrets.md` | `logging.md`, `personal-data.md` |
| Who may call this endpoint? And the shape of the refusal's body? | `authorization.md` / `api-rest.md` § Error body | — what the body must **not** reveal goes back to `authorization.md` |
| TLS on the way in? And on the call to a partner? | `transport-security.md` / `http-client.md` | — |
| A 429 for the caller's quota, or a 429 the partner returned? | `inbound-admission.md` / `http-client.md` (becomes `DependencyUnavailableException`) | — |
| Search by words, or by meaning? | `search.md` / `ai.md` | `persistence.md`, if it is full-text in the store's own database |
| May this read come from a cache? | `caching.md`, only with a stated freshness tolerance | `persistence.md` (second-level cache kept off) |
| Who deletes expired data? | `personal-data.md` decides the retention, `scheduling.md` the job that enforces it | — |
| `@Slf4j` on a domain class? | `logging.md`, which forbids it | `lombok.md`, which allows Lombok in general |
| Virtual threads on? And how many requests may run at once? | `virtual-threads.md` / `inbound-admission.md` § Concurrency cap | — |

---

## Example: one `catch` through the rules

The code below came from a generated project. The anonymize use case answers 204 even when the
customer does not exist:

```java
catch (CustomerNotFoundException e) {
    // answered as success by decision of the spec
}
```

Each rule answers one part:

1. **`error-handling.md`** — the `catch` must do one of four things. Returning it as the answer
   does not fit, because the 204 is identical to success. That leaves **logging**, and a domain
   family goes at `WARN`, no stack, with `errorCode` as a field.
2. **`code-quality.md`** — the comment does not make the block legitimate, and the reason it
   stated moves into the log message.
3. **`logging.md`** — `WARN` because it is an expected business rejection. The line lives in the
   adapter or the service, never in the domain.
4. **`logging.md` § Masking candidates** — log the `customerId`, a technical key, not the
   customer's document.
5. **`api-rest.md`** — the 204 itself remains a contract decision, recorded in the spec.

The build catches step 1 through Checkstyle's `EmptyCatchBlock`. Steps 3 to 5 stay with review.

---

## Keeping this page

A new rule enters here, in both languages, with the four entries and, where it applies, a row in
"Boundary questions". This page repeats no number, list or example from a rule: when a rule
changes, only "Role" and "Not its call" can go stale, and both change rarely. No CI job checks
this page against `.claude/rules/`.

# Architecture blueprints

*[Português](README.pt-br.md)*

Each folder here is an architecture ready to generate a Spring Boot project through
`project-bootstrap` (`/init-project`). The full contract every `<id>.yaml` follows is
at `@.claude/blueprints/_schema.md` — each file's own `when_to_choose` and
`trade_offs` are the source of truth; this README is a quick-read summary to help pick
where to start. Across all of them, `rules/architecture-ddd.md` (framework-free
domain, constructor injection, one use case per class) holds without exception — what
changes from architecture to architecture is how layers are named and grouped, never
those invariants.

## Quick table

| Architecture | Build | Core idea |
|---|---|---|
| [`layered`](#layered-n-tier) | single-module | Controller → Service → Repository, the vocabulary most Spring tutorials already use |
| [`clean-architecture-single-module`](#clean-architecture-uncle-bob) | single-module | domain/application/infrastructure as packages |
| [`clean-architecture-multi-module`](#clean-architecture-uncle-bob) | multi-module | domain/application/infrastructure as Maven modules |
| [`hexagonal`](#hexagonal-ports--adapters) | multi-module | Ports (interfaces) and inbound/outbound adapters in their own modules |
| [`hexagonal-single-module`](#hexagonal-ports--adapters) | single-module | The same ports and adapters as packages of one module |
| [`onion`](#onion-palermo) | multi-module | Domain model + domain services fused into one "Application Core"; any outer ring may call any inner ring |
| [`onion-single-module`](#onion-palermo) | single-module | The same Application Core and outer rings as packages of one module |
| [`modular-monolith`](#modular-monolith-spring-modulith) | single-module | Several bounded contexts as module-packages (`internal`), verified by Spring Modulith |
| [`vertical-slice`](#vertical-slice-feature-slices) | single-module | One self-contained package per use case (`features.<feature>.<usecase>`), not per technical layer |
| [`custom-template`](#custom-template) | — | Starting point to describe your own architecture |

The `Build` column is the module layout, not the build tool. Every blueprint declares
`build.tool: maven` as a **default**: `/init-project` asks Maven or Gradle for any of them, and
the Gradle exemplars live in `project-bootstrap/templates/`, not in the blueprint.

---

## `layered` (N-Tier)

**What:** classic Controller → Service → Repository stack, a single Maven module,
sibling packages (`controller`, `service`, `repository`, `entity`, `dto`, `mapper`).

**Pros**
- Vocabulary any Spring Boot team already knows — zero naming learning curve.
- Fewer files per feature: no port+adapter pair, the `Repository` is the boundary
  itself (`extends JpaRepository`).

**Cons**
- The boundary between layers is only validated by ArchUnit, not the compiler —
  nothing stops an improper import from compiling.
- Adding a second inbound/outbound channel later means editing the `Service` class's
  dependencies directly, with no port to swap behind.

**When to use it:** one inbound channel (REST) and one outbound channel (a single
relational database), CRUD-shaped work where a Spring Data repository is already
enough abstraction between service and database.

---

## Clean Architecture (Uncle Bob)

Two variants, same domain → application → infrastructure separation; the difference
is only where the boundary gets enforced.

### `clean-architecture-single-module`

**Pros:** no cost of maintaining 3 `pom.xml` files; same layering discipline as the
multi-module version.
**Cons:** the boundary is only validated at test time (ArchUnit) — a class in
`domain` can physically import Spring and only the test catches it.
**When to use it:** small/medium project where Maven's multi-module orchestration is
overhead nobody is going to amortize.

### `clean-architecture-multi-module`

**Pros:** the dependency rule is enforced by Maven itself (isolated compile =
boundary respected); a use case is a single class, no interface+impl boilerplate per
use case.
**Cons:** REST, persistence and messaging share a single `infrastructure` module — no
build isolation between adapters; swapping a use case's implementation means editing
the application layer, not just infra.
**When to use it:** long-lived system, small/medium team, business rules that need to
stay isolated from infrastructure details without paying for a module per adapter.

---

## Hexagonal (Ports & Adapters)

**What:** domain at the center, ports as interfaces, inbound and outbound adapters at
the edges, each in its own module.

**Pros**
- Tests a use case without spinning up the Spring context.
- Swaps infrastructure without touching the domain — multiple inbound/outbound
  channels (REST + messaging + batch) coexist without conflict.

**Cons**
- More modules and more explicit mapping than `layered`.
- Steeper entry curve for newcomers to the project.
- Overkill for simple CRUD.

**When to use it:** more than one inbound or outbound channel, a real need to swap
infrastructure without touching the domain.

**`hexagonal-single-module`** is the same architecture with the modules as packages of one
Maven module (the package map is identical, so code moves between the two). The boundary
is no longer enforced by the compiler but by the edit-time hook, CI's `doctor gate` and
ArchUnit; the boundary map derived for it is thinner than the multi-module one, and the
adapters are forbidden from importing each other package by package (a GraphQL resolver may use the REST adapter's security and admission wiring). Choose it when you
want the port/adapter vocabulary without one `pom.xml` per adapter.

---

## Onion (Palermo)

**What:** domain model and domain services fused into a single, central "Application
Core" (Palermo's own term, no separate `application` module); persistence and
presentation as independent outer rings — but unlike `layered`, any outer ring may
call any inner ring directly.

**Pros**
- Palermo's own terminology (Application Core, Gateway) instead of hexagonal's
  port/adapter split.
- Persistence and presentation don't need to be strangers to each other, but never
  call each other directly.

**Cons**
- The domain module is bigger than hexagonal's or clean architecture's — model,
  services, and every `Gateway` interface live together, so more changes touch the
  same compilation unit.
- "Any outer ring may call any inner ring" is looser than `layered`'s strict
  adjacency rule — easier to accidentally couple things.
- Persistence gets one indirection more than hexagonal: Gateway → Adapter →
  Repository.

**When to use it:** domain model and domain services are designed and changed
together, by the same people, most of the time — not worth a fourth `application`
module just to keep them apart.

**`onion-single-module`** is the same architecture with the modules as packages of one
Maven module (the package map is identical, so code moves between the two). The boundary
is no longer enforced by the compiler but by the edit-time hook, CI's `doctor gate` and
ArchUnit; the `app.*` packages keep the name of the composition-root module, and
`persistence` and `presentation` are forbidden from importing them. Choose it for Palermo's
vocabulary without one `pom.xml` per ring.

---

## `modular-monolith` (Spring Modulith)

**What:** one deployable, several bounded-context modules as direct sub-packages of
the application root, each with a hidden `internal` package and a small public
surface — verified at test time by `ApplicationModules.verify()`. Inside `internal`,
the same domain/application/adapter layering as hexagonal, at full rigor.

**Pros**
- A real module boundary without a separate deploy — an exit path to microservices
  already in place.
- `rules/architecture-ddd.md` never relaxes inside each module.

**Cons**
- A second verification tool on top of ArchUnit (`ApplicationModules.verify()`).
- Module names are business data, not architecture data — can't be pre-created
  before the first real use case exists.
- Still a single deployable: a runaway module can still exhaust the same JVM as
  every other module — structural isolation, not runtime isolation.

**When to use it:** several bounded contexts that don't yet justify separate
deployables, but need a boundary stronger than "please don't import that package"; or
when there's a real intent to split into microservices later.

---

## `vertical-slice` (Feature Slices)

**What:** code grouped by feature, not by technical layer — each use case gets its
own self-contained package (`features.<feature>.<usecase>`) with Command/Query,
Handler, Endpoint and Response (REPR pattern). Only the aggregate crosses the slice
boundary, kept in a shared `domain` kernel; the persistence port and its adapter live
inside the slice itself, not in a shared module like every other architecture here.

**Pros**
- Adding a feature costs only new files — never edits a service or repository class
  shared by everyone else.
- Isolation between slices reduces merge conflicts and prepares a future
  microservice extraction, one level below what `modular-monolith` does per bounded
  context.

**Cons**
- No mediator framework (MediatR's role in .NET) — no automatic
  validation/logging/transaction pipeline per handler, each slice wires its own.
- Persistence is scattered per slice instead of centralized — two slices touching the
  same aggregate can duplicate a port and query if nobody promotes it into the
  `domain` kernel.
- Sibling-slice isolation isn't a static ArchUnit rule (slice names don't exist yet
  at blueprint-authoring time) — only enforceable once a second slice exists, via
  ArchUnit's `slices()`.

**When to use it:** features diverge more than they overlap, the team wants a new
feature to cost only new files, or reuse across features matters less than isolation
and delivery speed.

---

## `custom-template`

Not an architecture — the starting point to describe your own. Copy
`custom-template/custom.template.yaml`, fill it in following `_schema.md`'s 5-rule
checklist, and validate: the final arbiter is the build (if it compiles respecting
the declared module boundaries, the architecture was respected).

---

## How to choose

See `@.claude/skills/project-bootstrap/references/blueprint-selection.md` for the
decision table shown during `/init-project` and the tie-breaker questions (inbound
channels in 12 months, bounded contexts, how many people touch the repo).

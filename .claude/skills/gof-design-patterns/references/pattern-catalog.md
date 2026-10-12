# External source — the full GoF catalog

<https://refactoring.guru/design-patterns/java> — intent, structure diagrams, Java code
samples, and pros/cons for all 23 classic patterns, plus the how-to-recognize and
how-to-refactor sections this repo's own `SKILL.md` catalog stays deliberately silent
on. Go there for the full explanation; come back here for the symptom → pattern lookup
and this repo's verdict on each one.

**This file is triage, not a menu.** The entry rule in `SKILL.md` still applies to every
row below: no pattern without an observed symptom at a real `file:line`. A row existing
here is not permission to apply it — it's how to name what you're already looking at.

## How to read the columns

- **Symptom** — what the code looks like when this pattern is the fix, phrased the way
  you'd actually describe the smell, not the pattern's textbook intent.
- **guide** — the refactoring.guru page for structure, participants, and trade-offs.
- **This repo** — one of:
  - `curated` — already in `SKILL.md` § Catalog; that table is the source of truth for
    the symptom threshold (e.g. "≥ 2 places"), this table doesn't repeat or loosen it.
  - `exemplar` — `curated`, and a shape reference exists in `templates/`.
  - `allowed` — no curated row, no exemplar, but nothing forbids it. Apply only with a
    real symptom in hand; name it per `@.claude/rules/naming.md` (the role, never the
    pattern, never `Impl`); size per `@.claude/rules/code-quality.md`.
  - `forbidden` — in `SKILL.md` § Forbidden in this repository; the reason is there,
    not repeated here.
  - `covered by the language/framework` — the JDK or Spring already gives you this;
    hand-rolling it is the anti-pattern, not the absence of one.

## Creational

| Pattern | Symptom | guide | This repo |
|---|---|---|---|
| Factory Method | A method's return type is an interface and the concrete class returned depends on input — today it's an `if`, tomorrow a new type needs a new branch | <https://refactoring.guru/design-patterns/factory-method> | `exemplar` — `templates/FactoryMethod.java.example`. This repo's static-factory idiom (`of`, `from`, curated in `SKILL.md`) covers the single-product case; reach for a full Factory Method hierarchy only once there are genuinely multiple concrete product families, not one constructor with validation |
| Abstract Factory | Several related objects have to be created together and stay consistent as a family — swap one, the rest must follow | <https://refactoring.guru/design-patterns/abstract-factory> | `exemplar` — `templates/AbstractFactory.java.example` |
| Builder | ≥ 4 optional constructor parameters, or construction has a step order that matters | <https://refactoring.guru/design-patterns/builder> | `exemplar` — `templates/Builder.java.example`. `SKILL.md` § Catalog. Lombok `@Builder` on a domain object hides invariants; a domain builder is hand-written, validating on `build()` |
| Prototype | An object is expensive or unsafe to reconstruct from scratch, and cloning an existing instance is cheaper — including when the concrete class isn't known at the call site | <https://refactoring.guru/design-patterns/prototype> | `exemplar` — `templates/Prototype.java.example`. Rare in a typical CRUD-shaped Spring service; more common in code that builds complex in-memory graphs |
| Singleton | One instance, reached from anywhere | <https://refactoring.guru/design-patterns/singleton> | `forbidden` — `SKILL.md` § Forbidden in this repository: the Spring container already gives singleton scope; the hand-rolled version blocks testing and hides global state |

## Structural

| Pattern | Symptom | guide | This repo |
|---|---|---|---|
| Adapter | Two interfaces that should talk don't match, and neither side can change | <https://refactoring.guru/design-patterns/adapter> | `curated` — `SKILL.md` § Catalog calls this "already the blueprint": every output port implementation in this repo's own architecture (`@.claude/rules/architecture-ddd.md` § Adapters) is this pattern applied to persistence and external systems |
| Bridge | An abstraction and its implementation vary on two independent axes, and subclassing one to cover every combination of the other is exploding the class count | <https://refactoring.guru/design-patterns/bridge> | `exemplar` — `templates/Bridge.java.example`. Check first whether the "two axes" are actually one axis with a config flag; Bridge for a single varying axis is Strategy with extra ceremony |
| Composite | Individual objects and compositions of them need to be treated through the same interface — a tree where leaf and branch respond to the same calls | <https://refactoring.guru/design-patterns/composite> | `exemplar` — `templates/Composite.java.example` |
| Decorator | The same cross-cutting behavior (retry, audit, rate limit) repeats across ≥ 2 implementations of one interface, and no Spring aspect (`@Transactional`, `@Retryable`) already does it. A cache is not this row: its decorator, keys and eviction are `@.claude/rules/caching.md`'s | <https://refactoring.guru/design-patterns/decorator> | `exemplar` — `templates/Decorator.java.example` |
| Facade | A subsystem's real interface is wide and mostly irrelevant to the common case; callers only ever need three of its twenty methods, always in the same order | <https://refactoring.guru/design-patterns/facade> | `exemplar` — `templates/Facade.java.example`. In this repo's layering, an application service already plays this role for the domain; a Facade on top of a Facade is a smell, not a fix |
| Flyweight | Many near-identical objects waste memory because most of their state is actually shared, not per-instance | <https://refactoring.guru/design-patterns/flyweight> | `exemplar` — `templates/Flyweight.java.example`. Uncommon outside high-volume in-memory processing; profile before reaching for it |
| Proxy | Something needs to stand in front of a real object to control access to it — lazy load, permission check, logging, caching — without the caller knowing | <https://refactoring.guru/design-patterns/proxy> | `covered by the language/framework` — Spring AOP proxies (`@Transactional`, `@Cacheable`, `@Async`) are this pattern; a hand-written proxy competes with infrastructure the framework already provides |

## Behavioral

| Pattern | Symptom | guide | This repo |
|---|---|---|---|
| Chain of Responsibility | A request has to pass through a sequence of possible handlers until one handles it, and the sender shouldn't know or care which one will | <https://refactoring.guru/design-patterns/chain-of-responsibility> | `exemplar` — `templates/ChainOfResponsibility.java.example`. Validation pipelines and `HandlerInterceptor` chains (`@.claude/skills/rest-api-architect/templates/IdempotencyKeyInterceptor.java.example`) are already an instance of this from Spring MVC |
| Command | An action needs to be parameterized, queued, logged, retried, or undone — the operation itself becomes an object instead of a direct method call | <https://refactoring.guru/design-patterns/command> | `exemplar` — `templates/Command.java.example`, the full `execute`/`undo` shape. This repo's own `Command` records (`@.claude/skills/domain-modeling/templates/Command.java.example`) are a lighter, unrelated use of the same name for use-case input — reach for the full pattern here only when queuing or undo is a real requirement |
| Iterator | A collection needs to be traversed without exposing how it's stored internally | <https://refactoring.guru/design-patterns/iterator> | `covered by the language/framework` — `Iterable`, `Stream`, and every JDK collection already are this; hand-rolling an iterator is almost never the right move in modern Java |
| Mediator | Objects reference and call each other directly in a tangle, and adding a new object means touching every existing one's code | <https://refactoring.guru/design-patterns/mediator> | `exemplar` — `templates/Mediator.java.example`. Check first whether an application service already is the mediator; a second one usually means a missing use-case boundary, not a missing pattern |
| Memento | An object's internal state needs to be captured and restored later without exposing that state to the object doing the capturing | <https://refactoring.guru/design-patterns/memento> | `exemplar` — `templates/Memento.java.example`. Rare in a typical CRUD-shaped service; more common in editors, workflows with explicit rollback |
| Observer | One object's state change has to notify a growing, decoupled set of dependents, and they're being called directly today | <https://refactoring.guru/design-patterns/observer> | `covered by the language/framework` — this repo's own Domain Events (`SKILL.md` § Catalog, `@.claude/skills/domain-modeling/templates/DomainEvent.java.example`) plus Spring's `ApplicationEventPublisher` already are this; a hand-written observer list competes with both |
| State | An object's behavior branches on its own internal state, and that branch is a `switch`/`if-else` that grows every time a new state is added | <https://refactoring.guru/design-patterns/state> | `exemplar` — `templates/State.java.example`. This is Strategy's close cousin: same fix (extract each branch into its own type), different framing (the type changes over the object's lifetime instead of being picked once). If `SKILL.md`'s Strategy row already matches the symptom, use that name — don't introduce a second name for the same shape |
| Strategy | `switch`/`if-else` over a type or enum, repeated in ≥ 2 places, growing with each new rule | <https://refactoring.guru/design-patterns/strategy> | `exemplar` — `templates/Strategy.java.example` |
| Template Method | Several classes implement the same algorithm with the same steps in the same order, and only one or two steps actually differ between them | <https://refactoring.guru/design-patterns/template-method> | `exemplar` — `templates/TemplateMethod.java.example`. Prefer composition (Strategy, or a plain injected collaborator) over a `protected abstract` hook when the varying step is a single method; Template Method earns its keep when several steps vary together in a fixed skeleton |
| Visitor | A new operation has to run across every type of an existing class hierarchy, and adding it as a method on each type would mean touching all of them for something that isn't really their responsibility | <https://refactoring.guru/design-patterns/visitor> | `exemplar` — `templates/Visitor.java.example`. The rarest fit in a typical Spring Boot codebase; confirm the hierarchy is genuinely closed (new operations arrive more often than new types) before reaching for it |

## Not in the GoF catalog, curated here anyway

| Pattern | Symptom | This repo |
|---|---|---|
| Specification | The same business predicate is duplicated between a service's `if` and a query's `WHERE` | `exemplar` — `templates/Specification.java.example`. `SKILL.md` § Catalog. DDD idiom (Evans/Fowler), not one of the 23 on refactoring.guru |
| Domain Events | An aggregate's state change has to be observed by ≥ 2 modules that shouldn't be called directly from inside the aggregate | `curated` — `SKILL.md` § Catalog. Architectural pattern for this repo's layering, not a refactoring.guru entry |

## What this catalog doesn't decide

Whether a symptom is real, whether it clears the "≥ 2 places" (or equivalent) threshold,
what the resulting class gets named, and what size limits it has to respect: all of that
is `SKILL.md`'s procedure and `@.claude/rules/naming.md` /
`@.claude/rules/code-quality.md`. Refactoring.guru explains what a pattern is; it doesn't
know this repository's conventions, and a page that suggests a name like `OrderStrategy`
loses to `@.claude/rules/naming.md` every time.

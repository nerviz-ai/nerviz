---
paths:
  - "**/src/**/*Mapper.java"
  - "**/pom.xml"
  - "**/build.gradle"
  - "**/build.gradle.kts"
# Loads on a mapper and on a build file — the annotation processor path is the half of this
# norm that breaks in silence. Names no package, so it travels unchanged.
status: active
---

# Mapping — translating between a boundary's types and the model

A mapper translates the types of one boundary — a request or response DTO, a persistence
entity or document, a GraphQL record, a message payload, a provider's DTO — into the types the
application and the domain know, and back. It lives in the adapter that owns those types, named
per `@.claude/rules/naming.md`; the domain and application layers never import a mapping
library, and each blueprint's `forbidden_imports` holds that boundary
(`@.claude/rules/architecture-ddd.md` § Adapters).

## One mechanism per project

The root `CLAUDE.md` carries the project's choice in one paragraph, opened by `**Mappers:`:

| Paragraph | Every mapper is |
|---|---|
| `**Mappers: manual.**`, or none | a class of plain methods written by hand |
| `**Mappers: MapStruct.**` | a MapStruct `@Mapper` interface |

Never both. A project that switches to MapStruct converts every existing mapper in the same
change, so no reader has to learn which style a given adapter uses. The only hand-written code
in a MapStruct project's mappers is the `default` method § What MapStruct generates and what
stays written requires — inside the `@Mapper` interface, not in a second class.

## Common to both mechanisms

- **A value object is built through its own factory** — `Email.of(raw)`, `new UserId(uuid)` —
  so its validation runs; never field by field.
- **Reconstitution goes through the aggregate's named factory** (`rehydrate`), never through a
  path that produces a state no behaviour produces.
- **A mapper does no I/O.** A lookup — the owner of an id, a reference row — is the adapter's
  call before or after the mapping, never a repository, client or cache handed into it.
- **A mapper decides no business default.** A value missing from the source stays missing; the
  default belongs to the domain or to the DTO's validation.
- **A mapper does not log** — it returns or throws, and the adapter logs
  (`@.claude/rules/logging.md`).
- **A mapper branches on a closed set by the type**, never by `instanceof` chains
  (`@.claude/rules/code-quality.md` § Branching on a closed set).
- **A mapper has no dedicated test.** Its conversions are proven by the adapter's own tests
  (`@.claude/rules/testing.md`); in a MapStruct project the compiler also refuses a target
  property nobody maps.

## Manual

A class of plain methods, one per direction it needs. Static methods where nothing is injected
and the translation is a pure function of its input; a package-private bean otherwise. A
direction nobody calls is not written.

## MapStruct

### Build

| Setting | Value | Why |
|---|---|---|
| `mapstruct.defaultComponentModel` | `spring` | A mapper is a bean, injected like any collaborator |
| `mapstruct.defaultInjectionStrategy` | `constructor` | `@.claude/rules/architecture-ddd.md` § Composition — field injection is forbidden, generated code included |
| `mapstruct.unmappedTargetPolicy` | `ERROR` | A target property nobody maps fails the compile — the check a manual mapper never had |

The three are processor options of the build (`-A` compiler arguments), not attributes repeated
on each `@Mapper`: one place, and a mapper cannot forget them. No `@Mapper` or `@MapperConfig`
lowers `unmappedTargetPolicy`.

**The annotation processor path lists every processor the build runs.** Declaring it turns
classpath discovery off: a processor present as a dependency and missing from the list stops
running with no error — Lombok's annotations then fail the compile with "cannot find symbol" at
best, and the configuration processor's metadata simply stops being written. The list holds, in
this order, Lombok, `org.projectlombok:lombok-mapstruct-binding`, `org.mapstruct:mapstruct-processor`,
then every other processor the build already ran (`spring-boot-configuration-processor`,
`hibernate-jpamodelgen`, …). A processor added later is added to the list in the same change.
The list that counts is the one the main compilation reads: a list inside the compiler plugin's
`default-compile` execution replaces a plugin-level one, so an entry written at the wrong level
is ignored with no error.
On Gradle each one is its own `annotationProcessor` line, and the same omission has the same
effect.

MapStruct's version is not managed by the Spring Boot parent. It sits in one build property
(`mapstruct.version`), resolved from Maven Central when the library is added and never written
from memory; the binding's version likewise.

### Shape

A package-private `interface` annotated `@Mapper`, in the adapter package of the types it
translates, injected by constructor. A conversion the adapter repeats — a value object to and
from its raw value — sits in one package-private class of that adapter named in `uses =`, with
one method per direction and a `@Named` qualifier when two methods share a signature.

### What MapStruct generates and what stays written

MapStruct reads a property only through a `getX()`/`isX()` accessor or a record component, and
builds a target through its public constructor, a record's canonical constructor, a Lombok
builder, or setters on a `@MappingTarget`. So:

| Direction | Mechanism |
|---|---|
| Source is a record or exposes `get`/`is` accessors, **and** the target is a record, a class built by its public constructor, or a `@MappingTarget` with setters | generated, with `@Mapping` where names differ |
| Target is built by a named factory — `rehydrate`, `of`, `register` | a `default` method written in the interface, exactly as a manual mapper would |
| Source is a class with fluent accessors (`order.lines()`) — a mutable aggregate | a `default` method |
| Target is a persistence entity with no setters | a `default` method calling the entity's constructor or its update method |

A `default` method may call the generated ones for the parts that qualify — a nested record, a
collection of records — so the hand-written part is only what MapStruct cannot see.

### Allowed

`@Mapping(target, source)`, `ignore = true` on a target the source genuinely lacks (a generated
id, a version the store writes), several source parameters, `@Named` with `qualifiedByName`,
`uses =`, `@MappingTarget` where the target has setters, `@SubclassMapping` for a sealed
hierarchy, `@ValueMapping` for an enum whose constants differ in name, `@InheritInverseConfiguration`.

### Forbidden

| Construct | Why | Instead |
|---|---|---|
| `expression = "java(...)"`, `defaultExpression` | Java in a string: no rename, no navigation, no compile-time type check until generation — the mapping is hidden exactly where it is least read | a `@Named` method, or a method of the `uses =` class |
| `Mappers.getMapper(...)`, an `INSTANCE` field | A dependency the container does not see; the processor options above already make every mapper a bean | constructor injection |
| `defaultValue`, `constant` targeting a domain type or a command | A business default written in an annotation | the domain, or the DTO's validation |
| A nested target into a value object (`target = "email.value"`) | Builds the value object without its factory | a `uses =` method calling the factory |
| `@Context` carrying a repository, client or cache | I/O inside a mapper | the adapter looks it up and passes the value |
| `@BeforeMapping`, `@AfterMapping` | Logic after the fact, mutating a half-built target — and a record target has no setter | a `default` method, or `@SubclassMapping` for a type branch |
| `NullValuePropertyMappingStrategy.IGNORE` on a `@Mapper`, a `@MapperConfig`, or any method whose source is not a partial-update payload | A null that must clear a column is silently kept | only on the `@BeanMapping` of a method mapping a PATCH body onto a `@MappingTarget` |
| `unmappedTargetPolicy` lowered anywhere | Turns the forgotten-field check off | `ignore = true` on the one target, with its reason |
| A `Map` source, `java.util.Date` | Untyped input; the legacy date API | a typed record; `java.time` |

## How to verify

```bash
# MapStruct project — every construct of § Forbidden; any match is a defect
grep -rnE 'expression *=|defaultExpression|Mappers\.getMapper|@(Before|After)Mapping|unmappedTargetPolicy' \
  --include='*.java' src/main
grep -rn '@Context' --include='*.java' src/main                                  # each one carries no repository, client or cache
grep -rn 'NullValuePropertyMappingStrategy.IGNORE' --include='*.java' src/main   # each one on a PATCH method only
grep -rn 'org.mapstruct' --include='*.java' src/main | grep -E '/(domain|application)/'   # must be empty

# Processor path — every processor the build runs appears in it
grep -n -A12 '<annotationProcessorPaths>' pom.xml
```

The compile is the rest of the check: `unmappedTargetPolicy = ERROR` refuses a forgotten target,
and a processor missing from the path breaks the build or empties its output.

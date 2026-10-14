---
paths:
  - "**/src/**/*.java"
status: active
---

# Lombok — what's allowed to be generated

Lombok generates code that nobody reviews. The criterion is a single one: **only
generate what cannot violate an invariant**. A read accessor can't; a mutator always
can. Hence the list below isn't negotiable for convenience.

The dependency is `provided`/`optional` and the annotations have `SOURCE` retention:
nothing from Lombok survives in the published bytecode. That's why Lombok doesn't count
as a framework for the purposes of `@.claude/rules/architecture-ddd.md` § Domain, and
it's legitimate to use it in the innermost module.

## Forbidden

| Annotation | Why |
|---|---|
| `@Data` | Generates `@Setter` on every field, `equals`/`hashCode` over mutable state, and a `toString` that dumps everything. An aggregate with `@Data` stops guaranteeing its invariants: any caller writes the field directly and skips the constructor |
| `@Setter` | On a class or on a field. Mutation from outside is exactly what `@.claude/rules/architecture-ddd.md` forbids: an invariant is the aggregate's responsibility, not the caller's. A state change enters through a method with a business name (`confirm()`, `cancel(reason)`), which validates before writing |

There's no exemption by layer. An inbound DTO with `@Setter` is the same defect with
fewer consequences, and the exception opens the door to the rest. For deserialization,
see below.

## `@Getter` — allowed with a condition

Allowed **if the returned object is immutable**. The getter is a read gate; if what
comes out of it is mutable, it's a write gate in disguise.

Immutable for this purpose:

- primitive, `String`, `enum`, `record`, `java.time.*`, `BigDecimal`, `UUID`
- domain value object (immutable per `@.claude/rules/architecture-ddd.md`)
- collection already returned defensively

A field of collection or mutable type **does not get `@Getter`**. Write the accessor by
hand and return a copy or immutable view:

```java
public List<OrderLine> lines() {
    return List.copyOf(lines);
}
```

`@Getter` on the field, not on the class, when only part of the fields are exposed.
`@Getter` on the class declares that **all** fields are publicly readable — rarely true
for an aggregate.

## `@FieldDefaults` — mandatory when every instance field is private

A class whose instance fields are all private declares it once at the top and omits the
modifier on each of them:

```java
// without @FieldDefaults — the modifier repeats on every field
private final OrderId id;
private final Clock clock;
private List<OrderLine> lines;

// with @FieldDefaults(level = AccessLevel.PRIVATE) declared at the top of the class
final OrderId id;
final Clock clock;
List<OrderLine> lines;
```

`makeFinal = true` is allowed and recommended when **all** fields are final —
`@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)`. Don't use it when some
field has to vary: forcing `final` and then annotating exceptions costs more than
writing `final` on the ones that are.

Practical rule: if at least one instance field needs a visibility other than private,
`@FieldDefaults` doesn't apply — declare all modifiers by hand and don't mix the two
styles in the same class.

`@FieldDefaults` never touches a `static` field: a constant keeps the modifiers it is
written with (`private static final`), and one written without `private` is
package-private under the annotation too. A class whose only fields are static carries no
`@FieldDefaults` — it would do nothing.

## Deserialization without setters

Jackson and JPA don't need `@Setter`:

- **Jackson** — a constructor with `@JsonCreator` + `@JsonProperty`, or a `record` as
  the DTO. A `record` solves the common case and allows no mutation at all
- **JPA** — a `protected` no-args constructor on the entity, with fields written via
  reflection. `@NoArgsConstructor(access = AccessLevel.PROTECTED)` is allowed: it opens
  no public write point

## Generated boilerplate — the default, not an option

What Lombok writes exactly as a person would, it writes. A hand-written copy is code
somebody reviews for nothing, and it drifts from the fields it mirrors.

- **Logger: `@Slf4j`.** It is the only way a class gets its logger — never
  `LoggerFactory.getLogger(...)` written by hand. The generated field is `log`. The
  domain never logs at all (`@.claude/rules/logging.md`)
- **Constructor that only assigns: `@RequiredArgsConstructor`.** A constructor whose body
  is nothing but `this.x = x;` for each `final` field is generated, with the access level
  it had (`access = AccessLevel.PACKAGE` / `PRIVATE` when it wasn't public). Over
  `final` fields, it is exactly the constructor `@.claude/rules/architecture-ddd.md`
  § Composition requires; field `@Autowired` remains forbidden. The parameter order is the
  field order — moving a field moves an argument
- **Field modifiers: `@FieldDefaults`**, as § `@FieldDefaults` above already requires

The constructor stays written when Lombok would change what it means or hide what it
shows:

- a domain class — aggregate, entity, value object, enum, domain event, domain exception:
  its constructor is part of the model a reviewer reads. An application service is not a
  domain class and follows the default
- a constructor with any other statement — a validation, a copy, a computed field
- a persistence entity: its fields aren't `final`, JPA or the document mapper writes them
  by reflection, and its constructor takes a subset of them
- a parameter carrying an annotation (`@Qualifier`, `@Value`): Lombok does not copy it to
  the generated parameter
- two parameters of one type: reordering their fields swaps the two arguments at every
  call site with no compile error
- more than one constructor

Test sources write all of this by hand. The Gradle build gives Lombok to the main
compilation only (`compileOnly` + `annotationProcessor`), so a Lombok annotation under
`src/test` compiles on Maven and fails on Gradle.

## Remaining annotations

`@AllArgsConstructor`, `@Builder`, `@Value`, `@EqualsAndHashCode` and `@ToString` are
allowed. One caution:

- **`@ToString`** — exclude sensitive fields (`@ToString.Exclude`) or don't use it. A
  `toString` generated over every field prints passwords, tokens, and PII the moment the
  class ends up in a log message

## How to verify

Not review: the compiler. `lombok.config` at the project root flags the forbidden
annotations with `flagUsage = ERROR`, and the build breaks at compile time, with file
and line.

```
lombok.data.flagUsage = ERROR
lombok.setter.flagUsage = ERROR
```

```bash
./mvnw -q test-compile
```

The `@Getter` immutability condition and § Generated boilerplate are the parts of this
rule no tool enforces in the project. They're verified in review — a written
`LoggerFactory.getLogger`, or a constructor that only assigns `final` fields, is the
sign to look for.

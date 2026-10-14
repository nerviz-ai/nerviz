# 0158 · Lombok writes the logger, the injection constructor and the field modifiers

- **Date:** 2026-10-14
- **Scenario:** "Refatorar templates para usar anotações úteis do lombok como: @Slf4j,
  @RequiredArgsConstructor"
- **Decision:** Form 4 — `.claude/rules/lombok.md` § Generated boilerplate (`@Slf4j`, `@RequiredArgsConstructor` for a
  constructor that only assigns, `@FieldDefaults` on private instance fields, no Lombok in test sources), every
  production template, the `lombok-generated-boilerplate` migration and `TemplateLombokTest`
- **State:** approved by Lucas Fernandes, on 2026-10-14 ("aprovo 1", after the two corrections in the interview);
  implemented on the branch `code-quality-improvement`

## What the code showed

Every `*.java.example` under `.claude/skills/` (154, `claude-code-architect-designer/templates/`
excluded), and `bank-app` at `45fc2bb`:

| Shape | Templates | `bank-app` | Today's norm |
|---|---|---|---|
| `private static final Logger log = LoggerFactory.getLogger(X.class)` | 4 — `ApiExceptionHandler`, `SecurityExceptionHandler`, `SecurityAuditListener`, `StructuredOutputAdapter` | 9 files | `lombok.md` silent on `@Slf4j` |
| `@Slf4j` | 2 | 1 | — |
| A constructor whose body is only `this.x = x;` | 75 constructors; 6 of them take two parameters of one type | 31 | `lombok.md` § Remaining annotations: `@RequiredArgsConstructor` **allowed** |
| A constructor with logic (validation, a computed field) | 39 | 18 | — |
| `@RequiredArgsConstructor` | 1 | 1 | — |
| `private` fields without `@FieldDefaults` | 73 files | — | `lombok.md` § `@FieldDefaults`: **mandatory** when every field is private |
| `@FieldDefaults` | 9 | — | — |

No parameter of a plain-assignment constructor carries `@Qualifier` or `@Value`. The persistence
entities' constructors (`UserEntity`, `OrderEntity`, `OutboxEventEntity`…) assign non-`final` fields
JPA writes by reflection, a subset of them, at package visibility — `@RequiredArgsConstructor`
does not produce them.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Templates write by hand what Lombok generates: the logger, the injection constructor | create nothing |
| 5 — Nature | A code-shape norm, owned by `lombok.md`; the templates follow | Forms 1–3 |
| 7 — Mandatoriness | `lombok.md` requires it; no build check in the generated project | Forms 7, 8; a Checkstyle check |
| `@FieldDefaults` | Fixed in the same pass, since the norm already requires it | — |
| Exceptions | Corrected by the user mid-proposal: "deixa os templates de gof e dominio sem uso do @RequiredArgsConstructor" — the 15 plain-assignment constructors of `gof-design-patterns/templates/` and `domain-modeling/templates/` stay written, besides a constructor with logic and the persistence entity. `@FieldDefaults` still applies there, since `lombok.md` already requires it. Then: "classes de servico podem ter o @RequiredArgsConstructor" — a `@Service` class converts wherever it sits, `CreateUserService` in `UseCasePort.java.example` included | — |
| 8 — Destination | New projects, and existing ones through a `migrations` entry | — (both) |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `lombok.md` requires `@Slf4j`, `@RequiredArgsConstructor` for a plain-assignment constructor, and keeps `@FieldDefaults` mandatory; every template follows; `migrations` entry; `TemplateLombokTest` | 8 | **Approved** |
| 2 | Option 1 + Checkstyle `RegexpSinglelineJava` on `LoggerFactory.getLogger` in the generated project | 5 | Rejected at the interview (axis 7) |
| 3 | Refactor the templates only; `lombok.md` keeps "allowed" | 4 | Rejected — the norm stays silent, the next template writes it by hand |
| 4 | create nothing | 1 | Rejected — 73 files keep violating `@FieldDefaults` |

### Option 1 — `rules/lombok.md` § Generated boilerplate (score 8)

**Motivator:** axes 1, 5 and 8 — `lombok.md` owns which annotations are generated; it lists
`@RequiredArgsConstructor` as allowed, never as the default, and never names `@Slf4j`, so each
template author chose, and 75 of 76 chose the written constructor.

**Pros:** one owner (invariant 2). `lombok.md` gains a section saying when Lombok writes the code:
`@Slf4j` is the only way a class gets its logger; a constructor whose body only assigns each
`final` field from its parameter is `@RequiredArgsConstructor`; `@FieldDefaults` stays mandatory
as it is. The exceptions are named in the norm, so the next template does not argue them again:
a domain class — aggregate, value object, enum, domain exception — whose constructor is part of
the model a reader reviews (a `@Service` class is not one, and converts); a constructor with logic; a persistence entity
(non-`final` fields set by reflection); a parameter
carrying an annotation (`@Qualifier`, `@Value` — Lombok drops it unless `lombok.config` copies it),
and two parameters of one type, where reordering two fields would swap two arguments with no
compile error. `architecture-ddd.md` § Composition is unchanged — constructor injection, now
generated. The domain still never logs: `lombok.extern..` stays in its forbidden imports (`0154`).

**Cons:** about 80 template files change shape in one diff. The generated constructor's parameter
order is the field order, so a field moved later is an argument moved — the two-of-one-type
exception covers the case the compiler cannot. The generated project gets no mechanical check
(axis 7, by choice).

**CI:** new `.claude/.ci/TemplateLombokTest.java`, a step of `validate`, failing by file and line on
`LoggerFactory.getLogger` outside a comment, on a constructor whose body only assigns `final`
fields from distinct-typed, unannotated parameters, and on a class whose fields are all `private`
without `@FieldDefaults`. In `gof-design-patterns/templates/` and `domain-modeling/templates/` — where a
pattern's wiring and the model are the point of the exemplar — the constructor check holds only
for a `@Service` class, and fails the other way on a `@RequiredArgsConstructor` on any other class. `JavaTemplatesTest` (`templates`, path-filtered) already compiles the
templates copied verbatim — `commons/`, `JpaEntity`, the Mongo, search and auditing blocks —
against an Initializr project with real Lombok. The other converted templates are compiled by hand
with `javac` and the Lombok jar. Every template test (`AdmissionTemplateTest`, `AiTemplateTest`,
`TemplateLogLevelTest`…) re-run, since several match on a template's text.

### Option 2 — Option 1 + Checkstyle (score 5)

`RegexpSinglelineJava` on `LoggerFactory\.getLogger` under `src/main`. Holds the logger in the
build; the constructor and `@FieldDefaults` stay unchecked, since neither is a regex. Rejected at
the interview (axis 7). **CI:** Option 1 plus `CheckstyleConfigTest`.

### Option 3 — templates only (score 4)

The templates change; `lombok.md` keeps "allowed", so the next template or executor writes the
constructor by hand again — the state the 75 constructors came from. **CI:** `JavaTemplatesTest`.

### Option 4 — create nothing (score 1)

The templates keep violating `lombok.md`'s own `@FieldDefaults` requirement in 73 files. **CI:** none.

## References

| Claim | Source |
|---|---|
| Lombok annotations allowed, `@FieldDefaults` mandatory | `.claude/rules/lombok.md` § Remaining annotations, § `@FieldDefaults` |
| Constructor injection, `private final` dependencies | `.claude/rules/architecture-ddd.md` § Composition |
| The domain never logs; `lombok.extern..` forbidden there | `@.claude/decisions/0154-domain-does-not-log.md`; `hexagonal.yaml` `forbidden_imports` |
| `@FieldDefaults` precedent in a template | `@.claude/decisions/0024-lessons-learned-001-remediation.md` (`JpaEntity`) |
| Verbatim templates compiled against real Lombok | `.claude/.ci/JavaTemplatesTest.java` header |
| A convention change reaches existing projects through `migrations` | `@.claude/decisions/0157-debug-logging-only-on-request.md` |

## Found while writing

Three facts the draft did not have, each now in the norm:

- **`@FieldDefaults` never touches a `static` field.** Compiled with `javac` and Lombok 1.18.48: under
  `@FieldDefaults(level = PRIVATE)` a `static final` written without `private` stays package-private.
  The first pass had stripped `private` from 44 constants, widening each one; every one was put back,
  `lombok.md` § `@FieldDefaults` now says static fields keep their modifiers, and 11 classes whose only
  fields are static lost the annotation, which did nothing there. Four constants that were
  package-private in the original (`DEPENDENCY`, `PORT`, `CURRENT_SCHEMA_VERSION`, `MAX_PAGE`) stay so.
- **Test sources use no Lombok.** `build.gradle.parent.example` wires it as `compileOnly` +
  `annotationProcessor`, which `src/test` does not inherit, so an annotation there compiles on Maven
  and fails on Gradle. No test template used it; the norm and the test say so.
- **Lombok drops a parameter's annotation.** The constructors with `@Value` or `@Qualifier` on a
  parameter (`RestClientAdapter`, `WebClientStreamAdapter`, `SearchRebuild`,
  `ProblemDetailSecurityHandlers`, `SearchIndexBootstrap`) keep the written constructor — the first
  pass converted the first four and lost the annotation; caught by comparing the annotation count per file
  against the original, and restored.

Two constructors carried Javadoc: `OrderConfirmedPublisher`'s moved to the `jsonMapper` field it
described; `SearchIndexBootstrap`'s stays, its constructor has an `@Value` parameter. Prose that named a
removed constructor changed with it: `ApiExceptionHandler`'s no-observability variant (the degradation
note now goes on `currentTraceId()`), `GraphQlExceptionResolver`'s admission note, and the rule lines of
`UseCasePort` and `RepositoryAdapter`. `gof-design-patterns/SKILL.md` step 4 says the exemplars keep the
constructor written and the generated class follows `lombok.md`.

## Propagation

| File | Change |
|---|---|
| `.claude/rules/lombok.md` | § Generated boilerplate (new); § `@FieldDefaults` scoped to instance fields, static fields keep their modifiers; § Remaining annotations without `@RequiredArgsConstructor`; § How to verify |
| `.claude/rules/00-index.md` | `lombok.md`'s row |
| 71 `*.java.example` under `.claude/skills/` | 4 loggers to `@Slf4j`; 53 plain-assignment constructors to `@RequiredArgsConstructor` (access level kept); `@FieldDefaults` added to 102 types, every one whose instance fields are all private; GoF and `domain-modeling` keep their constructors, except `CreateUserService` (`@Service`) |
| `.claude/skills/gof-design-patterns/SKILL.md` | step 4: exemplars keep the constructor written, generated code follows `lombok.md` |
| `.claude/schemas/extensions.json` | migration `lombok-generated-boilerplate` |
| `.claude/.ci/TemplateLombokTest.java`, `.github/workflows/validate.yml` | new test and its step |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | the step's row |
| `docs/pt-br/14-rules.md`, `docs/en/14-rules.md` | `lombok.md`'s role and verification |

Goes to the generated project: **yes** — the rule and the skills through `export`; an existing project
through the migration on its next `/arch-adopt` update. The test stays here (nothing under `.claude/.ci/`
travels); the project gets no mechanical check (axis 7).

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate` › `exemplars let Lombok write the logger, the plain constructor and the field modifiers` | no `LoggerFactory.getLogger`; no single constructor that only assigns final fields from unannotated, distinct-typed parameters; `@FieldDefaults` exactly where every instance field is private, with no written access modifier on an instance field; in GoF and `domain-modeling` only a `@Service` class uses `@RequiredArgsConstructor`; no Lombok in a test exemplar | green (128 production exemplars, 221 types) · red, each by file and line: a hand logger back in `ApiExceptionHandler`, `UserController`'s written constructor back, `private` on an instance field under `@FieldDefaults`, `@RequiredArgsConstructor` and `@lombok.RequiredArgsConstructor` on `ShippingLabelFacade` (the qualified form was missed at first; the test now matches both), a Lombok import in `UseCaseTest`, `UserController` without `@FieldDefaults`; `SearchIndexBootstrap`'s written constructor with an `@Value` parameter stays green |
| `templates` › `JavaTemplatesTest` | the 45 verbatim templates — `commons/`, `JpaEntity`, the Mongo, search and auditing blocks — compile with real Lombok in a fresh Initializr project, and the 3 shipped tests pass | green, run locally on the final tree |
| `validate` › `schema`, `MigrationsSchemaTest`, every other template test | the rule's frontmatter and export; the migration's shape; the templates' other claims | green |

Shapes outside `JavaTemplatesTest` — an enum with `@Getter @RequiredArgsConstructor @FieldDefaults`, a
package-access generated constructor, `@Slf4j` beside `@FieldDefaults`, a nested class, a class with
final and non-final fields — were compiled with `javac` and Lombok 1.18.48 and asserted by reflection:
field modifiers, constructor access, parameter order, the generated `org.slf4j.Logger`.

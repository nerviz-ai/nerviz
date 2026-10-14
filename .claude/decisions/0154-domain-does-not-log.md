# 0154 · The domain does not log: the logging APIs join the domain module's forbidden imports, and an ArchUnit rule holds it in every layout

- **Date:** 2026-10-14
- **Scenario:** follow-up of `0153`. Reading `error-handling.md` and `logging.md` side by side left
  two gaps: (1) `logging.md` exempted only "a typed business exception" from the stack trace, while
  `error-handling.md` exempts the integration families too; (2) "logging inside the domain is a
  smell" had no check — "not enforced yet", in the rule's own words — so case 4 of `0153`'s
  section (log it) was open to a `catch` in the domain, the one layer that cannot say what the
  event means outside.
- **Decision:** both gaps closed on the branch `code-quality-improvement`, the shape `0145`–`0149`
  gave every library kept out of the domain: the prefixes go into the domain module's
  `forbidden_imports`, and the rule says it plainly. No hook: `ArchHook.java check` already refuses
  a forbidden import at edit time and `doctor gate` in CI.
- **State:** approved by Lucas Fernandes, on 2026-10-14 ("vamos tratar as lacunas"); implemented on
  the branch `code-quality-improvement`

## What changed

| File | Change |
|---|---|
| `.claude/rules/logging.md` § Format and level | the stack-trace exemption names a typed domain **or integration** family, and `dependency` beside `errorCode`; "a smell" becomes "the domain does not log", with the APIs named |
| `.claude/rules/logging.md` § Per class type, § How to verify | the domain row says forbidden; the "not enforced yet" paragraph replaced by the two checks below |
| `.claude/rules/error-handling.md` § A caught exception is never silent | case 4 is never in the domain: a `catch` there rethrows, restores the interrupt, or answers |
| `hexagonal`, `clean-architecture-multi-module`, `onion`, `custom-template` | `org.slf4j..`, `ch.qos.logback..`, `org.apache.logging..`, `org.apache.commons.logging..`, `lombok.extern..` in the domain module's `forbidden_imports` |
| `test-architect/templates/ArchitectureTest.java.example` | `domain_does_not_log` — the single-module layouts have no module to forbid an import in, and it reads bytecode |
| `.claude/rules/00-index.md` | `logging.md`'s row: what verifies it |
| `migrations` in `extensions.json` | `domain-does-not-log`, every shipped blueprint |
| `.claude/.ci/AgentInstructionsTest.java` | the hexagonal domain fixture lists the new prefixes |

## Why two checks and not one

- **`forbidden_imports`** acts at the import, in the editor (`check`) and in CI (`doctor gate`) —
  but only where the domain is its own module. `clean-architecture-single-module`, `layered`,
  `modular-monolith` and `vertical-slice` keep it as a package, with `dependency_rules` alone.
- **`lombok.extern..`** is there because the import check reads source: `@Slf4j` imports
  `lombok.extern.slf4j.Slf4j`, never `org.slf4j`. Lombok itself stays allowed in the domain.
- **ArchUnit** reads bytecode, so it covers every layout with `features.archunit: true` (all
  seven shipped) and sees the `org.slf4j.Logger` field Lombok generates after the annotation is
  gone. JUL was already refused everywhere by `NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING`.

`java.lang.System.Logger` is left out: no template or generated project uses it, and a rule
against a failure nobody observed is the mirror of invariant 6.

## Propagation

Rules and blueprints travel through `export`. `.claude/forbidden-imports.txt`, each module
`AGENTS.md` and `ArchitectureTest.java` are written once, so an existing project gets the
`domain-does-not-log` migration on its next `/arch-adopt` update — it lists the domain classes that
log and waits for a go-ahead before moving any log line.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › AgentInstructionsTest` | `doctor` reads the new domain prefixes from the hexagonal blueprint and wants them in `domain/AGENTS.md` | green · red with the old fixture: `❌ every file present and matching … expected "Agent instructions . ✅ …"` |
| `validate · design › MigrationsSchemaTest`, frontmatter schema (`ArchHook.java schema`) | the `domain-does-not-log` entry's shape and blueprints; `extensions.json` valid | green |
| `validate › AiTemplateTest`, `AuditTemplateTest`, `GraphQlTemplateTest`, `PersistenceTemplateTest`, `SearchTemplateTest`, `BoundaryTest` | the blueprints still parse and keep every earlier boundary | green |

`ArchitectureTest.java.example` is compiled by no CI job — true before this change too. The new
rule was run by hand against ArchUnit 1.3.0: a domain class with Lombok `@Slf4j` failed on three
dependencies (the `org.slf4j.Logger` field, `LoggerFactory.getLogger`, `Logger.warn`); a domain class
that rethrows and an application class with an SLF4J logger passed.

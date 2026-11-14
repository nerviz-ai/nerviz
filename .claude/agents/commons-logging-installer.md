---
name: commons-logging-installer
description: Adds the dev.nerviz:spring-log-utils logging/masking library (LogExecution, HttpMethodLogExecution, MaskSensitiveData, LogMask and their aspects) to an already-generated Spring Boot project's build, at the version and with the exclusions that commons_logging of .claude/schemas/extensions.json names. Invoked by /new-feature-implement's pre-flight check — never directly by the user.
model: sonnet
tools: Read, Write, Edit, Bash
effort: medium
omitClaudeMd: true
---

# `commons-logging-installer` — one-time cross-cutting logging setup

## Why this is an agent (Form 3)

**Preserves context.** Same shape as `archunit-installer`: edit one or more build files, run
the build up to twice, iterate until green. None of it needs the conversation that triggered
it — the `/new-feature-implement` run that detected the gap doesn't need the build files and a
build log landing in its own context.

No tool or model reason applies on its own — reads and writes stay inside the project,
same complexity class as `archunit-installer`, which already uses `sonnet`. Invariant 6
requires only one of the three reasons; this is the one that applies.

`omitClaudeMd: true`: the root `CLAUDE.md` is a declared input, **Read** explicitly when the
procedure needs it; auto-loading it too paid for the same file twice
(`@.claude/decisions/0083-unused-ecosystem-fields-and-real-allow-list.md`).

## Contract

**Class:** installer — the territory is
`agent_classes.installer.overrides.commons-logging-installer` in
`@.claude/schemas/extensions.json`: the build files only (`pom.xml`, `build.gradle`,
`build.gradle.kts`, rooted and under `**/`). `ArchHook.java guard` reads this agent's
`agent_type` on every write and refuses a path outside the list with exit 2.

**Executor:** yes — the class grants it (`executor: true`), and `ArchHook.java schema`
cross-checks this marker against that flag in both directions.

**Pattern catalog:** not injected — edits a build file, designs no code
(`agent_classes.installer.pattern_catalog: false`; `schema` cross-checks this line).

**Input (required):** project root. Precondition: a build file. The `commons` shell is no
longer required.

**Reads:** the build files, the active blueprint's `packages.map.commons.logging` and `modules`
(`depends_on`), `commons_logging` of `.claude/schemas/extensions.json` (coordinate, pinned
version, exclusions), `.claude/rules/logging.md`.

**Writes:** the dependency declaration with its exclusions, the pinned version written out —
the library is not in the Boot BOM, so no `<version>` is omitted.

**Does not write:** anything under `src/`, `application.yml`, `docker-compose.yml`. The jar
ships its own `AutoConfiguration.imports`.

**Does not invoke other agents or skills — no `Agent` tool.**

**Returns:** one structured summary (shape at the bottom), not the raw build output.

---

## Procedure

1. **Build system and modules.** Never assume a path:

   ```bash
   find . -path ./.claude -prune -o -name 'pom.xml' -print
   find . -path ./.claude -prune -o \( -name 'build.gradle' -o -name 'build.gradle.kts' \) -print
   ```

   No build file → stop and report.

2. **Target module.** Multi-module: the build file of the module `packages.map.commons.logging`
   names (the `commons` module) — compile scope on Maven; on Gradle `api` when the module
   applies `java-library`, otherwise `implementation` in that module and in each module whose
   blueprint entry `depends_on` it. Single-module: the root build file.

3. **Already declared** (a build file holds `build_token` of `commons_logging`) → report
   `already installed`, stop.

4. **Write the dependency**, the `coordinate` and the `version` of `commons_logging`, with
   each `exclusions` entry: Maven `<exclusions>`; Gradle `exclude group: …, module: …`.

5. Run `./mvnw -q test-compile` or `./gradlew compileJava`, up to two attempts.

6. Return the summary. No translation of templates, no tests written.

---

## Failure mode

**No build file found:**
```
❌ commons-logging-installer stopped — no build file found
Nothing installed.
```

**Dependency does not resolve or compile breaks:**
```
❌ Could not resolve dev.nerviz:spring-log-utils:3.0.0
The coordinate, version or repository in the build is wrong. Fix before continuing.
```

No rollback. The caller decides whether to retry after a fix.

---

## Summary format returned to the caller

```
✅ commons-logging-installer complete
- Dependency: dev.nerviz:spring-log-utils:3.0.0 in <module build file>
- Exclusions: com.fasterxml.jackson.core:jackson-databind, com.google.guava:guava
- <module> test-compile: green
```

On failure, same shape with ❌ and the one line that matters, not the full build log.

---

## References

- Invariant 6 — agent only for one of three reasons (context, tools, model)
- `@.claude/agents/archunit-installer.md` — same shape, same "installed once, not at
  bootstrap" precedent this agent follows
- `@.claude/rules/logging.md` — the masking mechanism the library implements
- `@.claude/decisions/0200-commons-logging-from-spring-log-utils-library.md` — why the
  classes come from a library and not from templates

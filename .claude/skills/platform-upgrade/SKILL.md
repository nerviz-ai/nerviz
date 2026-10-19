---
name: platform-upgrade
description: >
  Takes a Maven or Gradle project that builds on a Spring Boot line below this architecture's
  floor up to it — the version bump, the renamed starters and test slices, the renamed
  configuration properties — and leaves the build green and the diff uncommitted. Use when
  `/arch-adopt` or `export` refused a project for its Spring Boot line, or when a project
  should move up before adopting. Explicit invocation only.
argument-hint: "[--ref <tag|sha|main>] [--source <local path>]"
disable-model-invocation: true
allowed-tools: Read, Write, Edit, Bash, Glob, Grep, AskUserQuestion
model: opus
---

# Platform Upgrade

Moves the project the session is rooted at from the Spring Boot line its build declares to the
one the Initializr serves today, because `ArchHook.java export` refuses a project below
`doctor.platform.min_line` and nothing else leads it there. The norms, exemplars and
architecture-test allowlist this `.claude/` ships are written for that floor and later.

**Two engines, in this order.** What is mechanical goes through a recipe that
`ArchHook.jar upgrade-recipe` writes from data — `doctor.platform.upgrade` of
`.claude/schemas/extensions.json` — and Spring's own metadata, and that the OpenRewrite core runs
through the project's own wrapper. What no rename covers is the guided residue (step 8), written
by you, one compile error at a time, against the norms. Never a recipe module under another
license: every artifact this procedure runs is Apache 2.0, and the data was written from what
this repository compiles, not from someone else's recipes.

**Never writes a version from memory.** The Spring Boot target is read from a project the
Initializr generates (step 3), the engine's version from the repository that serves it (step 4),
a dependency's version from where it is published (step 8). No network → stop and ask.

**Writes the build and `src/**`, and never commits.** The diff is the person's to review, and
`git checkout -- .` is how they undo it.

## Procedure

### 1 · Refuse to write over uncommitted work

```bash
git rev-parse --is-inside-work-tree && git status --porcelain
```

Not a git repository, or the output is not empty → **stop**. The upgrade rewrites the build and the
sources; `git diff` is how the person reviews it and `git checkout` how they undo it. Say what to
run (`git init` and a first commit, or commit or stash what is pending). Don't offer to commit.

Then the tools, once: `java -version` must report a JDK that the project's build accepts, and the
build tool is the one whose file sits at the project root — `pom.xml` (Maven) or `build.gradle` /
`build.gradle.kts` (Gradle). Both present → ask which one builds the project. Its wrapper
(`./mvnw`, `./gradlew`) is what runs everything below; none → stop and say so. This skill never
adds a wrapper and never assumes a Maven or Gradle on `PATH`.

### 2 · Fetch the source

A project below the floor has no `ArchHook.jar` of this shape, so the mode that writes the recipe
comes from the source, fetched as `arch-adopt` § 2-3 does it: `$SRC` named once there, the
`source` block of the `extensions.json` it finds, `--ref` and `--source <path>` with the same
meaning, nothing about the URL written from memory. An adopted project that already has
`.claude/` fetches too: a later floor needs the later data.

The plugin that delivers this skill ships only this file, as it does for `arch-adopt`: the two
Gradle init scripts it uses are read from `$SRC`, and so is the jar. Everything this procedure
downloads or writes outside the project goes in one directory:

```bash
WORK=${WORK:-/tmp/platform-upgrade}; mkdir -p "$WORK"
```

A ref older than this skill has no `upgrade-recipe` mode, and an unknown mode exits 0 with one
line on stderr — it would read as a success. So, before anything else:

```bash
grep -q 'upgrade-recipe' "$SRC/.claude/hooks/ArchHook.java"
```

Nothing found → stop and say that the fetched ref predates this skill; `--ref` names a newer one.

Read `doctor.platform.upgrade` of `$SRC/.claude/schemas/extensions.json` now. The keys named
below — `engine`, `recipes`, `test_starters` — are read from that file, never typed from this one.

### 3 · Resolve the target version

The Initializr is the version oracle. Generate a throwaway project into `$WORK` and read its
parent version:

```bash
mkdir -p "$WORK/initializr"
curl -sS --fail -L "<engine.initializr_starter>" -d type=maven-project -d language=java \
  | tar -xzf - -C "$WORK/initializr"
grep -m1 -A2 '<artifactId>spring-boot-starter-parent</artifactId>' "$WORK/initializr/pom.xml"
```

The version in the `<version>` line is `<target>`. No network, or the throwaway holds no version to read →
**stop and ask** the person for the version. `upgrade-recipe` checks its shape and that it is not
below the floor; it never chooses one.

### 4 · Resolve the engine's version

Read both from the repository that serves them. Print the versions chosen, so the report can name
them.

**Maven.** `<release>` of `engine.maven_plugin_metadata`. Central serves it and everything it
depends on.

**Gradle.** The Plugin Portal lists plugin versions that import a `rewrite-bom` Maven Central does
not serve, so the newest one does not resolve. Take the newest of the last
`engine.gradle_candidates` entries of `engine.gradle_plugin_metadata` whose POM
(`engine.gradle_plugin_pom`) names a `rewrite-bom` version that `engine.central_bom` answers 200
for — `curl -sSfI`. None → stop: the engine's public line moved, and `engine` is the block to edit.

### 5 · Pass 1 — the bump, the coordinates, the types

The project's line is read from its own build by the mode:

```bash
CLAUDE_PROJECT_DIR="$SRC" java -jar "$SRC/.claude/hooks/ArchHook.jar" upgrade-recipe . \
  --pass bump --target <target> --out "$WORK/bump.yml"
```

Exit 2 and a message that the project is already at or above the floor → say so and stop: there is
nothing to upgrade. Any other exit 2 is shown as it is. The recipe's name is
`doctor.platform.upgrade.recipes.bump_name`. Run it through the project's wrapper:

```bash
# Maven
./mvnw -B <engine.maven_plugin>:<maven version>:run \
  -Drewrite.configLocation="$WORK/bump.yml" -Drewrite.activeRecipes=<bump_name>

# Gradle — copy $SRC/.claude/skills/platform-upgrade/templates/rewrite-init.gradle.example to $WORK/rewrite-init.gradle first
./gradlew --init-script "$WORK/rewrite-init.gradle" -Dnerviz.plugin.version=<gradle version> \
  -Dnerviz.recipe="$WORK/bump.yml" -Dnerviz.recipe.name=<bump_name> rewriteRun
```

The recipe runs against the classpath the project **still has**: that is why the type renames sit
here and not after the bump. `git diff --stat` must show the build file changed. It did not → the
build declares its Spring Boot version where no step of the recipe reads it (a property in a
parent POM that is not this project's, a version catalog); name the file, ask the person to
change that one line, and run the step again. Never edit around it.

### 6 · The new classpath

Dependency resolution now sees the new BOM. List the jars it resolves — one file, every module:

```bash
# Maven — one file per module, joined with a newline between them
./mvnw -B -q dependency:build-classpath -Dmdep.outputFile=target/nerviz-classpath.txt
for f in $(find . -path '*/target/nerviz-classpath.txt'); do cat "$f"; echo; done > "$WORK/classpath.txt"

# Gradle — copy $SRC/.claude/skills/platform-upgrade/templates/classpath-init.gradle.example to $WORK/classpath-init.gradle first
rm -f "$WORK/classpath.txt"
./gradlew -q --init-script "$WORK/classpath-init.gradle" \
  -Dnerviz.classpath.out="$WORK/classpath.txt" nervizClasspath
```

A resolution failure here is the first signal that a dependency has no counterpart on the new line;
read the message, it names the artifact. That is step 8's, not a reason to stop.

### 7 · Pass 2 — the property renames

```bash
CLAUDE_PROJECT_DIR="$SRC" java -jar "$SRC/.claude/hooks/ArchHook.jar" upgrade-recipe . \
  --pass properties --classpath "$WORK/classpath.txt" --out "$WORK/properties.yml"
```

It reads the metadata each jar publishes and renames only what a jar itself declares an error with
a replacement. It writes nothing, and says so, when no jar declares one: skip the run. Otherwise run
it the way step 5 did, with `properties_name` for the recipe, and **without compiling**: the
sources do not compile until step 8, and the engine does not need them to rename a property.

```bash
# Maven — add:  -Dmaven.main.skip=true -Dmaven.test.skip=true
# Gradle — add: -x compileJava -x compileTestJava
```

### 8 · The guided residue

Compile, read the first errors, fix the cause, compile again:

```bash
./mvnw -B -q test-compile          # Maven
./gradlew -q compileTestJava       # Gradle
```

What recurs, and its owner:

| The error names | The cause | The fix |
|---|---|---|
| a package of a key of `test_starters` | the test slice moved to a module of its own | the test-scope dependency that key maps to, `group:artifact` and no version, in the module whose compile failed |
| `tools.jackson`, `JacksonException`, `com.fasterxml.jackson.databind`, a checked `JsonProcessingException` | Jackson 3 moved the core and made its exceptions unchecked; annotations stayed | the Jackson bullet of `$SRC/.claude/rules/architecture-ddd.md` — the two roots — then the code |
| a springdoc artifact the BOM no longer resolves | the line serving the old Boot is not the one serving the new | the version Maven Central lists as `<release>` of that artifact, after reading its own notes |
| anything else | an API that changed shape | the project's own code is changed, minimally; `engine.migration_guide` says what changed and why |

Two rules, no exceptions: **a test that fails is reported, never deleted, skipped or loosened**, and
**the same error three times in a row stops the loop** — say what was tried and ask. A Gradle
build that refuses the new plugin for its wrapper's version is changed once, with
`./gradlew wrapper --gradle-version <the "version" that engine.gradle_current returns>`.

### 9 · Verify

```bash
./mvnw -B verify          # Maven
./gradlew build           # Gradle
```

Green → continue. Red → the failing tests and the first error are the report; nothing is committed.
When the project already has `.claude/hooks/ArchHook.jar`, run `java -jar .claude/hooks/ArchHook.jar
doctor` too and put its `Platform` line in the report.

### 10 · Hand the diff over

`git status --short` and `git diff --stat`. Do **not** commit. Print the next command for the person
to type: `/arch-adopt` — now the project is at or above the floor, and a project that never had this
`.claude/` is adopted by it.

## Report

```
✅ upgraded — <from line> → <target>, <Maven | Gradle>

Source ....... <base_url host>, ref <ref>
Engine ....... <engine artifact> <version>
Pass 1 ....... <n> files changed (<n> sources, <n> build)
Pass 2 ....... <n> property renames applied | none declared by the jars
Residue ...... <n> edits by hand — <one line each>
Verify ....... <green | the failing tests, verbatim first line>

Review: git diff · Undo: git checkout -- .
Next: /arch-adopt
```

## Why this is a manually-invoked skill

Form 2 (`disable-model-invocation: true`): it rewrites a person's build and sources, so it may only
run when they ask for it by name — axis 2 of the interview. The closest rejected form was a step
inside `arch-adopt`: that skill runs remote content and travels into every project with a territory
of `.claude/`, and widening it to `src/**` would give the one skill that fetches the whole tree the
right to rewrite the whole project. The mechanical part is deliberately **not** here — it is
`ArchHook.jar upgrade-recipe`, Form 7c, because the same input has to produce the same recipe and
a test has to be able to hold it. (`@.claude/decisions/0175-platform-upgrade-below-the-floor.md`)

Pinned to `opus`: it edits code it did not write and decides what a compile error means (`@.claude/decisions/0081-skill-model-required-per-class.md`).

## Contract

**Class:** build — the territory is `skill_classes.build`'s override for this skill in
`@.claude/schemas/extensions.json`: the root build file, the wrapper files and `src/**`, at the
project root and under any module. `ArchHook.java guard` enforces it.

**Unfiltered Bash:** the upgrade fetches (`curl`, `tar`, `git ls-remote`), runs the project's own
wrapper — whose goals and tasks are the project's, not a fixed list — and the mode. Writes stay
under `guard` and `guard bash`, and a force push is blocked by `guard bash` (`guard.force_push`).

**Reads** `doctor.platform` of `.claude/schemas/extensions.json` in the fetched source — the
floor, the line patterns and the whole `upgrade` block — the project's build files and `src/**`,
and what the Initializr and the engine's repositories serve.

**Writes** the build file, wrapper files and `src/**` of the project, and `$WORK/**` outside it.
Never `.claude/**`: that is `/arch-adopt`'s.

**Refuses** a dirty worktree, a directory that is not a git repository, a project already at or
above the floor, and a build with no wrapper. Never commits, and never deletes, skips or loosens a
test.

**Travels into the project** (`export.skills.include`): a later floor raise sends an adopted project
here, and the plugin that delivered it may be gone by then.

**Hands off to** `/arch-adopt`, typed by the person once the diff is reviewed.

# Architecture blueprints

Primary source: `.claude/blueprints/_schema.md`.

## What a blueprint is

A blueprint is **data**, not code or procedure instruction — a YAML that fully
describes a Spring Boot project's architecture: modules, dependencies between them,
packages, active features, and the globs the `architecture-ddd.md` rule will use to
auto-load itself inside the generated project. It's the "orange" leaf in the
[00-overview.md](00-overview.md) diagram — like a rule, it never calls anyone; unlike
a rule, it's read programmatically by `project-bootstrap`, not loaded as prose
instruction.

Each architecture lives at `.claude/blueprints/<id>/<id>.yaml`. A folder without a
`.yaml` would be an architecture **not yet written**, and the skill would leave it out
of the interview listing — today all seven have a YAML. Pros, cons, and "when to
choose" for each are in `.claude/blueprints/README.md` (and `README.pt-br.md`).

| Blueprint | Layout | Summary |
|---|---|---|
| `hexagonal` | multi-module | Ports as interfaces, inbound and outbound adapters in their own modules |
| `clean-architecture-single-module` | single-module | domain/application/infrastructure as packages; boundary by ArchUnit only |
| `clean-architecture-multi-module` | multi-module | Same layers as Maven modules; the compiler enforces the direction |
| `layered` | single-module | controller/service/repository, the classic Spring Boot tutorial vocabulary instead of hexagonal's port/adapter naming |
| `modular-monolith` | single-module | Several bounded contexts (Spring Modulith), each with full domain/application/adapter layering inside its own `internal`; cross-module isolation checked by `ApplicationModules.verify()`, not ArchUnit |
| `onion` | multi-module | Palermo's Application Core: domain model and domain services in one `domain` module, `persistence`/`presentation` as independent outer rings |
| `vertical-slice` | single-module | One self-contained package per use case (`features.<feature>.<usecase>`, REPR pattern); only the aggregate crosses the slice boundary, in a shared `domain` kernel |
| `custom-template` | — | Starting point for creating your own, not a usable blueprint by itself |

## Who reads the YAML, and when

`project-bootstrap`, step 2 (`.claude/skills/project-bootstrap/SKILL.md` § 2 ·
Validate the blueprint), reads the file and validates it field by field before any
generation. No implicit defaults: if a required field is missing, generation stops
right there — it never proceeds with a made-up value.

## Contract fields

| Field | Required | Type | Description |
|---|---|---|---|
| `id` | ✅ | string | kebab-case, same as the file name |
| `name` | ✅ | string | Readable name, shown in the interview |
| `description` | ✅ | string | One sentence |
| `when_to_choose` | ✅ | string[] | Objective selection criteria |
| `trade_offs` | ✅ | string[] | Honest costs — a blueprint with no trade-offs is poorly written |
| `build.layout` | ✅ | `multi-module` \| `single-module` | |
| `build.tool` | ✅ | `maven` \| `gradle` | Overridable at initialization |
| `modules[]` | ✅ | list | See § `modules[]` below |
| `packages.base` | ✅ | string | Template, e.g. `{{groupId}}.{{artifactName}}` |
| `packages.map` | ✅ | map | Logical role → package suffix. A role ending in `.scheduling` is where `jobs-architect` puts every job trigger — without one it stops and names the missing line |
| `architecture_paths` | ✅ | string[] | Globs identifying the code covered by `rules/architecture-ddd.md`. They go **verbatim** into the `paths` of the file the bootstrap generates at `<project>/.claude/rules/architecture-ddd.md` — not inferred from `modules[].path` |
| `dependency_rules.direction` | ✅ | `inward` \| `explicit` | |
| `dependency_rules.forbidden[]` | ✅ | list | `{from, to[], reason}` |
| `templates` | ✅ | map | Role → template path |
| `features` | ✅ | bool map | Which dependencies and modules the project takes — **does not control code generation**, see the section below |

### `modules[]`

| Field | Required | Description |
|---|---|---|
| `id` | ✅ | Unique identifier |
| `path` | ✅ | Path relative to the root |
| `depends_on` | ✅ | List of `id`s, `[]`, or `["*"]` (for the bootstrap module) |
| `forbidden_imports` | ➖ | Forbidden prefixes. If present, generates a local `AGENTS.md`, plus the one-line `CLAUDE.md` that imports it |
| `feature` | ➖ | The module is only generated if the feature is active |
| `contains_main` | ➖ | Exactly one module with `true` |

## What `features` decides — and what it doesn't

An active feature is **a dependency and its configuration**. Four effects, all inside
`project-bootstrap`:

| Where | Effect |
|---|---|
| Step 3 | Enters the Spring Initializr's `-d dependencies=` |
| Step 4 | In `multi-module`, decides which module POM the dependency goes to |
| `modules[].feature` | The module is only generated if the feature is active |
| Step 4.7 | Configuration: `application-actuator.yml`, the `db/migration` directory |

**It does not decide code.** An active feature doesn't generate an entity, controller,
use case, or migration — the bootstrap doesn't write business code. Those classes come
later, from the real feature, via the skills that own each shape. `archunit` is the
extreme case: it's not even a bootstrap dependency, it's data the `test-architect`
skill reads later — in setup mode, passed on to the `archunit-installer` agent, which
is the one that actually translates `packages.map` into ArchUnit rules.

Corollary for whoever writes a blueprint: adding a feature means adding a line to
`project-bootstrap`'s `dependency-catalog.md`, not a code sample.

## Validation rules (the 6 that `project-bootstrap` runs at step 2)

1. The `depends_on` graph is acyclic.
2. Exactly **one** module has `contains_main: true`.
3. Every `feature` referenced by a module exists in `features`.
4. Every `templates.<role>` points to an existing file.
5. Under `layout: single-module`, `modules` has a single element, and
   `forbidden_imports` is enforced per package, not per module.
6. `architecture_paths` exists and is not empty.

**There's no external validator, nothing to install** — this template assumes only
the JDK, git, and curl — the Maven wrapper comes in the Initializr's `starter.tgz`. The
final arbiter is the build: a blueprint with poorly declared boundaries produces POMs
that don't compile. It fails later than a validator would, but it fails cleanly and
without installing anything.

## Real example — `hexagonal.yaml` (summary)

```yaml
id: hexagonal
name: Hexagonal (Ports & Adapters)
build:
  layout: multi-module
  tool: maven
architecture_paths:
  - "domain/**/*.java"
  - "application/**/*.java"
  - "adapters/**/*.java"
  - "bootstrap/**/*.java"
modules:
  - id: domain
    path: domain
    depends_on: []
    forbidden_imports: ["org.springframework..", "jakarta.persistence..", ...]
  - id: application
    path: application
    depends_on: [domain]
  - id: adapter-in-rest
    path: adapters/adapter-in-rest
    depends_on: [application, domain]
    feature: rest
  - id: bootstrap
    path: bootstrap
    depends_on: ["*"]
    contains_main: true
dependency_rules:
  direction: inward
  forbidden:
    - from: domain
      to: [application, "adapters/*", bootstrap]
      reason: "The domain does not know who uses it"
features:
  rest: true
  persistence-jpa: true
  uuid-v7: true
  flyway: true
  testcontainers: true
  actuator: true
  observability: true
  archunit: true
```

Every rule in `dependency_rules.forbidden` becomes, in the generated project, one line
of `.claude/forbidden-imports.txt` — that's what gives `ArchHook.java check` its teeth
(see [01-file-types.md § Hook](01-file-types.md#hook)).

## How to create a custom blueprint

```bash
mkdir -p .claude/blueprints/my-style
cp .claude/blueprints/custom-template/custom.template.yaml .claude/blueprints/my-style/my-style.yaml
$EDITOR .claude/blueprints/my-style/my-style.yaml
```

`custom-template/custom.template.yaml` already ships with every required field filled
with commented placeholders — two modules (`core`, `app`), one `forbidden` rule, and
the full `features` block with the most common values as a starting point:

```yaml
id: custom
name: "My architecture"
build:
  layout: multi-module
  tool: maven
architecture_paths:
  - "core/**/*.java"
  - "app/**/*.java"
modules:
  - id: core
    path: core
    depends_on: []
    forbidden_imports: ["org.springframework.."]
  - id: app
    path: app
    depends_on: [core]
    contains_main: true
dependency_rules:
  direction: inward
  forbidden:
    - from: core
      to: [app]
      reason: "The core does not know who uses it"
features:
  rest: true
  validation: true
  persistence-jpa: false
  testcontainers: true
  actuator: true
  # ...
```

Validation is the 6-rule checklist above, run by the agent at step 2 of
`project-bootstrap` — the same validation any native blueprint goes through. There's
no separate "publish" step: as soon as the `.yaml` exists and validates, it appears in
the dynamic listing `init-project` shows during the interview
(`` !`find .claude/blueprints -mindepth 2 -maxdepth 2 -name '*.yaml' ...` ``).

**No skill, agent, or command needs to change to add an architecture.** If you find
you need to change one of them, the design is broken — fix the design, not the
blueprint (`_schema.md`, last line).

## Where to put supporting material

- Specific to **one** architecture (articles, notes) → `.claude/blueprints/<id>/references/`
- Cross-cutting across **several** architectures → `.claude/blueprints/references/`

# Blueprint contract

Each architecture lives in its own folder: `.claude/blueprints/<id>/<id>.yaml`.
Every such file follows this schema. It is read and validated by the agent in step 2 of
`project-bootstrap`, so required fields have no implicit defaults: if one is missing,
generation stops.

Folders without a `.yaml` (e.g. `vertical-slice/`) are architectures not yet written —
they only have a `README.md` pointing to this contract. The skill ignores them until they
get a YAML. Supporting material (articles, notes) specific to one architecture lives in
`.claude/blueprints/<id>/references/`, inside the blueprint's own folder. Material that
cuts across several architectures lives in `.claude/blueprints/references/`, shared.

## Fields

| Field | Required | Type | Description |
|---|---|---|---|
| `id` | ✅ | string | kebab-case, same as the file name |
| `name` | ✅ | string | Readable name shown in the interview |
| `description` | ✅ | string | One sentence |
| `when_to_choose` | ✅ | string[] | Objective selection criteria |
| `trade_offs` | ✅ | string[] | Honest costs. A blueprint with no trade-offs is poorly written |
| `build.layout` | ✅ | `multi-module` \| `single-module` | |
| `build.tool` | ✅ | `maven` \| `gradle` | Overridable at initialization |
| `modules[]` | ✅ | list | See below |
| `packages.base` | ✅ | string | Template, e.g. `{{groupId}}.{{artifactName}}` |
| `packages.map` | ✅ | map | Logical role → package suffix |
| `architecture_paths` | ✅ | string[] | Globs identifying the code covered by `rules/architecture-ddd.md`. They go verbatim into the `paths` of the file the bootstrap generates at `<project>/.claude/rules/architecture-ddd.md` — the bootstrap does not infer them from `modules[].path`, it only uses what's here |
| `dependency_rules.direction` | ✅ | `inward` \| `explicit` | |
| `dependency_rules.forbidden[]` | ✅ | list | `{from, to[], reason}` |
| `templates` | ✅ | map | Role → template path |
| `features` | ✅ | bool map | Which dependencies and which modules the project takes. **Does not control code generation** — see below |

## What `features` decides — and what it does not

An active feature is **a dependency and its configuration**. Four effects, all in
`project-bootstrap`:

| Where | Effect |
|---|---|
| Step 3 | Enters the Spring Initializr's `-d dependencies=` |
| Step 4 | In `multi-module`, decides which module POM the dependency goes to |
| `modules[].feature` | The module is only generated if the feature is active |
| Step 4.7 | Configuration: `application-actuator.yml`, the `db/migration` directory |

**It does not decide code.** An active feature does not spawn an entity, controller, use
case or migration — the bootstrap does not write any business code. Those classes come
later, from the actual use case, via the skills that own each shape. `archunit` is the
extreme case and serves as a read: it is not even a bootstrap dependency, it's data that
the testing skill reads later.

Corollary for whoever writes a blueprint: adding a feature means adding a line to
`project-bootstrap`'s `dependency-catalog.md`, not a code sample.

## `modules[]`

| Field | Required | Description |
|---|---|---|
| `id` | ✅ | Unique identifier |
| `path` | ✅ | Path relative to the root |
| `depends_on` | ✅ | List of `id`s, `[]`, or `["*"]` for the bootstrap |
| `forbidden_imports` | ➖ | Forbidden prefixes. If present, generates a local `AGENTS.md`, plus the one-line `CLAUDE.md` that imports it |
| `feature` | ➖ | Module is only generated if the feature is active |
| `contains_main` | ➖ | Exactly one module with `true` |

## Validation rules

1. `depends_on` graph is acyclic.
2. Exactly one module has `contains_main: true`.
3. Every referenced `feature` exists in `features`.
4. Every `templates.<role>` points to an existing file.
5. Under `layout: single-module`, `modules` has a single element and
   `forbidden_imports` is enforced per package, not per module.
6. `architecture_paths` exists and is not empty.
7. `features.messaging-sqs` is absent or `false`. The feature was retired
   (`@.claude/decisions/0178-retire-the-sqs-blueprint-feature.md`); a blueprint that sets it `true` is
   refused, and the key is set `false` or removed.

## Creating your own blueprint

```bash
mkdir -p .claude/blueprints/my-style
cp .claude/blueprints/custom-template/custom.template.yaml .claude/blueprints/my-style/my-style.yaml
$EDITOR .claude/blueprints/my-style/my-style.yaml
```

Validation is the 6-rule checklist above, run by the agent in step 2 of
`project-bootstrap`. **There is no external validator and nothing to install** — this
template targets Java developers and assumes only JDK, Maven, git, curl and bash.

The final arbiter is the build: a blueprint with poorly declared boundaries produces
POMs that don't compile. It fails later than a validator would, but it fails cleanly and
without installing anything.

No skill, agent or command needs to change to add an architecture. If you find you need
to change them, the design is broken — fix the design, not the blueprint.

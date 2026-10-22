# Contributing

Thanks for considering it. Blueprints and norms are the most useful contributions.

This repository is **not a Java application**: it has no `pom.xml`, nothing compiles,
there are no Maven tests. What gets edited here are instruction files, data, and one
hook. Read `CLAUDE.md` before the first PR — its § Invariants is what review checks
against. The traps that fail silently — the runtime's and this repository's — are in
`docs/en/11-pitfalls.md`.

## Setup

```bash
git clone https://github.com/nerviz-ai/nerviz.git
cd nerviz
java .claude/hooks/ArchHook.java doctor
```

Dependencies: `java` (JDK 21+), `git`, `curl`. Nothing else — no Python, no shell
scripts, no `mvn` on `PATH` (the wrapper arrives inside the Initializr's `starter.tgz`).
A PR that adds a dependency outside that list fails `validate.yml`.

`claude` itself is only needed to exercise the skills, not to validate a change.

## Before you open a PR

```bash
java .claude/hooks/ArchHook.java doctor     # diagnoses the setup
java .claude/hooks/ArchHook.java schema     # validates frontmatter + .mcp.json (invariants 10, 11)
claude plugin validate .claude/skills       # malformed YAML only — see below
```

`claude plugin validate` **does not validate fields**. It accepts `metadata:`, accepts
camelCase, accepts an invented field, always printing `✔ Validation passed`. It never
looks at `.claude/agents/`, `.claude/rules/` or `.claude/settings.json`. What validates
fields is `ArchHook.java schema`.

`settings.json` and `.mcp.json` are read **only at session startup**. If your change
touches either, restart `claude` before claiming you tested it.

CI (`validate.yml`) runs the hook on Linux, macOS and Windows, then turns each invariant
into a check, then resolves every `import` in every `.java.example` against JARs from a
real `start.spring.io` request. Full map: [`docs/en/07-ci-validate.md`](docs/en/07-ci-validate.md).

## Deciding what you're writing

Everything here is one of five file types. Picking the wrong one is the most common
review finding, and there is a skill for it:

```
/claude-code-architect-designer
```

| You want to state | It's a |
|---|---|
| A rule about the code that is always true | norm in `.claude/rules/` |
| A procedure with steps | skill in `.claude/skills/` |
| The same procedure, but it must preserve context, restrict tools, or change model | agent in `.claude/agents/` |
| A rule that must hold even when the model forgets it | hook, or `permissions.deny` |
| Routing ("when X, read Y"), a pitfall, an invariant | section of `CLAUDE.md` |
| An architecture | blueprint in `.claude/blueprints/` — data, no prompt changes |

Norms are a leaf of the graph: a norm never mentions a skill, an agent or a command. If
it needs to, it's a procedure and belongs in a skill.

## Per-type rules

### Blueprint (new architecture)

- Own folder: `.claude/blueprints/<id>/<id>.yaml`. Start from
  `custom-template/custom.template.yaml`.
- Fulfills [`.claude/blueprints/_schema.md`](.claude/blueprints/_schema.md) and passes
  its 6-rule checklist. There is no external validator and nothing to install; the final
  arbiter is whether the generated POMs compile.
- Declares honest `trade_offs`. A blueprint with no trade-offs is poorly written.
- `architecture_paths` is required and goes **verbatim** into the generated
  `<project>/.claude/rules/architecture-ddd.md`. A glob copied from another blueprint
  leaves the norm silently unloaded.
- Changes no skill, agent or command. If you find you need to, the design is broken —
  fix the design, not the blueprint.

### Norm

- Own file under `.claude/rules/`, listed in
  [`00-index.md`](.claude/rules/00-index.md), never copied into a skill.
- Declare `paths` — the narrowest glob that holds it. A rule without `paths` loads at
  launch, every session; Java is `**/src/**/*.java`, never `**/*.java`, which also matches
  `ArchHook.java`.
- A glob by package must name a package some blueprint's `packages.map` declares, **and**
  the norm must have an `export.derived_paths` entry in `schemas/extensions.json`. Both are
  checked (CI and `ArchHook.java schema`), because the failure mode — a norm that never
  loads — is silent.
- No class, record, interface or enum declaration in the body. Boilerplate lives in
  `skills/<name>/templates/*.example`.
- Every norm carries a § How to verify. Prose nobody checks is decoration.

### Skill

- `.claude/skills/<name>/SKILL.md`. The folder name becomes the slash command, so it
  must not collide with a native one (`/doctor`, `/init`, `/context`, `/memory`, …) —
  shadowing produces no error, it runs the wrong command.
- Human-only invocation: `disable-model-invocation: true`. Never create
  `.claude/commands/`.
- Everything the model must obey goes in the **body**. No `metadata:` — it costs tokens
  on every invocation and enforces nothing. Ownership, `reads` and `handoff` live in the
  body's `## Contract` section.
- Every skill sits in exactly one class of `skill_classes` in `schemas/extensions.json`,
  carries a `**Class:** <c>` line in its `## Contract`, the sections that class requires,
  and a `model` from the class's `allowed_models`. The class is also its write territory —
  `guard` blocks the rest. `ArchHook.java schema` fails by name on a skill in no class.
- Exemplars go in `templates/` with the `.example` suffix **last**
  (`Foo.java.example`), and every `import` in a `.java.example` must resolve against the
  current Initializr classpath.
- Restricting Bash: `allowed-tools: Bash(cmd:*)` filters each segment of a pipe
  separately. Write injections as a single command.

### Agent

- Exists only to preserve context, restrict tools, or change model. Otherwise it's a
  skill.
- Sits in one class of `agent_classes` (`driver`, `executor`, `installer`), declares
  `model` and `tools` in its frontmatter, and a `**Class:** <c>` line in its body. The class
  fixes the sections it carries, whether it may write at all, and where.

### Hook

- One source: `.claude/hooks/ArchHook.java`. Every hook launches the committed
  `.claude/hooks/ArchHook.jar`, so editing the source changes nothing until
  `java .claude/hooks/ArchHook.java build` rewrites the jar under the JDK
  `hook_build.javac_feature` pins — commit both; CI's `build --verify` compares them byte for
  byte. Must run identically on Linux, macOS and Windows — CI proves it on all three.
- A list the hook reads (names, paths, patterns) lives in `schemas/extensions.json`, never as
  a constant in the Java (invariant 10).
- Exit 0 when a precondition is absent (this repo has no `./mvnw`; that's expected, not
  a failure).

### MCP server

- Declared for this meta-repo (`.mcp.json`), for the generated project
  (`project-bootstrap/templates/mcp.json.example` — written by
  `claude-code-architect-designer`, and absent until some server needs it; `export.optional_copy`
  copies it when it exists), or both. A server useful to both is written in both files on
  purpose.
- Secrets only as `${VAR}` / `${VAR:-default}`, `oauth`, or `headersHelper`. Never a
  token spelled out — `ArchHook.java schema` scans `headers` and `env`, because a leaked
  token in git history is irreversible.
- Never work around the one-time human approval of a project-scoped server with
  `enableAllProjectMcpServers`. That prompt is the trust boundary a cloned repo can't
  skip.

### Documentation

- `docs/pt-br/` is português, `docs/en/` is English, numbered in parallel. Changing a
  behaviour means changing both sides.
- `.claude/decisions/` is not documentation and not a norm: it records what was decided
  on a date, not what holds today. No `paths`, not listed in `00-index.md`, not copied
  into generated projects. Supersede a record with a new one; don't rewrite the old one.

## Self-containment (invariant 9)

Whoever clones a generated project does not have Nerviz. Everything
cited from inside a generated project must exist inside it. What travels is **data**: the
`export` block of `.claude/schemas/extensions.json`, which `ArchHook.java export` reads for
both `/init-project` and `/arch-adopt`. A new norm, skill or agent is only complete once that
block lists it — `ArchHook.java schema` fails by name on the gap:

| What you added | Where in the `export` block |
|---|---|
| Norm | Travels by default; a rule whose `paths` names a package also needs an `export.derived_paths` entry |
| Skill | `export.skills.include` (travels) or `exclude` (a creation skill that must not) — every skill on disk in one of the two |
| Agent | `include` or `exclude` of the agents list, same rule |
| A renamed or dropped file | `export.retired`, so an adopted project stops carrying the old copy — one entry per file |
| MCP server | `export.optional_copy` already names `templates/mcp.json.example` |

`ArchHook.java`, `ArchHook.jar` and `schemas/extensions.json` travel whole. Creation skills
(`project-bootstrap`, `init-project`, `claude-code-architect-designer`) and the blueprint
catalog are left out on purpose — they only serve before the project exists; the **active**
blueprint travels (`export.blueprint_copy`).

## Versions

Never write a Java or Spring Boot version from memory, anywhere outside
`.claude/decisions/` and dated "Tested to compile" notes. Versions are resolved at
runtime via Spring Initializr; without network access, ask. CI greps for this.

## Git

- Branch off `main`: `<type>/<kebab-case-description>`, e.g.
  `feat/vertical-slice-blueprint`, `fix/otlp-collector-port`.
- Conventional Commits for the subject: `feat(persistence): …`, `fix(observability): …`,
  `docs: …`. Scope is the area, not the file.
- Commit messages, PR titles and descriptions, and issue text in English. The body of a
  commit explains why, not what the diff already shows.
- One concern per PR. A blueprint plus the norm it needs is one concern; a blueprint plus
  an unrelated docs rewrite is two.
- Fill in `.github/PULL_REQUEST_TEMPLATE.md` and let CI pass before asking for review.

## Releases

Every merge into `main` is tagged. `release.yml` creates the annotated tag from the merge
commit, and it reads the level from § Release bump of the PR description — `major`,
`minor` or `patch`, exactly one ticked. The `release bump is declared` check fails while
the PR is open otherwise, so the decision is made by review and not after the fact.

Right after tagging, `release.yml` sends a `repository_dispatch` to
[`nerviz-ai/marketplace`](https://github.com/nerviz-ai/marketplace),
whose `sync.yml` runs `./sync.sh vX.Y.Z`, sets the plugin version, validates the result and
opens a PR. The dispatch needs the `MARKETPLACE_DISPATCH_TOKEN` secret here — a fine-grained
PAT scoped to the marketplace with Contents: read and write; without it the step only warns.

GitHub Releases come in batches, not one per merge. `release-notes.yml` runs every Friday,
and by hand (`workflow_dispatch`) whenever a batch should ship sooner. It takes every tag
since the latest published Release, groups them by bump level (Breaking, New, Fixes and
docs) under § What changes of each PR, lists the `migrations` entries added in the range,
and publishes one Release on the newest tag. `dry_run` prints the notes without publishing.
That is why § What changes is worth writing for a reader: it becomes the Release text.

One thing stays manual on purpose: merging that marketplace PR — publishing is a decision,
not a consequence of merging.

## Reporting instead of contributing

Bugs, feature proposals and blueprint ideas have forms:
[new issue](https://github.com/nerviz-ai/nerviz/issues/new/choose).
Questions go to
[Discussions](https://github.com/nerviz-ai/nerviz/discussions).
Before proposing a redesign, check
[`.claude/decisions/`](.claude/decisions/README.md) — it may already record why the
current shape was chosen.

## License

By contributing you agree your contribution is licensed under the
[MIT License](LICENSE).

## License of contributions and sign-off

This repository is under the [Apache License 2.0](LICENSE). By submitting a contribution you
license it under those terms (Apache License § 5) — no CLA to sign.

Every commit should carry a [Developer Certificate of Origin](https://developercertificate.org)
sign-off, which says you wrote it or have the right to submit it:

```bash
git commit -s -m "feat: ..."      # adds: Signed-off-by: Your Name <you@example.com>
```

Do not paste code from a project whose license is not compatible with Apache 2.0 (GPL, AGPL,
SSPL, BSL, or "source available"). The name and logo are not part of the license — see
[`TRADEMARK.md`](TRADEMARK.md).
